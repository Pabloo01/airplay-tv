#ifndef TIMELINE_BUFFER_H
#define TIMELINE_BUFFER_H

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>

#include "audio_time.h"

/*
 * lock-free SPSC ring of int16 samples (interleaved frames); positions are
 * non-wrapping 64-bit counters (uint64_t even on 32-bit ABIs) so empty/full are
 * unambiguous without spare slot, and consumed() is an absolute sample index.
 * capacity, writes and skips are whole multiples of `align` (one frame), so a frame
 * never straddles the wrap
 */
class SpscRing {
    static_assert(std::atomic<uint64_t>::is_always_lock_free, "ring counters are touched from the audio callback");

public:
    explicit SpscRing(size_t capacitySamples, size_t align = 1)
        : mAlign(align ? align : 1),
          mCapacity(std::max(capacitySamples - capacitySamples % mAlign, mAlign)),
          mBuf(new int16_t[mCapacity]) {}

    size_t available() const {  // samples readable
        return (size_t)(mWrite.load(std::memory_order_acquire) - mRead.load(std::memory_order_acquire));
    }

    // total samples ever written; producer thread
    uint64_t written() const { return mWrite.load(std::memory_order_relaxed); }

    // total samples ever read or skipped, i.e. absolute index of the next sample to read; any thread
    uint64_t consumed() const { return mRead.load(std::memory_order_acquire); }

    // producer: push up to n samples (whole frames), drops what doesn't fit
    size_t write(const int16_t *src, size_t n) {
        const uint64_t w = mWrite.load(std::memory_order_relaxed);
        const uint64_t r = mRead.load(std::memory_order_acquire);
        const size_t freeSpace = mCapacity - (size_t)(w - r);
        if (n > freeSpace) n = freeSpace;
        n -= n % mAlign;
        const size_t wi = (size_t)(w % mCapacity);
        const size_t first = (n < mCapacity - wi) ? n : (mCapacity - wi);
        memcpy(&mBuf[wi], src, first * sizeof(int16_t));
        if (n > first) memcpy(&mBuf[0], src + first, (n - first) * sizeof(int16_t));
        mWrite.store(w + n, std::memory_order_release);
        return n;
    }

    // consumer: discard up to n oldest samples (whole frames)
    void skip(size_t n) {
        const uint64_t r = mRead.load(std::memory_order_relaxed);
        const uint64_t w = mWrite.load(std::memory_order_acquire);
        const size_t avail = (size_t)(w - r);
        if (n > avail) n = avail;
        n -= n % mAlign;
        mRead.store(r + n, std::memory_order_release);
    }

    // consumer: in-place access to buffered samples [consumed(), consumed() + available()). the
    // producer never touches them until skip() releases them
    size_t capacity() const { return mCapacity; }
    size_t indexOf(uint64_t absSample) const { return (size_t)(absSample % mCapacity); }
    const int16_t *data() const { return mBuf.get(); }

private:
    const size_t mAlign;
    const size_t mCapacity;
    std::unique_ptr<int16_t[]> mBuf;
    std::atomic<uint64_t> mWrite{0};
    std::atomic<uint64_t> mRead{0};
};

/*
 * adaptive jitter cushion tuner, NetEQ-inspired but simplified: decaying histogram of
 * packet jitter, cushion = high percentile of it + 1 packet of margin.
 * observe() is producer-thread only; result published via atomic, consumer reads it live
 */
class DelayTracker {
public:
    // staticCushionMs > 0: fixed cushion, tuner disabled
    // staticCushionMs <= 0: tune between MIN_CUSHION_MS and MAX_CUSHION_MS
    DelayTracker(int sampleRate, int channels, int staticCushionMs, int percentilePct)
        : mSampleRate(sampleRate), mChannels(channels),
          mAdaptive(staticCushionMs <= 0),
          mFloor(msToSamples(sampleRate, channels, mAdaptive ? MIN_CUSHION_MS : staticCushionMs)),
          mCeil(msToSamples(sampleRate, channels, mAdaptive ? MAX_CUSHION_MS : staticCushionMs)),
          mPercentilePct(percentilePct),
          mTuned(mFloor) {}

    // ptsNs: packet presentation time; arrivalNs: local arrival time; durNs: packet duration
    void observe(int64_t ptsNs, int64_t arrivalNs, int64_t durNs) {
        if (!mAdaptive) return;

        const int64_t raw = arrivalNs - ptsNs; // arrival latency
        // jitter = latency relative to min latency seen so far (mBaseNs)
        if (!mHaveBase) { mBaseNs = raw; mHaveBase = true; }
        else if (raw < mBaseNs) mBaseNs = raw;
        // creep mBaseNs up slowly: counters clock drift and one freak fast packet
        else mBaseNs += (raw - mBaseNs) >> BASE_CREEP_SHIFT;
        int64_t rel = raw - mBaseNs;
        if (rel < 0) rel = 0;

        int bin = (int)(rel / BUCKET_NS);
        if (bin >= NBUCKETS) bin = NBUCKETS - 1;

        // decay: each bin bleeds 1/2^FORGET_SHIFT per packet, current bin topped up by
        // ONE/2^FORGET_SHIFT, so total converges to ONE
        int64_t sum = 0;
        for (int i = 0; i < NBUCKETS; i++) {
            mHist[i] -= (mHist[i] + (1 << FORGET_SHIFT) - 1) >> FORGET_SHIFT;
            sum += mHist[i];
        }
        mHist[bin] += ONE >> FORGET_SHIFT;
        sum += ONE >> FORGET_SHIFT;

        // cushion = smallest delay whose cumulative histogram share reaches percentile
        const int64_t thresh = sum * mPercentilePct / 100;
        int64_t cum = 0; int b = 0;
        for (; b < NBUCKETS - 1; b++) { cum += mHist[b]; if (cum >= thresh) break; }
        // bin is lower bound on delay: take top edge + margin. margin must keep output
        // fed for one data callback and tide us over until next packet
        const int64_t marginNs = std::max(durNs, mOutputBufferNs.load(std::memory_order_relaxed));
        const int64_t targetNs = (int64_t)(b + 1) * BUCKET_NS + marginNs;
        size_t samples = (size_t)(targetNs * mSampleRate / NS_PER_SEC) * mChannels;
        if (samples < mFloor) samples = mFloor;
        if (samples > mCeil) samples = mCeil;
        mTuned.store(samples, std::memory_order_relaxed);
    }

