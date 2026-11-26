package io.github.openmusic.data

import io.github.openmusic.core.EvaluationEvent
import io.github.openmusic.core.EvaluationKind
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SessionArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }

    @Test fun summaryStreamsPastOneHundredThousandMidiEvents() {
        val file = temporary.newFile("large.json")
        val base = json.encodeToString(record("large", 100))
        val chunks = base.split("\"rawEvents\":[]", limit = 2)
        assertEquals(2, chunks.size)
        val event = json.encodeToString(RecordedInput("NOTE_ON", 60, 80, 1.0, 2_000_000_000L, 1.0))
        file.bufferedWriter().use { writer ->
            writer.write(chunks[0]); writer.write("\"rawEvents\":[")
            repeat(100000) { index ->
                if (index > 0) writer.write(",")
                writer.write(event)
            }
            writer.write("]"); writer.write(chunks[1])
        }
        assertTrue(file.length() > 8 * 1024 * 1024)
        val tracked = TrackedInput(file.inputStream())
        val summary = tracked.use(SessionArchive::readSummary)
        assertEquals("large", summary.id)
        assertEquals("含中文的练习", summary.scoreTitle)
        assertEquals(42, summary.hits)
        assertEquals(7.5, summary.meanAbsoluteTimingMs!!, 0.0)
        assertEquals(file.length(), tracked.bytesRead)
        assertTrue(tracked.largestRead <= 65536)
        assertTrue(tracked.closed)
    }

    @Test fun summariesSkipUnreadableTruncatedAndTrailingGarbageRecords() {
        val first = json.encodeToString(record("first", 100))
        val latest = json.encodeToString(record("latest", 300))
        val sources = sequenceOf<() -> InputStream>(
            { first.byteInputStream() },
            { "{\"id\":\"unfinished\",\"rawEvents\":[".byteInputStream() },
            { throw IOException("unreadable") },
            { (first + " broken suffix").byteInputStream() },
            { latest.byteInputStream() },
        )
        assertEquals(listOf("latest", "first"), SessionArchive.loadSummaries(sources).map { it.id })
    }

    @Test fun exportRetainsCompleteRecordsAndSkipsDamageWithoutBrokenCommas() {
        val older = record("older", 100).copy(
            rawEvents = listOf(RecordedInput("PEDAL", 0, 0, 1.5, 3_500_000_000L, 1.5, 2, 127)),
            evaluationEvents = listOf(EvaluationEvent(EvaluationKind.CORRECT, "n1@1", 60, 1.5, 1.48, 20.0, listOf("n1"))),
        )
        val newer = record("newer", 300)
        // Summary metadata is readable, but the full record is invalid. Export must reject
        // it before emitting either its separating comma or any of its JSON body.
        val bad = json.encodeToString(record("invalid-events", 200)).replace("\"rawEvents\":[]", "\"rawEvents\":[null]")
        val opened = mutableListOf<TrackedInput>()
        val sources = listOf(json.encodeToString(older), "{broken", bad, json.encodeToString(newer)).asSequence().map { text ->
            val open: () -> InputStream = { TrackedInput(text.byteInputStream()).also { opened += it } }
            open
        }
        val output = OpenOutput()
        assertEquals(2, SessionArchive.exportSessionsTo(sources, output))
        assertEquals(listOf(newer, older), json.decodeFromString<List<SessionRecord>>(output.toString("UTF-8")))
        assertTrue(opened.all { it.closed })
        assertFalse(output.closed)
    }

    @Test fun outputFailurePropagatesAndEmptyArchiveIsAnArray() {
        val empty = OpenOutput()
        assertEquals(0, SessionArchive.exportSessionsTo(emptySequence(), empty))
        assertEquals("[]", empty.toString("UTF-8"))
        val brokenOutput = object : OutputStream() {
            override fun write(value: Int) { throw IOException("destination full") }
        }
        val error = assertThrows(IOException::class.java) {
            SessionArchive.exportSessionsTo(sequenceOf({ json.encodeToString(record("one", 1)).byteInputStream() }), brokenOutput)
        }
        assertEquals("destination full", error.message)
    }

    @Test fun fileDiscoveryIncludesBackupOnlySessionsAndIgnoresUncommittedFiles() {
        val directory = temporary.newFolder("sessions")
        listOf("first.json", "first.json.bak", "recover.json.bak", "pending.json.new", "readme.txt").forEach {
            directory.resolve(it).writeText("{}")
        }
        directory.resolve("folder.json").mkdir()
        assertEquals(setOf("first.json", "recover.json"), SessionArchive.recordFiles(directory).map { it.name }.toSet())
    }

    private fun record(id: String, startedAt: Long) = SessionRecord(
        id = id, scoreId = "score", scoreTitle = "含中文的练习", scoreSha256 = "abc", startedAt = startedAt,
        mode = "FOLLOW", hands = "BOTH", bpm = 80, startMeasure = 1, endMeasure = 8,
        inputSource = "USB MIDI", inputOffsetMs = 35, durationSeconds = 12.5, evaluated = true,
        outcome = "完成", hits = 42, misses = 1, extras = 2, early = 3, late = 4,
        meanAbsoluteTimingMs = 7.5, difficultMeasures = listOf(2, 4), rawEvents = emptyList(),
    )

    private class TrackedInput(input: InputStream) : FilterInputStream(input) {
        var bytesRead = 0L
        var largestRead = 0
        var closed = false
        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            largestRead = maxOf(largestRead, length)
            return super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
        }
        override fun close() { closed = true; super.close() }
    }

    private class OpenOutput : ByteArrayOutputStream() {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}
