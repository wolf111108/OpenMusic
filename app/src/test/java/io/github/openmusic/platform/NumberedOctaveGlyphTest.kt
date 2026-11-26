@file:OptIn(kotlin.contracts.ExperimentalContracts::class, kotlin.ExperimentalUnsignedTypes::class)

package io.github.openmusic.platform

import alphaTab.Environment
import alphaTab.NotationElement
import alphaTab.RenderEngineFactory
import alphaTab.Settings
import alphaTab.collections.DoubleList
import alphaTab.model.Bar
import alphaTab.model.Beat
import alphaTab.model.Duration
import alphaTab.model.MasterBar
import alphaTab.model.MusicFontSymbol
import alphaTab.model.Note
import alphaTab.model.Score
import alphaTab.model.Staff
import alphaTab.model.Track
import alphaTab.model.Voice
import alphaTab.platform.ICanvas
import alphaTab.platform.MeasuredText
import alphaTab.platform.TextAlign
import alphaTab.platform.TextBaseline
import alphaTab.rendering.ScoreRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.math.abs

/** Exercises the published engraving implementation; no duplicated octave formula or pitch shift. */
class NumberedOctaveGlyphTest {
    @Test fun actualNumberedGlyphPaintsTheCorrectOctaveDotsWithoutChangingPitch() {
        val cases = listOf(
            Case(36, "1", -2), Case(48, "1", -1), Case(52, "3", -1),
            Case(55, "5", -1), Case(60, "1", 0), Case(62, "2", 0), Case(72, "1", 1),
        )
        val engine = "openmusic-octave-glyph-test"
        val previous = Environment.renderEngines.get(engine)
        try {
            for (case in cases) {
                val settings = Settings().apply {
                    core.engine = engine
                    core.enableLazyLoading = false
                    NotationElement.entries.filter { it.name.startsWith("Score") }
                        .forEach { notation.elements.set(it, false) }
                }
                val recording = RecordingCanvas(settings)
                Environment.renderEngines.set(engine, RenderEngineFactory(false) { recording.canvas })
                val (score, note) = singleNoteScore(case.midi, settings)
                val renderer = ScoreRenderer(settings)
                try {
                    renderer.error.on { throw AssertionError("Real glyph rendering failed for MIDI ${case.midi}", it) }
                    renderer.width = 800.0
                    renderer.renderScore(score, DoubleList(0.0), null)
                    val head = recording.texts.single { it.numberedFont && it.text == case.digit }
                    assertEquals("Actual rendered octave-dot count for MIDI ${case.midi}", abs(case.dots), recording.dots.size)
                    for (dot in recording.dots) {
                        assertTrue("Octave dots must be ${if (case.dots < 0) "below" else "above"} MIDI ${case.midi}",
                            if (case.dots < 0) dot.y > head.y else dot.y < head.y)
                    }
                    assertEquals("Rendering must retain the exact integer MIDI pitch", case.midi.toDouble(), note.realValue, 0.0)
                    assertEquals("Display pitch must retain the exact integer MIDI pitch", case.midi.toDouble(), note.displayValue, 0.0)
                } finally {
                    renderer.destroy()
                }
            }
        } finally {
            if (previous == null) Environment.renderEngines.delete(engine)
            else Environment.renderEngines.set(engine, previous)
        }
    }

    private data class Case(val midi: Int, val digit: String, val dots: Int)
    private data class PaintedText(val text: String, val y: Double, val numberedFont: Boolean)
    private data class PaintedDot(val y: Double)

    private fun singleNoteScore(midi: Int, settings: Settings): Pair<Score, Note> {
        val score = Score()
        score.addMasterBar(MasterBar().apply { timeSignatureNumerator = 1.0; timeSignatureDenominator = 4.0 })
        val track = Track()
        score.addTrack(track)
        val staff = Staff().apply { showNumbered = true; showStandardNotation = false; showTablature = false }
        track.addStaff(staff)
        val bar = Bar()
        staff.addBar(bar)
        val voice = Voice()
        bar.addVoice(voice)
        val beat = Beat().apply { duration = Duration.Quarter }
        voice.addBeat(beat)
        val note = Note().apply { octave = (midi / 12).toDouble(); tone = (midi % 12).toDouble() }
        beat.addNote(note)
        score.finish(settings)
        return score to note
    }

    /** Only font measurement and drawing destination are replaced; real alphaTab lays out/paints. */
    private class RecordingCanvas(settings: Settings) {
        val texts = mutableListOf<PaintedText>()
        val dots = mutableListOf<PaintedDot>()
        private val properties = mutableMapOf<String, Any?>(
            "Settings" to settings, "Color" to settings.display.resources.mainGlyphColor,
            "Font" to settings.display.resources.numberedNotationFont, "LineWidth" to 1.0,
            "TextAlign" to TextAlign.Left, "TextBaseline" to TextBaseline.Alphabetic,
        )
        val canvas = Proxy.newProxyInstance(ICanvas::class.java.classLoader, arrayOf(ICanvas::class.java)) { _, method, args ->
            val values = args.orEmpty()
            when {
                method.name.startsWith("set") -> { properties[method.name.removePrefix("set")] = values[0]; null }
                method.name.startsWith("get") -> properties.getValue(method.name.removePrefix("get"))
                method.name == "measureText" -> MeasuredText(values[0].toString().length * 8.0, 16.0)
                method.name == "fillText" -> {
                    texts += PaintedText(values[0] as String, values[2] as Double,
                        properties["Font"] === settings.display.resources.numberedNotationFont)
                    null
                }
                method.name == "fillMusicFontSymbol" -> {
                    if (values[3] == MusicFontSymbol.AugmentationDot) dots += PaintedDot(values[1] as Double)
                    null
                }
                method.name == "fillMusicFontSymbols" -> {
                    (values[3] as Iterable<*>).forEach { if (it == MusicFontSymbol.AugmentationDot) dots += PaintedDot(values[1] as Double) }
                    null
                }
                else -> null
            }
        } as ICanvas
    }
}
