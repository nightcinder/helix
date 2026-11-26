package com.nightcinder.helix.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SftpSession(
    @JsonNames("sessionId", "id", "session_id")
    val sessionId: String = "",
    val hostId: Long = 0L,
    @JsonNames("homePath", "cwd", "path")
    val homePath: String = "/",
    val connected: Boolean = true,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SftpEntry(
    val name: String = "",
    val path: String = "",
    @JsonNames("isDirectory", "isDir", "directory")
    val isDirectory: Boolean = false,
    @JsonNames("sizeBytes", "size")
    val sizeBytes: Long = 0L,
    @JsonNames("modifiedEpochMs", "mtime", "modifiedAt")
    val modifiedEpochMs: Long? = null,
    @JsonNames("permissions", "mode")
    val permissions: String? = null,
    val owner: String? = null,
    val isSymlink: Boolean = false,
    /** Termix file-manager v1.x sends `type: "directory" | "link" | "file"` instead of a boolean. */
    val type: String? = null,
    val linkTarget: String? = null,
) {
    /** Folds Termix's [type] string into [isDirectory] / [isSymlink]. */
    fun normalized(): SftpEntry = when (type?.lowercase()) {
        "directory", "dir" -> copy(isDirectory = true)
        "link", "symlink" -> copy(isSymlink = true)
        else -> this
    }

    /**
     * Absolute path a symlink entry points at. Relative [linkTarget] values resolve against
     * [parentPath]; `.` and `..` segments are collapsed. Falls back to [path] when no target is known.
     */
    fun resolvedLinkPath(parentPath: String): String {
        val target = linkTarget?.trim().orEmpty()
        if (target.isEmpty()) return path
        val raw = if (target.startsWith("/")) target else "${parentPath.trimEnd('/')}/$target"
        val stack = ArrayDeque<String>()
        for (segment in raw.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return "/" + stack.joinToString("/")
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SftpFileContent(
    val path: String = "",
    @JsonNames("content", "data", "text")
    val content: String = "",
    val encoding: String = "UTF-8",
    val isTruncated: Boolean = false,
    val sizeBytes: Long? = null,
)

@Serializable
data class SftpTransferResult(
    val success: Boolean = true,
    val sourcePath: String = "",
    val targetPath: String = "",
    val bytesTransferred: Long = 0L,
    val targetHostId: Long? = null,
    val message: String? = null,
)
