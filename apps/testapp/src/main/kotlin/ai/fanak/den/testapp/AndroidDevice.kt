package ai.fanak.den.testapp

import ai.fanak.den.core.device.DeviceProbe
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.device.Platform
import ai.fanak.den.core.device.SocFamilies
import ai.fanak.den.core.device.ThermalState
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

// Moves to the SDK with #5.
class AndroidDevice(private val context: Context) : DeviceProbe {
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
            thermal = thermal(),
        )
    }

    override fun thermal(): ThermalState {
        if (Build.VERSION.SDK_INT < 29) return ThermalState.NONE
        val status = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        return ThermalState.entries.getOrElse(status) { ThermalState.NONE }
    }
}
