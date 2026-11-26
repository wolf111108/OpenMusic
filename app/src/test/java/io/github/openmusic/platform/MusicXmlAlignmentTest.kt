@file:OptIn(kotlin.contracts.ExperimentalContracts::class, kotlin.ExperimentalUnsignedTypes::class)

package io.github.openmusic.platform

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.MidiFile
import alphaTab.midi.MidiFileGenerator
import io.github.openmusic.core.Pitch
import io.github.openmusic.core.ScoreCodec
import io.github.openmusic.core.ScoreCompiler
import io.github.openmusic.core.ScoreDocument
import io.github.openmusic.core.ScoreEvent
import io.github.openmusic.core.ScoreMeasure
import io.github.openmusic.core.ScoreMetadata
import io.github.openmusic.core.ScorePart
import io.github.openmusic.core.ScoreStaff
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Exercises the real engraving importer's timing model without an Android view or audio device. */
class MusicXmlAlignmentTest {
    private val piano = ScorePart("piano", staves = listOf(ScoreStaff("RH"), ScoreStaff("LH", "bass", 2)))

    @Test fun builtInScoresKeepEveryNotatedOnsetAfterExpansion() {
        val samples = listOf(File("../samples"), File("samples")).first { it.isDirectory }
        samples.listFiles { file -> file.name.endsWith(".scoreir.json") }!!.forEach { file ->
            assertAligned(ScoreCodec.parse(file.readText()))
        }
    }

    @Test fun pickupDoesNotShiftTheFollowingMeasureCursor() {
        assertAligned(document("pickup",
            ScoreMeasure("p", 0, tempoBpm = 60.0, isPickup = true, durationQn = "1/1", events = listOf(note("n0"))),
            ScoreMeasure("m", 1, events = listOf(note("n1", 64), note("n2", 48, staff = "LH", duration = "4/1"))),
        ))
    }

    @Test fun voicesAndUncommonFractionsKeepTheirExactTimelines() {
        listOf(
            document("polyvoice", ScoreMeasure("m", 1, tempoBpm = 60.0, events = listOf(
                note("a", duration = "4/1"),
                note("b", 64, onset = "1/1", voice = 2),
                note("c", 48, staff = "LH", duration = "2/1"),
                note("d", 50, onset = "2/1", staff = "LH", duration = "2/1"),
            ))),
            document("fraction", ScoreMeasure("m", 1, tempoBpm = 60.0, events = listOf(
                note("a", duration = "1/7"),
                note("b", 62, onset = "1/7", duration = "1/7"),
                note("c", 64, onset = "2/7", duration = "1/7"),
            ))),
        ).forEach(::assertAligned)
    }

    private fun assertAligned(document: ScoreDocument) {
        val compiled = ScoreCompiler.compile(document)
        val settings = Settings()
        val loaded = ScoreLoader.loadScoreFromBytes(Uint8Array(compiled.musicXml.toByteArray(Charsets.UTF_8).asUByteArray()), settings)
        val generator = MidiFileGenerator(loaded, settings, AlphaSynthMidiFileHandler(MidiFile()))
        generator.applyTranspositionPitches = false
        generator.generate()
        val ticks = generator.tickLookup
        assertEquals("Expanded measure count", compiled.measures.size, ticks.masterBars.length.toInt())
        ticks.masterBars.forEachIndexed { index, bar ->
            val expected = compiled.measures[index]
            assertEquals("Measure $index onset", expected.startQn, bar.start / 960.0, 1e-6)
            assertEquals("Measure $index end", expected.endQn, bar.end / 960.0, 1e-6)
        }
        val actualNotes = mutableListOf<NotatedPoint>()
        for (track in loaded.tracks) for (staff in track.staves) for (bar in staff.bars) {
            for (voice in bar.voices) for (beat in voice.beats) for (note in beat.notes) {
                // MasterBar.start / Beat.absolutePlaybackStart omit a preceding anacrusis in
                // alphaTab. The public tick lookup is the actual playback/cursor coordinate.
                actualNotes += NotatedPoint(track.index.toInt(), staff.index.toInt(), voice.index.toInt(),
                    note.realValue.toInt(), ticks.getBeatStart(beat) / 960.0, beat.playbackDuration / 960.0)
            }
        }
        val expectedNotes = mutableListOf<NotatedPoint>()
        for (occurrence in compiled.measures) {
            for (event in document.measures[occurrence.sourceMeasureIndex].events.filter { it.kind == "note" }) {
                val partIndex = document.parts.indexOfFirst { it.id == event.partId }
                val staffIndex = document.parts[partIndex].staves.first { it.id == event.staffId }.index - 1
                expectedNotes += NotatedPoint(partIndex, staffIndex, staffIndex * 4 + event.voice - 1,
                    event.pitch!!.midi, occurrence.startQn + quarter(event.onsetQn), quarter(event.durationQn))
            }
        }
        val order = compareBy<NotatedPoint> { it.part }.thenBy { it.staff }.thenBy { it.voice }.thenBy { it.onset }.thenBy { it.pitch }
        assertEquals("Notated note count", expectedNotes.size, actualNotes.size)
        actualNotes.sortedWith(order).zip(expectedNotes.sortedWith(order)).forEach { (actual, expected) ->
            assertEquals("Part", expected.part, actual.part)
            assertEquals("Staff", expected.staff, actual.staff)
            assertEquals("Voice", expected.voice, actual.voice)
            assertEquals("Pitch", expected.pitch, actual.pitch)
            assertEquals("Onset", expected.onset, actual.onset, 1e-6)
            assertEquals("Duration", expected.duration, actual.duration, 1e-6)
        }
    }

    private data class NotatedPoint(val part: Int, val staff: Int, val voice: Int, val pitch: Int, val onset: Double, val duration: Double)
    private fun quarter(fraction: String) = fraction.split('/').let { it[0].toDouble() / it[1].toDouble() }
    private fun document(name: String, vararg bars: ScoreMeasure) = ScoreDocument(
        scoreId = name, metadata = ScoreMetadata(name), parts = listOf(piano), measures = bars.toList(),
    )
    private fun note(id: String, pitch: Int = 60, onset: String = "0/1", duration: String = "1/1", staff: String = "RH", voice: Int = 1): ScoreEvent {
        val steps = listOf("C", "C", "D", "E", "E", "F", "F", "G", "A", "A", "B", "B")
        val alters = listOf(0, 1, 0, -1, 0, 0, 1, 0, -1, 0, -1, 0)
        return ScoreEvent(id, partId = "piano", staffId = staff, voice = voice, onsetQn = onset, durationQn = duration,
            pitch = Pitch(steps[pitch % 12], alters[pitch % 12], pitch / 12 - 1, pitch))
    }
}
