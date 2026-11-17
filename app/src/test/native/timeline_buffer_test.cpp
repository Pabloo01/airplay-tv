/*
 * standalone host test for TimelineBuffer (playout buffer + drift steering). not part of gradle:
 *
 *   clang++ -std=c++17 -O2 -Wall -Wextra -I app/src/main/cpp \
 *       app/src/test/native/timeline_buffer_test.cpp -o build/timeline_buffer_test \
 *     && build/timeline_buffer_test
 *
 * simulates a sender producing 480-frame packets (AAC-ELD, as in screen mirroring) at exactly
 * 44100 Hz (its own clock) that arrive with network latency + jitter, and an output device
 * pulling 512-frame callbacks at
 * 44100 Hz +- N ppm of the sender's clock. signal: ch0 = 440 Hz sine (clicks show up as big
 * sample-to-sample steps), ch1 = sample-index ramp (linear interpolation keeps it exact, so the
 * first frame written by each callback tells which input position was really played)
 */
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

#include "timeline_buffer.h"

namespace {

constexpr int RATE = 44100;
constexpr int CH = 2;
constexpr int64_t MS = 1'000'000LL;
constexpr int64_t SEC = 1'000'000'000LL;
constexpr int64_t PTS0 = 1000 * SEC;
constexpr int AMP = 8000;
constexpr int RAMP = 32768;

int64_t framesToNs(int64_t f) { return f / RATE * SEC + f % RATE * SEC / RATE; }

struct Scenario {
    const char *name;
    double seconds;
    double ppm = 0;                 // output clock vs sender, + = output fast (backlog shrinks)
    int cbFrames = 512;
    int pktFrames = 480;            // AAC-ELD
    int64_t latNs = 30 * MS;        // network latency
    int64_t jitterNs = 10 * MS;     // + uniform 0..jitter
    double stallAt = -1;            // s: no arrivals for stallFor s, then everything at once
    double stallFor = 0;
    int64_t lostPacket = -1;        // packet index never delivered
    double shiftAt = -1;            // s: arrival latency steps by shiftNs (negative = burst)
    int64_t shiftNs = 0;
    double warmup = 600;            // s excluded from steady-state backlog/delay checks
    int percentile = 95;            // tuner percentile (the app's default is 99)
    // sender break (seek / pause in the mirrored app): the sender stops at breakAt (s of its
    // timeline) and restarts breakFor s later, its timeline jumped by as much
    double breakAt = -1;
    double breakFor = 0;
    // on restart, its packets arrive lateNs later than usual for lateFor s (sender time), then it
    // catches up in one burst
    int64_t lateNs = 0;
    double lateFor = 0;
    // timeline continuous across the break instead (rtp clock paused): the timestamps jump by
    // breakFor only relabelAfter s after the restart (sender's next timestamp sync)
    double relabelAfter = -1;
    double readyAt = -1;            // s: audio on schedule available again after the break
    double afterAt = -1;            // s: 1 s window for the "after" delay mean (default event + 4 s)
    int64_t cbJitterNs = 0;         // callbacks run up to this early / late (the DAC doesn't)
};

struct Result {
    TimelineMetrics::Debug m{};
    uint64_t slips = 0;
    int32_t steerPpm = 0;
    double maxBacklogErrMs = 0;     // worst 1 s average of backlog - cushion, after warmup
    double delayMinMs = 1e18, delayMaxMs = -1e18, delayMeanMs = 0;  // after warmup
    int maxStep = 0;                // ch0 sample-to-sample step, whole run (sine alone: ~501)
    double maxPtsErrFrames = 0;     // read-head pts claimed vs frame actually written
    int64_t ptsChecks = 0;
    double delayBeforeMs = 0, delayAfterMs = 0;  // around stall/shift/break: 1 s means
    // sender break
    double resumeAt = -1;           // s (sender time): first callback playing again after the event's underrun
    double maxDelayErrMs = 0;       // worst |presentation delay - before| while playing, from the break on
    double cushionBeforeMs = 0, maxCushionAfterMs = 0;
    uint32_t settledTrims = 0;      // trims from 0.5 s after resuming to the end
};

int16_t sineTable[RATE];

void makeTable() {
    for (int i = 0; i < RATE; i++) sineTable[i] = (int16_t)lrint(AMP * sin(2 * M_PI * i / RATE));
}

void fillPacket(int64_t firstFrame, int frames, int16_t *dst) {
    for (int f = 0; f < frames; f++) {
        const int64_t s = firstFrame + f;
        dst[f * CH] = sineTable[(s * 440) % RATE];
        dst[f * CH + 1] = (int16_t)(s % RAMP);
    }
}

Result run(const Scenario &sc, TimelineBuffer &tb) {
    std::mt19937_64 rng(42), cbRng(7);
    std::uniform_int_distribution<int64_t> jitter(0, sc.jitterNs);
    std::uniform_int_distribution<int64_t> cbJitter(-sc.cbJitterNs, sc.cbJitterNs);
    const double outRate = RATE * (1.0 + sc.ppm * 1e-6);
    const int64_t cb0 = PTS0 + 50 * MS;          // first output callback
    const int64_t outLatNs = 20 * MS;           // written frame -> heard
    const int64_t totalCbs = (int64_t)(sc.seconds * outRate / sc.cbFrames);
    std::vector<int16_t> pkt((size_t)sc.pktFrames * CH), out((size_t)sc.cbFrames * CH);

    // sender break: packet i is sent at real(i) (its timeline after the restart is jumped by the
    // break) and stamped label(i) (jumped too, or only from the relabel on). content follows the
    // label, so the ramp check holds whatever the labels say
    const int64_t breakIdx = sc.breakAt >= 0 ? (int64_t)ceil(sc.breakAt * RATE / sc.pktFrames) : INT64_MAX;
    const int64_t breakFrames = llround(sc.breakFor * RATE);
    const int64_t relabelIdx = sc.relabelAfter >= 0 ? breakIdx + (int64_t)(sc.relabelAfter * RATE / sc.pktFrames) : breakIdx;
    auto realFrames = [&](int64_t i) { return i * sc.pktFrames + (i >= breakIdx ? breakFrames : 0); };
    auto labelFrames = [&](int64_t i) { return i * sc.pktFrames + (i >= relabelIdx ? breakFrames : 0); };

    int64_t lastArrival = INT64_MIN;
    auto arrivalOf = [&](int64_t i) {
        const int64_t pts = PTS0 + framesToNs(realFrames(i));
        int64_t a = pts + sc.latNs + jitter(rng);
        if (sc.shiftAt >= 0 && pts - PTS0 >= (int64_t)(sc.shiftAt * SEC)) a += sc.shiftNs;
        if (i >= breakIdx && pts - (PTS0 + framesToNs(realFrames(breakIdx))) < (int64_t)(sc.lateFor * SEC))
            a += sc.lateNs;
        if (sc.stallAt >= 0) {
            const int64_t s0 = PTS0 + (int64_t)(sc.stallAt * SEC), s1 = s0 + (int64_t)(sc.stallFor * SEC);
            if (a >= s0 && a < s1) a = s1;
        }
        if (a < lastArrival) a = lastArrival;  // in order
        lastArrival = a;
        return a;
    };

    Result r;
    int64_t pktIdx = 0;
    int64_t nextArrival = arrivalOf(0);
    int prev0 = 0;
    bool havePrev = false;
    double secErrSum = 0;
    int secErrN = 0;
    int64_t nextSecNs = cb0 + SEC;
    double delaySum = 0;
    int64_t delayN = 0;
    double evBeforeSum = 0, evAfterSum = 0;
    int evBeforeN = 0, evAfterN = 0;
    const double evAt = sc.breakAt >= 0 ? sc.breakAt : sc.stallAt >= 0 ? sc.stallAt : sc.shiftAt;
    const double afterAt = sc.afterAt >= 0 ? sc.afterAt : evAt + 4.0;
    uint32_t prevEvents = 0, prevPrevEvents = 0;
    bool prevClaimed = false;
    bool gapSinceEv = false;
    uint32_t trimsAtSettle = 0;
    bool settleSeen = false;

    for (int64_t j = 0; j < totalCbs; j++) {
        const int64_t now = cb0 + (int64_t)llround((double)j * sc.cbFrames * 1e9 / outRate);
        while (nextArrival <= now) {
            if (pktIdx != sc.lostPacket) {
                fillPacket(labelFrames(pktIdx), sc.pktFrames, pkt.data());
                tb.write(pkt.data(), pkt.size(), PTS0 + framesToNs(labelFrames(pktIdx)), nextArrival);
            }
            pktIdx++;
            nextArrival = arrivalOf(pktIdx);
        }

        const int64_t claimed = tb.readHeadPtsNs();
        const TimelineBuffer::Debug before = tb.debugInfo();
        tb.read(out.data(), sc.cbFrames, sc.cbJitterNs ? now + cbJitter(cbRng) : now);
        const TimelineBuffer::Debug after = tb.debugInfo();
        const double t = (double)(now - cb0) / SEC;

        // clicks: biggest sample step on the sine channel
        for (int k = 0; k < sc.cbFrames; k++) {
            const int v = out[(size_t)k * CH];
            if (havePrev) r.maxStep = std::max(r.maxStep, std::abs(v - prev0));
            prev0 = v;
            havePrev = true;
        }

        // the read-head pts claimed before the callback must be the frame it then wrote.
        // skipped around fades (skips, silences, underruns scale the ramp channel)
        const uint32_t events = after.metrics.trims + after.metrics.silences + after.metrics.underruns;
        const bool quiet = events == prevEvents && prevEvents == prevPrevEvents;
        prevPrevEvents = prevEvents;
        prevEvents = events;
        const int64_t lostPts = sc.lostPacket >= 0 ? PTS0 + framesToNs(sc.lostPacket * sc.pktFrames) : 0;
        const bool nearLost = sc.lostPacket >= 0 && claimed != TimelineBuffer::NO_PTS &&
                              claimed > lostPts - 30 * MS && claimed < lostPts + 40 * MS;
        // (and not the callback after a restart: its fade-in may still run when the restart
        // started mid-callback, on schedule)
        const bool restarted = !prevClaimed;
        prevClaimed = claimed != TimelineBuffer::NO_PTS;
        if (claimed != TimelineBuffer::NO_PTS && quiet && !nearLost && !restarted &&
                tb.readHeadPtsNs() != TimelineBuffer::NO_PTS) {
            const double s = (double)(claimed - PTS0) * RATE / 1e9;
            const double expect = fmod(s, RAMP);
            if (expect > 4 && expect < RAMP - 4) {
                const double err = std::fabs(out[1] - expect);
                r.maxPtsErrFrames = std::max(r.maxPtsErrFrames, err);
                r.ptsChecks++;
            }
        }

        // presentation delay: next frame to be written is heard at this time, minus its pts
        const int64_t head = tb.readHeadPtsNs();
        if (head != TimelineBuffer::NO_PTS) {
            const double heard = (double)cb0 + outLatNs + (double)(j + 1) * sc.cbFrames * 1e9 / outRate;
            const double delayMs = (heard - (double)head) / 1e6;
            if (t >= sc.warmup) {
                r.delayMinMs = std::min(r.delayMinMs, delayMs);
                r.delayMaxMs = std::max(r.delayMaxMs, delayMs);
                delaySum += delayMs;
                delayN++;
            }
            if (evAt >= 0 && t >= evAt - 1.0 && t < evAt) { evBeforeSum += delayMs; evBeforeN++; }
            if (evAt >= 0 && t >= afterAt && t < afterAt + 1.0) { evAfterSum += delayMs; evAfterN++; }
            if (evAt >= 0 && t >= evAt && evBeforeN > 0)
                r.maxDelayErrMs = std::max(r.maxDelayErrMs, std::fabs(delayMs - evBeforeSum / evBeforeN));
        }
        if (evAt >= 0 && t >= evAt) {
            if (r.cushionBeforeMs == 0) r.cushionBeforeMs = before.tunedCushionMs;
            r.maxCushionAfterMs = std::max(r.maxCushionAfterMs, (double)after.tunedCushionMs);
            if (head == TimelineBuffer::NO_PTS) gapSinceEv = true;
            else if (gapSinceEv && r.resumeAt < 0) r.resumeAt = (double)(now - PTS0) / SEC;  // sender time
            if (r.resumeAt >= 0 && !settleSeen && (double)(now - PTS0) / SEC >= r.resumeAt + 0.5) {
                settleSeen = true;
                trimsAtSettle = after.metrics.trims;
            }
        }

        // backlog error vs the tuned cushion, 1 s averages
        if (t >= sc.warmup) {
            secErrSum += (double)before.backlogMs - (double)before.tunedCushionMs;
            secErrN++;
        }
        if (now >= nextSecNs) {
            if (secErrN >= 50) r.maxBacklogErrMs = std::max(r.maxBacklogErrMs, std::fabs(secErrSum / secErrN));
            secErrSum = 0;
            secErrN = 0;
            nextSecNs += SEC;
        }
    }
    r.m = tb.debugInfo().metrics;
    r.slips = tb.slips();
    r.steerPpm = tb.steerPpm();
    r.delayMeanMs = delayN ? delaySum / (double)delayN : 0;
    r.delayBeforeMs = evBeforeN ? evBeforeSum / evBeforeN : 0;
    r.delayAfterMs = evAfterN ? evAfterSum / evAfterN : 0;
    r.settledTrims = settleSeen ? r.m.trims - trimsAtSettle : 0;
    return r;
}

Result run(const Scenario &sc) {
    TimelineBuffer tb(RATE, CH, 0, sc.percentile);
    return run(sc, tb);
}

int failures = 0;

void check(bool ok, const char *what) {
    printf("    [%s] %s\n", ok ? "ok" : "FAIL", what);
    if (!ok) failures++;
}

void print(const Scenario &sc, const Result &r) {
    printf("%s (%.0f s simulated, output %+.0f ppm)\n", sc.name, sc.seconds, sc.ppm);
    printf("    trims=%u underruns=%u silences=%u drops=%u slips=%llu steer=%+d ppm\n",
           r.m.trims, r.m.underruns, r.m.silences, r.m.drops, (unsigned long long)r.slips, r.steerPpm);
    if (sc.warmup < sc.seconds)
        printf("    after %.0f s warmup: |backlog-cushion| worst 1 s mean %.2f ms; presentation delay "
               "%.2f..%.2f ms (mean %.2f, spread %.2f ms)\n",
               sc.warmup, r.maxBacklogErrMs, r.delayMinMs, r.delayMaxMs, r.delayMeanMs,
               r.delayMaxMs - r.delayMinMs);
    printf("    read-head pts vs played frame: max err %.2f frames over %lld checks; max sine step %d\n",
           r.maxPtsErrFrames, (long long)r.ptsChecks, r.maxStep);
    if (sc.stallAt >= 0 || sc.shiftAt >= 0)
        printf("    presentation delay 1 s before event %.2f ms, 4-5 s after %.2f ms\n",
               r.delayBeforeMs, r.delayAfterMs);
}

// sine alone steps by at most ~501; a hard cut to/from silence would step up to AMP
constexpr int MAX_CLEAN_STEP = 600;

void driftCase(double ppm) {
    Scenario sc{ppm > 0 ? "drift, output fast" : "drift, output slow", 2 * 3600.0};
    sc.ppm = ppm;
    const Result r = run(sc);
    print(sc, r);
    printf("    (uncompensated, the backlog would have moved %.0f ms by the end)\n",
           std::fabs(ppm) * 1e-6 * sc.seconds * 1000);
    check(r.m.underruns == 0, "no underruns");
    check(r.m.trims == 0, "no hard skips");
    check(r.m.silences == 0 && r.m.drops == 0, "no silences / drops");
    check(r.maxBacklogErrMs <= 5.0, "backlog within 5 ms of the cushion (1 s means)");
    check(r.delayMaxMs - r.delayMinMs <= 8.0, "presentation delay steady within 8 ms for 2 h");
    check(r.maxPtsErrFrames <= 1.0 && r.ptsChecks > 100000, "read-head pts exact to 1 frame");
    check(r.maxStep <= MAX_CLEAN_STEP, "no clicks (slips are seamless)");
    check(std::abs(r.steerPpm + (int)ppm) <= 15, "steering settled on the clock drift (rate offset ~ -drift)");
}

void stallCase(double stallMs, bool expectUnderrun) {
    char name[64];
    snprintf(name, sizeof name, "wi-fi stall %.0f ms", stallMs);
    Scenario sc{name, 60.0};
    sc.stallAt = 30.0;
    sc.stallFor = stallMs / 1000.0;
    sc.warmup = 1e9;
    const Result r = run(sc);
    print(sc, r);
    if (expectUnderrun) {
        check(r.m.underruns == 1, "one underrun (stall longer than the 250 ms cushion)");
        check(r.m.trims == 1, "stale burst dropped once on resume (catch-up, while silent)");
    } else {
        check(r.m.underruns == 0 && r.m.trims == 0, "absorbed by the cushion: no underrun, no skip");
    }
    // resolution: the catch-up lands on the cushion at one instant, the steady state centres the
    // packet/callback sawtooth on it, so they differ by up to about half a packet + callback
    check(std::fabs(r.delayAfterMs - r.delayBeforeMs) <= 10.0, "presentation delay back where it was (10 ms)");
    if (stallMs > 1000) {
        printf("    cushion %.0f ms before, max %.0f ms after\n", r.cushionBeforeMs, r.maxCushionAfterMs);
        check(r.maxCushionAfterMs <= r.cushionBeforeMs + 10.0,
              "longer than any cushion: not learned from (no cushion inflation)");
    }
    check(r.maxStep <= MAX_CLEAN_STEP, "no clicks (underrun and resume are faded)");
    check(r.maxPtsErrFrames <= 1.0, "read-head pts exact to 1 frame");
}

void lostPacketCase() {
    Scenario sc{"lost packet (gap filled with silence)", 30.0};
    sc.lostPacket = 1000;
    sc.warmup = 1e9;
    const Result r = run(sc);
    print(sc, r);
    check(r.m.silences == 1 && r.m.underruns == 0 && r.m.trims == 0, "one silence, nothing else");
    check(r.maxStep <= MAX_CLEAN_STEP, "no clicks (silence edges are faded)");
    check(r.maxPtsErrFrames <= 1.0, "read-head pts exact to 1 frame");
}

void hardSkipCase() {
    Scenario sc{"800 ms burst ahead of schedule (large error)", 60.0};
    sc.shiftAt = 20.0;
    sc.shiftNs = -800 * MS;
    sc.warmup = 1e9;
    const Result r = run(sc);
    print(sc, r);
    check(r.m.trims == 1 && r.m.underruns == 0, "one hard skip, no underrun");
    check(r.maxStep <= MAX_CLEAN_STEP, "no clicks (skip faded out and in)");
    check(std::fabs(r.delayAfterMs - (r.delayBeforeMs - 800)) <= 30.0,
          "presentation delay follows the new arrival schedule after the skip");
}

// a sender break: the movie is sought or paused on the phone. modelled on the TV log: audio
// stops (one underrun), the sender restarts with its timeline jumped, sometimes ~1.6 s late at
// first and then catching up in a burst. the app's tuner runs at the 99th percentile
Scenario breakScenario(const char *name, double breakFor) {
    Scenario sc{name, 60.0};
    sc.percentile = 99;
    sc.warmup = 1e9;
    sc.breakAt = 30.0;
    sc.breakFor = breakFor;
    sc.afterAt = 52.0;  // well after the restart, whatever it took
    // first audio after the restart: sender's restart + network latency
    sc.readyAt = sc.breakAt + breakFor + (double)sc.latNs / SEC;
    return sc;
}

// expectNewScheduleMs: the sender's schedule moved for good by this much (0: transient only)
void breakCase(const Scenario &sc, double expectNewScheduleMs = 0) {
    TimelineBuffer tb(RATE, CH, 0, sc.percentile);
    const Result r = run(sc, tb);
    print(sc, r);
    printf("    break at %.1f s: cushion %.0f ms before, max %.0f ms after; audio back at %.2f s "
           "(audio on schedule from %.2f s); presentation delay %.2f ms before, worst %.2f ms off it "
           "from the break on; trims after settling %u\n",
           sc.breakAt, r.cushionBeforeMs, r.maxCushionAfterMs, r.resumeAt, sc.readyAt, r.delayBeforeMs,
           r.maxDelayErrMs, r.settledTrims);
    printf("    breaks=%u resyncs=%u abandoned=%u\n", tb.breaks(), tb.resyncs(), tb.resyncAbandons());
    check(r.m.underruns == 1, "one underrun (the sender stopped)");
    check(r.maxCushionAfterMs <= r.cushionBeforeMs + 10.0, "no cushion inflation (within 10 ms of before)");
    if (expectNewScheduleMs == 0) {
        check(r.maxDelayErrMs <= 20.0, "presentation delay within 20 ms of before, every callback from the break on");
        check(r.resumeAt >= 0 && r.resumeAt <= sc.readyAt + 1.0, "audio back within 1 s of audio on schedule arriving");
    } else {
        check(std::fabs(r.delayAfterMs - r.delayBeforeMs - expectNewScheduleMs) <= 20.0,
              "presentation delay follows the sender's new schedule (20 ms)");
        check(r.resumeAt >= 0 && r.resumeAt <= sc.readyAt + TimelineBuffer::RESYNC_TIMEOUT_MS / 1000.0 + 1.0,
              "audio back within the resync timeout + 1 s");
    }
    check(r.m.trims <= 1 && r.settledTrims == 0, "stale audio dropped at most once, while silent; no skips after");
    check(r.maxStep <= MAX_CLEAN_STEP, "no clicks (stop and restart are faded)");
    check(r.maxPtsErrFrames <= 1.0 && r.ptsChecks > 1000, "read-head pts exact to 1 frame");
}

}  // namespace

