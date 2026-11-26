@file:OptIn(kotlin.contracts.ExperimentalContracts::class, kotlin.ExperimentalUnsignedTypes::class)

package io.github.openmusic.platform

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.MidiFile
import alphaTab.midi.MidiFileGenerator
import alphaTab.model.Duration
import alphaTab.model.Score
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real alphaTab models and MIDI lookup, including the voices/chords its Jianpu renderer omits. */
class NumberedScoreAdapterTest {
    @Test fun builtInScoresKeepEveryNoteAndLeaveTheStandardScoreUnchanged() {
        val samples = listOf(File("../samples"), File("samples")).first { it.isDirectory }
        samples.listFiles { file -> file.name.endsWith(".scoreir.json") }!!.forEach { file ->
            val (source, numbered) = assertPreserved(ScoreCodec.parse(file.readText()))
            assertEquals(2, source.tracks[0].staves.length.toInt())
            if (file.name == "first_steps.scoreir.json") {
                assertEquals("One right-hand melody and three left-hand chord lines", 4, numbered.tracks[0].staves.length.toInt())
                assertEquals(3, source.tracks[0].staves[1].bars[0].voices[4].beats[0].notes.length.toInt())
            }
        }
    }

    @Test fun secondaryVoicesMissingMeasuresAndFractionalBeatsRetainTheirTimelines() {
        assertPreserved(document("voices-and-pickup",
            ScoreMeasure("pickup", 0, tempoBpm = 60.0, isPickup = true, durationQn = "1/1", events = listOf(
                note("pickup-r", 60, duration = "1/7"),
                note("pickup-l", 48, staff = "LH"),
            )),
            ScoreMeasure("bar", 1, events = listOf(
                note("r1", 60, duration = "4/1"),
                note("r2", 64, onset = "1/7", duration = "1/7", voice = 2),
                note("l1", 48, staff = "LH", duration = "4/1"),
                note("l2", 52, staff = "LH", duration = "4/1"),
                note("l3", 55, staff = "LH", onset = "1/1", duration = "1/1", voice = 3),
            )),
        ))
    }

    @Test fun missingVoiceInAOneQuarterPickupUsesAQuarterRestForBothDrawingAndTiming() {
        val (_, numbered) = assertPreserved(document("one-quarter-rest",
            ScoreMeasure("pickup", 0, tempoBpm = 60.0, isPickup = true, durationQn = "1/1", events = listOf(
                note("pickup-r", 60),
            )),
            ScoreMeasure("bar", 1, events = listOf(
                note("r1", 60, duration = "4/1"),
                note("r2", 64, duration = "4/1", voice = 2),
                note("l1", 48, duration = "4/1", staff = "LH"),
            )),
        ))
        // The second right-hand voice is absent in the pickup but present in the next measure.
        val missingVoice = numbered.tracks[0].staves[1].bars[0].voices[0]
        assertEquals(1, missingVoice.beats.length.toInt())
        val rest = missingVoice.beats[0]
        assertTrue(rest.isRest)
        assertFalse(rest.isEmpty)
        assertEquals("NumberedBeatGlyph reads the enum for its visible duration", Duration.Quarter, rest.duration)
        assertEquals(0.0, rest.dots, 0.0)
        assertEquals(960.0, rest.displayDuration, 1e-6)
        assertEquals(960.0, rest.playbackDuration, 1e-6)
    }

    @Test fun tiedPitchStaysOnItsLaneWhenItsPositionWithinAChordChanges() {
        val (_, numbered) = assertPreserved(document("changing-chord-tie",
            ScoreMeasure("a", 1, tempoBpm = 60.0, events = listOf(
                note("r-a", 60, duration = "4/1"),
                note("l-a-c", 48, staff = "LH", duration = "4/1"),
                note("l-a-e", 52, staff = "LH", duration = "4/1", tie = "start"),
                note("l-a-g", 55, staff = "LH", duration = "4/1"),
            )),
            ScoreMeasure("b", 2, events = listOf(
                note("r-b", 62, duration = "4/1"),
                note("l-b-e", 52, staff = "LH", duration = "4/1", tie = "stop"),
                note("l-b-g", 55, staff = "LH", duration = "4/1"),
            )),
        ))
        val destinations = numbered.tracks.flatMap { it.staves.toList() }.flatMap { it.bars.toList() }
            .flatMap { it.voices.toList() }.flatMap { it.beats.toList() }.flatMap { it.notes.toList() }
            .filter { it.isTieDestination }
        assertEquals(1, destinations.size)
        val destination = destinations.single()
        assertEquals(52, destination.realValue.toInt())
        assertEquals(destination.beat.voice.bar.staff, destination.tieOrigin!!.beat.voice.bar.staff)
        assertEquals(destination, destination.tieOrigin!!.tieDestination)
    }

