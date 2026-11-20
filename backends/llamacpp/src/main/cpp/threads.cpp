#include "threads.h"

#include <sched.h>

#include <algorithm>
#include <cstdio>
#include <vector>

namespace den {

namespace {

long read_cpu(int cpu, const char *file) {
    char path[96];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/%s", cpu, file);
    FILE *f = std::fopen(path, "r");
    if (!f) return 0;
    long v = 0;
    if (std::fscanf(f, "%ld", &v) != 1) v = 0;
    std::fclose(f);
    return v;
}

// A core below this fraction of the fastest one slows decode down.
// Capacity, the kernel's measure of work per second: T760 A55 377, A76 1022 (0.37, slow).
constexpr double kSlowCapacity = 0.5;
// Frequency, when the kernel gives no capacity. It misses little cores clocked high (T760: 2.00/2.21 = 0.91).
// 778G: 1.80/2.40 = 0.75 (slow); 8 Elite: 3.53/4.47 = 0.79 (fast).
constexpr double kSlowFreq = 0.77;

int count_fast(const std::vector<long> &speeds, double ratio) {
    long top = *std::max_element(speeds.begin(), speeds.end());
    int fast = 0;
    for (long s : speeds) {
        if (top == 0 || s >= top * ratio) ++fast;
    }
    return fast;
}

} // namespace

Threads pick_threads() {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) != 0) return {4, 4};

    std::vector<long> caps, freqs;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
        if (!CPU_ISSET(cpu, &set)) continue;
        caps.push_back(read_cpu(cpu, "cpu_capacity"));
        freqs.push_back(read_cpu(cpu, "cpufreq/cpuinfo_max_freq"));
    }
    if (freqs.empty()) return {1, 1};

    bool has_caps = std::all_of(caps.begin(), caps.end(), [](long c) { return c > 0; });
    int fast = has_caps ? count_fast(caps, kSlowCapacity) : count_fast(freqs, kSlowFreq);
    int all = (int) freqs.size();
    return {std::max(1, fast), all};
}

} // namespace den
