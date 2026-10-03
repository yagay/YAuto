package com.yagay.yauto.core.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImporterRegistryTest {
    @Test
    fun `lazy registration exposes metadata without constructing importer`() {
        val registry = ImporterRegistry()
        var constructions = 0
        registry.registerLazy("test", "Test Importer") {
            constructions += 1
            fakeImporter("test", "Test Importer", confidence = 100)
        }

        assertEquals(listOf(ImporterDescriptor("test", "Test Importer")), registry.descriptors())
        assertEquals(listOf("Test Importer"), registry.displayNames())
        assertEquals(listOf("Test Importer"), registry.all().map { it.displayName })
        assertEquals(0, constructions)

        val selected = registry.bestFor(ImportInput(fileName = "sample.test", bytes = byteArrayOf(1)))

        assertNotNull(selected)
        assertEquals(1, constructions)
    }

    @Test
    fun `broken optional importer is isolated from other importers`() {
        val registry = ImporterRegistry()
        var brokenConstructions = 0
        registry.registerLazy("broken", "Broken") {
            brokenConstructions += 1
            error("broken importer constructor")
        }
        registry.registerLazy("working", "Working") {
            fakeImporter("working", "Working", confidence = 80)
        }

        assertEquals(listOf("Broken", "Working"), registry.displayNames())
        assertEquals(0, brokenConstructions)

        val result = registry.import(ImportInput(fileName = "sample", bytes = byteArrayOf(1)))

        assertTrue(result.success)
        assertEquals("working", result.importerId)
        assertTrue(brokenConstructions >= 1)
    }

    @Test
    fun `import exceptions become localized failed results instead of escaping`() {
        val registry = ImporterRegistry()
        registry.registerLazy("throws", "Throws") {
            object : AutomationImporter {
                override val id = "throws"
                override val displayName = "Throws"
                override fun confidence(input: ImportInput) = 100
                override fun import(input: ImportInput): ImportResult = error("bad source")
            }
        }

        val result = registry.import(ImportInput(fileName = "bad.xml", bytes = "bad".toByteArray()))

        assertFalse(result.success)
        assertEquals("throws", result.importerId)
        assertEquals("feature.operation_failed", result.issues.single().message)
    }

    private fun fakeImporter(
        importerId: String,
        importerName: String,
        confidence: Int,
    ): AutomationImporter = object : AutomationImporter {
        override val id = importerId
        override val displayName = importerName
        override fun confidence(input: ImportInput) = confidence
        override fun import(input: ImportInput) = ImportResult(importerId = importerId, success = true)
    }
}
