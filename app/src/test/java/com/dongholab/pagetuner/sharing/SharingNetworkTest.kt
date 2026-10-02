package com.dongholab.pagetuner.sharing

import org.junit.Assert.*
import org.junit.Test

class SharingNetworkTest {
    @Test fun sharingOffersOnlyLiteralPrivateAddresses() {
        listOf("192.168.43.1", "10.0.0.1", "172.16.1.1", "172.31.255.254").forEach { assertTrue(it, isSharingAddress(it)) }
        listOf("127.0.0.1", "0.0.0.0", "8.8.8.8", "100.64.0.1", "172.32.0.1", "192.168.001.1", "192.168.1.256", "localhost", "::1").forEach { assertFalse(it, isSharingAddress(it)) }
    }
    @Test fun cellularAndVpnInterfacesAreNotOffered() {
        listOf("rmnet0", "v4-rmnet_data0", "ccmni0", "ccinet1", "pdp0", "wg0", "tun0", "ipsec0").forEach { assertFalse(it, isSharingInterface(it)) }
        listOf("wlan0", "swlan0", "ap0", "eth0").forEach { assertTrue(it, isSharingInterface(it)) }
    }
    @Test fun networkLossAndDeadlineStopSessionWithoutInternetProbe() {
        val wifi = listOf(SharingAddress("192.168.43.1", "ap0"))
        assertFalse(shouldStopSharing(99, 100, "192.168.43.1", wifi))
        assertTrue(shouldStopSharing(100, 100, "192.168.43.1", wifi))
        assertTrue(shouldStopSharing(90, 100, "192.168.43.1", emptyList()))
        assertTrue(shouldStopSharing(90, 100, "192.168.44.1", wifi))
    }
}
