package com.yagay.yauto.core.storage

import java.io.File

/** Newer workspace schemas must never be silently restored over from an older backup. */
class UnsupportedWorkspaceSchemaException(val schemaVersion: Int) :
    IllegalStateException("Unsupported workspace schema version $schemaVersion; original data preserved")


/**
 * Reads an atomic workspace pair without ever silently treating corrupt user data as an empty
 * workspace. An absent workspace is new; a present but unreadable workspace is an error.
 *
 * Decode and recovery writes are intentionally separated: failure to restore a readable backup
 * must propagate, not be mistaken for a corrupt backup.
 */
fun readWorkspaceCandidates(
    primary: File,
    backup: File,
    decode: (File) -> WorkspaceData,
    recoverBackup: (WorkspaceData) -> Unit,
    onDecodeFailure: (File, Exception) -> Unit = { _, _ -> },
): WorkspaceData {
    val candidates = listOf(primary, backup).filter(File::isFile)
    if (candidates.isEmpty()) return WorkspaceData()

    var lastError: Exception? = null
    for (candidate in candidates) {
        val data = try {
            decode(candidate)
        } catch (error: Exception) {
            if (error is UnsupportedWorkspaceSchemaException) throw error
            lastError = error
            onDecodeFailure(candidate, error)
            continue
        }
        if (candidate != primary) recoverBackup(data)
        return data
    }

    throw IllegalStateException(
        "Existing workspace files are unreadable. Original files were preserved; " +
            "YAuto will not replace them with an empty workspace.",
        lastError,
    )
}
