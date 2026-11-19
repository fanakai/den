#include "threads.h"

#include <sched.h>

#include <algorithm>
#include <cstdio>
#include <vector>

namespace den {

namespace {

long max_freq(int cpu) {
    char path[96];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE *f = std::fopen(path, "r");
    if (!f) return 0;
    long khz = 0;
    if (std::fscanf(f, "%ld", &khz) != 1) khz = 0;
    std::fclose(f);
    return khz;
}

// A cluster below this fraction of the fastest one slows decode down.
// 778G: 1.80/2.40 = 0.75 (slow); 8 Elite: 3.53/4.47 = 0.79 (fast).
constexpr double kSlowRatio = 0.77;

} // namespace

Threads pick_threads() {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) != 0) return {4, 4};

    std::vector<long> freqs;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
        if (CPU_ISSET(cpu, &set)) freqs.push_back(max_freq(cpu));
    }
    if (freqs.empty()) return {1, 1};

    long top = *std::max_element(freqs.begin(), freqs.end());
    int fast = 0;
    for (long f : freqs) {
        if (top == 0 || f >= top * kSlowRatio) ++fast;
    }
    int all = (int) freqs.size();
    return {std::max(1, fast), all};
}

} // namespace den