    private fun assertPreserved(document: ScoreDocument): Pair<Score, Score> {
        val settings = Settings()
        val compiled = ScoreCompiler.compile(document)
        val source = ScoreLoader.loadScoreFromBytes(Uint8Array(compiled.musicXml.toByteArray().asUByteArray()), settings)
        val before = snapshot(source)
        val numbered = NumberedScoreAdapter.adapt(source, settings)
        assertEquals("Original standard score must remain unchanged", before, snapshot(source))
        val after = snapshot(numbered)
        assertEquals("Every source note is visible exactly once", before.notes.keys, after.notes.keys)
        before.notes.forEach { (id, expected) ->
            val actual = after.notes.getValue(id)
            assertEquals("Pitch of note $id", expected.pitch, actual.pitch)
            assertEquals("Onset of note $id", expected.onset, actual.onset, 1e-6)
            assertEquals("Duration of note $id", expected.duration, actual.duration, 1e-6)
            assertEquals("Tie origin of note $id", expected.tieOrigin, actual.tieOrigin)
            assertEquals("Tie destination of note $id", expected.tieDestination, actual.tieDestination)
        }
        assertEquals(before.bars.size, after.bars.size)
        before.bars.zip(after.bars).forEach { (expected, actual) ->
            assertEquals(expected.first, actual.first, 1e-6)
            assertEquals(expected.second, actual.second, 1e-6)
        }
        val beatIds = mutableSetOf<Double>()
        for (track in numbered.tracks) for (staff in track.staves) {
            assertTrue(staff.showNumbered)
            assertFalse(staff.showStandardNotation)
            assertEquals(compiled.measures.size, staff.bars.length.toInt())
            staff.bars.forEachIndexed { index, bar ->
                assertEquals(1, bar.voices.length.toInt())
                assertEquals(0.0, bar.voices[0].index, 0.0)
                val beats = bar.voices[0].beats
                assertEquals("Rest padding keeps lane measure duration", compiled.measures[index].durationQn,
                    beats.sumOf { it.playbackDuration } / 960.0, 1e-6)
                for (beat in beats) {
                    assertTrue("At most one note per visible numbered beat", beat.notes.length <= 1.0)
                    assertTrue("Duplicated beats need unique lookup IDs", beatIds.add(beat.id))
                }
            }
        }
        return source to numbered
    }

    private data class Point(val pitch: Int, val onset: Double, val duration: Double, val tieOrigin: Double?, val tieDestination: Double?)
    private data class Snapshot(val notes: Map<Double, Point>, val bars: List<Pair<Double, Double>>)

    private fun snapshot(score: Score): Snapshot {
        val generator = MidiFileGenerator(score, Settings(), AlphaSynthMidiFileHandler(MidiFile()))
        generator.applyTranspositionPitches = false
        generator.generate()
        val lookup = generator.tickLookup
        val notes = mutableMapOf<Double, Point>()
        for (track in score.tracks) for (staff in track.staves) for (bar in staff.bars) {
            for (voice in bar.voices) for (beat in voice.beats) for (note in beat.notes) {
                check(notes.put(note.id, Point(note.realValue.toInt(), lookup.getBeatStart(beat), beat.playbackDuration,
                    note.tieOrigin?.id, note.tieDestination?.id)) == null) { "Duplicate note ID" }
            }
        }
        return Snapshot(notes, lookup.masterBars.toList().map { it.start to it.end })
    }

    private fun document(name: String, vararg bars: ScoreMeasure) = ScoreDocument(
        scoreId = name, metadata = ScoreMetadata(name),
        parts = listOf(ScorePart("piano", staves = listOf(ScoreStaff("RH"), ScoreStaff("LH", "bass", 2)))),
        measures = bars.toList(),
    )

    private fun note(id: String, midi: Int, onset: String = "0/1", duration: String = "1/1", staff: String = "RH", voice: Int = 1, tie: String = "none"): ScoreEvent {
        val steps = listOf("C", "C", "D", "E", "E", "F", "F", "G", "A", "A", "B", "B")
        val alters = listOf(0, 1, 0, -1, 0, 0, 1, 0, -1, 0, -1, 0)
        return ScoreEvent(id, partId = "piano", staffId = staff, voice = voice, onsetQn = onset, durationQn = duration,
            pitch = Pitch(steps[midi % 12], alters[midi % 12], midi / 12 - 1, midi), tie = tie)
    }
}
