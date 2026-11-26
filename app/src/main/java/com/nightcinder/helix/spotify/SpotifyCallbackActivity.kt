package com.nightcinder.helix.spotify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.nightcinder.helix.HelixApp
import com.nightcinder.helix.MainActivity
import com.nightcinder.helix.di.AppContainer

/** Non-UI trampoline for `com.nightcinder.helix://spotify-callback`; finishes immediately. */
class SpotifyCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as HelixApp).getOrCreateContainer { AppContainer.create(it) }
        SpotifyCallbackTrampoline.handle(
            dataString = intent?.dataString,
            sink = container.spotifyAuthService::onCallback,
            startMain = { flags ->
                startActivity(Intent(this, MainActivity::class.java).addFlags(flags))
            },
        )
        finish()
    }
}