    // reset clock base after discontinuity or clock shift/resync so it doesn't poison
    // jitter calculations; producer-thread only (same as observe())
    void reanchor() { mHaveBase = false; }

    // latest tuned cushion in samples; any thread
    size_t target() const { return mTuned.load(std::memory_order_relaxed); }

    void noteOutputBufferFrames(int frames) {
        mOutputBufferNs.store((int64_t)frames * NS_PER_SEC / mSampleRate, std::memory_order_relaxed);
    }

    // largest cushion tuner can reach, in samples
    size_t ceil() const { return mCeil; }

    // "movie mode" headroom: the adaptive cushion never goes below this, so a wi-fi hiccup up to
    // about this long is absorbed instead of underrunning. costs latency, not lip-sync: video
    // follows the measured audio presentation delay
    static constexpr int MIN_CUSHION_MS = 250;

private:
    static size_t msToSamples(int sampleRate, int channels, int ms) {
        return (size_t)sampleRate * ms / 1000 * channels;
    }

    static constexpr int MAX_CUSHION_MS = 1000;
    static constexpr int BUCKET_MS = 5;                  // histogram granularity
    static constexpr int NBUCKETS = MAX_CUSHION_MS / BUCKET_MS;
    static constexpr int64_t BUCKET_NS = (int64_t)BUCKET_MS * 1000000LL;
    static constexpr int FORGET_SHIFT = 8;               // ~1/256 decay per packet (~6s @ 23ms)
    static constexpr int BASE_CREEP_SHIFT = 10;          // mBaseNs creep speed
    static constexpr int64_t ONE = 1 << 20;              // histogram fixed-point unit

    const int mSampleRate;
    const int mChannels;
    const bool mAdaptive;                // false = fixed cushion (tuner disabled)
    const size_t mFloor;
    const size_t mCeil;
    const int mPercentilePct;            // arrival-delay percentile cushion targets
    std::atomic<size_t> mTuned;
    std::atomic<int64_t> mOutputBufferNs{0};  // output->producer: oboe output buffer duration
    int64_t mHist[NBUCKETS] = {};
    int64_t mBaseNs = 0;
    bool mHaveBase = false;
};

// cumulative diagnostic counters, bumped from producer/consumer, read from stats thread
class TimelineMetrics {
public:
    // packed: nests into AudioDebugData with fixed layout
    struct __attribute__((packed)) Debug {
        uint32_t trims, drops, silences, underruns;  // cumulative; 32-bit to avoid wrap
    };

    void countTrim()     { mTrims.fetch_add(1, std::memory_order_relaxed); }
    void countDrop()     { mDrops.fetch_add(1, std::memory_order_relaxed); }
    void countSilence()  { mSilences.fetch_add(1, std::memory_order_relaxed); }
    void countUnderrun() { mUnderruns.fetch_add(1, std::memory_order_relaxed); }

    // any thread
    Debug debugInfo() const {
        Debug d{};
        d.trims = mTrims.load(std::memory_order_relaxed);
        d.drops = mDrops.load(std::memory_order_relaxed);
        d.silences = mSilences.load(std::memory_order_relaxed);
        d.underruns = mUnderruns.load(std::memory_order_relaxed);
        return d;
    }

private:
    std::atomic<uint32_t> mTrims{0};      // backlog skipped: large excess, or stale burst on resume
    std::atomic<uint32_t> mDrops{0};      // late packets dropped, pts slot already passed
    std::atomic<uint32_t> mSilences{0};   // gaps filled with silence
    std::atomic<uint32_t> mUnderruns{0};  // ring ran dry, output padded with silence
};

/*
 * Timeline-synchronized playout buffer: absorbs jitter, clock drift and clock resyncs,
 * syncs audio to its presentation-time tag.
 *
 * drift: the output pulls frames at the DAC's rate, the sender produces them at its own, so the
 * backlog creeps (~4 ms/min measured). the consumer steers it back inaudibly: the backlog error
 * vs the tuned cushion is smoothed over ~10 s and turned into a tiny rate offset (<= 0.2 %),
 * applied by slipping one frame at a time with linear interpolation spread over SLIP_FRAMES
 * output frames (no step, no pitch wobble; bit-exact passthrough between slips). only errors
 * above HARD_SKIP_MS still skip, and every skip / underrun / inserted silence is faded (~5 ms).
 *
 * sender breaks (seek / pause in the mirrored app): the sender stops, the output underruns, and
 * the sender restarts with its timeline jumped, often delivering late at first and catching up
 * in a burst. that transient is not network jitter: the tuner ignores SETTLE_MS of arrivals
 * after a break. and playout restarts on the schedule it had (the read head's lag behind its pts
 * on the output clock): audio already too late for its slot is dropped while still silent, so
 * the presentation delay comes back where it was instead of carrying the sender's lateness. a
 * plain wi-fi stall (same timeline, delivered late) keeps the usual cushion refill.
 *
 * One concurrent reader + one concurrent writer OK; multiple readers or writers NOT safe.
 * samples = frames * channels
 */
class TimelineBuffer {
public:
    // cushion policy lives in mTracker; timeline reads mTracker.target() live
    TimelineBuffer(int sampleRate, int channels, int staticCushionMs, int percentilePct)
        : mSampleRate(sampleRate),
          mChannels(channels),
          mTracker(sampleRate, channels, staticCushionMs, percentilePct),
          mRing(ringCapacity(sampleRate, channels, mTracker.ceil()), (size_t)channels),
          mFadeFrames((uint32_t)std::max(1, sampleRate * FADE_MS / 1000)),
          mCatchupFrames((size_t)sampleRate * CATCHUP_MS / 1000),
          mHardSkipFrames((size_t)sampleRate * HARD_SKIP_MS / 1000),
          mLastOut(new int32_t[channels]()),
          mDecayFrom(new int32_t[channels]()),
          mLastWritten(new int16_t[channels]()),
          mScratch(new int16_t[SCRATCH_FRAMES * channels]) {}

