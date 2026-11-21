package ai.fanak.den.core.router

internal actual fun Throwable.isNetworkError() =
    this is java.net.UnknownHostException || this is java.net.SocketTimeoutException || this is java.net.ConnectException
