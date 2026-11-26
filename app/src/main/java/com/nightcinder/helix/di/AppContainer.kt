package com.nightcinder.helix.di

import android.app.Application
import com.nightcinder.helix.api.DockerApiClient
import com.nightcinder.helix.api.HomelabRepositoryImpl
import com.nightcinder.helix.api.SftpApiClient
import com.nightcinder.helix.api.TermixApiClient
import com.nightcinder.helix.api.WorkspaceTreeRepositoryImpl
import com.nightcinder.helix.BuildConfig
import com.nightcinder.helix.auth.AuthentikPasskeyAuthManager
import com.nightcinder.helix.auth.BiometricLockManager
import com.nightcinder.helix.auth.CredentialManagerPasskeyRequester
import com.nightcinder.helix.auth.DataExpiredListener
import com.nightcinder.helix.auth.DataStoreBiometricLockStore
import com.nightcinder.helix.auth.EncryptedCookieJar
import com.nightcinder.helix.auth.canDeviceAuthenticate
import com.nightcinder.helix.auth.TermixAuthInterceptor
import com.nightcinder.helix.auth.TermixSessionStore
import com.nightcinder.helix.auth.TinkCryptoStore
import com.nightcinder.helix.clipboard.AndroidClipboardAdapter
import com.nightcinder.helix.clipboard.UnifiedClipboardBridge
import com.nightcinder.helix.core.api.DockerApi
import com.nightcinder.helix.core.api.HomelabRepository
import com.nightcinder.helix.core.api.SftpApi
import com.nightcinder.helix.core.api.TermixApi
import com.nightcinder.helix.core.api.WorkspaceTreeRepository
import com.nightcinder.helix.core.auth.ActivityProvider
import com.nightcinder.helix.core.auth.AuthManager
import com.nightcinder.helix.core.auth.TokenProvider
import com.nightcinder.helix.core.auth.WeakReferenceActivityProvider
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.monitoring.MonitoringRepository
import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.stream.ClipboardBridge
import com.nightcinder.helix.core.stream.MoonlightStreamClient
import com.nightcinder.helix.core.stream.VideoFrameSink
import com.nightcinder.helix.core.terminal.TerminalTransport
import com.nightcinder.helix.monitoring.DiunLogAlertSource
import com.nightcinder.helix.monitoring.GrafanaAlertsClient
import com.nightcinder.helix.media.JellyfinClient
import com.nightcinder.helix.media.Media3PlayerController
import com.nightcinder.helix.media.MediaPlayerController
import com.nightcinder.helix.media.PlexClient
import com.nightcinder.helix.monitoring.MonitoringRepositoryImpl
import com.nightcinder.helix.monitoring.PrometheusApiClient
import com.nightcinder.helix.spatial.UrxrPresentationManager
import com.nightcinder.helix.spotify.AndroidLocalSessionSource
import com.nightcinder.helix.spotify.LocalSpotifyPlayer
import com.nightcinder.helix.spotify.SpotifyAuthService
import com.nightcinder.helix.spotify.SpotifyClientConfig
import com.nightcinder.helix.spotify.SpotifyWebApi
import com.nightcinder.helix.spatial.XrSessionController
import com.nightcinder.helix.stream.DualPathMoonlightClient
import com.nightcinder.helix.stream.MediaCodecVideoDecoder
import com.nightcinder.helix.stream.MoonlightNativeBridge
import com.nightcinder.helix.stream.MoonlightWebSocketTransport
import com.nightcinder.helix.stream.nv.DefaultNvGateway
import com.nightcinder.helix.stream.nv.NvIdentityStore
import com.nightcinder.helix.terminal.TermixWebSocketTerminalTransport
import com.nightcinder.helix.ui.layout.CockpitLayoutManager
import com.nightcinder.helix.ui.layout.DataStoreCockpitLayoutStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Dependency injection container interface for Project Helix.
 */
interface AppContainer {
    val activityProvider: ActivityProvider
    val authManager: AuthManager
    val tokenProvider: TokenProvider
    val termixApi: TermixApi
    val homelabRepository: HomelabRepository
    val displayBackendDetector: DisplayBackendDetector

    // Phase 2 DI components
    val terminalTransport: TerminalTransport
    val dockerApi: DockerApi
    val sftpApi: SftpApi
    val workspaceTreeRepository: WorkspaceTreeRepository

    // Phase 3 DI components
    val moonlightNativeBridge: MoonlightNativeBridge
    val videoFrameSink: VideoFrameSink
    val moonlightWebSocketTransport: MoonlightWebSocketTransport
    val moonlightStreamClient: MoonlightStreamClient
    val clipboardBridge: ClipboardBridge

