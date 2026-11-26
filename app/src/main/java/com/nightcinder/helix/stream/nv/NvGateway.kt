package com.nightcinder.helix.stream.nv

import com.nightcinder.helix.core.model.AppDiscovery
import com.nightcinder.helix.core.model.HostApp
import com.nightcinder.helix.core.model.HostAppCatalog
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.VirtualDisplayStatus
import java.security.SecureRandom

/** Everything the native stream session needs from a successful /launch or /resume. */
class NvLaunch(
    val host: String,
    val rtspSessionUrl: String,
    val appVersion: String,
    val gfeVersion: String,
    val serverCodecModeSupport: Int,
    val riKey: ByteArray,
    val riKeyId: Int,
    val appTitle: String,
    val resumed: Boolean,
)

sealed class PairingCheck {
    object Paired : PairingCheck()
    object NotPaired : PairingCheck()
}

/** Host-facing operations the stream client drives; faked in unit tests. */
interface NvGateway {
    /** Reads serverinfo and reports whether this client is paired with the host. Throws [java.io.IOException]. */
    suspend fun checkPairing(host: String): PairingCheck

    /** Runs the full PIN pairing; suspends until the owner types [pin] on the host (cancellable). */
    suspend fun pair(host: String, pin: String): PairResult

    /**
     * applist -> app pick -> /launch, /resume or /cancel + /launch (see [DefaultNvGateway.launch]).
     * Requires a paired host. Throws before the native session starts when the host refuses.
     */
    suspend fun launch(host: String, config: StreamConfig, launchQueryParams: String): NvLaunch

    /**
     * Lists the host's apps and virtual-display readiness. [AppDiscovery.Unpaired] (no request sent) when this
     * client has no pairing with [host]. Throws [java.io.IOException] when the host cannot be read.
     */
    suspend fun discoverApps(host: String): AppDiscovery =
        AppDiscovery.Unavailable("App discovery not supported")

    /** Forgets the stored pairing for [host] (local only). */
    suspend fun forgetPairing(host: String)

    /** Reads plain-text clipboard from the paired host over GameStream mTLS (`47984/actions/clipboard`). */
    suspend fun getClipboard(host: String): String =
        throw UnsupportedOperationException("Clipboard not supported")

    /** Sets plain-text clipboard on the paired host over GameStream mTLS (`47984/actions/clipboard`). */
    suspend fun setClipboard(host: String, text: String): Unit =
        throw UnsupportedOperationException("Clipboard not supported")
}

