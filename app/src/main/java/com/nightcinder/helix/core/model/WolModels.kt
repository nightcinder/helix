package com.nightcinder.helix.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

object WolMacNormalizer {
    fun normalize(mac: String?): String =
        mac.orEmpty()
            .lowercase()
            .replace(":", "")
            .replace("-", "")
            .replace(".", "")
            .trim()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WolDevice(
    val id: Long = 0L,
    val hostId: Long? = null,
    val name: String = "",
    val hostName: String? = null,
    @JsonNames("mac", "macAddress", "mac_address")
    val macAddress: String = "",
    val broadcastAddress: String? = null,
    val port: Int = 9,
    val ip: String? = null,
    val status: String? = null,
    val isOnline: Boolean? = null,
) {
    val effectiveHostId: Long
        get() = hostId ?: id

    fun matches(
        mac: String? = null,
        targetHostName: String? = null,
        targetHostId: Long? = null,
    ): Boolean {
        val normalizedTargetMac = WolMacNormalizer.normalize(mac)
        if (normalizedTargetMac.isNotEmpty() &&
            WolMacNormalizer.normalize(macAddress) == normalizedTargetMac
        ) {
            return true
        }
        if (targetHostId != null && (hostId == targetHostId || id == targetHostId)) {
            return true
        }
        val queryName = targetHostName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val deviceNames = listOfNotNull(name, hostName)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        return deviceNames.any { candidate ->
            candidate == queryName || candidate.substringBefore('.') == queryName.substringBefore('.')
        }
    }
}

@Serializable
data class WolWakeResult(
    val success: Boolean = false,
    val deviceId: Long? = null,
    val hostId: Long? = null,
    val deviceName: String? = null,
    val macAddress: String? = null,
    val endpointUsed: String? = null,
    val message: String? = null,
)
