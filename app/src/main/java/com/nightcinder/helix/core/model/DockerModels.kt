package com.nightcinder.helix.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Termix docker v1.3 lists containers with `docker ps --format '{..."ports":"{{.Ports}}"}'`, so ports
 * arrive as one comma-separated string; older/other shapes send an array. Accept both.
 */
object DockerPortsSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> element
        is JsonNull -> JsonArray(emptyList())
        is JsonPrimitive -> JsonArray(
            element.content.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) },
        )
        else -> JsonArray(emptyList())
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DockerSshSession(
    @JsonNames("sessionId", "id", "session_id")
    val sessionId: String = "",
    val hostId: Long = 0L,
    val connected: Boolean = true,
    val createdAtEpochMs: Long? = null,
    val message: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DockerContainer(
    @JsonNames("id", "containerId", "container_id")
    val id: String = "",
    @JsonNames("name", "names")
    val name: String = "",
    val image: String = "",
    val state: String = "",
    val status: String = "",
    @JsonNames("stack", "stackName", "project")
    val stackName: String? = null,
    @Serializable(with = DockerPortsSerializer::class)
    val ports: List<String> = emptyList(),
    val createdEpochSec: Long? = null,
    val hostId: Long? = null,
) {
    val displayName: String
        get() = name.removePrefix("/").ifBlank { id.take(12) }

    val isRunning: Boolean
        get() = state.equals("running", ignoreCase = true) ||
            status.startsWith("Up", ignoreCase = true)
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DockerStack(
    @JsonNames("name", "stack", "stackName")
    val name: String = "",
    // docker v1.3 stacks: { name, managed, state, running, total, composeFile }
    @JsonNames("status", "state")
    val status: String = "",
    val path: String? = null,
    @JsonNames("composePath", "configFiles", "composeFile")
    val composePath: String? = null,
    @JsonNames("serviceCount", "total")
    val serviceCount: Int = 0,
    @JsonNames("runningCount", "running")
    val runningCount: Int = 0,
    val containers: List<DockerContainer> = emptyList(),
    val hostId: Long? = null,
)

@Serializable
enum class DockerContainerAction(val wireName: String) {
    START("start"),
    STOP("stop"),
    RESTART("restart"),
    PAUSE("pause"),
    UNPAUSE("unpause"),
}

@Serializable
enum class DockerStackAction(val wireName: String) {
    // Termix docker v1.3 STACK_ACTIONS: deploy, redeploy, pull, start, stop, restart, pause, unpause,
    // down, destroy. There is no "up": deploy runs `compose up -d`.
    UP("deploy"),
    DOWN("down"),
    RESTART("restart"),
    PULL("pull"),
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DockerLogsResult(
    val containerId: String = "",
    @JsonNames("logs", "output", "data")
    val logs: String = "",
    val lines: List<String> = emptyList(),
    val tail: Int? = null,
    val timestampEpochMs: Long? = null,
) {
    val combinedText: String
        get() = logs.ifBlank { lines.joinToString("\n") }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DockerComposeFile(
    @JsonNames("stack", "stackName", "name")
    val stackName: String = "",
    @JsonNames("content", "compose", "yaml")
    val content: String = "",
    val path: String? = null,
    val updatedAtEpochMs: Long? = null,
)

@Serializable
data class DockerActionResult(
    val success: Boolean = true,
    val action: String? = null,
    val targetId: String? = null,
    val message: String? = null,
    val output: String? = null,
)
