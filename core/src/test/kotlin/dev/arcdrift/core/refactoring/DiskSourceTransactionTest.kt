package dev.arcdrift.core.refactoring

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiskSourceTransactionTest {

    @Test
    fun `execute commits all file writes on success`() {
        val tempDir = Files.createTempDirectory("arcdrift-tx-commit").toFile()
        try {
            val existing = tempDir.resolve("Existing.kt").apply { writeText("old") }
            val created = tempDir.resolve("NewPort.kt")

            DiskSourceTransaction.execute { tx ->
                tx.writeText(existing, "new")
                tx.writeText(created, "interface NewPort")
            }

            assertEquals("new", existing.readText())
            assertTrue(created.exists())
            assertEquals("interface NewPort", created.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `execute rolls back modified and newly created files when an error occurs mid-refactoring`() {
        val tempDir = Files.createTempDirectory("arcdrift-tx-rollback").toFile()
        try {
            val existing = tempDir.resolve("Service.kt").apply { writeText("class Service") }
            val created = tempDir.resolve("Port.kt")
            val deleted = tempDir.resolve("Legacy.kt").apply { writeText("class Legacy") }

            assertFailsWith<IllegalStateException> {
                DiskSourceTransaction.execute { tx ->
                    tx.writeText(created, "interface Port")
                    tx.writeText(existing, "class Service : Port")
                    tx.deleteFile(deleted)
                    error("Simulated mid-refactoring failure")
                }
            }

            assertEquals("class Service", existing.readText())
            assertFalse(created.exists(), "Newly created file should be removed on rollback")
            assertTrue(deleted.exists(), "Deleted file should be restored on rollback")
            assertEquals("class Legacy", deleted.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
