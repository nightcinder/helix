package com.nightcinder.helix.spotify

import android.content.Intent

/** Receives the PKCE callback URI (bound to [SpotifyAuthService.onCallback]). */
fun interface SpotifyCallbackSink {
    fun onCallback(uri: String)
}

/** Pure logic of the non-UI callback activity (testable without an Activity). */
object SpotifyCallbackTrampoline {
    /** Reuses the existing singleTop MainActivity instead of creating a second one. */
    const val FORWARD_FLAGS = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    fun handle(dataString: String?, sink: SpotifyCallbackSink, startMain: (flags: Int) -> Unit) {
        if (!dataString.isNullOrBlank()) sink.onCallback(dataString)
        startMain(FORWARD_FLAGS)
    }
}
