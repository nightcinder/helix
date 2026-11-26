package com.nightcinder.helix.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent

sealed interface SpatialFinState {
    data object Closed : SpatialFinState

    /** `dev.spatialfin` is not installed (or its launcher activity is not resolvable). */
    data object NotInstalled : SpatialFinState

    /** Embedding allowed to be attempted; [intent] targets SpatialFin's main activity explicitly. */
    data class Ready(val intent: Intent) : SpatialFinState

    /** The system or SpatialFin refused cross-UID embedding (untrusted signing cert / SecurityException). */
    data class Rejected(val reason: String) : SpatialFinState
}

/**
 * Helix-side Epic 1C host: decides whether SpatialFin can be embedded in a `SpatialActivityPanel` and what to show
 * otherwise. Embedding only works when SpatialFin lists Helix's release signing cert in
 * `android:knownActivityEmbeddingCerts` (or opts in untrusted embedding); until then the user gets an explicit state
 * plus [standaloneIntent] instead of a blank panel.
 */
class SpatialFinHost(private val canResolve: (Intent) -> Boolean) {

    fun open(): SpatialFinState {
        val intent = embedIntent()
        return if (canResolve(intent)) SpatialFinState.Ready(intent) else SpatialFinState.NotInstalled
    }

    /** Runs [attempt] (e.g. controller.startActivity) and converts embedding refusals into [SpatialFinState.Rejected]. */
    fun guardedEmbed(intent: Intent, attempt: (Intent) -> Unit): SpatialFinState =
        try {
            attempt(intent)
            SpatialFinState.Ready(intent)
        } catch (e: SecurityException) {
            SpatialFinState.Rejected(
                "SpatialFin rejected embedding in Helix (its knownActivityEmbeddingCerts must list the Helix release cert).",
            )
        } catch (e: IllegalStateException) {
            SpatialFinState.Rejected("Embedding failed: ${e.message ?: "activity refused"}")
        }

    fun embedIntent(): Intent {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        intent.component = ComponentName(PACKAGE, MAIN_ACTIVITY)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /** Fallback when embedding is unavailable: open SpatialFin as its own app. */
    fun standaloneIntent(): Intent = embedIntent()

    companion object {
        const val PACKAGE = "dev.spatialfin"
        const val MAIN_ACTIVITY = "dev.spatialfin.unified.UnifiedMainActivity"

        fun forContext(context: Context): SpatialFinHost = SpatialFinHost { intent ->
            context.packageManager.resolveActivity(intent, 0) != null
        }
    }
}
