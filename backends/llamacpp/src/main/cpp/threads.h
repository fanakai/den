#pragma once

namespace den {

struct Threads {
    int decode;
    int batch;
};

// Threads from the cores this process may use now (Android moves apps between cpusets).
// Decode skips a clearly slower cluster; batch uses every allowed core.
Threads pick_threads();

} // namespace den
