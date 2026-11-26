package com.nightcinder.helix.media

import com.nightcinder.helix.core.model.SiteConfig
import com.nightcinder.helix.auth.CryptoStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

data class QuickConnectTicket(val secret: String, val code: String)

/**
 * Jellyfin REST client. Endpoints and payloads were probed live against a Jellyfin 10.x server
 * (`/QuickConnect/Enabled`, `/QuickConnect/Initiate`, `/Users/AuthenticateByName`, 401 on unauthenticated reads).
 * No Authentik outpost sits in front of Jellyfin, so only the MediaBrowser Authorization header is used.
 */
class JellyfinClient(
    private val client: OkHttpClient,
    private val store: CryptoStore,
    private val clientVersion: String = "0.1.0",
    private val defaultBaseUrl: String = DEFAULT_BASE_URL,
) : MediaProviderClient {

    override val provider = MediaProvider.JELLYFIN

    /** Server URL: user override (e.g. direct :8443) from [store], else [defaultBaseUrl]. */
    suspend fun baseUrl(): String =
        (store.getString(KEY_BASE_URL) ?: defaultBaseUrl).trimEnd('/')

    suspend fun setBaseUrlOverride(url: String?) {
        if (url.isNullOrBlank()) {
            store.remove(KEY_BASE_URL)
            return
        }
        val parsed = url.trim().toHttpUrlOrNull()
        require(parsed != null && parsed.isHttps) { "Jellyfin URL must be https" }
        store.putString(KEY_BASE_URL, parsed.toString().trimEnd('/'))
    }

    override suspend fun isSignedIn(): Boolean = store.getString(KEY_TOKEN) != null && store.getString(KEY_USER_ID) != null

    override suspend fun signOut() {
        store.remove(KEY_TOKEN)
        store.remove(KEY_USER_ID)
    }

    // --- Authentication ---

    suspend fun isQuickConnectEnabled(): Boolean =
        get("/QuickConnect/Enabled", authed = false).trim().equals("true", ignoreCase = true)

    suspend fun quickConnectInitiate(): Result<QuickConnectTicket> = runCatchingApi {
        val obj = parseObject(send("POST", "/QuickConnect/Initiate", authed = false))
        QuickConnectTicket(
            secret = obj.str("Secret") ?: throw MediaApiException.Invalid("Missing Secret"),
            code = obj.str("Code") ?: throw MediaApiException.Invalid("Missing Code"),
        )
    }

    /** True once the user has approved the code from an already signed-in Jellyfin client. */
    suspend fun quickConnectApproved(secret: String): Result<Boolean> = runCatchingApi {
        val url = baseUrl().toHttpUrl().newBuilder().addPathSegments("QuickConnect/Connect")
            .addQueryParameter("secret", secret).build()
        val obj = parseObject(client.execute(request(url, authed = false).build()).ok())
        obj.bool("Authenticated") == true
    }

    suspend fun signInWithQuickConnect(secret: String): Result<Unit> = runCatchingApi {
        val body = buildJsonObject { put("Secret", secret) }
        storeSession(parseObject(send("POST", "/Users/AuthenticateWithQuickConnect", authed = false, body = body)))
    }

    suspend fun signInWithPassword(username: String, password: String): Result<Unit> = runCatchingApi {
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }
        storeSession(parseObject(send("POST", "/Users/AuthenticateByName", authed = false, body = body)))
    }

    private suspend fun storeSession(auth: JsonObject) {
        val token = auth.str("AccessToken") ?: throw MediaApiException.Invalid("Missing AccessToken")
        val userId = auth.obj("User")?.str("Id") ?: throw MediaApiException.Invalid("Missing User.Id")
        store.putString(KEY_TOKEN, token)
        store.putString(KEY_USER_ID, userId)
    }

    // --- Library ---

    override suspend fun continueWatching(): Result<List<MediaItem>> = runCatchingApi {
        val userId = requireUser()
        val root = parseObject(
            get("/Users/$userId/Items/Resume", mapOf("MediaTypes" to "Video", "Limit" to "20", "Fields" to "Overview")),
        )
        root["Items"].arr().mapNotNull { it.obj()?.toItem() }
    }

    override suspend fun upNext(): Result<List<MediaItem>> = runCatchingApi {
        val userId = requireUser()
        val root = parseObject(get("/Shows/NextUp", mapOf("UserId" to userId, "Limit" to "20")))
        root["Items"].arr().mapNotNull { it.obj()?.toItem() }
    }

    private fun JsonObject.toItem(): MediaItem? {
        val id = str("Id") ?: return null
        val name = str("Name") ?: return null
        val series = str("SeriesName")
        val episode = if (str("Type") == "Episode") {
            val s = long("ParentIndexNumber")
            val e = long("IndexNumber")
            if (s != null && e != null) "S%02dE%02d".format(s, e) else null
        } else {
            null
        }
        return MediaItem(
            provider = MediaProvider.JELLYFIN,
            id = id,
            title = series ?: name,
            subtitle = listOfNotNull(episode, name.takeIf { series != null }).joinToString(" · ").ifEmpty { null },
            resumePositionMs = (obj("UserData")?.long("PlaybackPositionTicks") ?: 0L) / TICKS_PER_MS,
            durationMs = (long("RunTimeTicks") ?: 0L) / TICKS_PER_MS,
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key].obj()

    // --- Direct Play resolution ---

    override suspend fun resolve(item: MediaItem): Result<ResolvedStream> = runCatchingApi {
        val userId = requireUser()
        val info = parseObject(get("/Items/${item.id}/PlaybackInfo", mapOf("UserId" to userId)))
        val source = info["MediaSources"].arr().firstOrNull().obj()
            ?: throw MediaApiException.Unsupported("No playable media source")
        if (source.bool("SupportsDirectPlay") == false) {
            throw MediaApiException.Unsupported("Direct Play unavailable for this file")
        }
        val sourceId = source.str("Id") ?: throw MediaApiException.Invalid("Missing media source id")
        val playSession = info.str("PlaySessionId").orEmpty()
        val base = baseUrl()
        val token = requireToken()

        val audio = ArrayList<ProviderStream>()
        val subs = ArrayList<ProviderStream>()
        val external = ArrayList<ExternalSubtitle>()
        for (s in source["MediaStreams"].arr().mapNotNull { it.obj() }) {
            val index = s.long("Index")?.toString() ?: continue
            val label = s.str("DisplayTitle") ?: s.str("Language") ?: "Track $index"
            val stream = ProviderStream(index, label, s.str("Language"), s.bool("IsDefault") == true)
            when (s.str("Type")) {
                "Audio" -> audio += stream
                "Subtitle" -> {
                    subs += stream
                    val delivery = s.str("DeliveryUrl")
                    if (s.bool("IsExternal") == true && delivery != null) {
                        external += ExternalSubtitle(
                            url = base + delivery,
                            label = label,
                            language = s.str("Language"),
                            mimeType = subtitleMime(s.str("Codec")),
                        )
                    }
                }
            }
        }
        val streamUrl = base.toHttpUrl().newBuilder()
            .addPathSegments("Videos/${item.id}/stream")
            .addQueryParameter("static", "true")
            .addQueryParameter("MediaSourceId", sourceId)
            .addQueryParameter("PlaySessionId", playSession)
            .build()
        ResolvedStream(
            url = streamUrl.toString(),
            headers = mapOf("Authorization" to authHeader(token)),
            audio = audio,
            subtitles = subs,
            externalSubtitles = external,
            sessionRef = "$sourceId|$playSession",
        )
    }

    private fun subtitleMime(codec: String?): String = when (codec?.lowercase()) {
        "srt", "subrip" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        else -> "text/vtt"
    }

    // --- Playback reporting (best effort: failures never interrupt playback) ---

    override suspend fun reportStart(item: MediaItem, stream: ResolvedStream, positionMs: Long) =
        report("/Sessions/Playing", item, stream, positionMs, paused = false)

    override suspend fun reportProgress(item: MediaItem, stream: ResolvedStream, positionMs: Long, paused: Boolean) =
        report("/Sessions/Playing/Progress", item, stream, positionMs, paused)

    override suspend fun reportStop(item: MediaItem, stream: ResolvedStream, positionMs: Long) =
        report("/Sessions/Playing/Stopped", item, stream, positionMs, paused = false)

    private suspend fun report(path: String, item: MediaItem, stream: ResolvedStream, positionMs: Long, paused: Boolean) {
        runCatchingApi {
            val (sourceId, playSession) = stream.sessionRef.split('|').let { it.first() to it.getOrElse(1) { "" } }
            val body = buildJsonObject {
                put("ItemId", item.id)
                put("MediaSourceId", sourceId)
                put("PlaySessionId", playSession)
                put("PositionTicks", positionMs * TICKS_PER_MS)
                put("IsPaused", paused)
                put("CanSeek", true)
            }
            send("POST", path, authed = true, body = body)
        }
    }

    // --- HTTP plumbing ---

    private suspend fun requireToken(): String = store.getString(KEY_TOKEN) ?: throw MediaApiException.NotSignedIn()
    private suspend fun requireUser(): String = store.getString(KEY_USER_ID) ?: throw MediaApiException.NotSignedIn()

    private suspend fun authHeader(token: String?): String = buildString {
        append("MediaBrowser Client=\"Helix\", Device=\"Helix XR\", DeviceId=\"${deviceId()}\", Version=\"$clientVersion\"")
        if (token != null) append(", Token=\"$token\"")
    }

    private suspend fun deviceId(): String =
        store.getString(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().also { store.putString(KEY_DEVICE_ID, it) }

    private suspend fun request(url: HttpUrl, authed: Boolean): Request.Builder {
        val token = if (authed) requireToken() else null
        return Request.Builder().url(url).header("Authorization", authHeader(token)).header("Accept", "application/json")
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap(), authed: Boolean = true): String =
        send("GET", path, authed, query)

    private suspend fun send(
        method: String,
        path: String,
        authed: Boolean,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
    ): String {
        val url = baseUrl().toHttpUrl().newBuilder().apply {
            addPathSegments(path.trimStart('/'))
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = request(url, authed)
            .method(method, if (method == "GET") null else (body?.toString() ?: "").toRequestBody(JSON))
            .build()
        return client.execute(req).ok(onUnauthorized = { if (authed) signOut() })
    }

    private inline fun <T> runCatchingApi(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: MediaApiException) {
            Result.failure(e)
        }

    private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

    companion object {
        const val DEFAULT_BASE_URL = SiteConfig.JELLYFIN_URL
        const val KEY_TOKEN = "media.jellyfin.token"
        const val KEY_USER_ID = "media.jellyfin.userId"
        const val KEY_DEVICE_ID = "media.jellyfin.deviceId"
        const val KEY_BASE_URL = "media.jellyfin.baseUrl"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
