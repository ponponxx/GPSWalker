package com.gpswalker.companion

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /** Returns the first site-local IPv4 address (the Wi-Fi LAN IP), or null. */
    fun localIp(): String? {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress &&
                        addr is Inet4Address &&
                        addr.isSiteLocalAddress
                    ) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
