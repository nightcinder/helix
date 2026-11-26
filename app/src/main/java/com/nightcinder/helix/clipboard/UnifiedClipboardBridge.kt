package com.nightcinder.helix.clipboard

import com.nightcinder.helix.core.model.SiteConfig
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.nightcinder.helix.api.DefaultTermixJson
import com.nightcinder.helix.core.model.AuthError
import com.nightcinder.helix.core.model.ClipboardSource
import com.nightcinder.helix.core.model.ClipboardSyncState
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.TermixApiException
import com.nightcinder.helix.core.stream.ClipboardBridge
import com.nightcinder.helix.core.terminal.TerminalTransport
import com.nightcinder.helix.stream.nv.NvGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface ClipboardAdapter {
    fun getText(): String?
    fun setText(text: String)
    fun setChangeListener(listener: ((String) -> Unit)?) {}
}

class AndroidClipboardAdapter(private val context: Context) : ClipboardAdapter {
    private val clipboard: ClipboardManager?
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private var activeListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun getText(): String? {
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount > 0) {
            return clip.getItemAt(0)?.text?.toString()
        }
        return null
    }

    override fun setText(text: String) {
        val clip = ClipData.newPlainText("Helix", text)
        clipboard?.setPrimaryClip(clip)
    }

    override fun setChangeListener(listener: ((String) -> Unit)?) {
        val cm = clipboard ?: return
        activeListener?.let { cm.removePrimaryClipChangedListener(it) }
        activeListener = null
        if (listener == null) return
        val registered = ClipboardManager.OnPrimaryClipChangedListener {
            getText()?.takeIf { it.isNotEmpty() }?.let(listener)
        }
        activeListener = registered
        cm.addPrimaryClipChangedListener(registered)
    }
}

class InMemoryClipboardAdapter(private var currentText: String? = null) : ClipboardAdapter {
    private var changeListener: ((String) -> Unit)? = null

    override fun getText(): String? = currentText

    override fun setText(text: String) {
        currentText = text
        if (text.isNotEmpty()) {
            changeListener?.invoke(text)
        }
    }

    override fun setChangeListener(listener: ((String) -> Unit)?) {
        changeListener = listener
    }
}

/**
 * Unified cross-device clipboard bridge synchronizing text between Android local clipboard,
 * active Termix SSH terminal sessions (via bracketed paste), and Vibepollo over GameStream
 * mTLS (`https://<host>:47984/actions/clipboard?type=text`).
 *
 * Implements strict echo suppression by recording synced content hash before updating Android's
 * local clipboard so [ClipboardManager.OnPrimaryClipChangedListener] never re-pushes pulled text.
 */
