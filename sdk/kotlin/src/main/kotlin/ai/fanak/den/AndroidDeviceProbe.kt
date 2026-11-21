package ai.fanak.den

import ai.fanak.den.core.device.CpuFeatures
import ai.fanak.den.core.device.DeviceProbe
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.device.NpuInfo
import ai.fanak.den.core.device.Platform
import ai.fanak.den.core.device.SocFamilies
import ai.fanak.den.core.device.ThermalState
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import java.io.File

class AndroidDeviceProbe(context: Context) : DeviceProbe {
    private val context = context.applicationContext

    // What does not change while the app runs, read once. Each part is left empty when the system does not let an app
    // read it.
    private val cpuFeatures by lazy { runCatching { CpuFeatures.parse(File("/proc/cpuinfo").readText()) }.getOrDefault(emptySet()) }
    private val gpu by lazy { runCatching { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim() }.getOrNull()?.ifEmpty { null } }
    private val openCl by lazy { VENDOR_LIBS.any { File(it, "libOpenCL.so").exists() } }
    private val vulkan by lazy {
        context.packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?.let { "${it.version shr 22}.${(it.version shr 12) and 0x3ff}" }
    }

    // An NPU runtime by its vendor library, found without loading it.
    private val npu by lazy {
        NPU_LIBS.firstNotNullOfOrNull { (lib, vendor) -> vendor.takeIf { VENDOR_LIBS.any { File(it, lib).exists() } } }?.let { NpuInfo(it) }
    }

    override fun profile(): DeviceProfile {
        val mem = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        return DeviceProfile(
            platform = Platform.ANDROID,
            osApi = Build.VERSION.SDK_INT,
            soc = soc,
            socFamily = SocFamilies.of(soc),
            abi = Build.SUPPORTED_ABIS.first(),
            ramMb = (mem.totalMem shr 20).toInt(),
            availRamMb = (mem.availMem shr 20).toInt(),
            gpu = gpu,
            npu = npu,
            thermal = thermal(),
            cpuFeatures = cpuFeatures,
            vulkan = vulkan,
            openCl = openCl,
        )
    }

    override fun thermal(): ThermalState {
        if (Build.VERSION.SDK_INT < 29) return ThermalState.NONE
        val status = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        return ThermalState.entries.getOrElse(status) { ThermalState.NONE }
    }

    private companion object {
        val VENDOR_LIBS = listOf("/vendor/lib64", "/system/vendor/lib64").map(::File)

        // Qualcomm Hexagon (FastRPC to the DSP), MediaTek APU (NeuroPilot), Samsung (ENN).
        val NPU_LIBS = listOf("libcdsprpc.so" to "qualcomm-hexagon", "libneuron_adapter.so" to "mediatek-apu", "libenn_user.so" to "samsung-enn")
    }
}
