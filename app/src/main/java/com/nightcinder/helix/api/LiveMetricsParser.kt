package com.nightcinder.helix.api

import com.nightcinder.helix.core.model.LiveHostMetrics
import com.nightcinder.helix.core.model.ProcessSample
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses a host-metrics live sample.
 *
 * Termix host-metrics v26.11 (plugin-sdk `parseLive`) sends, possibly wrapped in `sample`,
 * `metrics` or `data`:
 * `{ cpu, cores, memTotalKb, memUsedKb, swapTotalKb, swapUsedKb, load: [1,5,15],
 *    net: [{ name, rxBps, txBps }], procs: [{ pid, name, cpu, memKb, state }], uptime, netTotals }`.
 * Disk is not part of the live sample (it comes from /metrics/summary).
 * Older/flat shapes with `memoryPercent` etc. still decode through [LiveHostMetrics]'s own names.
 */
internal fun parseLiveHostMetrics(json: Json, body: String): LiveHostMetrics {
    val root = json.parseToJsonElement(body)
    val obj = unwrap(root) ?: return json.decodeFromJsonElement(LiveHostMetrics.serializer(), root)

    val looksLikeSdkSample = obj.containsKey("memTotalKb") || obj.containsKey("procs") || obj.containsKey("load")
    if (!looksLikeSdkSample) {
        return json.decodeFromJsonElement(LiveHostMetrics.serializer(), obj)
    }

    val memTotalKb = obj.num("memTotalKb")
    val memUsedKb = obj.num("memUsedKb")
    val memPercent = if (memTotalKb != null && memTotalKb > 0 && memUsedKb != null) {
        (memUsedKb / memTotalKb * 100.0).coerceIn(0.0, 100.0)
    } else {
        null
    }
    val net = (obj["net"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val rx = net.mapNotNull { it.num("rxBps") }.takeIf { it.isNotEmpty() }?.sum()
    val tx = net.mapNotNull { it.num("txBps") }.takeIf { it.isNotEmpty() }?.sum()
    val totals = obj["netTotals"] as? JsonObject
    val procs = (obj["procs"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().map { p ->
        val memKb = p.num("memKb")
        ProcessSample(
            pid = p.num("pid")?.toInt(),
            name = p.str("name").orEmpty(),
            cpuPercent = p.num("cpu"),
            memoryBytes = memKb?.let { (it * 1024).toLong() },
            memoryPercent = if (memKb != null && memTotalKb != null && memTotalKb > 0) memKb / memTotalKb * 100.0 else null,
        )
    }
    return LiveHostMetrics(
        hostId = obj.num("hostId")?.toLong() ?: 0L,
        cpuPercent = obj.num("cpu"),
        cpuCores = (obj["cores"] as? JsonArray)?.size,
        loadAverage = (obj["load"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty(),
        memoryPercent = memPercent,
        memoryUsedBytes = memUsedKb?.let { (it * 1024).toLong() },
        memoryTotalBytes = memTotalKb?.let { (it * 1024).toLong() },
        networkRxBytesPerSec = rx?.toLong(),
        networkTxBytesPerSec = tx?.toLong(),
        networkRxTotalBytes = totals?.num("rx")?.toLong(),
        networkTxTotalBytes = totals?.num("tx")?.toLong(),
        uptimeSeconds = obj.num("uptime")?.toLong(),
        topProcesses = procs,
    )
}

private fun unwrap(root: JsonElement): JsonObject? {
    var cur = root as? JsonObject ?: return null
    repeat(3) {
        val inner = listOf("sample", "metrics", "data").firstNotNullOfOrNull { cur[it] as? JsonObject } ?: return cur
        cur = inner
    }
    return cur
}

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.longOrNull?.toDouble() }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