class UnifiedClipboardBridge(
    private val clipboardAdapter: ClipboardAdapter,
    private val terminalTransport: TerminalTransport,
    private val activeTabProvider: () -> String?,
    private val client: OkHttpClient,
    private val remoteBaseUrlProvider: () -> String = { SiteConfig.MOONLIGHT_URL },
    private val json: Json = DefaultTermixJson,
    private val nvGateway: NvGateway? = null,
    private val streamHostIpProvider: () -> String = { StreamConfig.DEFAULT_HOST_IP },
    private val canAutoPushProvider: () -> Boolean = { true },
    private val bridgeScope: CoroutineScope? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ClipboardBridge {

    companion object {
        const val BRACKETED_PASTE_START = "\u001B[200~"
        const val BRACKETED_PASTE_END = "\u001B[201~"
        const val CLIPBOARD_ENDPOINT = "/api/clipboard"
        private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val _state = MutableStateFlow(ClipboardSyncState())
    override val state: StateFlow<ClipboardSyncState> = _state.asStateFlow()

    init {
        if (bridgeScope != null) {
            clipboardAdapter.setChangeListener { text ->
                if (_state.value.autoSyncEnabled && canAutoPushProvider()) {
                    bridgeScope.launch {
                        onLocalClipboardChanged(text, ClipboardSource.ANDROID)
                    }
                }
            }
        }
    }

    override fun setAutoSync(enabled: Boolean) {
        _state.update { it.copy(autoSyncEnabled = enabled) }
    }

    override suspend fun pullFromRemote(): Result<String> = withContext(ioDispatcher) {
        _state.update { it.copy(isSyncing = true, lastError = null) }
        try {
            val remoteText = if (nvGateway != null) {
                nvGateway.getClipboard(streamHostIpProvider())
            } else {
                pullLegacyHttp()
            }

            if (remoteText.isNotEmpty()) {
                val hash = remoteText.hashCode()
                // Record hash BEFORE writing Android clipboard so PrimaryClipChanged listener suppresses echo.
                _state.update {
                    it.copy(
                        lastSyncedText = remoteText,
                        lastSyncedHash = hash,
                        lastSource = ClipboardSource.HOST_REMOTE,
                        lastSyncEpochMs = System.currentTimeMillis(),
                        isSyncing = false,
                        lastError = null,
                    )
                }
                withContext(mainDispatcher) {
                    clipboardAdapter.setText(remoteText)
                }
            } else {
                _state.update { it.copy(isSyncing = false, lastError = null) }
            }
            Result.success(remoteText)
        } catch (t: Throwable) {
            _state.update { it.copy(isSyncing = false, lastError = t.message) }
            Result.failure(t)
        }
    }

    override suspend fun pushToRemote(text: String): Result<Unit> =
        pushInternal(text, force = false)

    override suspend fun pushLocalClipboard(): Result<Unit> {
        val localText = clipboardAdapter.getText()?.takeIf { it.isNotEmpty() }
            ?: _state.value.lastSyncedText.orEmpty()
        if (localText.isEmpty()) {
            _state.update { it.copy(lastError = "Local clipboard is empty") }
            return Result.failure(IllegalStateException("Local clipboard is empty"))
        }
        return pushInternal(localText, force = true)
    }

    private suspend fun pushInternal(text: String, force: Boolean): Result<Unit> = withContext(ioDispatcher) {
        if (text.isEmpty()) return@withContext Result.success(Unit)

        if (!force && text == _state.value.lastSyncedText && text.hashCode() == _state.value.lastSyncedHash) {
            return@withContext Result.success(Unit)
        }

        _state.update { it.copy(isSyncing = true, lastError = null) }
        try {
            if (nvGateway != null) {
                nvGateway.setClipboard(streamHostIpProvider(), text)
            } else {
                pushLegacyHttp(text)
            }

            val hash = text.hashCode()
            _state.update {
                it.copy(
                    lastSyncedText = text,
                    lastSyncedHash = hash,
                    lastSource = ClipboardSource.ANDROID,
                    lastSyncEpochMs = System.currentTimeMillis(),
                    isSyncing = false,
                    lastError = null,
                )
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            _state.update { it.copy(isSyncing = false, lastError = t.message) }
            Result.failure(t)
        }
    }

    private fun pullLegacyHttp(): String {
        val base = remoteBaseUrlProvider().trimEnd('/')
        val request = Request.Builder()
            .url("$base$CLIPBOARD_ENDPOINT")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401 || AuthError.isDataExpiredBody(body)) {
                throw TermixApiException.DataExpired(
                    message = "Remote clipboard auth expired (DATA_EXPIRED)",
                    httpStatus = response.code,
                )
            }
            if (!response.isSuccessful) {
                throw TermixApiException.HttpError(
                    httpStatus = response.code,
                    responseBody = body,
                    message = "Failed to pull remote clipboard: HTTP ${response.code}",
                )
            }
            return parseClipboardText(body)
        }
    }

    private fun pushLegacyHttp(text: String) {
        val base = remoteBaseUrlProvider().trimEnd('/')
        val payload = buildJsonObject {
            put("text", text)
            put("timestamp", System.currentTimeMillis())
        }
        val requestBody = json.encodeToString(JsonObject.serializer(), payload)
            .toRequestBody(MEDIA_TYPE_JSON)

        val request = Request.Builder()
            .url("$base$CLIPBOARD_ENDPOINT")
            .header("Accept", "application/json")
            .header("X-Electron-App", "true")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401 || AuthError.isDataExpiredBody(body)) {
                throw TermixApiException.DataExpired(
                    message = "Remote clipboard push expired (DATA_EXPIRED)",
                    httpStatus = response.code,
                )
            }
            if (!response.isSuccessful) {
                throw TermixApiException.HttpError(
                    httpStatus = response.code,
                    responseBody = body,
                    message = "Failed to push remote clipboard: HTTP ${response.code}",
                )
            }
        }
    }

    override suspend fun pasteToActiveTerminal(text: String): Result<Unit> {
        val activeTabId = activeTabProvider()
            ?: return Result.failure(IllegalStateException("No active terminal tab selected"))

        return try {
            val bracketedPayload = "$BRACKETED_PASTE_START$text$BRACKETED_PASTE_END"
            terminalTransport.sendInput(activeTabId, bracketedPayload)

            val hash = text.hashCode()
            _state.update {
                it.copy(
                    lastSyncedText = text,
                    lastSyncedHash = hash,
                    lastSource = ClipboardSource.SSH_TERMINAL,
                    lastSyncEpochMs = System.currentTimeMillis(),
                )
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun onLocalClipboardChanged(
        text: String,
        source: ClipboardSource,
    ): Result<Unit> {
        val hash = text.hashCode()

        // Echo suppression: Ignore if identical to last synced text
        if (text == _state.value.lastSyncedText && hash == _state.value.lastSyncedHash) {
            return Result.success(Unit)
        }

        return if (_state.value.autoSyncEnabled && source != ClipboardSource.HOST_REMOTE) {
            pushInternal(text, force = false)
        } else {
            _state.update {
                it.copy(
                    lastSyncedText = text,
                    lastSyncedHash = hash,
                    lastSource = source,
                    lastSyncEpochMs = System.currentTimeMillis(),
                )
            }
            Result.success(Unit)
        }
    }

    private fun parseClipboardText(bodyString: String): String {
        val trimmed = bodyString.trim()
        if (trimmed.startsWith("{")) {
            return try {
                val obj = json.parseToJsonElement(trimmed).jsonObject
                obj["text"]?.jsonPrimitive?.content
                    ?: obj["content"]?.jsonPrimitive?.content
                    ?: obj["data"]?.jsonPrimitive?.content
                    ?: ""
            } catch (_: Exception) {
                ""
            }
        }
        return trimmed
    }
}