    void noteOutputBufferFrames(int frames) { mTracker.noteOutputBufferFrames(frames); }

    // producer: push samples
    void write(const int16_t *pcm, size_t samples, int64_t ptsNs) {
        write(pcm, samples, ptsNs, monoNs());
    }

    // producer, arrival time explicit (CLOCK_MONOTONIC ns; injectable for host tests)
    void write(const int16_t *pcm, size_t samples, int64_t ptsNs, int64_t arrivalNs) {
        const size_t ch = (size_t)mChannels;
        samples -= samples % ch;
        if (samples == 0) return;
        const int64_t arrivalGapNs = mLastArrivalNs == NO_TIME ? 0 : arrivalNs - mLastArrivalNs;
        mLastArrivalNs = arrivalNs;
        // pts 0 = sender clock not NTP synced yet
        if (ptsNs == 0) {
            push(pcm, samples);
            publishAnchor(NO_PTS);
            return;
        }
        const int64_t durNs = framesToNs(samples / ch);

        // codec switch / stream (re)start asked elsewhere: settle the tuner, no playout resync
        bool settle = mSettleRequested.exchange(false, std::memory_order_acquire);
        const bool firstAnchor = mExpectedPtsNs == 0;  // stream start: no break to count
        int64_t gapNs = ptsNs - mExpectedPtsNs;
        bool senderBreak = false;
        if (mUnderran.exchange(false, std::memory_order_acquire)) {
            // consumer underran since last write: it already played the gap as real-time
            // silence, re-anchor so we don't also insert it here. a wi-fi stall delivers the same
            // timeline late (pts continue, short arrival gap): its burst is real network jitter.
            // anything else is a sender break: the sender stopped (seek, pause), or the gap
            // outlasted any cushion
            senderBreak = gapNs > CONTINUOUS_NS || gapNs < -CONTINUOUS_NS || arrivalGapNs > STALL_LEARN_MAX_NS;
            mExpectedPtsNs = ptsNs;
            gapNs = 0;
        }
        const bool discontinuity = gapNs > MAX_GAP_NS || gapNs < -MAX_GAP_NS;
        if (discontinuity || senderBreak) {
            // consumer, priming: restart on the old schedule. stored before this packet lands in
            // the ring, so a consumer that sees the data sees the flag
            mSenderBreak.store(true, std::memory_order_relaxed);
            if (!firstAnchor) mBreaks.fetch_add(1, std::memory_order_relaxed);
            settle = true;
        }

        // tuner learns network jitter, not the transient around a discontinuity (arrival gap, a
        // late restart, the catch-up burst): it skips SETTLE_NS of arrivals, keeping the cushion
        // it had, then measures from a fresh base (clock shift / new latency). no-op in
        // static-cushion mode
        if (settle) mSettleUntilNs = arrivalNs + SETTLE_NS;
        if (mSettleUntilNs != NO_TIME && arrivalNs >= mSettleUntilNs) {
            mSettleUntilNs = NO_TIME;
            mTracker.reanchor();  // before observe(), so the reset applies to this observation
        }
        if (mSettleUntilNs == NO_TIME) mTracker.observe(ptsNs, arrivalNs, durNs);

        bool fadeIn = false;
        if (discontinuity) {
            // write immediately: new sound after long silence resets backlog to ideal size (less
            // latency); on clock resync we keep the sound. splices onto what still plays, so
            // ramp the old sound out and fade the new one in
            pushDecay(mFadeFrames);
            fadeIn = true;
        } else if (gapNs > SLACK_NS) {
            // sender left gap (or a packet was lost): reproduce as silence so timing is exact
            pushDecay((size_t)(gapNs * mSampleRate / NS_PER_SEC));
            mMetrics.countSilence();
            fadeIn = true;
        } else if (gapNs < -SLACK_NS) {
            // slot already passed (late/overlap): drop
            mMetrics.countDrop();
            return;
        }
        if (fadeIn) pushFadedIn(pcm, samples);
        else push(pcm, samples);
        mExpectedPtsNs = ptsNs + durNs;
        // ring content is one gapless timeline (gaps became silence, overlaps were dropped), so
        // a single anchor gives every sample's pts: publish the pts ring sample 0 would carry.
        // one atomic, so readers never see a pts and a write position from different writes
        publishAnchor(mExpectedPtsNs - framesToNs(mRing.written() / ch));
    }

    // consumer (oboe data callback): frames frames into out, silence-padded on underrun
    void read(int16_t *out, int32_t frames) { read(out, frames, monoNs()); }

    /*
     * consumer, time explicit (CLOCK_MONOTONIC ns; injectable for host tests). real-time safe:
     * no allocation, no locks, no logging, bounded work per frame
     */
    void read(int16_t *out, int32_t frames, int64_t nowNs) {
        if (frames <= 0) return;
        tickOutputClock((size_t)frames, nowNs);
        readFrames(out, (size_t)frames, nowNs);
        mOutFrames += (uint64_t)frames;
    }

    // call after discontinuity or clock shift/resync (e.g. codec switch); any thread: the
    // producer applies it on its next write (the tuner skips SETTLE_NS of arrivals, then re-bases)
    void reanchorTracker() { mSettleRequested.store(true, std::memory_order_release); }

    // rebuild prebuffer cushion before resuming, e.g. after output stream restart;
    // must not run concurrently with read()
    void reprime() {
        resetConsumer();
    }

