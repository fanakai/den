package ai.fanak.den.core.router

// No network code in the native target yet: its stores report offline through OfflineException.
internal actual fun Throwable.isNetworkError() = false
