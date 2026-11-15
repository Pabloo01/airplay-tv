#ifndef AUDIO_TIME_H
#define AUDIO_TIME_H

#include <cstdint>
#include <ctime>

static constexpr int64_t NS_PER_SEC = 1000000000LL;

static inline int64_t monoNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * NS_PER_SEC + ts.tv_nsec;
}

// clock of the sender timestamps once UxPlay maps them to receiver time (raop_ntp_get_local_time)
static inline int64_t realtimeNs() {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * NS_PER_SEC + ts.tv_nsec;
}

#endif  // AUDIO_TIME_H