    // drop buffered audio + rebuild cushion, so resume after pause doesn't play stale
    // tail; only while output callback is stopped (skip() is consumer-side). a new stream:
    // the tuner settles, playout primes on the cushion (no old schedule to keep)
    void flushAndReprime() {
        mRing.skip(mRing.available());
        resetConsumer();
        reanchorTracker();
    }

private:
    static constexpr int64_t WAIT = -1;     // resyncStart: keep priming
    static constexpr int64_t ABANDON = -2;  // resyncStart: off the old schedule, cushion rules

    void readFrames(int16_t *out, size_t n, int64_t nowNs) {
        const size_t ch = (size_t)mChannels;
        const size_t tuned = mTracker.target() / ch;  // frames
        size_t avail = mRing.available() / ch;        // frames
        const int64_t outNowNs = outputTimeNs();      // when this callback's first frame plays

        if (!mPriming) noteLag(outNowNs, tuned);

        // prebuffer: hold output until cushion fills, builds jitter headroom
        size_t k = 0;  // first output frame played from the ring
        if (mPriming) {
            // the sender broke off and came back (seek, pause): restart on the old schedule
            if (!mResync && mHaveLag && mSenderBreak.load(std::memory_order_acquire)) mResync = true;
            bool started = false;
            if (mResync) {
                const int64_t lead = resyncStart(n, nowNs, outNowNs, tuned, avail);
                if (lead == WAIT) {
                    writeSilence(out, 0, n);
                    mFlowing.store(false, std::memory_order_relaxed);
                    return;
                }
                if (lead == ABANDON) {
                    // sender off the old schedule for good (or no timestamps): new schedule,
                    // set by the cushion like a fresh start
                    mResync = false;
                    mHaveLag = false;
                    mResyncAbandons.fetch_add(1, std::memory_order_relaxed);
                } else {
                    k = (size_t)lead;
                    started = true;
                    mResync = false;
                    mResyncs.fetch_add(1, std::memory_order_relaxed);
                }
            }
            if (!started) {
                // time the wait only while holding partial data; reset on empty ring so the
                // producer gets the full window once a sound starts, otherwise we could time
                // out on pre-sound silence and underrun the instant the first frame lands
                if (avail == 0) mPrimeSilenceFrames = 0;
                else mPrimeSilenceFrames += (uint32_t)n;
                // short sound can end before filling cushion; after holding partial data
                // through 2x cushion of silence the sound is complete: play it
                const bool starved = avail > 0 && mPrimeSilenceFrames >= 2 * tuned;
                // also keep priming on empty ring: with 0 cushion `avail < tuned` is never
                // true, so we'd otherwise underrun on every empty read between sounds
                if (avail == 0 || (avail < tuned && !starved)) {
                    writeSilence(out, 0, n);
                    mFlowing.store(false, std::memory_order_relaxed);
                    return;
                }
                // resuming with more than the cushion buffered (a burst that landed after a wi-fi
                // stall underran us): the gap already played as silence, so drop the stale excess
                // and keep the presentation delay where it was. output is silent here: no glitch
                if (avail > tuned + mCatchupFrames) {
                    mRing.skip((avail - tuned) * ch);
                    avail = tuned;
                    mMetrics.countTrim();
                }
            }
            mPriming = false;
            mPrimeSilenceFrames = 0;
            mFadeInLeft = mFadeFrames;
            mAboveSinceNs = NO_TIME;
            // on schedule to the frame: silence until the head's slot inside this callback
            if (k > 0) writeSilence(out, 0, k);
        }

        // bound latency: only a large excess skips (fade out, skip, fade in), the drift
        // steering below handles the rest inaudibly. skip only after the excess held for
        // TRIM_SUSTAIN (transient bursts), at most once per TRIM_THROTTLE, and not within that
        // window of an underrun (trimming while rebuilding cushion is counterproductive)
        if (avail > tuned + mHardSkipFrames) {
            if (mAboveSinceNs == NO_TIME) mAboveSinceNs = nowNs;
        } else {
            mAboveSinceNs = NO_TIME;
        }
        if (mAboveSinceNs != NO_TIME && mFadeOutLeft == 0 && nowNs - mAboveSinceNs >= TRIM_SUSTAIN_NS
                && (mLastTrimBlockNs == NO_TIME || nowNs - mLastTrimBlockNs >= TRIM_THROTTLE_NS)) {
            mFadeOutLeft = mFadeFrames;  // the skip itself happens when the fade-out completes
            mLastTrimBlockNs = nowNs;
            mAboveSinceNs = NO_TIME;
            mMetrics.countTrim();
        }

        // drift steering: smoothed backlog error -> small rate offset (input frames per output
        // frame - 1); proportional, critically-ish damped with the smoothing (zeta ~0.87)
        {
            const double err = (double)avail - (double)tuned;
            double a = (double)n / (STEER_SMOOTH_S * mSampleRate);
            if (a > 1.0) a = 1.0;
            mErrEma += a * (err - mErrEma);
            mRateDev = std::clamp(mErrEma / (STEER_CORRECT_S * mSampleRate), -STEER_MAX_DEV, STEER_MAX_DEV);
            mSteerPpm.store((int32_t)(mRateDev * 1e6), std::memory_order_relaxed);
        }

        const int16_t *buf = mRing.data();
        const size_t cap = mRing.capacity();
        const uint64_t head = mRing.consumed();
        const size_t ri = mRing.indexOf(head);
        size_t pos = 0;           // input frames advanced past the ring head
        uint32_t frac = mFrac;    // read head = pos + frac / SLIP_FRAMES
        for (; k < n; k++) {
            // owe a whole frame to the drift correction: slip it over the next SLIP_FRAMES
            if (mSlipLeft == 0 && mFadeOutLeft == 0) {
                if (mSlipAcc >= 1.0) {
                    mSlipDir = 1; mSlipLeft = SLIP_FRAMES; mSlipAcc -= 1.0;
                    mSlips.fetch_add(1, std::memory_order_relaxed);
                } else if (mSlipAcc <= -1.0) {
                    mSlipDir = -1; mSlipLeft = SLIP_FRAMES; mSlipAcc += 1.0;
                    mSlips.fetch_add(1, std::memory_order_relaxed);
                }
            }
            mSlipAcc += mRateDev;
            // need the frame under the head, and the next one while between frames
            if (pos >= avail || (frac != 0 && pos + 1 >= avail)) break;
            size_t ia = ri + pos * ch;
            if (ia >= cap) ia -= cap;
            int16_t *o = out + k * ch;
            if (frac == 0) {
                for (size_t c = 0; c < ch; c++) o[c] = buf[ia + c];
            } else {
                size_t ib = ia + ch;
                if (ib >= cap) ib -= cap;
                const int32_t wb = (int32_t)frac;
                const int32_t wa = SLIP_FRAMES - wb;
                for (size_t c = 0; c < ch; c++)
                    o[c] = (int16_t)((buf[ia + c] * wa + buf[ib + c] * wb + SLIP_FRAMES / 2) >> SLIP_SHIFT);
            }
            if (mFadeOutLeft > 0) {
                applyGain(o, mFadeOutLeft - 1);
            } else if (mFadeInLeft > 0) {
                applyGain(o, mFadeFrames - mFadeInLeft + 1);
                mFadeInLeft--;
            }
            for (size_t c = 0; c < ch; c++) mLastOut[c] = o[c];

            // advance the head one output frame (+-1/SLIP_FRAMES of a frame while slipping)
            frac += (uint32_t)(SLIP_FRAMES + (mSlipLeft ? mSlipDir : 0));
            if (mSlipLeft && --mSlipLeft == 0) mSlipDir = 0;
            while (frac >= (uint32_t)SLIP_FRAMES) { frac -= SLIP_FRAMES; pos++; }

            if (mFadeOutLeft > 0 && --mFadeOutLeft == 0) {
                // faded out: drop the excess down to the cushion and fade back in
                const size_t left = avail - pos;
                if (left > tuned) pos += left - tuned;
                frac = 0;
                resetSteering();
                mFadeInLeft = mFadeFrames;
            }
        }

        if (k < n) {
            // underrun: fade out what we had, then ramp the last level to silence
            if (k >= mFadeFrames) {
                for (uint32_t j = 0; j < mFadeFrames; j++)
                    applyGain(out + (k - mFadeFrames + j) * ch, mFadeFrames - 1 - j);
                for (size_t c = 0; c < ch; c++) mLastOut[c] = out[(k - 1) * ch + c];
            }
            startDecay();
            writeSilence(out, k, n);
            frac = 0;
            resetSteering();
            mFadeInLeft = 0;
            mFadeOutLeft = 0;
            mPriming = true;
            mLastTrimBlockNs = nowNs;  // hold off trims while rebuilding
            mResync = false;
            mResyncDataNs = NO_TIME;
            mResyncDropped = false;
            // the producer classifies what comes next (stall or sender break): clear the old
            // verdict before it can see the underrun
            mSenderBreak.store(false, std::memory_order_relaxed);
            mUnderran.store(true, std::memory_order_release);  // producer re-anchors
            mMetrics.countUnderrun();
            mFlowing.store(false, std::memory_order_relaxed);
        } else {
            mFlowing.store(true, std::memory_order_relaxed);
        }
        mRing.skip(pos * ch);
        mFrac = frac;
        mHeadPos.store((head / ch + pos) * SLIP_FRAMES + frac, std::memory_order_release);
    }

