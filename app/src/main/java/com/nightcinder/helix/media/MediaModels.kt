package com.nightcinder.helix.media

enum class MediaProvider { JELLYFIN, PLEX }

/** A playable entry in Continue Watching / Up Next. [id] is the Jellyfin item id or the Plex ratingKey. */
data class MediaItem(
    val provider: MediaProvider,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val resumePositionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/** Audio / subtitle stream as reported by the provider (container streams, not player renderer groups). */
data class ProviderStream(
    val id: String,
    val label: String,
    val language: String?,
    val isDefault: Boolean = false,
)

/** A sidecar subtitle the player must be told about; embedded subtitles come from the container itself. */
data class ExternalSubtitle(val url: String, val label: String, val language: String?, val mimeType: String)

/** Direct Play source: the original file, never a transcode. */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val audio: List<ProviderStream>,
    val subtitles: List<ProviderStream>,
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    /** Provider session handle echoed in progress reports (Jellyfin PlaySessionId / MediaSourceId). */
    val sessionRef: String = "",
)

sealed class MediaApiException(message: String) : Exception(message) {
    class NotSignedIn : MediaApiException("Not signed in")
    class Unauthorized : MediaApiException("Session rejected by server (401)")
    class Http(val code: Int) : MediaApiException("Server returned HTTP $code")
    class Network(detail: String) : MediaApiException(detail)
    class Unsupported(detail: String) : MediaApiException(detail)
    class Invalid(detail: String) : MediaApiException(detail)
}

/** What the view model needs from Jellyfin or Plex; the player never sees provider specifics. */
interface MediaProviderClient {
    val provider: MediaProvider
    suspend fun isSignedIn(): Boolean
    suspend fun signOut()
    suspend fun continueWatching(): Result<List<MediaItem>>
    suspend fun upNext(): Result<List<MediaItem>>
    suspend fun resolve(item: MediaItem): Result<ResolvedStream>
    suspend fun reportStart(item: MediaItem, stream: ResolvedStream, positionMs: Long)
    suspend fun reportProgress(item: MediaItem, stream: ResolvedStream, positionMs: Long, paused: Boolean)
    suspend fun reportStop(item: MediaItem, stream: ResolvedStream, positionMs: Long)
}

internal const val TICKS_PER_MS = 10_000L
