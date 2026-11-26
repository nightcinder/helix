package com.nightcinder.helix.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
enum class HostStatus {
    ONLINE,
    OFFLINE,
    DEGRADED,
    SLEEPING,
    UNKNOWN,
}

@Serializable
data class HomelabHostProfile(
    val slug: String,
    val displayRole: String,
    val notes: String,
    val wolMacAddress: String? = null,
    val highlightWhenOffline: Boolean = false,
)

object HomelabDefaults {
    /** Quick-wake target from HELIX_WOL_HOST / HELIX_WOL_MAC ([SiteConfig.wolHost]/[SiteConfig.wolMac]); blank when unset. */
    val WOL_HOST: String get() = SiteConfig.wolHost
    val WOL_MAC: String get() = SiteConfig.wolMac

    val defaultProfiles: Map<String, HomelabHostProfile>
        get() = buildMap {
            val wolHost = WOL_HOST.trim().lowercase()
            val wolMac = WOL_MAC.trim()
            if (wolHost.isNotEmpty()) {
                put(
                    wolHost,
                    HomelabHostProfile(
                        slug = wolHost,
                        displayRole = "Workstation (WOL)",
                        notes = "Wake-on-LAN capable workstation${if (wolMac.isNotEmpty()) " (MAC $wolMac)" else ""}.",
                        wolMacAddress = wolMac.takeIf { it.isNotEmpty() },
                        highlightWhenOffline = true,
                    ),
                )
            }
        }

    fun profileFor(hostName: String?, hostname: String? = null): HomelabHostProfile? {
        val candidates = listOfNotNull(hostName, hostname)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        for (candidate in candidates) {
            val shortName = candidate.substringBefore('.')
            defaultProfiles[candidate]?.let { return it }
            defaultProfiles[shortName]?.let { return it }
        }
        return null
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TermixHost(
    val id: Long = 0L,
    val name: String = "",
    @JsonNames("ip", "host", "address")
    val ip: String = "",
    val hostname: String? = null,
    val port: Int = 22,
    val username: String? = null,
    val authType: String? = null,
    val folder: String? = null,
    val tags: List<String> = emptyList(),
    val pin: Boolean = false,
    val os: String? = null,
    val distro: String? = null,
    val enableTerminal: Boolean = true,
    val enableTunnel: Boolean = false,
    val enableFileManager: Boolean = false,
    val notes: String? = null,
    @JsonNames("mac", "macAddress", "mac_address")
    val macAddress: String? = null,
    val status: HostStatus = HostStatus.UNKNOWN,
    val homelabProfile: HomelabHostProfile? = null,
) {
    val displayName: String
        get() = name.ifBlank { hostname?.takeIf { it.isNotBlank() } ?: ip.ifBlank { "Host #$id" } }

    val displayAddress: String
        get() = ip.ifBlank { hostname.orEmpty() }

    val effectiveHomelabProfile: HomelabHostProfile?
        get() = homelabProfile ?: HomelabDefaults.profileFor(name, hostname)
}