    // consumer: output clock. frames written so far, pinned to nowNs by a slowly smoothed offset:
    // callbacks jitter (and come in bursts on deep buffers), the DAC plays frames back to back.
    // keeps running through underruns (silence is output too), so a schedule outlives them
    void tickOutputClock(size_t n, int64_t nowNs) {
        const int64_t sample = nowNs - framesToNs(mOutFrames);
        if (!mHaveClock) {
            mClockOffsetNs = sample;
            mHaveClock = true;
            return;
        }
        double a = (double)n / (CLOCK_SMOOTH_S * mSampleRate);
        if (a > 1.0) a = 1.0;
        mClockOffsetNs += (int64_t)std::llround(a * (double)(sample - mClockOffsetNs));
    }

    // consumer: when the next output frame plays, on the output clock (minus the constant output
    // latency, which cancels: lags are compared on this clock only)
    int64_t outputTimeNs() const { return framesToNs(mOutFrames) + mClockOffsetNs; }

    // consumer: pts of the ring head frame once the producer published an anchor covering it
    // (anchor frames load first: the pts then stored is at least as new); false when unknown
    bool anchoredHeadPts(int64_t &pts, size_t &anchoredFrames) const {
        const uint64_t covered = mAnchorFrames.load(std::memory_order_acquire);
        const int64_t start = mRingStartPtsNs.load(std::memory_order_acquire);
        const uint64_t head = mRing.consumed() / (uint64_t)mChannels;
        if (start == NO_PTS || covered <= head) return false;
        pts = start + framesToNs(head);
        anchoredFrames = (size_t)(covered - head);
        return true;
    }

    // consumer, while playing: how far the read head trails its pts on the output clock (with the
    // cushion it rests on). a sender break restarts playout on this schedule (resyncStart)
    void noteLag(int64_t outNowNs, size_t tuned) {
        int64_t headPts;
        size_t anchored;
        if (!anchoredHeadPts(headPts, anchored)) return;
        const int64_t fracNs = (int64_t)mFrac * NS_PER_SEC / ((int64_t)mSampleRate * SLIP_FRAMES);
        mLagNs = outNowNs - headPts - fracNs;
        mLagCushion = tuned;
        mHaveLag = true;
    }

    // ns -> frames, rounded; ns >= 0, overflow-free
    size_t nsToFramesRound(int64_t ns) const {
        const uint64_t rate = (uint64_t)mSampleRate;
        const uint64_t q = (uint64_t)(ns / NS_PER_SEC), r = (uint64_t)(ns % NS_PER_SEC);
        return (size_t)(q * rate + (r * rate + NS_PER_SEC / 2) / NS_PER_SEC);
    }

