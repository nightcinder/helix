package com.nightcinder.helix.spotify

import com.nightcinder.helix.auth.CryptoStore

/**
 * Public Spotify `client_id` (PKCE: there is no client secret anywhere in the app). The build-time id comes from
 * `HELIX_SPOTIFY_CLIENT_ID`; an optional runtime override is set only from the Spotify panel.
 */
class SpotifyClientConfig(
    private val buildClientId: String,
    private val store: CryptoStore,
) {
    suspend fun overrideClientId(): String? = store.getString(KEY_OVERRIDE)?.takeIf { it.isNotBlank() }

    /** Blank clears the override. Returns false (and stores nothing) for a malformed id. */
    suspend fun setOverride(raw: String): Boolean {
        val id = raw.trim()
        if (id.isEmpty()) {
            store.remove(KEY_OVERRIDE)
            return true
        }
        if (!CLIENT_ID_REGEX.matches(id)) return false
        store.putString(KEY_OVERRIDE, id)
        return true
    }

    /** Effective client id, or null when neither build-time nor override is configured. */
    suspend fun clientId(): String? =
        overrideClientId() ?: buildClientId.trim().takeIf { it.isNotEmpty() }

    companion object {
        const val KEY_OVERRIDE = "spotify.client_id_override"
        private val CLIENT_ID_REGEX = Regex("[A-Za-z0-9]{8,64}")
    }
}
