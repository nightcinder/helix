package com.nightcinder.helix.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal val mediaJson = Json { ignoreUnknownKeys = true }

internal data class HttpResult(val code: Int, val body: String)

internal suspend fun OkHttpClient.execute(request: Request): HttpResult = withContext(Dispatchers.IO) {
    try {
        newCall(request).execute().use { HttpResult(it.code, it.body?.string().orEmpty()) }
    } catch (e: IOException) {
        throw MediaApiException.Network(e.message ?: "I/O error")
    }
}

/** Maps non-2xx to typed errors; 401 additionally invokes [onUnauthorized] so the stale session is wiped. */
internal suspend fun HttpResult.ok(onUnauthorized: suspend () -> Unit = {}): String {
    if (code in 200..299) return body
    if (code == 401) {
        onUnauthorized()
        throw MediaApiException.Unauthorized()
    }
    throw MediaApiException.Http(code)
}

internal fun parseObject(text: String): JsonObject =
    try {
        mediaJson.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: throw MediaApiException.Invalid("Unexpected response body")

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