    /*
     * consumer, priming after a sender break: restart playout where the schedule it had puts the
     * head (its lag, moved by any cushion change since), so the presentation delay comes back
     * unchanged. audio already too late for its slot is dropped while still silent (a restarting
     * sender often delivers late, then catches up in a burst); audio early for its slot waits.
     * returns output frames of silence before the head plays (< n), WAIT (keep priming), or
     * ABANDON (no timestamps, or the sender stayed off the old schedule: cushion rules)
     */
    int64_t resyncStart(size_t n, int64_t nowNs, int64_t outNowNs, size_t tuned, size_t &avail) {
        const size_t ch = (size_t)mChannels;
        if (avail > 0 && mResyncDataNs == NO_TIME) mResyncDataNs = nowNs;
        if (mResyncDataNs != NO_TIME && nowNs - mResyncDataNs >= RESYNC_TIMEOUT_NS) return ABANDON;
        int64_t headPts;
        size_t anchored;
        if (!anchoredHeadPts(headPts, anchored)) {
            // sender not NTP synced: nothing to schedule by. else the anchor is a write behind
            const bool unsynced = avail > 0 && mRingStartPtsNs.load(std::memory_order_acquire) == NO_PTS;
            return unsynced ? ABANDON : WAIT;
        }
        size_t usable = std::min(avail, anchored);
        const int64_t lagNs = mLagNs + framesToNs(tuned) - framesToNs(mLagCushion);
        int64_t lateNs = outNowNs - lagNs - headPts;  // > 0: the head's slot already passed
        if (lateNs > 0) {
            const size_t drop = lateNs >= framesToNs(usable) ? usable : nsToFramesRound(lateNs);
            if (drop > 0) {
                mRing.skip(drop * ch);
                avail -= drop;
                if (!mResyncDropped) {
                    mResyncDropped = true;
                    mMetrics.countTrim();
                }
                // all of it too late: wait for audio on schedule
                if (drop == usable || !anchoredHeadPts(headPts, anchored)) return WAIT;
                usable = std::min(avail, anchored);
                lateNs = outNowNs - lagNs - headPts;
            }
        }
        const size_t lead = lateNs < 0 ? nsToFramesRound(-lateNs) : 0;
        if (lead >= n) {
            // slot not in this callback yet. a sender this far ahead of its old schedule moved
            // it: don't wait for a backlog the hard skip would trim anyway
            return avail >= tuned + mHardSkipFrames ? ABANDON : WAIT;
        }
        // slot is now: start once there's enough on schedule to play on (less than the cushion
        // only while the sender is still a little late; the drift steering refills it)
        if (usable < std::max(tuned / 2, n)) return WAIT;
        return (int64_t)lead;
    }

public:
    // pts of the next frame the output callback will write, NO_PTS when unknown: not
    // playing ring data (priming / underrun / stopped) or sender not NTP synced. any thread.
    // exact under drift steering: includes the head's fractional position between frames.
    // the head only moves inside the data callback, so it pairs with the output stream's
    // frames-written counter (see AudioEngine::presentationDelayNs)
    int64_t readHeadPtsNs() const {
        if (!mFlowing.load(std::memory_order_relaxed)) return NO_PTS;
        const int64_t start = mRingStartPtsNs.load(std::memory_order_acquire);
        if (start == NO_PTS) return NO_PTS;
        const uint64_t headPos = mHeadPos.load(std::memory_order_acquire);
        const int64_t fracNs = (int64_t)(headPos % SLIP_FRAMES) * NS_PER_SEC / ((int64_t)mSampleRate * SLIP_FRAMES);
        return start + framesToNs(headPos / SLIP_FRAMES) + fracNs;
    }

    // read head in 1/SLIP_FRAMES frames; changes only when the data callback runs. any thread
    uint64_t readPosition() const { return mHeadPos.load(std::memory_order_acquire); }

    // drift steering diagnostics, any thread: current rate offset (ppm, + = consuming faster)
    // and frames slipped so far
    int32_t steerPpm() const { return mSteerPpm.load(std::memory_order_relaxed); }
    uint64_t slips() const { return mSlips.load(std::memory_order_relaxed); }

    // sender-break diagnostics, any thread: breaks seen (seek / pause / timestamp jump), playout
    // restarts on the old schedule, and restarts that gave up on it (sender stayed off it)
    uint32_t breaks() const { return mBreaks.load(std::memory_order_relaxed); }
    uint32_t resyncs() const { return mResyncs.load(std::memory_order_relaxed); }
    uint32_t resyncAbandons() const { return mResyncAbandons.load(std::memory_order_relaxed); }

    static constexpr int64_t NO_PTS = INT64_MIN;

    // debug snapshot: backlog + tuned cushion (ms) + counters; reads only atomics, any thread
    struct __attribute__((packed)) Debug {
        uint16_t backlogMs;         // bounded by ring (few seconds)
        uint16_t tunedCushionMs;    // bounded by MAX_CUSHION_MS
        TimelineMetrics::Debug metrics;
    };
    Debug debugInfo() const {
        Debug d{};
        d.backlogMs = (uint16_t)(mRing.available() / mChannels * 1000 / mSampleRate);
        d.tunedCushionMs = (uint16_t)(mTracker.target() / mChannels * 1000 / mSampleRate);
        d.metrics = mMetrics.debugInfo();
        return d;
    }

    static constexpr int FADE_MS = 5;           // fade at every skip / underrun / silence edge
    static constexpr int CATCHUP_MS = 40;       // resume excess above cushion skipped while silent
    static constexpr int HARD_SKIP_MS = 500;    // backlog excess above cushion that still skips
    static constexpr int SLIP_SHIFT = 7;
    static constexpr int SLIP_FRAMES = 1 << SLIP_SHIFT;  // ~2.9 ms: one frame slipped over this many
    static constexpr double STEER_SMOOTH_S = 10.0;       // backlog error smoothing time constant
    static constexpr double STEER_CORRECT_S = 30.0;      // proportional gain: error s / this = rate
    static constexpr double STEER_MAX_DEV = 0.002;       // +-0.2 % (3.5 cents): inaudible
    static constexpr int SETTLE_MS = 2500;               // tuner ignores arrivals this long after a break
    static constexpr int RESYNC_TIMEOUT_MS = 5000;       // sender misses its old schedule this long: new one

private:
    static constexpr int64_t NO_TIME = INT64_MIN;
    static constexpr size_t SCRATCH_FRAMES = 256;

