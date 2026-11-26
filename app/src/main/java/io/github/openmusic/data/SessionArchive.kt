package io.github.openmusic.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** History rows deliberately omit raw MIDI and detailed evaluation event arrays. */
@Serializable
data class SessionSummary(
    val id: String,
    val scoreId: String,
    val scoreTitle: String,
    val startedAt: Long,
    val mode: String,
    val bpm: Int,
    val durationSeconds: Double,
    val evaluated: Boolean,
    val outcome: String,
    val hits: Int,
    val misses: Int,
    val extras: Int,
    val early: Int,
    val late: Int,
    val meanAbsoluteTimingMs: Double?,
)

/**
 * Pure JVM archive operations. Sources are reopenable so only summaries need to remain
 * in memory while sorting. Callers own the output; each opened input is always closed.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object SessionArchive {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    /** Unknown arrays are skipped by the streaming lexer, without materializing JsonElement. */
    fun readSummary(input: InputStream): SessionSummary = json.decodeFromStream(input)

    fun loadSummaries(sources: Sequence<() -> InputStream>): List<SessionSummary> =
        index(sources).map { it.summary }

    /** The base file may be absent when an older AtomicFile write left only its backup. */
    fun recordFiles(directory: File): List<File> = directory.listFiles().orEmpty()
        .asSequence()
        .filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
        .map { if (it.name.endsWith(".bak")) File(directory, it.name.removeSuffix(".bak")) else it }
        .distinctBy { it.path }
        .toList()

    /**
     * Writes the existing SessionRecord array format, newest first. At most one complete
     * record is decoded at a time. A malformed record is discarded before its comma or
     * body is written; output failures propagate instead of leaving a reported success.
     */
    fun exportSessionsTo(sources: Sequence<() -> InputStream>, output: OutputStream): Int {
        val entries = index(sources)
        output.write('['.code)
        var written = 0
        for (entry in entries) {
            val record = try {
                entry.open().use { json.decodeFromStream<SessionRecord>(it) }
            } catch (_: Exception) {
                continue
            }
            if (written > 0) output.write(','.code)
            output.write('\n'.code)
            json.encodeToStream(record, output)
            written++
        }
        if (written > 0) output.write('\n'.code)
        output.write(']'.code)
        output.flush()
        return written
    }

    private data class IndexedSession(val summary: SessionSummary, val open: () -> InputStream)

    private fun index(sources: Sequence<() -> InputStream>): List<IndexedSession> =
        sources.mapNotNull { open ->
            try {
                IndexedSession(open().use(::readSummary), open)
            } catch (_: Exception) {
                null // A damaged or unreadable session must not prevent the other rows loading.
            }
        }.sortedWith(compareByDescending<IndexedSession> { it.summary.startedAt }.thenBy { it.summary.id }).toList()
}
