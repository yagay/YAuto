package com.yagay.yauto.core.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class VersionedDocument(
    val typeId: String,
    val schemaVersion: Int,
    val payload: JsonObject,
)

fun interface PayloadMigration {
    fun migrate(payload: JsonObject): JsonObject
}

data class MigrationStep(
    val typeId: String,
    val fromVersion: Int,
    val toVersion: Int,
    val migration: PayloadMigration,
) {
    init {
        require(toVersion > fromVersion) { "Migration must move forward" }
    }
}

class MigrationRegistry {
    private val steps = mutableMapOf<Pair<String, Int>, MigrationStep>()

    fun register(step: MigrationStep) {
        val key = step.typeId to step.fromVersion
        require(key !in steps) { "Duplicate migration for ${step.typeId} v${step.fromVersion}" }
        steps[key] = step
    }

    fun migrate(document: VersionedDocument, targetVersion: Int): VersionedDocument {
        require(targetVersion >= document.schemaVersion)
        var current = document
        while (current.schemaVersion < targetVersion) {
            val step = steps[current.typeId to current.schemaVersion]
                ?: error("Missing migration for ${current.typeId} v${current.schemaVersion}")
            current = current.copy(
                schemaVersion = step.toVersion,
                payload = step.migration.migrate(current.payload),
            )
        }
        require(current.schemaVersion == targetVersion) {
            "Migration chain overshot target version $targetVersion"
        }
        return current
    }
}

class JsonDocumentCodec(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
) {
    fun encode(document: VersionedDocument): String = json.encodeToString(VersionedDocument.serializer(), document)
    fun decode(text: String): VersionedDocument = json.decodeFromString(VersionedDocument.serializer(), text)
}
