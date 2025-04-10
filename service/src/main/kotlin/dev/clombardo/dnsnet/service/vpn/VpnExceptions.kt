/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */

package dev.clombardo.dnsnet.service.vpn

class NoNetworkException : Exception {
    constructor(s: String?) : super(s)
    constructor(s: String?, t: Throwable?) : super(s, t)
}