    // ring can't be reallocated mid-stream: size for worst-case backlog, i.e. the hard-skip
    // point above the largest reachable cushion + one sustain window of overshoot, 2x slack
    static size_t ringCapacity(int sampleRate, int channels, size_t ceilSamples) {
        const size_t ceilFrames = ceilSamples / (size_t)channels;
        const size_t frames = 2 * (ceilFrames + (size_t)sampleRate * HARD_SKIP_MS / 1000)
                            + (size_t)sampleRate * TRIM_SUSTAIN_MS / 1000;
        return frames * (size_t)channels;
    }

    // exact and overflow-free for any frame count
    int64_t framesToNs(uint64_t frames) const {
        const uint64_t rate = (uint64_t)mSampleRate;
        return (int64_t)(frames / rate) * NS_PER_SEC + (int64_t)(frames % rate) * NS_PER_SEC / (int64_t)rate;
    }

    // o *= gain / mFadeFrames, one frame
    void applyGain(int16_t *o, uint32_t gain) const {
        for (int c = 0; c < mChannels; c++) o[c] = (int16_t)((int32_t)o[c] * (int32_t)gain / (int32_t)mFadeFrames);
    }

    void resetSteering() {
        mSlipLeft = 0;
        mSlipDir = 0;
        mSlipAcc = 0.0;
        mErrEma = 0.0;
    }

    // consumer: begin ramping the last output level down to zero (no step into silence)
    void startDecay() {
        for (int c = 0; c < mChannels; c++) mDecayFrom[c] = mLastOut[c];
        mDecayLeft = mFadeFrames;
    }

    // consumer: silence for out frames [from, to), continuing any decay ramp
    void writeSilence(int16_t *out, size_t from, size_t to) {
        const size_t ch = (size_t)mChannels;
        for (size_t f = from; f < to; f++) {
            int16_t *o = out + f * ch;
            if (mDecayLeft > 0) {
                mDecayLeft--;
                for (size_t c = 0; c < ch; c++)
                    o[c] = (int16_t)(mDecayFrom[c] * (int32_t)mDecayLeft / (int32_t)mFadeFrames);
            } else {
                for (size_t c = 0; c < ch; c++) o[c] = 0;
            }
        }
        if (to > from) {
            for (size_t c = 0; c < ch; c++) mLastOut[c] = out[(to - 1) * ch + c];
        }
    }

    // consumer, callback stopped: back to priming from silence
    void resetConsumer() {
        mPriming = true;
        mPrimeSilenceFrames = 0;
        mFrac = 0;
        resetSteering();
        mFadeInLeft = 0;
        mFadeOutLeft = 0;
        mDecayLeft = 0;
        mAboveSinceNs = NO_TIME;
        for (int c = 0; c < mChannels; c++) mLastOut[c] = 0;
        // new output stream (new clock) or new input stream: no schedule to keep
        mHaveClock = false;
        mHaveLag = false;
        mResync = false;
        mResyncDataNs = NO_TIME;
        mResyncDropped = false;
        mHeadPos.store(mRing.consumed() / (size_t)mChannels * SLIP_FRAMES, std::memory_order_release);
        mFlowing.store(false, std::memory_order_relaxed);
    }

    // producer: publish the pts of ring frame 0 and the frames it covers (pts first: a reader
    // loading the frame count first gets a pts at least that new)
    void publishAnchor(int64_t ringStartPtsNs) {
        mRingStartPtsNs.store(ringStartPtsNs, std::memory_order_release);
        mAnchorFrames.store(mRing.written() / (uint64_t)mChannels, std::memory_order_release);
    }

    // producer: append whole frames, remembering the last one for the next silence ramp
    void push(const int16_t *pcm, size_t samples) {
        mRing.write(pcm, samples);
        const size_t ch = (size_t)mChannels;
        if (samples >= ch) memcpy(mLastWritten.get(), pcm + samples - ch, ch * sizeof(int16_t));
    }

    // producer: `frames` of silence, the first ones ramping the last written level to zero
    void pushDecay(size_t frames) {
        const size_t ch = (size_t)mChannels;
        int16_t *tmp = mScratch.get();
        size_t done = 0;
        while (done < frames) {
            const size_t m = std::min(frames - done, SCRATCH_FRAMES);
            for (size_t f = 0; f < m; f++) {
                const size_t i = done + f;  // 0-based frame of the silence
                const int32_t g = i < mFadeFrames ? (int32_t)(mFadeFrames - 1 - i) : 0;
                for (size_t c = 0; c < ch; c++)
                    tmp[f * ch + c] = (int16_t)(mLastWritten[c] * g / (int32_t)mFadeFrames);
            }
            mRing.write(tmp, m * ch);
            done += m;
        }
        for (size_t c = 0; c < ch; c++) mLastWritten[c] = 0;
    }

    // producer: append with the first mFadeFrames frames faded in
    void pushFadedIn(const int16_t *pcm, size_t samples) {
        const size_t ch = (size_t)mChannels;
        const size_t frames = samples / ch;
        const size_t fadeFrames = std::min(frames, (size_t)mFadeFrames);
        int16_t *tmp = mScratch.get();
        size_t done = 0;
        while (done < fadeFrames) {
            const size_t m = std::min(fadeFrames - done, SCRATCH_FRAMES);
            for (size_t f = 0; f < m; f++) {
                const int32_t g = (int32_t)(done + f + 1);
                for (size_t c = 0; c < ch; c++)
                    tmp[f * ch + c] = (int16_t)(pcm[(done + f) * ch + c] * g / (int32_t)mFadeFrames);
            }
            mRing.write(tmp, m * ch);
            done += m;
        }
        if (frames > fadeFrames) {
            push(pcm + fadeFrames * ch, (frames - fadeFrames) * ch);
        } else {
            // whole packet inside the fade: its last frame carries gain frames / mFadeFrames
            for (size_t c = 0; c < ch; c++)
                mLastWritten[c] = (int16_t)(pcm[(frames - 1) * ch + c] * (int32_t)frames / (int32_t)mFadeFrames);
        }
    }

