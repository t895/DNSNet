package dev.clombardo.dnsnet.vpn

class NoNetworkException : Exception {
    constructor(s: String?) : super(s)
    constructor(s: String?, t: Throwable?) : super(s, t)
}

class PrepareFailedException: Exception {
    constructor(s: String?) : super(s)
    constructor(s: String?, t: Throwable?) : super(s, t)
}
