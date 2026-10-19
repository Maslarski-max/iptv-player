package com.maslarski.iptv.util

import java.io.File
import java.net.NetworkInterface

object DeviceInfo {
    private val PREFERRED_INTERFACES = listOf("eth0", "wlan0", "eth1", "wlan1")
    private val VIRTUAL_PREFIXES = listOf("lo", "dummy", "tun", "tap", "p2p", "rmnet", "ccmni", "ip6tnl", "sit", "ifb", "gre", "veth", "br-", "docker", "wlan-aware")
    private val MAC_PATTERN = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
    private val PLACEHOLDER_MACS = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

    /**
     * Hardware (MAC) address of the Ethernet/Wi-Fi adapter. `/sys/class/net/<iface>/address` is read
     * first (interfaces that are up win, then eth0/wlan0/eth1/wlan1, then any other physical adapter such
     * as `usb0`), falling back to [NetworkInterface.getHardwareAddress]. Returns `null` when the OS hides
     * both (WifiManager returns 02:00:00:00:00:00 since Android 6; Android 11+ sandboxes sysfs and
     * NetworkInterface for third-party apps).
     */
    fun macAddress(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            .filter { isPhysical(it.name) }
            .sortedWith(
                compareByDescending<NetworkInterface> { runCatching { it.isUp }.getOrDefault(false) }
                    .thenBy { preferenceRank(it.name) },
            )
        val sysfsNames = File("/sys/class/net").listFiles()?.map { it.name }.orEmpty()
            .filter { isPhysical(it) }.sortedBy { preferenceRank(it) }
        (interfaces.map { it.name } + PREFERRED_INTERFACES + sysfsNames).distinct()
            .firstNotNullOfOrNull(::sysfsAddress)?.let { return it }
        return interfaces.firstNotNullOfOrNull { nic -> runCatching { nic.hardwareAddress }.getOrNull()?.let(::normalize) }
    }

    private fun sysfsAddress(name: String): String? =
        runCatching { File("/sys/class/net/$name/address").readText() }.getOrNull()?.let(::normalize)

    private fun isPhysical(name: String): Boolean =
        name in PREFERRED_INTERFACES ||
            (VIRTUAL_PREFIXES.none { name.startsWith(it) } && File("/sys/class/net/$name/device").exists())

    private fun preferenceRank(name: String): Int =
        PREFERRED_INTERFACES.indexOf(name).let { if (it < 0) PREFERRED_INTERFACES.size else it }

    private fun normalize(bytes: ByteArray): String? =
        if (bytes.size != 6) null else normalize(bytes.joinToString(":") { "%02X".format(it) })

    private fun normalize(raw: String): String? =
        raw.trim().uppercase().takeIf { it.matches(MAC_PATTERN) && it !in PLACEHOLDER_MACS }
}
