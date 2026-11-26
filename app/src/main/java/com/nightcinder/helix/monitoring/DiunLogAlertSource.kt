package com.nightcinder.helix.monitoring

import com.nightcinder.helix.core.api.DockerApi
import com.nightcinder.helix.core.model.AlertSeverity
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.MonitoringAlert
import com.nightcinder.helix.core.model.MonitoringSourceException
import com.nightcinder.helix.core.model.SiteConfig
import com.nightcinder.helix.core.model.TermixHost
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Extracts container image update notifications from Diun logs on [diunHostName] (HELIX_DIUN_HOST) via Termix [DockerApi].
 * Caches its fallback SSH session across refreshes to avoid leaking sessions when WorkspaceTree is unopened.
 */
class DiunLogAlertSource(
    private val dockerApi: DockerApi,
    private val existingSessionIdProvider: (hostId: Long) -> String? = { null },
    /** Termix host running the Diun container; blank disables this source. */
    private val diunHostName: String = SiteConfig.diunHost,
) {

    private val fallbackSessions = ConcurrentHashMap<Long, String>()

    fun clearSessions() {
        fallbackSessions.clear()
    }

    suspend fun fetchDiunAlerts(hosts: List<TermixHost>): List<MonitoringAlert> {
        if (diunHostName.isBlank()) return emptyList()
        val diunHost = HostKeyMatcher.findMatchingHost(hosts, diunHostName) ?: return emptyList()

        val existingId = existingSessionIdProvider(diunHost.id)?.takeIf { it.isNotBlank() }
            ?: fallbackSessions[diunHost.id]?.takeIf { it.isNotBlank() }

        var sessionId = existingId ?: try {
            dockerApi.connectSsh(diunHost.id).sessionId.also { newId ->
                if (newId.isNotBlank()) fallbackSessions[diunHost.id] = newId
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MonitoringSourceException(
                source = AlertSource.DIUN,
                message = "Diun SSH connect failed on ${diunHost.displayName}: ${e.message}",
                cause = e,
            )
        }

        val containers = try {
            dockerApi.listContainers(sessionId, all = false)
        } catch (e: CancellationException) {
            throw e
        } catch (firstErr: Exception) {
            if (existingId != null) {
                fallbackSessions.remove(diunHost.id)
                sessionId = try {
                    dockerApi.connectSsh(diunHost.id).sessionId.also { newId ->
                        if (newId.isNotBlank()) fallbackSessions[diunHost.id] = newId
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    throw MonitoringSourceException(
                        source = AlertSource.DIUN,
                        message = "Diun SSH reconnect failed: ${e.message}",
                        cause = e,
                    )
                }
                try {
                    dockerApi.listContainers(sessionId, all = false)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    throw MonitoringSourceException(
                        source = AlertSource.DIUN,
                        message = "Diun container list failed: ${e.message}",
                        cause = e,
                    )
                }
            } else {
                throw MonitoringSourceException(
                    source = AlertSource.DIUN,
                    message = "Diun container list failed: ${firstErr.message}",
                    cause = firstErr,
                )
            }
        }

        val diunContainer = containers.firstOrNull { container ->
            val clean = container.displayName.trim().lowercase(Locale.US)
            clean == "diun" || clean.endsWith("/diun")
        } ?: return emptyList()

        val logsResult = try {
            dockerApi.getContainerLogs(sessionId, diunContainer.id, tail = DEFAULT_TAIL_LINES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MonitoringSourceException(
                source = AlertSource.DIUN,
                message = "Failed to read Diun logs: ${e.message}",
                cause = e,
            )
        }

        return parseDiunLogs(
            rawLogs = logsResult.combinedText,
            targetHostName = diunHost.name.ifBlank { diunHostName },
            targetHostId = diunHost.id,
        )
    }

    companion object {
        private const val DEFAULT_TAIL_LINES = 200
        private val ANSI_ESCAPE_REGEX = Regex("""\u001B\[[0-9;]*[A-Za-z]""")
        private val IMAGE_UPDATE_REGEX = Regex(
            """(?:New image found|Image update found).*?\bimage=([^\s"']+)""",
            RegexOption.IGNORE_CASE,
        )
        private val RFC1123_TIMESTAMP_REGEX = Regex(
            """([A-Z][a-z]{2},\s+\d{1,2}\s+[A-Z][a-z]{2}\s+\d{4}\s+\d{2}:\d{2}:\d{2}\s+[A-Z0-9+-]+)""",
        )
        private val DIUN_NAMED_TZ_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z", Locale.US)

        fun parseDiunLogs(
            rawLogs: String,
            targetHostName: String = SiteConfig.diunHost,
            targetHostId: Long? = null,
        ): List<MonitoringAlert> {
            if (rawLogs.isBlank()) return emptyList()

            val byImage = linkedMapOf<String, MonitoringAlert>()
            for (rawLine in rawLogs.lineSequence()) {
                val strippedMux = stripDockerMuxHeader(rawLine)
                val line = ANSI_ESCAPE_REGEX.replace(strippedMux, "")
                    .trimStart { it <= '\u001F' }
                    .trim()
                if (line.isEmpty()) continue

                val match = IMAGE_UPDATE_REGEX.find(line) ?: continue
                val rawImageTag = match.groupValues[1].trim().trim('"', '\'', ',', ';')
                if (rawImageTag.isEmpty()) continue

                val shortImage = rawImageTag.substringAfterLast('/').ifBlank { rawImageTag }
                val triggeredAtMs = parseDiunTimestamp(line)

                byImage[rawImageTag] = MonitoringAlert(
                    id = "diun:$rawImageTag",
                    source = AlertSource.DIUN,
                    severity = AlertSeverity.INFO,
                    title = "Update: $shortImage",
                    summary = rawImageTag,
                    targetHostName = targetHostName,
                    targetHostId = targetHostId,
                    instance = targetHostName,
                    triggeredAtEpochMs = triggeredAtMs,
                    state = "available",
                )
            }
            return byImage.values.toList()
        }

        private fun stripDockerMuxHeader(line: String): String {
            if (line.length >= 8 &&
                (line[0] == '\u0001' || line[0] == '\u0002') &&
                line[1] == '\u0000' &&
                line[2] == '\u0000' &&
                line[3] == '\u0000'
            ) {
                return line.substring(8)
            }
            return line
        }

        private fun parseDiunTimestamp(line: String): Long? {
            val match = RFC1123_TIMESTAMP_REGEX.find(line) ?: return null
            val rawTs = match.groupValues[1]
            return runCatching {
                ZonedDateTime.parse(rawTs, DIUN_NAMED_TZ_FORMATTER).toInstant().toEpochMilli()
            }.getOrNull() ?: runCatching {
                ZonedDateTime.parse(rawTs, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }
}
