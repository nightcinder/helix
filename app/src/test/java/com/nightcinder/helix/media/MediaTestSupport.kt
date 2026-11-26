package com.nightcinder.helix.media

import android.graphics.SurfaceTexture
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

internal fun MockWebServer.base(): String = url("/").toString().trimEnd('/')

/** Path-routed responses so tests do not depend on request ordering. */
internal class RoutingDispatcher : Dispatcher() {
    val routes = LinkedHashMap<String, (RecordedRequest) -> MockResponse>()
    val seen = ArrayList<RecordedRequest>()

    fun on(prefix: String, response: (RecordedRequest) -> MockResponse) {
        routes[prefix] = response
    }

    fun json(prefix: String, body: String, code: Int = 200) = on(prefix) { MockResponse().setResponseCode(code).setBody(body) }

    override fun dispatch(request: RecordedRequest): MockResponse {
        synchronized(seen) { seen += request }
        val path = request.path.orEmpty()
        return routes.entries.firstOrNull { path.startsWith(it.key) }?.value?.invoke(request) ?: MockResponse().setResponseCode(404)
    }

    fun requestsTo(prefix: String): List<RecordedRequest> = synchronized(seen) { seen.filter { it.path.orEmpty().startsWith(prefix) } }
}

internal fun dummySurface(): Surface = Surface(null as SurfaceTexture?)

internal class FakePlayer : MediaPlayerController {
    val stateFlow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val tracksFlow = MutableStateFlow(PlayerTracks())
    override val state: StateFlow<PlaybackState> = stateFlow
    override val tracks: StateFlow<PlayerTracks> = tracksFlow
    val calls = ArrayList<String>()
    var prepared: ResolvedStream? = null
    var startPosition = -1L
    var position = 0L

    override fun prepare(stream: ResolvedStream, startPositionMs: Long) {
        calls += "prepare"
        prepared = stream
        startPosition = startPositionMs
        stateFlow.value = PlaybackState.Loading
    }

    override fun play() { calls += "play"; stateFlow.value = PlaybackState.Playing }
    override fun pause() { calls += "pause"; stateFlow.value = PlaybackState.Paused }
    override fun seekTo(positionMs: Long) { calls += "seek:$positionMs"; position = positionMs }
    override fun stop() { calls += "stop"; stateFlow.value = PlaybackState.Idle }
    override fun attachSurface(surface: Surface) { calls += "attach" }
    override fun detachSurface() { calls += "detach"; if (stateFlow.value == PlaybackState.Playing) stateFlow.value = PlaybackState.Paused }
    override fun selectAudio(id: String) { calls += "audio:$id" }
    override fun selectSubtitle(id: String?) { calls += "sub:$id" }
    override fun positionMs() = position
    override fun durationMs() = 0L
    override fun release() { calls += "release" }
}
