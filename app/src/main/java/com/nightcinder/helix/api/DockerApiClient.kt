package com.nightcinder.helix.api

import com.nightcinder.helix.core.api.DockerApi
import com.nightcinder.helix.core.model.AuthError
import com.nightcinder.helix.core.model.AuthentikConfig
import com.nightcinder.helix.core.model.DockerActionResult
import com.nightcinder.helix.core.model.DockerComposeFile
import com.nightcinder.helix.core.model.DockerContainer
import com.nightcinder.helix.core.model.DockerContainerAction
import com.nightcinder.helix.core.model.DockerLogsResult
import com.nightcinder.helix.core.model.DockerSshSession
import com.nightcinder.helix.core.model.DockerStack
import com.nightcinder.helix.core.model.DockerStackAction
import com.nightcinder.helix.core.model.TermixApiException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * REST client for Termix v26.11.2 Docker Manager plugin (`/plugin-api/docker/...`).
 *
 * Enforces verified contracts:
 * - Attaches `X-Electron-App: true` on mutating requests (`POST`, `PUT`, `PATCH`, `DELETE`).
 * - Maps `401` / `DATA_EXPIRED` payloads to [TermixApiException.DataExpired].
 */
class DockerApiClient(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val json: Json = DefaultTermixJson,
) : DockerApi {

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
    private val emptyJsonBody = "{}".toRequestBody(mediaTypeJson)

    override suspend fun connectSsh(hostId: Long): DockerSshSession {
        // docker v1.3.x: the CLIENT picks the session id; the server answers
        // { success: true, status: "success" } or { status: "auth_required" | "passphrase_required" }
        // or { success: false, message } / { error }.
        val sessionId = java.util.UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("hostId", hostId)
        }
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaTypeJson)
        val request = newRequest("POST", "/plugin-api/docker/ssh/connect", body)
        val response = executeRaw(request)
        return handleResponse(response) { bodyString ->
            val root = runCatching { json.parseToJsonElement(bodyString) }.getOrNull() as? JsonObject
            val status = root?.get("status")?.jsonPrimitive?.contentOrNull
            val success = root?.get("success")?.jsonPrimitive?.booleanOrNull
            if (success == false || (status != null && status != "success")) {
                val reason = root?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: root?.get("error")?.jsonPrimitive?.contentOrNull
                    ?: status
                    ?: "unknown"
                throw TermixApiException.HttpError(
                    httpStatus = 200,
                    errorCode = status,
                    responseBody = bodyString,
                    message = "Docker SSH connect for host $hostId did not complete: $reason",
                )
            }
            DockerSshSession(sessionId = sessionId, hostId = hostId, connected = true)
        }
    }

    override suspend fun keepaliveSsh(sessionId: String): Boolean {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
        }
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaTypeJson)
        val request = newRequest("POST", "/plugin-api/docker/ssh/keepalive", body)
        val response = executeRaw(request)
        return handleResponse(response) { bodyString ->
            val trimmed = bodyString.trim()
            if (trimmed.isEmpty()) return@handleResponse true
            if (trimmed.equals("true", ignoreCase = true)) return@handleResponse true
            if (trimmed.equals("false", ignoreCase = true)) return@handleResponse false
            if (trimmed.startsWith("{")) {
                val root = json.parseToJsonElement(trimmed)
                if (root is JsonObject) {
                    val flag = root["alive"]?.jsonPrimitive?.booleanOrNull
                        ?: root["success"]?.jsonPrimitive?.booleanOrNull
                        ?: root["connected"]?.jsonPrimitive?.booleanOrNull
                        ?: root["ok"]?.jsonPrimitive?.booleanOrNull
                    return@handleResponse flag ?: true
                }
            }
            true
        }
    }

    override suspend fun listContainers(
        sessionId: String,
        all: Boolean,
    ): List<DockerContainer> {
        val path = if (all) {
            "/plugin-api/docker/containers/$sessionId"
        } else {
            "/plugin-api/docker/containers/$sessionId?all=false"
        }
        val request = newRequest("GET", path)
        return executeAndDecodeList(request, "containers", "items", "data")
    }

    override suspend fun containerAction(
        sessionId: String,
        containerId: String,
        action: DockerContainerAction,
    ): DockerActionResult {
        val path = "/plugin-api/docker/containers/$sessionId/$containerId/${action.wireName}"
        val request = newRequest("POST", path, emptyJsonBody)
        val result: DockerActionResult = executeAndDecodeObject(request, "result", "data")
        return result.copy(
            action = result.action ?: action.wireName,
            targetId = result.targetId ?: containerId,
        )
    }

    override suspend fun getContainerLogs(
        sessionId: String,
        containerId: String,
        tail: Int,
    ): DockerLogsResult {
        val path = "/plugin-api/docker/containers/$sessionId/$containerId/logs?tail=$tail"
        val request = newRequest("GET", path)
        val response = executeRaw(request)
        return handleResponse(response) { bodyString ->
            val trimmed = bodyString.trim()
            if (trimmed.startsWith("{")) {
                val decoded = decodeObject(trimmed, DockerLogsResult.serializer(), "logs", "data", "result")
                decoded.copy(
                    containerId = decoded.containerId.ifBlank { containerId },
                    tail = decoded.tail ?: tail,
                )
            } else {
                DockerLogsResult(
                    containerId = containerId,
                    logs = bodyString,
                    lines = if (bodyString.isEmpty()) emptyList() else bodyString.lines(),
                    tail = tail,
                )
            }
        }
    }

    override suspend fun listStacks(sessionId: String): List<DockerStack> {
        val request = newRequest("GET", "/plugin-api/docker/stacks/$sessionId")
        return executeAndDecodeList(request, "stacks", "items", "data")
    }

    override suspend fun listStackContainerNames(sessionId: String, stackName: String): List<String> {
        // GET /stacks/:sessionId/:stackName -> { services: [{ service, name, id, state, ... }] }
        val request = newRequest("GET", "/plugin-api/docker/stacks/$sessionId/$stackName")
        val response = executeRaw(request)
        return handleResponse(response) { bodyString ->
            val root = runCatching { json.parseToJsonElement(bodyString) }.getOrNull() as? JsonObject
            (root?.get("services") as? JsonArray).orEmpty().mapNotNull { svc ->
                (svc as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        }
    }

    override suspend fun getStackCompose(
        sessionId: String,
        stackName: String,
    ): DockerComposeFile {
        // docker v1.3 has no GET .../compose; GET /stacks/:sessionId/:stackName returns
        // { name, managed, composeFile, compose, env, services, ... }.
        val request = newRequest("GET", "/plugin-api/docker/stacks/$sessionId/$stackName")
        val response = executeRaw(request)
        return handleResponse(response) { bodyString ->
            val trimmed = bodyString.trim()
            if (trimmed.startsWith("{")) {
                val decoded = decodeObject(trimmed, DockerComposeFile.serializer(), "compose", "data", "result")
                decoded.copy(stackName = decoded.stackName.ifBlank { stackName })
            } else {
                DockerComposeFile(
                    stackName = stackName,
                    content = bodyString,
                )
            }
        }
    }

    override suspend fun updateStackCompose(
        sessionId: String,
        stackName: String,
        content: String,
    ): DockerComposeFile {
        val payload = buildJsonObject {
            put("stackName", stackName)
            put("content", content)
        }
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaTypeJson)
        val request = newRequest("PUT", "/plugin-api/docker/stacks/$sessionId/$stackName/compose", body)
        val result: DockerComposeFile = executeAndDecodeObject(request, "compose", "data", "result")
        return result.copy(
            stackName = result.stackName.ifBlank { stackName },
            content = result.content.ifBlank { content },
        )
    }

    override suspend fun stackAction(
        sessionId: String,
        stackName: String,
        action: DockerStackAction,
    ): DockerActionResult {
        val payload = buildJsonObject {
            put("action", action.wireName)
        }
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody(mediaTypeJson)
        val request = newRequest("POST", "/plugin-api/docker/stacks/$sessionId/$stackName/action", body)
        val result: DockerActionResult = executeAndDecodeObject(request, "result", "data")
        return result.copy(
            action = result.action ?: action.wireName,
            targetId = result.targetId ?: stackName,
        )
    }

    // --- Internal HTTP Execution & Parsing ---

    private fun newRequest(
        method: String,
        path: String,
        body: RequestBody? = null,
    ): Request {
        val base = baseUrlProvider().trimEnd('/')
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val fullUrl = "$base$cleanPath"

        val builder = Request.Builder()
            .url(fullUrl)
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
                    message = "Failed to deserialize Docker API response: ${e.message}",
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
                message = "Failed to decode Docker object: ${directEx.message}",
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
                throw TermixApiException.ParseError("Failed to decode Docker array: ${e.message}", e)
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
                throw TermixApiException.ParseError("Failed to parse Docker wrapper object: ${e.message}", e)
            }
        }

        return try {
            json.decodeFromString(listSerializer, trimmed)
        } catch (e: Exception) {
            throw TermixApiException.ParseError(
                message = "Failed to decode Docker list: ${e.message}",
                cause = e,
            )
        }
    }
}
