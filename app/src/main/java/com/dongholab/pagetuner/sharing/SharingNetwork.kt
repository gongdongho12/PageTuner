package com.dongholab.pagetuner.sharing

import java.net.Inet4Address
import java.net.NetworkInterface

data class SharingAddress(val address: String, val interfaceName: String)

internal fun isSharingAddress(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { char -> char !in '0'..'9' } || (it.length > 1 && it.startsWith('0')) }) return false
    val bytes = parts.map { it.toIntOrNull() ?: return false }
    if (bytes.any { it !in 0..255 }) return false
    return bytes[0] == 10 || bytes[0] == 192 && bytes[1] == 168 || bytes[0] == 172 && bytes[1] in 16..31
}

fun sharingAddresses(): List<SharingAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { network ->
        network.isUp && !network.isLoopback && isSharingInterface(network.name)
    }.flatMap { network -> network.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { address ->
        address.hostAddress?.takeIf(::isSharingAddress)?.let { SharingAddress(it, network.name) }
    } }.distinctBy { it.address }.sortedBy { it.address }
}.getOrDefault(emptyList())

internal fun isSharingInterface(name: String): Boolean = listOf("tun", "tap", "wg", "rmnet", "v4-rmnet", "ppp", "ccmni", "ccinet", "pdp", "ipsec")
    .none { name.lowercase().startsWith(it) }

internal const val SHARING_DURATION_MILLIS = 2L * 60 * 60 * 1000

internal fun shouldStopSharing(now: Long, expiresAt: Long, boundAddress: String, addresses: List<SharingAddress>): Boolean =
    now >= expiresAt || addresses.none { it.address == boundAddress }
