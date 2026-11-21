package ai.fanak.den.core.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Platform { ANDROID, IOS }

@Serializable
enum class Accel {
    @SerialName("cpu") CPU,
    @SerialName("gpu") GPU,
    @SerialName("npu") NPU,
}

// Same levels as Android PowerManager thermal status.
enum class ThermalState { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

@Serializable
data class NpuInfo(
    val vendor: String,
    val version: String? = null,
)

@Serializable
data class DeviceProfile(
    val platform: Platform,
    val osApi: Int,
    val soc: String,
    val socFamily: String,
    val abi: String,
    val ramMb: Int,
    val availRamMb: Int,
    val gpu: String? = null,
    val npu: NpuInfo? = null,
    val thermal: ThermalState = ThermalState.NONE,
    // CPU features that decide the speed of quantized kernels: dotprod, fp16, i8mm, bf16, sve, sve2.
    val cpuFeatures: Set<String> = emptySet(),
    // Vulkan version the GPU driver reports (e.g. "1.1"), and whether an OpenCL driver is present.
    val vulkan: String? = null,
    val openCl: Boolean = false,
)

interface DeviceProbe {
    fun profile(): DeviceProfile
    fun thermal(): ThermalState
}
