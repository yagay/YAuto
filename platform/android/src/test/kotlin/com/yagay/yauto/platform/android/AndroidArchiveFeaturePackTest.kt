package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidArchiveFeaturePackTest {
    @Test fun `zip target cannot escape destination`() {
        val root = createTempDir(prefix = "yauto-zip-root-")
        try {
            assertEquals(File(root, "folder/file.txt").canonicalFile, safeZipTarget(root, "folder/file.txt"))
            assertTrue(runCatching { safeZipTarget(root, "../outside.txt") }.isFailure)
            assertTrue(runCatching { safeZipTarget(root, "/absolute.txt") }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `sha256 hash matches known value`() {
        val file = File.createTempFile("yauto-hash-", ".txt")
        try {
            file.writeText("hello")
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                hashFile(file, "SHA-256"),
            )
        } finally {
            file.delete()
        }
    }

    @Test fun `zip round trip preserves files`() {
        val base = createTempDir(prefix = "yauto-archive-")
        val source = File(base, "source").apply { mkdirs() }
        File(source, "a.txt").writeText("alpha")
        File(source, "nested").mkdirs()
        File(source, "nested/b.txt").writeText("beta")
        val zip = File(base, "backup.zip")
        val output = File(base, "out")
        try {
            createZipArchive(source, zip, includeRoot = false)
            val result = extractZipArchive(zip, output, overwrite = false, maxExtractedBytes = 1024 * 1024)
            assertTrue(result.entries >= 2)
            assertEquals("alpha", File(output, "a.txt").readText())
            assertEquals("beta", File(output, "nested/b.txt").readText())
        } finally {
            base.deleteRecursively()
        }
    }
}