    static constexpr int64_t SLACK_NS = 10'000'000LL;    // ignore <10ms gaps (NTP shift, rounding)
    static constexpr int64_t MAX_GAP_NS = 750'000'000LL; // >750ms = discontinuity, re-anchor
    static constexpr int TRIM_THROTTLE_MS = 2000;        // min spacing between skips, and after underrun
    static constexpr int64_t TRIM_THROTTLE_NS = (int64_t)TRIM_THROTTLE_MS * 1'000'000LL;
    static constexpr int TRIM_SUSTAIN_MS = 2000;         // excess must hold this long before a skip
    static constexpr int64_t TRIM_SUSTAIN_NS = (int64_t)TRIM_SUSTAIN_MS * 1'000'000LL;
    // across an underrun: pts gap still a wi-fi stall (lost packets), and the longest arrival gap
    // whose burst the tuner learns from (no cushion absorbs a longer one, it'd only pin the ceiling)
    static constexpr int64_t CONTINUOUS_NS = 100'000'000LL;
    static constexpr int64_t STALL_LEARN_MAX_NS = 1'000'000'000LL;
    static constexpr int64_t SETTLE_NS = (int64_t)SETTLE_MS * 1'000'000LL;
    static constexpr int64_t RESYNC_TIMEOUT_NS = (int64_t)RESYNC_TIMEOUT_MS * 1'000'000LL;
    static constexpr double CLOCK_SMOOTH_S = 2.0;        // output clock offset smoothing

    const int mSampleRate;
    const int mChannels;
    DelayTracker mTracker;               // owns cushion policy; read live each read()
    SpscRing mRing;
    const uint32_t mFadeFrames;          // FADE_MS in frames
    const size_t mCatchupFrames;         // CATCHUP_MS in frames
    const size_t mHardSkipFrames;        // HARD_SKIP_MS in frames
    TimelineMetrics mMetrics;

    // consumer-only state (data callback; or reset while it is stopped)
    bool mPriming = true;                // stream start: buffer output to build cushion
    uint32_t mPrimeSilenceFrames = 0;    // silence frames output while priming with partial data
    int64_t mLastTrimBlockNs = NO_TIME;  // last skip/underrun time (skip throttle)
    int64_t mAboveSinceNs = NO_TIME;     // when backlog first exceeded the hard-skip point
    uint32_t mFrac = 0;                  // head position between ring head frame and the next
    uint32_t mSlipLeft = 0;              // output frames left in the current slip
    int32_t mSlipDir = 0;                // +1 dropping a frame, -1 repeating one, 0 passthrough
    double mSlipAcc = 0.0;               // frames owed to the drift correction (+ = drop)
    double mErrEma = 0.0;                // smoothed backlog - cushion, frames
    double mRateDev = 0.0;               // current rate offset
    uint32_t mFadeInLeft = 0;            // output frames left in a fade-in
    uint32_t mFadeOutLeft = 0;           // output frames left in a fade-out before a skip
    uint32_t mDecayLeft = 0;             // output frames left ramping mDecayFrom to silence
    std::unique_ptr<int32_t[]> mLastOut;    // last output frame
    std::unique_ptr<int32_t[]> mDecayFrom;  // level the silence ramp starts from
    uint64_t mOutFrames = 0;             // frames output so far: the output clock
    int64_t mClockOffsetNs = 0;          // smoothed nowNs - output clock
    bool mHaveClock = false;
    int64_t mLagNs = 0;                  // while playing: output clock - pts of the head frame
    size_t mLagCushion = 0;              // tuned cushion (frames) mLagNs was measured on
    bool mHaveLag = false;
    bool mResync = false;                // priming after a sender break: restart on mLagNs
    int64_t mResyncDataNs = NO_TIME;     // first resync callback holding data (timeout start)
    bool mResyncDropped = false;         // stale audio of this restart already counted as a trim

    // producer-only state
    int64_t mExpectedPtsNs = 0;          // presentation time expected at write head
    int64_t mLastArrivalNs = NO_TIME;    // arrival of the previous write
    int64_t mSettleUntilNs = NO_TIME;    // tuner ignores arrivals before this, NO_TIME = learning
    std::unique_ptr<int16_t[]> mLastWritten;  // last frame written, start of the next silence ramp
    std::unique_ptr<int16_t[]> mScratch;      // fade staging, SCRATCH_FRAMES frames

    std::atomic<bool> mSettleRequested{false};  // any->producer: settle the tuner on next write
    std::atomic<bool> mSenderBreak{false};  // producer->consumer: break since the last underrun
    std::atomic<uint64_t> mAnchorFrames{0};  // producer->consumer: ring frames mRingStartPtsNs covers
    std::atomic<uint32_t> mBreaks{0};       // producer->any: diagnostics
    std::atomic<uint32_t> mResyncs{0};      // consumer->any: diagnostics
    std::atomic<uint32_t> mResyncAbandons{0};  // consumer->any: diagnostics
    std::atomic<bool> mUnderran{false};  // consumer->producer: underrun happened
    std::atomic<bool> mFlowing{false};   // consumer->any: last callback played ring data (not priming/underrun)
    std::atomic<uint64_t> mHeadPos{0};   // consumer->any: read head, ring frame * SLIP_FRAMES + frac
    std::atomic<int32_t> mSteerPpm{0};   // consumer->any: diagnostics
    std::atomic<uint64_t> mSlips{0};     // consumer->any: diagnostics
    std::atomic<int64_t> mRingStartPtsNs{NO_PTS};  // producer->any: pts of ring sample 0, NO_PTS = unknown
};

#endif  // TIMELINE_BUFFER_H
