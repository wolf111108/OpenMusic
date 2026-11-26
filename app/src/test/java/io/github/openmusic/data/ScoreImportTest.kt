package io.github.openmusic.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ScoreImportTest {
    @Test fun jsonAndBom() { assertEquals("{}", ScoreImport.read(ByteArrayInputStream("\uFEFF{}".toByteArray()))) }
    @Test fun packageUsesOnlySemanticSource() {
        val zip = pack(mapOf("score.json" to "{}", "practice.json" to "untrusted cache"))
        assertEquals("{}", ScoreImport.read(ByteArrayInputStream(zip)))
    }
    @Test fun missingSourceRejected() = rejected { ScoreImport.read(ByteArrayInputStream(pack(mapOf("practice.json" to "{}")))) }
    @Test fun unsafePathRejected() = rejected { ScoreImport.read(ByteArrayInputStream(pack(mapOf("../score.json" to "{}")))) }
    @Test fun inflatedZipRejected() = rejected {
        ScoreImport.read(ByteArrayInputStream(pack(mapOf("score.json" to "a".repeat(ScoreImport.MAX_JSON_BYTES + 1)))))
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    private fun pack(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }
}