    // Phase 4 DI components
    val xrSessionController: XrSessionController
    val urxrPresentationManager: UrxrPresentationManager

    // Monitoring DI component (Prometheus + Grafana + Diun)
    val monitoringRepository: MonitoringRepository

    // Session biometric / device-credential lock manager
    val biometricLockManager: BiometricLockManager

    // Runtime cockpit layout mode (Panels <-> Tree)
    val cockpitLayoutManager: CockpitLayoutManager

    // Spotify (OAuth2 PKCE, independent of biometric lock state)
    val spotifyClientConfig: SpotifyClientConfig
    val spotifyAuthService: SpotifyAuthService
    val spotifyWebApi: SpotifyWebApi
    val localSpotifyPlayer: LocalSpotifyPlayer

    // Native Media3 video (Jellyfin / Plex)
    val jellyfinClient: JellyfinClient
    val plexClient: PlexClient
    val mediaPlayerController: MediaPlayerController

    companion object {
        fun create(application: Application): AppContainer {
            return DefaultAppContainer(application)
        }
    }
}

class DefaultAppContainer(
    private val application: Application,
) : AppContainer {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val activityProvider: ActivityProvider by lazy {
        WeakReferenceActivityProvider()
    }

    override val displayBackendDetector: DisplayBackendDetector by lazy {
        DisplayBackendDetector(application)
    }

    private val cryptoStore by lazy {
        TinkCryptoStore(application)
    }

    private val cookieJar by lazy {
        EncryptedCookieJar(cryptoStore)
    }

    private val sessionStore by lazy {
        TermixSessionStore(cryptoStore)
    }

    private val termixAuthInterceptor by lazy {
        TermixAuthInterceptor(
            session = sessionStore,
        )
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(termixAuthInterceptor)
            // keep idle WebSockets (SSH tabs behind Cloudflare, ~100 s idle limit) alive
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    private val passkeyRequester by lazy {
        CredentialManagerPasskeyRequester(activityProvider)
    }

    private val authentikPasskeyAuthManager by lazy {
        AuthentikPasskeyAuthManager(
            httpClient = okHttpClient,
            cookieJar = cookieJar,
            session = sessionStore,
            passkeyRequester = passkeyRequester,
        ).also { authMgr ->
            termixAuthInterceptor.listener = DataExpiredListener {
                applicationScope.launch {
                    authMgr.handleDataExpired()
                }
            }
            applicationScope.launch {
                authMgr.initialize()
            }
            applicationScope.launch {
                authMgr.sessionState
                    .map { it.isAuthenticated }
                    .distinctUntilChanged()
                    .collect { authenticated ->
                        if (!authenticated) {
                            biometricLockManager.onSignOut()
                        }
                    }
            }
        }
    }

    override val authManager: AuthManager
        get() = authentikPasskeyAuthManager

    override val tokenProvider: TokenProvider
        get() = authentikPasskeyAuthManager

    override val termixApi: TermixApi by lazy {
        TermixApiClient(
            client = okHttpClient,
            baseUrlProvider = {
                sessionStore.config().termixBaseUrl
            }
        )
    }

    override val homelabRepository: HomelabRepository by lazy {
        HomelabRepositoryImpl(api = termixApi)
    }

    // Phase 2 components wiring
    override val terminalTransport: TerminalTransport by lazy {
        TermixWebSocketTerminalTransport(
            client = okHttpClient,
            termixApi = termixApi,
            baseUrlProvider = {
                sessionStore.config().termixBaseUrl
            },
            onDataExpiredReauth = { authentikPasskeyAuthManager.onDataExpired().isSuccess },
        )
    }

    override val dockerApi: DockerApi by lazy {
        DockerApiClient(
            client = okHttpClient,
            baseUrlProvider = {
                sessionStore.config().termixBaseUrl
            }
        )
    }

    override val sftpApi: SftpApi by lazy {
        SftpApiClient(
            client = okHttpClient,
            baseUrlProvider = {
                sessionStore.config().termixBaseUrl
            }
        )
    }

    override val workspaceTreeRepository: WorkspaceTreeRepository by lazy {
        WorkspaceTreeRepositoryImpl(
            dockerApi = dockerApi,
            sftpApi = sftpApi,
        )
    }

    // Phase 3 components wiring
    override val moonlightNativeBridge: MoonlightNativeBridge by lazy {
        MoonlightNativeBridge()
    }

    override val videoFrameSink: VideoFrameSink by lazy {
        MediaCodecVideoDecoder()
    }

    override val moonlightWebSocketTransport: MoonlightWebSocketTransport by lazy {
        MoonlightWebSocketTransport(
            client = okHttpClient,
            frameSink = videoFrameSink,
        )
    }

    private val nvGateway by lazy {
        DefaultNvGateway(NvIdentityStore(cryptoStore))
    }

    override val moonlightStreamClient: MoonlightStreamClient by lazy {
        DualPathMoonlightClient(
            nativeBridge = moonlightNativeBridge,
            webSocketTransport = moonlightWebSocketTransport,
            frameSink = videoFrameSink,
            nvGateway = nvGateway,
            onAuthExpired = { authentikPasskeyAuthManager.refreshOutpostCookies(force = true).isSuccess },
        )
    }

    override val clipboardBridge: ClipboardBridge by lazy {
        UnifiedClipboardBridge(
            clipboardAdapter = AndroidClipboardAdapter(application),
            terminalTransport = terminalTransport,
            activeTabProvider = { workspaceTreeRepository.activeTerminalTabId.value },
            client = okHttpClient,
            remoteBaseUrlProvider = { sessionStore.config().moonlightBaseUrl },
            nvGateway = nvGateway,
            streamHostIpProvider = {
                moonlightStreamClient.state.value.targetHostIp.ifBlank { StreamConfig.DEFAULT_HOST_IP }
            },
            canAutoPushProvider = {
                val st = moonlightStreamClient.state.value
                st.status == StreamConnectionStatus.Connected &&
                    st.activeTransport == StreamTransportMode.DIRECT_UDP
            },
            bridgeScope = applicationScope,
        )
    }

    // Phase 4 components wiring
    override val xrSessionController: XrSessionController by lazy {
        XrSessionController(backendDetector = displayBackendDetector)
    }

    override val urxrPresentationManager: UrxrPresentationManager by lazy {
        UrxrPresentationManager(context = application, backendDetector = displayBackendDetector)
    }

    // Monitoring components wiring (bounded timeouts so off-LAN drops never stall UI loading)
    private val monitoringHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val prometheusApiClient by lazy {
        PrometheusApiClient(
            client = monitoringHttpClient,
            baseUrlProvider = { sessionStore.config().prometheusBaseUrl },
        )
    }

    private val grafanaAlertsClient by lazy {
        GrafanaAlertsClient(
            client = monitoringHttpClient,
            baseUrlProvider = { sessionStore.config().grafanaBaseUrl },
        )
    }

    private val diunLogAlertSource by lazy {
        DiunLogAlertSource(
            dockerApi = dockerApi,
            existingSessionIdProvider = { hostId ->
                workspaceTreeRepository.dockerSessionsByHost.value[hostId]?.sessionId
            },
        )
    }

    override val monitoringRepository: MonitoringRepository by lazy {
        MonitoringRepositoryImpl(
            prometheusClient = prometheusApiClient,
            grafanaClient = grafanaAlertsClient,
            diunSource = diunLogAlertSource,
        )
    }

    override val biometricLockManager: BiometricLockManager by lazy {
        BiometricLockManager(
            store = DataStoreBiometricLockStore(application),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            deviceSupportsAuth = { canDeviceAuthenticate(application) },
        )
    }

    override val cockpitLayoutManager: CockpitLayoutManager by lazy {
        CockpitLayoutManager(
            store = DataStoreCockpitLayoutStore(application),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    // Plain client: no Termix cookie jar / auth interceptor, so Termix credentials never reach Spotify hosts.
    private val spotifyHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override val spotifyClientConfig: SpotifyClientConfig by lazy {
        SpotifyClientConfig(buildClientId = BuildConfig.SPOTIFY_CLIENT_ID, store = cryptoStore)
    }

    override val spotifyAuthService: SpotifyAuthService by lazy {
        SpotifyAuthService(
            client = spotifyHttpClient,
            store = cryptoStore,
            config = spotifyClientConfig,
            scope = applicationScope,
        )
    }

    override val spotifyWebApi: SpotifyWebApi by lazy {
        SpotifyWebApi(client = spotifyHttpClient, tokens = spotifyAuthService)
    }

    override val localSpotifyPlayer: LocalSpotifyPlayer by lazy {
        LocalSpotifyPlayer(AndroidLocalSessionSource(application))
    }

    // Plain client (no Termix cookie jar / auth interceptor): media tokens only ever go to Jellyfin / Plex hosts.
    private val mediaHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override val jellyfinClient: JellyfinClient by lazy {
        JellyfinClient(client = mediaHttpClient, store = cryptoStore)
    }

    override val plexClient: PlexClient by lazy {
        PlexClient(client = mediaHttpClient, store = cryptoStore)
    }

    override val mediaPlayerController: MediaPlayerController by lazy {
        Media3PlayerController(application)
    }
}
