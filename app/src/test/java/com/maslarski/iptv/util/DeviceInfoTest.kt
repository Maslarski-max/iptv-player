package com.maslarski.iptv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceInfoTest {
    @Test
    fun `ip link output prefers eth0 and skips loopback, placeholders and broadcast`() {
        val output = """
            1: lo: <LOOPBACK,UP> mtu 65536
                link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
            2: wlan0: <BROADCAST,MULTICAST,UP> mtu 1500
                link/ether 02:00:00:00:00:00 brd ff:ff:ff:ff:ff:ff
            3: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500
                link/ether a4:5e:60:12:34:56 brd ff:ff:ff:ff:ff:ff
        """.trimIndent()
        assertEquals("A4:5E:60:12:34:56", DeviceInfo.parseShellMacs("ip", output))
    }

    @Test
    fun `ip -o link single-line output is parsed per interface`() {
        val output = "1: lo: <LOOPBACK,UP> mtu 65536 link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00\n" +
            "2: wlan0: <BROADCAST,UP> mtu 1500 link/ether 1c:2a:3b:4c:5d:6e brd ff:ff:ff:ff:ff:ff\n" +
            "3: p2p0: <BROADCAST> mtu 1500 link/ether 1e:2a:3b:4c:5d:6e brd ff:ff:ff:ff:ff:ff"
        assertEquals("1C:2A:3B:4C:5D:6E", DeviceInfo.parseShellMacs("ip", output))
    }

    @Test
    fun `getprop output uses the vendor mac properties only`() {
        val output = "[ro.boot.serialno]: [ABC123]\n[ro.boot.btmacaddr]: [11:22:33:44:55:66]\n[ubootenv.var.ethaddr]: [a4:5e:60:aa:bb:cc]\n"
        assertEquals("A4:5E:60:AA:BB:CC", DeviceInfo.parseShellMacs("getprop", output))
        assertNull(DeviceInfo.parseShellMacs("getprop", "[ro.boot.mac]: [02:00:00:00:00:00]\n"))
    }

    @Test
    fun `sysfs listing keeps interface names and skips p2p and placeholders`() {
        val output = "/sys/class/net/lo/address 00:00:00:00:00:00\n" +
            "/sys/class/net/p2p0/address 1e:2a:3b:4c:5d:6e\n" +
            "/sys/class/net/wlan0/address a4:5e:60:00:11:22\n"
        assertEquals("A4:5E:60:00:11:22", DeviceInfo.parseShellMacs("sh", output))
        assertNull(DeviceInfo.parseShellMacs("sh", "/sys/class/net/p2p0/address 1e:2a:3b:4c:5d:6e\n"))
    }

    @Test
    fun `active wlan0 beats inactive eth0 and bridges are ignored`() {
        val output = "2: eth0: <NO-CARRIER,BROADCAST,MULTICAST> mtu 1500 link/ether a4:00:00:00:00:01 brd ff:ff:ff:ff:ff:ff\n" +
            "3: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 link/ether b8:00:00:00:00:02 brd ff:ff:ff:ff:ff:ff\n" +
            "4: bridge0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 link/ether 1a:11:22:33:44:55 brd ff:ff:ff:ff:ff:ff"
        assertEquals("B8:00:00:00:00:02", DeviceInfo.parseShellMacs("ip", output))
        assertNull(DeviceInfo.parseShellMacs("ip", "4: bridge0: <BROADCAST,UP> mtu 1500 link/ether 1a:11:22:33:44:55 brd ff:ff:ff:ff:ff:ff"))
    }

    @Test
    fun `predictable adapter names such as end0 are accepted`() {
        val output = "2: end0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 link/ether a4:5e:60:12:34:56 brd ff:ff:ff:ff:ff:ff"
        assertEquals("A4:5E:60:12:34:56", DeviceInfo.parseShellMacs("ip", output))
    }
}
