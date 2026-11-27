package ai.fanak.den.core.router

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun routerCasesDir(): String = getenv("DEN_ROUTER_CASES")?.toKString() ?: error("DEN_ROUTER_CASES is not set")