class DefaultNvGateway(
    private val identityStore: NvIdentityStore,
    private val random: SecureRandom = SecureRandom(),
    /** Test seam for the host calls behind discovery and launch; defaults to the mTLS [NvHttp]. */
    private val hostApiFactory: (suspend (String) -> NvHostApi)? = null,
) : NvGateway {

    private suspend fun hostApi(host: String): NvHostApi = hostApiFactory?.invoke(host) ?: newHttp(host)

    private suspend fun newHttp(host: String): NvHttp {
        val identity = identityStore.loadOrCreateIdentity()
        return NvHttp(
            host = host,
            uniqueId = identityStore.uniqueId(),
            identity = identity,
            serverCert = identityStore.pinnedServerCert(host),
        )
    }

    override suspend fun checkPairing(host: String): PairingCheck {
        val http = newHttp(host)
        val pinned = http.serverCert
        val info = http.getServerInfo()
        if (pinned != null && info.isPaired) return PairingCheck.Paired
        if (pinned != null) {
            if (!http.lastServerInfoViaHttps) {
                // HTTPS failed (network blip, host restarting, or a changed host cert). HTTP always says
                // PairStatus 0, so it proves nothing: keep the pin and let the caller retry/launch fail.
                throw NvHttpException(
                    "Host did not accept the paired certificate over HTTPS (${http.lastHttpsFailure ?: "no detail"}). " +
                        "Retry; if this device was " +
                        "removed in Vibepollo, use Forget pairing and pair again.",
                )
            }
            // The host answered over HTTPS but no longer lists us (unpaired there): drop the stale pin.
            identityStore.clearPairing(host)
        }
        return PairingCheck.NotPaired
    }

    override suspend fun pair(host: String, pin: String): PairResult {
        val http = newHttp(host)
        // Pair over plain HTTP regardless of any stale pin.
        http.serverCert = null
        val info = http.getServerInfo()
        val result = PairingManager(identityStore.loadOrCreateIdentity()).pair(info.majorVersion, pin, http)
        if (result is PairResult.Paired) identityStore.savePinnedServerCert(host, result.serverCert)
        return result
    }

    override suspend fun discoverApps(host: String): AppDiscovery {
        val http = hostApi(host)
        if (!http.isPinned) return AppDiscovery.Unpaired
        val info = http.getServerInfo()
        if (!info.isPaired) throw NvHttpException("Host no longer lists this client as paired")
        val apps = http.getAppList().map { HostApp(it.id, it.title, it.isHdrSupported) }
        return AppDiscovery.Loaded(
            HostAppCatalog(
                apps = apps,
                currentGame = info.currentGame,
                virtualDisplay = VirtualDisplayStatus(
                    capable = info.virtualDisplayCapable,
                    driverReady = info.virtualDisplayDriverReady,
                    hdrCapable = info.virtualDisplayHdrCapable,
                ),
            ),
        )
    }

    override suspend fun launch(host: String, config: StreamConfig, launchQueryParams: String): NvLaunch {
        val http = hostApi(host)
        if (!http.isPinned) throw NvHttpException("Host is not paired")
        val info = http.getServerInfo()
        if (!info.isPaired) throw NvHttpException("Host no longer lists this client as paired")

        val apps = http.getAppList()
        if (apps.isEmpty()) throw NvHttpException("Host has no apps to stream")
        val app = pickApp(apps, config)

        val riKey = ByteArray(16).also { random.nextBytes(it) }
        val riKeyId = random.nextInt()
        val params = NvLaunchParams(
            appId = app.id,
            width = config.width,
            height = config.height,
            fps = config.fps,
            riKey = riKey,
            riKeyId = riKeyId,
            extraQuery = launchQueryParams,
        )
        val running = info.currentGame
        val resumed = running == app.id
        val response = when {
            running == 0 -> http.launch(params)
            resumed -> http.resume(params)
            // Remote Monitor / Remote Input run beside the active app on their own virtual session.
            isConcurrentApp(app) -> http.launch(params)
            config.replaceRunningApp -> {
                if (!http.cancel()) throw NvHttpException("Host did not stop the running app")
                http.launch(params)
            }
            else -> throw NvHttpException(
                "Host is already running another app. Resume it or choose Replace & Launch for ${app.title}.",
            )
        }
        val url = response.sessionUrl?.takeIf { it.isNotBlank() }
            ?: throw NvHttpException("Host did not return an RTSP session URL")

        return NvLaunch(
            host = host,
            rtspSessionUrl = url,
            appVersion = info.appVersion,
            gfeVersion = info.gfeVersion,
            serverCodecModeSupport = info.serverCodecModeSupport,
            riKey = riKey,
            riKeyId = riKeyId,
            appTitle = app.title,
            resumed = resumed,
        )
    }

    private fun pickApp(apps: List<NvApp>, config: StreamConfig): NvApp {
        val id = config.appId
        if (id != null) {
            return apps.firstOrNull { it.id == id }
                ?: throw NvHttpException("${config.appTitle} is no longer offered by the host. Refresh the app list.")
        }
        return apps.firstOrNull { it.title.equals(config.appTitle, ignoreCase = true) }
            ?: apps.firstOrNull { it.title.equals(StreamConfig.DEFAULT_APP_TITLE, ignoreCase = true) }
            ?: apps.first()
    }

    private fun isConcurrentApp(app: NvApp) = CONCURRENT_APP_TITLES.any { it.equals(app.title, ignoreCase = true) }

    companion object {
        /** Vibepollo virtual-session apps that can run while another app is active. */
        val CONCURRENT_APP_TITLES = setOf("Remote Monitor", "Remote Input")
    }

    override suspend fun forgetPairing(host: String) {
        newHttp(host).unpair()
        identityStore.clearPairing(host)
    }

    override suspend fun getClipboard(host: String): String {
        val http = newHttp(host)
        if (http.serverCert == null) throw NvHttpException("Host is not paired")
        return http.getClipboardText()
    }

    override suspend fun setClipboard(host: String, text: String) {
        val http = newHttp(host)
        if (http.serverCert == null) throw NvHttpException("Host is not paired")
        http.setClipboardText(text)
    }
}
