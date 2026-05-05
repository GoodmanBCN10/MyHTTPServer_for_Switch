package com.example.myhttpserver

import java.net.NetworkInterface
import java.net.Inet4Address
import java.util.Collections

object NetworkUtils {
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Priorizamos interfaces de wifi o hotspot (wlan)
            for (intf in interfaces.sortedByDescending { it.name.contains("wlan") }) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }
}