int main() {
    makeTable();
    driftCase(+60);
    driftCase(-60);
    stallCase(300, true);
    stallCase(200, false);
    lostPacketCase();
    hardSkipCase();

    // seek: the sender restarts on its usual schedule
    breakCase(breakScenario("seek, sender restarts on time", 2.5));
    // seek as on the TV: the first 2 s of restarted audio arrive 1.6 s late, then a burst
    {
        Scenario sc = breakScenario("seek, restart 1.6 s late, then catch-up burst (TV log)", 2.5);
        sc.lateNs = 1600 * MS;
        sc.lateFor = 2.0;
        // the burst: the last late packet's arrival, the on-schedule ones queued behind it
        sc.readyAt = sc.breakAt + sc.breakFor + sc.lateFor + (double)(sc.lateNs + sc.latNs) / SEC;
        breakCase(sc);
    }
    // seek, the restarted audio arrives in one burst 1 s after the restart
    {
        Scenario sc = breakScenario("seek, restart delivered as one burst after 1 s", 2.5);
        sc.stallAt = sc.breakAt + sc.breakFor;
        sc.stallFor = 1.0;
        sc.readyAt = sc.stallAt + sc.stallFor;
        breakCase(sc);
    }
    // timeline paused instead of jumped: stamps 2.5 s stale until the sender's next timestamp sync
    {
        Scenario sc = breakScenario("seek, timestamps stale until a resync 1 s in", 2.5);
        sc.relabelAfter = 1.0;
        sc.readyAt = sc.breakAt + sc.breakFor + sc.relabelAfter + (double)sc.latNs / SEC;
        breakCase(sc);
    }
    // pause / resume
    breakCase(breakScenario("pause 20 s, resume", 20.0));
    // the TV-log seek with callbacks running up to 5 ms early / late
    {
        Scenario sc = breakScenario("seek as on the TV, jittery output callbacks (+-5 ms)", 2.5);
        sc.lateNs = 1600 * MS;
        sc.lateFor = 2.0;
        sc.readyAt = sc.breakAt + sc.breakFor + sc.lateFor + (double)(sc.lateNs + sc.latNs) / SEC;
        sc.cbJitterNs = 5 * MS;
        breakCase(sc);
    }
    // the sender comes back 600 ms late for good: after the resync timeout, its new schedule
    {
        Scenario sc = breakScenario("sender restarts 600 ms late for good (new schedule)", 2.5);
        sc.lateNs = 600 * MS;
        sc.lateFor = 1e6;
        breakCase(sc, 600);
    }
    // a wi-fi stall longer than any cushion: same timeline, but not learned from
    stallCase(1500, true);
    printf(failures ? "\n%d check(s) FAILED\n" : "\nall checks passed\n", failures);
    return failures ? 1 : 0;
}
