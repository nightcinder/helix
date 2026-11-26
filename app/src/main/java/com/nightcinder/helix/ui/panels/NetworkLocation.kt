package com.nightcinder.helix.ui.panels

import com.nightcinder.helix.core.model.SiteConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** Where the phone/headset currently sits relative to the homelab. */
enum class NetworkLocation(val label: String) {
    /** Resolve not finished yet. */
    CHECKING("..."),
    LAN("LAN"),
    WAN("WAN"),
}

/**
 * Decides LAN vs WAN by resolving a homelab hostname (by default the Termix host): inside the house split-horizon
 * DNS answers with a private (RFC 1918) address, from outside it answers with the public edge address.
 *
 * The resolver is injectable so the decision logic is unit-testable without a network.
 */
class NetworkLocationClassifier(
    private val host: String = DEFAULT_HOST,
    private val resolver: (String) -> List<String> = { name ->
        InetAddress.getAllByName(name).mapNotNull { it.hostAddress }
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** LAN when any resolved address is a homelab LAN address, otherwise WAN. */
    fun classify(addresses: List<String>): NetworkLocation =
        if (addresses.any { isLanAddress(it) }) NetworkLocation.LAN else NetworkLocation.WAN

    /** Resolves [host]; a failed lookup (offline, DNS error) or no configured host counts as WAN. */
    suspend fun check(): NetworkLocation = withContext(dispatcher) {
        if (host.isBlank()) return@withContext NetworkLocation.WAN
        runCatching { resolver(host) }
            .map { classify(it) }
            .getOrDefault(NetworkLocation.WAN)
    }

    companion object {
        /** Host part of the build-time Termix URL (HELIX_TERMIX_URL); blank when not configured. */
        val DEFAULT_HOST: String = hostOf(SiteConfig.TERMIX_URL)

        /** Host part of a base URL (`https://termix.example.com:8443/x` -> `termix.example.com`); blank in, blank out. */
        fun hostOf(baseUrl: String): String =
            baseUrl.trim().substringAfter("://").substringBefore('/').substringBefore(':')

        /** True for a dotted-quad private address: 10.0.0.0/8, 172.16.0.0/12 or 192.168.0.0/16. */
        fun isLanAddress(address: String): Boolean {
            val parts = address.trim().split('.')
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return when {
                octets[0] == 10 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                else -> false
            }
        }
    }
}
