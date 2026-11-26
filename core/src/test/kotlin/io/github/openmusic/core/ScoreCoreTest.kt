package io.github.openmusic.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ScoreCoreTest {
    @Test fun originalDesignExampleRemainsImportable() {
        val text = checkNotNull(javaClass.getResource("/scoreir_original_example.json")).readText()
        val compiled = ScoreCompiler.compile(ScoreCodec.parse(text))
        assertEquals("demo_piano_001", compiled.document.scoreId)
        assertEquals(4, compiled.notes.size)
        assertEquals(listOf(55, 60, 62, 64), compiled.notes.map { it.midiPitch })
        assertEquals(3.0, compiled.durationSeconds, 1e-9)
        assertEquals(1, compiled.measures.size)
    }

    private val piano = ScorePart("piano", staves = listOf(ScoreStaff("RH"), ScoreStaff("LH", "bass", 2)))
    private fun note(id: String, pitch: Int = 60, onset: String = "0/1", duration: String = "1/1", staff: String = "RH", tie: String = "none"): ScoreEvent {
        val steps = listOf("C", "C", "D", "E", "E", "F", "F", "G", "A", "A", "B", "B")
        val alters = listOf(0, 1, 0, -1, 0, 0, 1, 0, -1, 0, -1, 0)
        return ScoreEvent(id, partId = "piano", staffId = staff, onsetQn = onset, durationQn = duration, pitch = Pitch(steps[pitch % 12], alters[pitch % 12], pitch / 12 - 1, pitch), tie = tie)
    }
    private fun measure(id: String, events: List<ScoreEvent>, bpm: Double? = 60.0) = ScoreMeasure(id, 1, tempoBpm = bpm, events = events)
    private fun document(vararg measures: ScoreMeasure, playback: PlaybackStructure = PlaybackStructure()) = ScoreDocument(scoreId = "test", metadata = ScoreMetadata("Test <score>"), parts = listOf(piano), measures = measures.toList(), playbackStructure = playback)
    private fun compile(vararg measures: ScoreMeasure, playback: PlaybackStructure = PlaybackStructure()) = ScoreCompiler.compile(document(*measures, playback = playback))

    @Test fun tripletsStayExactAndRenderAsTuplets() {
        val score = compile(measure("m1", listOf(note("n1", onset = "0/1", duration = "1/3"), note("n2", 62, "1/3", "1/3"), note("n3", 64, "2/3", "1/3"))))
        assertEquals(1.0 / 3, score.notes[1].onsetSeconds, 1e-12)
        assertEquals(1.0, score.notes[2].endSeconds, 1e-12)
        assertTrue(score.musicXml.contains("<actual-notes>3</actual-notes>"))
        assertTrue(score.musicXml.contains("Test &lt;score&gt;"))
    }

    @Test fun tempoMapIsInvertibleAcrossChangesAndSpeed() {
        val score = compile(measure("m1", listOf(note("n1", duration = "4/1")), 60.0), measure("m2", listOf(note("n2", duration = "4/1")), 120.0))
        assertEquals(6.0, score.durationSeconds, 1e-9)
        assertEquals(3.0, score.tempoMap.secondsAt(8.0, 2.0), 1e-9)
        for (quarter in listOf(0.0, 1.0, 3.99, 4.0, 5.0, 8.0)) {
            assertEquals(quarter, score.tempoMap.quarterAt(score.tempoMap.secondsAt(quarter, 1.5), 1.5), 1e-9)
        }
    }

    @Test fun inMeasureTempoChangesAreApplied() {
        val score = compile(measure("m1", listOf(note("n1", duration = "4/1"))), playback = PlaybackStructure(tempoChanges = listOf(TempoChange("m1", "2/1", 120.0))))
        assertEquals(3.0, score.durationSeconds, 1e-9)
        assertEquals(3.0, score.notes.single().durationSeconds, 1e-9)
    }

    @Test fun musicXmlTempoMarksAppearOnlyWhenEffectiveTempoChanges() {
        fun tempoMarks(score: CompiledScore): List<Pair<Double, Double>> {
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(score.musicXml.byteInputStream())
            val bars = (xml.getElementsByTagName("part").item(0) as org.w3c.dom.Element).getElementsByTagName("measure")
            return (0 until bars.length).flatMap { index ->
                val bar = bars.item(index) as org.w3c.dom.Element
                val divisions = bar.getElementsByTagName("divisions").item(0).textContent.toDouble()
                val directions = bar.getElementsByTagName("direction")
                (0 until directions.length).map { directionIndex ->
                    val direction = directions.item(directionIndex) as org.w3c.dom.Element
                    val offset = direction.getElementsByTagName("offset").item(0)?.textContent?.toDouble() ?: 0.0
                    val bpm = direction.getElementsByTagName("per-minute").item(0).textContent.toDouble()
                    score.measures[index].startQn + offset / divisions to bpm
                }
            }
        }
        val steady = compile(
            measure("a", listOf(note("a1", duration = "4/1")), 72.0),
            measure("b", listOf(note("b1", duration = "4/1")), 72.0),
            measure("c", listOf(note("c1", duration = "4/1")), 72.0),
            playback = PlaybackStructure(tempoChanges = listOf(TempoChange("b", "2/1", 72.0))),
        )
        assertEquals(listOf(0.0 to 72.0), tempoMarks(steady))
        assertEquals(10.0, steady.durationSeconds, 1e-9)

        val repeated = compile(
            measure("a", listOf(note("a1", duration = "4/1")), 72.0),
            measure("b", listOf(note("b1", duration = "4/1")), null),
            playback = PlaybackStructure(
                repeats = listOf(RepeatRegion("a", "b", 2)),
                tempoChanges = listOf(TempoChange("b", "2/1", 96.0)),
            ),
        )
        val changes = listOf(0.0 to 72.0, 6.0 to 96.0, 8.0 to 72.0, 14.0 to 96.0)
        assertEquals(changes, tempoMarks(repeated))
        assertEquals(changes, repeated.tempoMap.points.map { it.quarter to it.bpm })
        assertEquals(listOf(0.0, 4.0, 8.0, 12.0), repeated.notes.map { it.onsetQn })
        assertEquals(12.5, repeated.durationSeconds, 1e-9)
    }

    @Test fun repeatExpandsOccurrencesAndResetsSourceTempo() {
        val score = compile(measure("a", listOf(note("n1")), 60.0), measure("b", listOf(note("n2", 64)), 120.0), playback = PlaybackStructure(repeats = listOf(RepeatRegion("a", "b", 2))))
        assertEquals(listOf("a", "b", "a", "b"), score.measures.map { it.sourceMeasureId })
        assertEquals(listOf(1, 1, 2, 2), score.measures.map { it.occurrence })
        assertEquals(12.0, score.durationSeconds, 1e-9)
        assertEquals(listOf("n1@1", "n2@1", "n1@2", "n2@2"), score.notes.map { it.id })
    }

    @Test fun tiesMergeWhileRepeatedKeysRemainSeparate() {
        val score = compile(measure("a", listOf(note("start", duration = "4/1", tie = "start"))), measure("b", listOf(note("stop", duration = "1/1", tie = "stop"), note("replay", onset = "1/1"))))
        assertEquals(2, score.notes.size)
        assertEquals(5.0, score.notes.first().durationQn, 1e-9)
        assertEquals(listOf("start", "stop"), score.notes.first().sourceIds)
        assertEquals(5.0, score.notes.last().onsetQn, 1e-9)
    }

    @Test fun sharedPhysicalKeyCountsOnlyOnceAcrossStaves() {
        val score = compile(measure("a", listOf(note("rh"), note("lh", staff = "LH"))))
        assertEquals(1, score.notes.size)
        assertEquals(setOf("RH", "LH"), score.notes.single().staffIds)
        assertEquals(1, PracticeEvaluator(score, selectedStaffIds = setOf("LH")).targets.size)
    }

    @Test fun handSelectionPrecedesSharedKeyDurationMerge() {
        val score = compile(
            measure("a", listOf(note("right"), note("leftStart", staff = "LH", duration = "4/1", tie = "start"))),
            measure("b", listOf(note("leftEnd", staff = "LH", duration = "4/1", tie = "stop"))),
        )
        assertEquals(1, score.notes.size)
        assertEquals(8.0, score.notes.single().durationQn, 1e-9)
        val right = targetsInRange(score, selectedStaffIds = setOf("RH"))
        assertEquals(1.0, right.single().durationQn, 1e-9)
        assertEquals(listOf("right"), right.single().sourceIds)
        assertTrue(targetsInRange(score, selectedStaffIds = setOf("RH"), startQn = 4.0, endQn = 8.0).isEmpty())
        assertEquals(1, targetsInRange(score, selectedStaffIds = setOf("LH"), startQn = 4.0, endQn = 8.0).size)
    }

    @Test fun unisonPartsShareOnePhysicalKeyButRemainSelectable() {
        val secondPart = ScorePart("flute", "flute", staves = listOf(ScoreStaff("fluteStaff")))
        val other = note("fluteNote").copy(partId = "flute", staffId = "fluteStaff")
        val doc = document(measure("a", listOf(note("pianoNote"), other))).copy(parts = listOf(piano, secondPart))
        val score = ScoreCompiler.compile(doc)
        assertEquals(1, score.notes.size)
        assertEquals(setOf("pianoNote", "fluteNote"), score.notes.single().sourceIds.toSet())
        assertEquals(2, score.sourceNotes.size)
        val evaluator = PracticeEvaluator(score)
        assertEquals(EvaluationKind.CORRECT, evaluator.onNoteOn(60, 0.0).kind)
        assertEquals(0, evaluator.finish().missed)
        assertEquals(listOf("fluteNote"), targetsInRange(score, setOf("fluteStaff")).single().sourceIds)
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(score.musicXml.byteInputStream())
        val measureIds = xml.getElementsByTagName("measure").let { measures ->
            (0 until measures.length).map { measures.item(it).attributes.getNamedItem("id").nodeValue }
        }
        assertEquals(measureIds.size, measureIds.toSet().size)
    }

    @Test fun phraseBoundaryRearticulatesAndClipsTie() {
        val score = compile(measure("a", listOf(note("start", duration = "4/1", tie = "start"))), measure("b", listOf(note("stop", duration = "4/1", tie = "stop"))))
        val targets = targetsInRange(score, startQn = 4.0, endQn = 6.0)
        assertEquals(1, targets.size)
        assertEquals(4.0, targets.single().onsetQn, 1e-9)
        assertEquals(2.0, targets.single().durationQn, 1e-9)
        assertEquals(1, targets.single().measureOccurrenceIndex)
        val evaluator = PracticeEvaluator(score, startQn = 4.0, endQn = 6.0)
        assertEquals(EvaluationKind.CORRECT, evaluator.onNoteOn(60, 4.0).kind)
    }

    @Test fun matchingCountsMissesExtrasAndTimingSeparately() {
        val score = compile(measure("a", listOf(note("n1"), note("n2", 62, "1/1"), note("n3", 64, "2/1"))))
        val evaluator = PracticeEvaluator(score)
        assertEquals(EvaluationKind.CORRECT, evaluator.onNoteOn(60, 0.04).kind)
        assertEquals(EvaluationKind.EARLY, evaluator.onNoteOn(62, 0.8).kind)
        assertEquals(EvaluationKind.EXTRA, evaluator.onNoteOn(66, 2.0).kind)
        assertEquals(1, evaluator.advance(2.36).size)
        val summary = evaluator.finish()
        assertEquals(2, summary.matched)
        assertEquals(1, summary.missed)
        assertEquals(1, summary.extra)
        assertEquals(0.5, summary.noteAccuracy, 1e-9)
        assertEquals(120.0, summary.meanAbsoluteErrorMs, 1e-9)
    }

    @Test fun timingToleranceUsesWallTimeAtChangedPlaybackSpeed() {
        val score = compile(measure("a", listOf(note("n1"))))
        val evaluator = PracticeEvaluator(score, playbackSpeed = 2.0)
        val event = evaluator.onNoteOn(60, 0.15)
        assertEquals(EvaluationKind.CORRECT, event.kind)
        assertEquals(75.0, event.deltaMs!!, 1e-9)
    }

    @Test fun waitingRequiresEveryChordNoteHeldAndNewOnsetForRepeatedGroup() {
        val score = compile(measure("a", listOf(note("n1"), note("n2", 64), note("n3", 60, "1/1"))))
        val waiting = WaitingMatcher(score.groups)
        assertFalse(waiting.noteOn(60, 0.0).groupCompleted)
        waiting.noteOff(60)
        assertFalse(waiting.noteOn(64, 0.05).groupCompleted)
        assertTrue(waiting.noteOn(60, 0.1).groupCompleted)
        assertFalse(waiting.complete)
        waiting.noteOff(60)
        assertTrue(waiting.noteOn(60, 0.2).complete)
    }

    @Test fun codecRejectsVersionPitchAndOverlappingVoices() {
        val valid = document(measure("a", listOf(note("n1"))))
        assertThrows(ScoreValidationException::class.java) { ScoreCodec.validate(valid.copy(schemaVersion = "9")) }
        assertThrows(ScoreValidationException::class.java) { compile(measure("a", listOf(note("n1").copy(pitch = Pitch("C", 0, 4, 61))))) }
        assertThrows(ScoreValidationException::class.java) { compile(measure("a", listOf(note("n1", duration = "2/1"), note("n2", 62, "1/1")))) }
    }

    @Test fun independentChordDurationsRequireSeparateVoicesForFaithfulNotation() {
        val longNote = note("long", duration = "4/1")
        val shortNote = note("short", 64)
        val failure = assertThrows(ScoreValidationException::class.java) { compile(measure("a", listOf(longNote, shortNote))) }
        assertTrue(failure.message!!.contains("voice"))
        val supported = compile(measure("a", listOf(longNote, shortNote.copy(voice = 2))))
        assertEquals(2, supported.notes.size)
        assertEquals(listOf(4.0, 1.0), supported.notes.map { it.durationQn })
    }

    @Test fun codecRejectsUnknownFieldsAndBadFractions() {
        val valid = ScoreCodec.encode(document(measure("a", listOf(note("n1")))))
        assertThrows(ScoreValidationException::class.java) { ScoreCodec.parse(valid.replace("\"schema_version\"", "\"unknown_field\":true,\"schema_version\"")) }
        assertThrows(ScoreValidationException::class.java) { compile(measure("a", listOf(note("n1", duration = "1/0")))) }
        assertThrows(ScoreValidationException::class.java) { compile(measure("a", listOf(note("n1", duration = "0/1")))) }
    }

    @Test fun incompatibleFractionDenominatorsFailBeforeExpansion() {
        val doc = document(measure("a", listOf(note("n1", duration = "1/9973"), note("n2", 62, "1/9973", "1/9967"))))
        val failure = assertThrows(ScoreValidationException::class.java) { ScoreCodec.validate(doc) }
        assertTrue(failure.message!!.contains("分母"))
    }

    @Test fun rejectsNestedRepeatsAndUnclosedTies() {
        val a = measure("a", listOf(note("n1")))
        val b = measure("b", listOf(note("n2")))
        assertThrows(ScoreValidationException::class.java) { compile(a, b, playback = PlaybackStructure(repeats = listOf(RepeatRegion("a", "b"), RepeatRegion("b", "b")))) }
        assertThrows(ScoreValidationException::class.java) { compile(measure("a", listOf(note("n1", tie = "start")))) }
    }

    @Test fun pickupDurationAndImplicitRestAreValidMusicXml() {
        val m = measure("pickup", listOf(note("n1"))).copy(isPickup = true, durationQn = "1/1")
        val score = compile(m)
        assertEquals(1.0, score.totalQuarterNotes, 1e-9)
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(score.musicXml.byteInputStream())
        assertEquals("score-partwise", xml.documentElement.tagName)
        assertTrue(score.musicXml.contains("implicit=\"yes\""))
        assertTrue(score.musicXml.contains("<backup><duration>1</duration></backup>"))
    }

    @Test fun suppliedSamplesParseCompileAndReencode() {
        val sampleDir = listOf(File("../samples"), File("samples")).first { it.exists() }
        sampleDir.listFiles { file -> file.name.endsWith(".scoreir.json") }!!.forEach { file ->
            val document = ScoreCodec.parse(file.readText())
            val compiled = ScoreCompiler.compile(document)
            assertTrue(compiled.notes.isNotEmpty())
            assertEquals(document, ScoreCodec.parse(ScoreCodec.encode(document)))
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(compiled.musicXml.byteInputStream())
        }
    }
}
