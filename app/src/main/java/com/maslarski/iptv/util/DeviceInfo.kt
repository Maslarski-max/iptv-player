package com.maslarski.iptv.util

import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

object DeviceInfo {
    private val PREFERRED_INTERFACES = listOf("eth0", "wlan0", "eth1", "wlan1")
    private val VIRTUAL_PREFIXES = listOf("lo", "dummy", "tun", "tap", "p2p", "rmnet", "ccmni", "ip6tnl", "sit", "ifb", "gre", "veth", "br-", "docker", "wlan-aware")
    private val MAC_PATTERN = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
    private val PLACEHOLDER_MACS = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

    private val SHELL_COMMANDS = listOf(
        listOf("ip", "-o", "link", "show"),
        listOf("ip", "link", "show"),
        listOf("sh", "-c", "cat /sys/class/net/*/address"),
        listOf("getprop"),
    )
    private val MAC_PROPS = listOf(
        "ro.boot.mac", "ro.boot.ethaddr", "ubootenv.var.ethaddr", "ro.mac", "persist.sys.mac", "ro.boot.wifimac",
        "ro.boot.wifi_mac", "ro.wifi.mac", "persist.sys.wifi.mac", "ro.vendor.mac", "ro.hardware.mac",
    )
    private val ANY_MAC = Regex("(?i)\\b([0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    private val IP_LINK_IFACE = Regex("^\\d+:\\s*([^:@\\s]+)")

    /**
     * Physical (MAC) address of the Ethernet/Wi-Fi adapter, or `null` when none can be read. All interface
     * names known to [NetworkInterface] or sysfs are ranked together (up first, then eth0/wlan0/eth1/wlan1,
     * then any other adapter such as `usb0`); for each one `/sys/class/net/<iface>/address` is read before
     * [NetworkInterface.getHardwareAddress]. When Android 11+ sandboxes both, the kernel view is read through
     * `ip link show` / `cat` of the sysfs address files and finally the vendor boot properties (`getprop`)
     * that Amlogic/Rockchip boxes use to store the factory MAC. Never returns a placeholder or derived id.
     */
    fun macAddress(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            .filter { isPhysical(it.name) }.associateBy { it.name }
        val sysfsNames = File("/sys/class/net").listFiles()?.map { it.name }.orEmpty().filter { isPhysical(it) }
        val ranked = (interfaces.keys + PREFERRED_INTERFACES + sysfsNames).distinct().sortedWith(
            compareByDescending<String> { interfaces[it]?.let { nic -> runCatching { nic.isUp }.getOrDefault(false) } ?: false }
                .thenBy(::preferenceRank),
        )
        ranked.firstNotNullOfOrNull { name ->
            sysfsAddress(name) ?: interfaces[name]?.let { nic -> runCatching { nic.hardwareAddress }.getOrNull()?.let(::normalize) }
        }?.let { return it }
        return SHELL_COMMANDS.firstNotNullOfOrNull { command -> shellOutput(command)?.let { parseShellMacs(command.first(), it) } }
    }

    private fun shellOutput(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
        } finally {
            process.destroy()
        }
    }.getOrNull()

    /** Picks the best real MAC from shell output; `ip link` output is matched to its interface names. */
    internal fun parseShellMacs(tool: String, output: String): String? {
        if (tool == "getprop") {
            return MAC_PROPS.firstNotNullOfOrNull { key ->
                Regex("\\[" + Regex.escape(key) + "\\]: \\[([^\\]]*)\\]").find(output)?.groupValues?.get(1)?.let(::normalize)
            }
        }
        val byInterface = linkedMapOf<String, String>()
        var current: String? = null
        output.lineSequence().forEach { line ->
            IP_LINK_IFACE.find(line)?.let { current = it.groupValues[1] }
            val mac = ANY_MAC.find(line)?.value?.let(::normalize) ?: return@forEach
            val name = current ?: "sysfs${byInterface.size}"
            if (name !in byInterface && (tool != "ip" || isPhysicalName(name)) && !line.contains("brd $mac", ignoreCase = true)) {
                byInterface[name] = mac
            }
        }
        return byInterface.entries.sortedBy { preferenceRank(it.key) }.firstOrNull()?.value
    }

    private fun isPhysicalName(name: String): Boolean = name in PREFERRED_INTERFACES || VIRTUAL_PREFIXES.none { name.startsWith(it) }

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
