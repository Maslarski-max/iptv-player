package com.maslarski.iptv.util

import java.io.File
import java.net.NetworkInterface

object DeviceInfo {
    private val PHYSICAL_INTERFACES = listOf("wlan0", "eth0", "wlan1", "eth1")
    private val MAC_PATTERN = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
    private val PLACEHOLDER_MACS = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

    /**
     * Hardware (MAC) address of the Wi-Fi/Ethernet adapter, preferring an interface that is currently
     * up, or `null` when the OS hides it (WifiManager returns 02:00:00:00:00:00 since Android 6 and
     * NetworkInterface may return null on Android 11+, so sysfs is tried as a last resort).
     */
    fun macAddress(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            .filter { it.name in PHYSICAL_INTERFACES }
            .sortedWith(compareByDescending<NetworkInterface> { runCatching { it.isUp }.getOrDefault(false) }
                .thenBy { PHYSICAL_INTERFACES.indexOf(it.name) })
        interfaces.firstNotNullOfOrNull { nic -> runCatching { nic.hardwareAddress }.getOrNull()?.let(::normalize) }
            ?.let { return it }
        val upFirst = interfaces.map { it.name } + PHYSICAL_INTERFACES
        return upFirst.distinct().firstNotNullOfOrNull { name ->
            runCatching { File("/sys/class/net/$name/address").readText() }.getOrNull()?.let(::normalize)
        }
    }

    private fun normalize(bytes: ByteArray): String? =
        if (bytes.size != 6) null else normalize(bytes.joinToString(":") { "%02X".format(it) })

    private fun normalize(raw: String): String? =
        raw.trim().uppercase().takeIf { it.matches(MAC_PATTERN) && it !in PLACEHOLDER_MACS }
}
