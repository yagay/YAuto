package com.yagay.yauto.core.storage

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationRegistryTest {
    @Test
    fun migratesFeaturePayloadSequentially() {
        val registry = MigrationRegistry().apply {
            register(
                MigrationStep("device.nfc.set", 1, 2) { payload ->
                    buildJsonObject { put("mode", JsonPrimitive(if (payload["enabled"]?.toString() == "true") "ENABLE" else "DISABLE")) }
                }
            )
        }

        val migrated = registry.migrate(
            VersionedDocument(
                typeId = "device.nfc.set",
                schemaVersion = 1,
                payload = buildJsonObject { put("enabled", JsonPrimitive(true)) },
            ),
            targetVersion = 2,
        )

        assertEquals(2, migrated.schemaVersion)
        assertEquals("\"ENABLE\"", migrated.payload["mode"].toString())
    }
}
