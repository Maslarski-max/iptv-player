package com.maslarski.iptv.util

import java.io.File
import java.net.NetworkInterface

object DeviceInfo {
    private val PREFERRED_INTERFACES = listOf("wlan0", "eth0")

    /**
     * Hardware (MAC) address of the active Wi-Fi/Ethernet interface, or `null` when the OS hides it
     * (WifiManager returns 02:00:00:00:00:00 since Android 6 and NetworkInterface may return null on
     * Android 11+, so sysfs is tried as a last resort).
     */
    fun macAddress(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        val ordered = PREFERRED_INTERFACES.mapNotNull { name -> interfaces.firstOrNull { it.name == name } } +
            interfaces.filter { it.name !in PREFERRED_INTERFACES && !it.isLoopback }
        ordered.firstNotNullOfOrNull { nic -> runCatching { nic.hardwareAddress }.getOrNull()?.let(::format) }
            ?.let { return it }
        return PREFERRED_INTERFACES.firstNotNullOfOrNull { name ->
            runCatching { File("/sys/class/net/$name/address").readText().trim().uppercase() }.getOrNull()
                ?.takeIf { it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) && !it.startsWith("00:00:00:00:00:00") }
        }
    }

    private fun format(bytes: ByteArray): String? {
        if (bytes.size != 6 || bytes.all { it == 0.toByte() }) return null
        return bytes.joinToString(":") { "%02X".format(it) }
    }
}
