package com.nightcinder.helix.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class SpotifyPlaylist(val id: String, val name: String, val uri: String, val trackCount: Int?)

data class SpotifyTrack(val id: String, val name: String, val artists: String, val uri: String)

data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val isRestricted: Boolean,
)

sealed class SpotifyApiException(message: String) : Exception(message) {
    class NotSignedIn : SpotifyApiException("Not signed in to Spotify")
    class Unauthorized : SpotifyApiException("Spotify session expired")
    class Forbidden : SpotifyApiException("Spotify refused the request (Premium required or device restricted)")
    class NotFound : SpotifyApiException("Spotify device or resource not found")
    class Http(val code: Int) : SpotifyApiException("Spotify API error (HTTP $code)")
    class Network(detail: String) : SpotifyApiException("Spotify network error: $detail")
    class Malformed : SpotifyApiException("Unexpected Spotify response")
}

/** Spotify Web API client (Bearer PKCE tokens, one transparent refresh + retry on 401). */
class SpotifyWebApi(
    private val client: OkHttpClient,
    private val tokens: SpotifyTokenSource,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun playlists(limit: Int = PAGE): Result<List<SpotifyPlaylist>> =
        get("/v1/me/playlists", mapOf("limit" to "$limit")).mapCatching { root ->
            root.items().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                val total = ((o["items"] ?: o["tracks"]) as? JsonObject)?.get("total")?.jsonPrimitive?.intOrNull
                SpotifyPlaylist(id, o.str("name").orEmpty(), o.str("uri").orEmpty(), total)
            }
        }

    suspend fun likedSongs(limit: Int = PAGE): Result<List<SpotifyTrack>> =
        get("/v1/me/tracks", mapOf("limit" to "$limit")).mapCatching { root ->
            root.items().mapNotNull { item ->
                val t = (item as? JsonObject)?.get("track") as? JsonObject ?: return@mapNotNull null
                val id = t.str("id") ?: return@mapNotNull null
                val artists = (t["artists"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.str("name") }.joinToString(", ")
                SpotifyTrack(id, t.str("name").orEmpty(), artists, t.str("uri").orEmpty())
            }
        }

    /** Connect devices; an empty list is a valid success (no device currently available). */
    suspend fun devices(): Result<List<SpotifyDevice>> =
        get("/v1/me/player/devices", emptyMap()).mapCatching { root ->
            (root["devices"] as? JsonArray).orEmpty().mapNotNull { d ->
                val o = d as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null // restricted devices may omit the id
                SpotifyDevice(
                    id = id,
                    name = o.str("name").orEmpty(),
                    type = o.str("type").orEmpty(),
                    isActive = o["is_active"]?.jsonPrimitive?.booleanOrNull == true,
                    isRestricted = o["is_restricted"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
        }

    suspend fun transferPlayback(deviceId: String, play: Boolean = true): Result<Unit> =
        put(
            "/v1/me/player",
            buildJsonObject {
                put("device_ids", JsonArray(listOf(JsonPrimitive(deviceId))))
                put("play", play)
            }.toString(),
        )

    /** Starts [contextUri] (playlist) on the active device, or on [deviceId] when given. */
    suspend fun startContext(contextUri: String, deviceId: String? = null): Result<Unit> =
        put(
            "/v1/me/player/play",
            buildJsonObject { put("context_uri", contextUri) }.toString(),
            deviceId?.let { mapOf("device_id" to it) }.orEmpty(),
        )

    suspend fun startTrack(trackUri: String, deviceId: String? = null): Result<Unit> =
        put(
            "/v1/me/player/play",
            buildJsonObject { put("uris", JsonArray(listOf(JsonPrimitive(trackUri)))) }.toString(),
            deviceId?.let { mapOf("device_id" to it) }.orEmpty(),
        )

    private suspend fun get(path: String, query: Map<String, String>): Result<JsonObject> =
        call("GET", path, query, null).mapCatching { body ->
            runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw SpotifyApiException.Malformed() }
        }

    private suspend fun put(path: String, body: String, query: Map<String, String> = emptyMap()): Result<Unit> =
        call("PUT", path, query, body).map { }

    private suspend fun call(method: String, path: String, query: Map<String, String>, body: String?): Result<String> =
        withContext(Dispatchers.IO) {
            var token = tokens.accessToken() ?: return@withContext Result.failure(SpotifyApiException.NotSignedIn())
            for (attempt in 0..1) {
                val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
                    query.forEach { (k, v) -> addQueryParameter(k, v) }
                }.build()
                val request = Request.Builder().url(url)
                    .header("Authorization", "Bearer $token")
                    .method(method, body?.toRequestBody(JSON_MEDIA))
                    .build()
                val (code, text) = try {
                    client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
                } catch (e: IOException) {
                    return@withContext Result.failure(SpotifyApiException.Network(e.message ?: "I/O error"))
                }
                when {
                    code in 200..299 -> return@withContext Result.success(text)
                    code == 401 && attempt == 0 ->
                        token = tokens.refreshAfterUnauthorized(token) ?: break
                    code == 401 -> break
                    code == 403 -> return@withContext Result.failure(SpotifyApiException.Forbidden())
                    code == 404 -> return@withContext Result.failure(SpotifyApiException.NotFound())
                    else -> return@withContext Result.failure(SpotifyApiException.Http(code))
                }
            }
            Result.failure(SpotifyApiException.Unauthorized())
        }

    private fun JsonObject.items(): List<JsonElement> = (this["items"] as? JsonArray).orEmpty()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val DEFAULT_BASE_URL = "https://api.spotify.com"
        private const val PAGE = 50
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
