package com.nightcinder.helix.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TermixSnippet(
    val id: Long = 0L,
    val name: String = "",
    val description: String? = null,
    @JsonNames("command", "content", "script")
    val command: String = "",
    val folder: String? = null,
    val tags: List<String> = emptyList(),
    val requiresSecrets: Boolean = false,
    val hostIds: List<Long> = emptyList(),
)

@Serializable
data class SnippetExecutionRequest(
    val snippetId: Long,
    val hostId: Long,
    val resolvedSecrets: Map<String, String> = emptyMap(),
    val timeoutSeconds: Int? = null,
)

@Serializable
data class SnippetSecretResolveRequest(
    val snippetId: Long,
    val hostId: Long? = null,
)

@Serializable
data class SnippetSecretResolveResult(
    val secrets: Map<String, String> = emptyMap(),
    val unresolvedKeys: List<String> = emptyList(),
)

@Serializable
data class SnippetExecutionResult(
    val snippetId: Long? = null,
    val hostId: Long? = null,
    val success: Boolean = false,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val output: String? = null,
    val durationMs: Long? = null,
    val error: String? = null,
) {
    val combinedOutput: String
        get() = buildString {
            val primary = output?.takeIf { it.isNotBlank() } ?: stdout
            if (primary.isNotBlank()) append(primary.trimEnd())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) appendLine().appendLine()
                append("[stderr] ").append(stderr.trimEnd())
            }
            if (!error.isNullOrBlank()) {
                if (isNotEmpty()) appendLine().appendLine()
                append("[error] ").append(error.trimEnd())
            }
        }
}
