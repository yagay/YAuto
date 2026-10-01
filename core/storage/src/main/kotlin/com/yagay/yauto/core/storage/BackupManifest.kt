package com.yagay.yauto.core.storage

import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    val createdAtEpochMs: Long,
    val requiredFeatureIds: Set<String> = emptySet(),
    val databaseSchemaVersion: Int = 1,
    val pluginApiVersion: Int = 1,
    val ipcProtocolVersion: Int = 1,
)

object BackupFormat { const val CURRENT_VERSION = 1 }
