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
        listOf("sh", "-c", "for f in /sys/class/net/*/address; do echo \"\$f \$(cat \"\$f\")\"; done"),
        listOf("getprop"),
    )
    private val MAC_PROPS = listOf(
        "ro.boot.mac", "ro.boot.ethaddr", "ubootenv.var.ethaddr", "ro.mac", "persist.sys.mac", "ro.boot.wifimac",
        "ro.boot.wifi_mac", "ro.wifi.mac", "persist.sys.wifi.mac", "ro.vendor.mac", "ro.hardware.mac",
    )
    private val IP_LINK_IFACE = Regex("^\\d+:\\s*([^:@\\s]+)")
    private val IP_LINK_UP = Regex("<[^>]*\\bUP\\b[^>]*>")
    private val IP_LINK_ETHER = Regex("link/ether\\s+(\\S+)")
    private val SYSFS_LINE = Regex("^/sys/class/net/([^/\\s]+)/address\\s+(\\S+)")
    private val DERIVED_IFACE = Regex("(?i)p2p|mon\\d*$|aware|nan\\d*$|-")
    private val PHYSICAL_NAME = Regex("^(eth|wlan|usb|en[a-z0-9]*|wl[a-z0-9]*)\\d*$")

    /**
     * Physical (MAC) address of the Ethernet/Wi-Fi adapter, or `null` when none can be read. All interface
     * names known to [NetworkInterface] or sysfs are ranked together (up first, then eth0/wlan0/eth1/wlan1,
     * then any other adapter such as `usb0`); for each one `/sys/class/net/<iface>/address` is read before
     * [NetworkInterface.getHardwareAddress]. When Android 11+ sandboxes both, the kernel view is read through
     * `ip link show` / a shell listing of the sysfs address files and finally the vendor boot properties (`getprop`)
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
        val output = StringBuffer()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().use { it.forEachLine { line -> output.append(line).append('\n') } } }
        }.apply { isDaemon = true; start() }
        try {
            val exited = process.waitFor(3, TimeUnit.SECONDS)
            if (!exited) process.destroyForcibly()
            reader.join(1_000)
            if (exited && process.exitValue() == 0 && !reader.isAlive) output.toString() else null
        } finally {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
    }.getOrNull()

    /**
     * Picks the best real MAC from shell output. `ip link` and the sysfs listing keep the interface name, so
     * only `eth*`/`wlan*`/`usb*` adapters qualify (no bridges, p2p, tunnels); interfaces flagged UP win, then
     * the preferred-name order. `getprop` output is limited to the known vendor MAC properties.
     */
    internal fun parseShellMacs(tool: String, output: String): String? {
        if (tool == "getprop") {
            return MAC_PROPS.firstNotNullOfOrNull { key ->
                Regex("\\[" + Regex.escape(key) + "\\]: \\[([^\\]]*)\\]").find(output)?.groupValues?.get(1)?.let(::normalize)
            }
        }
        val found = linkedMapOf<String, Pair<Boolean, String>>()
        var current: String? = null
        var currentUp = false
        output.lineSequence().forEach { line ->
            SYSFS_LINE.find(line)?.let { m ->
                val name = m.groupValues[1]
                val mac = normalize(m.groupValues[2])
                if (mac != null && isShellPhysical(name) && name !in found) found[name] = false to mac
                return@forEach
            }
            IP_LINK_IFACE.find(line)?.let {
                current = it.groupValues[1]
                currentUp = IP_LINK_UP.containsMatchIn(line)
            }
            val name = current ?: return@forEach
            val mac = IP_LINK_ETHER.find(line)?.groupValues?.get(1)?.let(::normalize) ?: return@forEach
            if (isShellPhysical(name) && name !in found) found[name] = currentUp to mac
        }
        return found.entries
            .sortedWith(compareByDescending<Map.Entry<String, Pair<Boolean, String>>> { it.value.first }.thenBy { preferenceRank(it.key) })
            .firstOrNull()?.value?.second
    }

    private fun sysfsAddress(name: String): String? =
        runCatching { File("/sys/class/net/$name/address").readText() }.getOrNull()?.let(::normalize)

    /** Shell results carry only a name: accept kernel/udev adapter names (eth*, wlan*, usb*, en*, wl*) or anything sysfs proves physical. */
    private fun isShellPhysical(name: String): Boolean =
        !DERIVED_IFACE.containsMatchIn(name) && (PHYSICAL_NAME.matches(name) || isPhysical(name))

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
