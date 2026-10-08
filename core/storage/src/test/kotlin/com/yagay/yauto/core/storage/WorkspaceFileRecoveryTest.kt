package com.yagay.yauto.core.storage

import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.AutomationId
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFileRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(file: File) =
        json.decodeFromString(WorkspaceData.serializer(), file.readText())

    private fun write(file: File, data: WorkspaceData) {
        file.writeText(json.encodeToString(WorkspaceData.serializer(), data))
    }

    @Test fun `future workspace must not be overwritten by a valid older backup`() {
        val primary = folder.newFile("workspace.json")
        val backup = folder.newFile("workspace.json.bak")
        primary.writeText("{\"schemaVersion\":2,\"futureFeature\":true}")
        val original = primary.readText()
        write(backup, WorkspaceData())
        try {
            readWorkspaceCandidates(
                primary, backup,
                decode = { file ->
                    val data = decode(file)
                    if (data.schemaVersion != 1) throw UnsupportedWorkspaceSchemaException(data.schemaVersion)
                    data
                },
                recoverBackup = { fail("Old backup must not replace newer schema") },
            )
            fail("Expected a future-schema error")
        } catch (expected: UnsupportedWorkspaceSchemaException) {
            assertEquals(2, expected.schemaVersion)
        }
        assertEquals(original, primary.readText())
    }

    @Test fun `missing workspace is a valid fresh install`() {
        val primary = File(folder.root, "workspace.json")
        val backup = File(folder.root, "workspace.json.bak")
        assertEquals(
            WorkspaceData(),
            readWorkspaceCandidates(primary, backup, ::decode, { fail("No recovery expected") }),
        )
        assertFalse(primary.exists())
    }

    @Test fun `valid backup recovers corrupt primary without losing user workspace`() {
        val primary = folder.newFile("workspace.json").apply { writeText("{bad json") }
        val backup = folder.newFile("workspace.json.bak")
        val expected = WorkspaceData(
            automations = listOf(Automation(AutomationId("persist"), "Important automation")),
        )
        write(backup, expected)
        var failures = 0
        val loaded = readWorkspaceCandidates(
            primary, backup, ::decode, { write(primary, it) },
            { _, _ -> failures++ },
        )
        assertEquals(expected, loaded)
        assertEquals(expected, decode(primary))
        assertEquals(1, failures)
    }

    @Test fun `all corrupt candidates refuse to reset workspace or modify originals`() {
        val primary = folder.newFile("workspace.json").apply { writeText("{bad json") }
        val backup = folder.newFile("workspace.json.bak").apply { writeText("not json") }
        val primaryBytes = primary.readBytes().toList()
        val backupBytes = backup.readBytes().toList()
        try {
            readWorkspaceCandidates(primary, backup, ::decode, { fail("No recovery expected") })
            fail("Unreadable workspace must not be returned as empty data")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("preserved"))
        }
        assertEquals(primaryBytes, primary.readBytes().toList())
        assertEquals(backupBytes, backup.readBytes().toList())
    }

    @Test fun `backup recovery write failure propagates without suppressing data`() {
        val primary = folder.newFile("workspace.json").apply { writeText("{bad json") }
        val backup = folder.newFile("workspace.json.bak")
        write(backup, WorkspaceData())
        try {
            readWorkspaceCandidates(primary, backup, ::decode, {
                throw IllegalStateException("disk full")
            })
            fail("Recovery must report failure to write the readable backup")
        } catch (expected: IllegalStateException) {
            assertEquals("disk full", expected.message)
        }
        assertEquals("{bad json", primary.readText())
    }
}
