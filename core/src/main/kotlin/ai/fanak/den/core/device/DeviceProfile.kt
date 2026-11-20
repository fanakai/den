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
)

interface DeviceProbe {
    fun profile(): DeviceProfile
    fun thermal(): ThermalState
}
