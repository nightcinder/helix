package com.nightcinder.helix.api

import com.nightcinder.helix.core.api.SftpApi
import com.nightcinder.helix.core.model.AuthError
import com.nightcinder.helix.core.model.AuthentikConfig
import com.nightcinder.helix.core.model.SftpEntry
import com.nightcinder.helix.core.model.SftpFileContent
import com.nightcinder.helix.core.model.SftpSession
import com.nightcinder.helix.core.model.SftpTransferResult
import com.nightcinder.helix.core.model.TermixApiException
import com.nightcinder.helix.core.model.TermixHost
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * REST client for Termix v26.11.2 File Manager (SFTP) plugin (`/plugin-api/file-manager/...`).
 *
 * Enforces verified contracts:
 * - Attaches `X-Electron-App: true` on mutating requests (`POST`, `PUT`, `PATCH`, `DELETE`).
 * - Maps `401` / `DATA_EXPIRED` payloads to [TermixApiException.DataExpired].
 */
class SftpApiClient(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val json: Json = DefaultTermixJson,
) : SftpApi {

    constructor(
        client: OkHttpClient,
        baseUrl: String = AuthentikConfig.DEFAULT_TERMIX_URL,
        json: Json = DefaultTermixJson,
    ) : this(
        client = client,
        baseUrlProvider = { baseUrl },
        json = json,
    )

    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    override suspend fun connect(hostId: Long): SftpSession {
        // file-manager v1.2: the CLIENT picks the session id and must send ip/port/username, which the
        // server checks against the stored host (credentials are resolved server-side from hostId).
        // Success: { status: "success" }; otherwise status auth_required / passphrase_required /
        // host_key_changed / error, or an { error } body.
        val host: TermixHost = executeAndDecodeObject(newRequest("GET", "/host/db/host/$hostId"), "host", "data")
        val sessionId = java.util.UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("hostId", hostId)
            put("ip", host.ip.ifBlank { host.hostname.orEmpty() })
            put("port", host.port)
            host.username?.let { put("username", it) }
        }
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaTypeJson)
        val request = newRequest("POST", "/plugin-api/file-manager/connect", body = body)
        val response = executeRaw(request)
        return handleResponse(response) { bodyString ->
            val root = runCatching { json.parseToJsonElement(bodyString) }.getOrNull() as? JsonObject
            val status = root?.get("status")?.jsonPrimitive?.contentOrNull
            if (status != null && status != "success") {
                val reason = root["message"]?.jsonPrimitive?.contentOrNull ?: status
                throw TermixApiException.HttpError(
                    httpStatus = 200,
                    errorCode = status,
                    responseBody = bodyString,
                    message = "SFTP connect for host $hostId did not complete: $reason",
                )
            }
            SftpSession(sessionId = sessionId, hostId = hostId, connected = true)
        }
    }

    override suspend fun listFiles(
        sessionId: String,
        path: String,
    ): List<SftpEntry> {
        val request = newRequest(
            method = "GET",
            path = "/plugin-api/file-manager/listFiles",
            queryParams = mapOf(
                "sessionId" to sessionId,
                "path" to path,
            ),
        )
        return executeAndDecodeList<SftpEntry>(request, "files", "entries", "items", "data").map { it.normalized() }
    }

    override suspend fun readFile(
        sessionId: String,
        path: String,
    ): SftpFileContent {
        val request = newRequest(
            method = "GET",
            path = "/plugin-api/file-manager/readFile",
            queryParams = mapOf(
                "sessionId" to sessionId,
                "path" to path,
            ),
        )
        val response = executeRaw(request)
        return handleResponse(response) { bodyString ->
            val trimmed = bodyString.trim()
            if (trimmed.startsWith("{")) {
                val decoded = decodeObject(trimmed, SftpFileContent.serializer(), "file", "data", "result")
                decoded.copy(path = decoded.path.ifBlank { path })
            } else {
                SftpFileContent(
                    path = path,
                    content = bodyString,
                    sizeBytes = bodyString.encodeToByteArray().size.toLong(),
                )
            }
        }
    }

    override suspend fun writeFile(
        sessionId: String,
        path: String,
        content: String,
    ): SftpTransferResult {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("path", path)
            put("content", content)
        }
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaTypeJson)
        val request = newRequest("POST", "/plugin-api/file-manager/writeFile", body = body)
        val result: SftpTransferResult = executeAndDecodeObject(request, "result", "data")
        return result.copy(
            targetPath = result.targetPath.ifBlank { path },
            bytesTransferred = if (result.bytesTransferred > 0L) {
                result.bytesTransferred
            } else {
                content.encodeToByteArray().size.toLong()
            },
        )
    }

    override suspend fun downloadFile(
        sessionId: String,
        path: String,
    ): ByteArray {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("path", path)
        }
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaTypeJson)
        val request = newRequest("POST", "/plugin-api/file-manager/downloadFileStream", body = body)
        val response = executeRaw(request)
        response.use { resp ->
            val code = resp.code
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (code == 401) {
                val bodyString = bytes.decodeToString()
                if (AuthError.isDataExpiredBody(bodyString)) {
                    throw TermixApiException.DataExpired(
                        message = "Termix DEK expired (DATA_EXPIRED): $bodyString",
                        code = AuthError.CODE_DATA_EXPIRED,
                        httpStatus = code,
                    )
                } else {
                    throw TermixApiException.Unauthorized(
                        message = "Unauthorized (HTTP $code)",
                        httpStatus = code,
                        responseBody = bodyString,
                    )
                }
            }
            if (!resp.isSuccessful) {
                val bodyString = bytes.decodeToString()
                if (AuthError.isDataExpiredBody(bodyString)) {
                    throw TermixApiException.DataExpired(
                        message = "Termix DEK expired (DATA_EXPIRED): $bodyString",
                        code = AuthError.CODE_DATA_EXPIRED,
                        httpStatus = code,
                    )
                }
                throw TermixApiException.HttpError(
                    httpStatus = code,
                    responseBody = bodyString,
                    message = "HTTP $code from ${resp.request.url}: $bodyString",
                )
            }
            return bytes
        }
    }

    override suspend fun uploadFile(
        sessionId: String,
        path: String,
        bytes: ByteArray,
    ): SftpTransferResult {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("path", path)
            put("contentBase64", encoded)
            put("sizeBytes", bytes.size.toLong())
        }
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaTypeJson)
        val request = newRequest("POST", "/plugin-api/file-manager/uploadFileStream", body = body)
        val result: SftpTransferResult = executeAndDecodeObject(request, "result", "data")
        return result.copy(
            targetPath = result.targetPath.ifBlank { path },
            bytesTransferred = if (result.bytesTransferred > 0L) result.bytesTransferred else bytes.size.toLong(),
        )
    }

    override suspend fun transferToHost(
        sourceSessionId: String,
        sourcePath: String,
        targetHostId: Long,
        targetPath: String,
    ): SftpTransferResult {
        val payload = buildJsonObject {
            put("sourceSessionId", sourceSessionId)
            put("sourcePath", sourcePath)
            put("targetHostId", targetHostId)
            put("targetPath", targetPath)
        }
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaTypeJson)
        val request = newRequest("POST", "/plugin-api/file-manager/transferToHost", body = body)
        val result: SftpTransferResult = executeAndDecodeObject(request, "result", "data")
        return result.copy(
            sourcePath = result.sourcePath.ifBlank { sourcePath },
            targetPath = result.targetPath.ifBlank { targetPath },
            targetHostId = result.targetHostId ?: targetHostId,
        )
    }

    // --- Internal HTTP Execution & Parsing ---

    private fun newRequest(
        method: String,
        path: String,
        queryParams: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): Request {
        val base = baseUrlProvider().trimEnd('/')
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val httpUrlBuilder = "$base$cleanPath".toHttpUrl().newBuilder()
        for ((k, v) in queryParams) {
            httpUrlBuilder.addQueryParameter(k, v)
        }

        val builder = Request.Builder()
            .url(httpUrlBuilder.build())
            .header("Accept", "application/json")

        val isMutating = method.equals("POST", ignoreCase = true) ||
            method.equals("PUT", ignoreCase = true) ||
            method.equals("PATCH", ignoreCase = true) ||
            method.equals("DELETE", ignoreCase = true)

        if (isMutating) {
            builder.header("X-Electron-App", "true")
        }

        builder.method(method, body)
        return builder.build()
    }

    private suspend fun executeRaw(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            TermixApiException.NetworkError(
                                message = e.message ?: "Network error connecting to ${request.url}",
                                cause = e,
                            )
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    }
                }
            })
        }

    private suspend inline fun <reified T> executeAndDecodeObject(
        request: Request,
        vararg wrapperKeys: String,
    ): T {
        val response = executeRaw(request)
        return handleResponse(response) { bodyString ->
            decodeObject(bodyString, serializer<T>(), *wrapperKeys)
        }
    }

    private suspend inline fun <reified T> executeAndDecodeList(
        request: Request,
        vararg wrapperKeys: String,
    ): List<T> {
        val response = executeRaw(request)
        return handleResponse(response) { bodyString ->
            decodeList(bodyString, serializer<List<T>>(), *wrapperKeys)
        }
    }

    private inline fun <R> handleResponse(
        response: Response,
        decoder: (String) -> R,
    ): R {
        response.use { resp ->
            val code = resp.code
            val bodyString = resp.body?.string().orEmpty()

            if (code == 401 || AuthError.isDataExpiredBody(bodyString)) {
                if (AuthError.isDataExpiredBody(bodyString)) {
                    throw TermixApiException.DataExpired(
                        message = "Termix DEK expired (DATA_EXPIRED): $bodyString",
                        code = AuthError.CODE_DATA_EXPIRED,
                        httpStatus = code,
                    )
                } else {
                    throw TermixApiException.Unauthorized(
                        message = "Unauthorized (HTTP $code)",
                        httpStatus = code,
                        responseBody = bodyString,
                    )
                }
            }

            if (!resp.isSuccessful) {
                throw TermixApiException.HttpError(
                    httpStatus = code,
                    responseBody = bodyString,
                    message = "HTTP $code from ${resp.request.url}: $bodyString",
                )
            }

            return try {
                decoder(bodyString)
            } catch (e: TermixApiException) {
                throw e
            } catch (e: Exception) {
                throw TermixApiException.ParseError(
                    message = "Failed to deserialize SFTP API response: ${e.message}",
                    cause = e,
                )
            }
        }
    }

    private fun <T> decodeObject(
        bodyString: String,
        serializer: KSerializer<T>,
        vararg wrapperKeys: String,
    ): T {
        val trimmed = bodyString.trim()
        if (trimmed.isEmpty()) {
            return try {
                json.decodeFromString(serializer, "{}")
            } catch (e: Exception) {
                throw TermixApiException.ParseError("Empty response body cannot be deserialized", e)
            }
        }

        try {
            return json.decodeFromString(serializer, trimmed)
        } catch (directEx: Exception) {
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    val root = json.parseToJsonElement(trimmed)
                    if (root is JsonObject) {
                        val candidates = wrapperKeys.toList() + listOf("data", "result", "item")
                        for (key in candidates) {
                            val candidateElement = root[key]
                            if (candidateElement != null && candidateElement !is JsonArray) {
                                try {
                                    return json.decodeFromJsonElement(serializer, candidateElement)
                                } catch (_: Exception) {
                                    // continue
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // fall back
                }
            }
            throw TermixApiException.ParseError(
                message = "Failed to decode SFTP object: ${directEx.message}",
                cause = directEx,
            )
        }
    }

    private fun <T> decodeList(
        bodyString: String,
        listSerializer: KSerializer<List<T>>,
        vararg wrapperKeys: String,
    ): List<T> {
        val trimmed = bodyString.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.startsWith("[")) {
            return try {
                json.decodeFromString(listSerializer, trimmed)
            } catch (e: Exception) {
                throw TermixApiException.ParseError("Failed to decode SFTP array: ${e.message}", e)
            }
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val root = json.parseToJsonElement(trimmed)
                if (root is JsonObject) {
                    val candidates = wrapperKeys.toList() + listOf("data", "items", "results")
                    for (key in candidates) {
                        val candidateElement = root[key]
                        if (candidateElement is JsonArray) {
                            try {
                                return json.decodeFromJsonElement(listSerializer, candidateElement)
                            } catch (_: Exception) {
                                // continue
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                throw TermixApiException.ParseError("Failed to parse SFTP wrapper object: ${e.message}", e)
            }
        }

        return try {
            json.decodeFromString(listSerializer, trimmed)
        } catch (e: Exception) {
            throw TermixApiException.ParseError(
                message = "Failed to decode SFTP list: ${e.message}",
                cause = e,
            )
        }
    }
}
