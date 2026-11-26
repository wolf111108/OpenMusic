@file:OptIn(kotlin.contracts.ExperimentalContracts::class, kotlin.ExperimentalUnsignedTypes::class)

package io.github.openmusic.platform

import alphaTab.Settings
import alphaTab.collections.List as AlphaList
import alphaTab.collections.Map as AlphaMap
import alphaTab.model.Bar
import alphaTab.model.JsonConverter
import alphaTab.model.Score
import alphaTab.model.Staff
import alphaTab.model.Voice
import kotlin.math.abs

/**
 * alphaTab's numbered renderer draws only voice 0 and one pitch per beat.
 * Expand the engraving model into aligned monophonic staves
 * so chords and secondary voices remain visible. The practice/audio score is untouched.
 *
 * Use the public JSON round-trip API to preserve notation and explicit tie references.
 * Note IDs remain unique and unchanged; duplicated bars/voices/beats receive fresh IDs.
 */
internal object NumberedScoreAdapter {
    fun adapt(source: Score, settings: Settings): Score {
        val encoded = JsonConverter.scoreToJsObject(source).node()
        // Keep the original serialized staves available while earlier tracks are expanded.
        val originalTrackStaves = encoded.array("tracks").toList().map { it.node().array("staves") }
        source.tracks.forEachIndexed { trackIndex, track ->
            val trackNode = encoded.array("tracks")[trackIndex].node()
            val originalStaves = originalTrackStaves[trackIndex]
            val expandedStaves = AlphaList<Any?>()
            track.staves.forEachIndexed { staffIndex, staff ->
                val staffNode = originalStaves[staffIndex].node()
                for (plan in lanes(staff)) {
                    val displayStaff = staffNode.copyNode()
                    val displayBars = AlphaList<Any?>()
                    staff.bars.forEachIndexed { barIndex, bar ->
                        val barNode = staffNode.array("bars")[barIndex].node()
                        val displayBar = barNode.copyNode()
                        val voice = bar.voices.getOrNull(plan.voiceIndex)
                        val voiceNode = barNode.array("voices").getOrNull(plan.voiceIndex)?.node()
                        val displayVoice = voiceNode?.copyNode() ?: AlphaMap()
                        val displayBeats = AlphaList<Any?>()
                        if (voice == null || voice.beats.length == 0.0 || voice.isEmpty) {
                            // NumberedBeatGlyph uses the duration enum for its dashes, ignoring
                            // overrideDisplayDuration. Reuse this measure's actual rhythm so a
                            // one-quarter pickup is also drawn as one quarter, rather than Whole.
                            val rhythm = restRhythm(source, bar)
                            val rhythmStaff = rhythm.bar.staff
                            val rhythmNode = originalTrackStaves[rhythmStaff.track.index.toInt()][rhythmStaff.index.toInt()]
                                .node().array("bars")[barIndex].node().array("voices")[rhythm.index.toInt()].node()
                            rhythm.beats.forEachIndexed { beatIndex, beat ->
                                displayBeats.push(rhythmNode.array("beats")[beatIndex].node().copyNode().apply {
                                    set("notes", AlphaList<Any?>())
                                    set("isempty", false)
                                    set("overridedisplayduration", beat.playbackDuration)
                                })
                            }
                        } else {
                            voice.beats.forEachIndexed { beatIndex, beat ->
                                val beatNode = voiceNode!!.array("beats")[beatIndex].node()
                                val displayBeat = beatNode.copyNode()
                                val selected = AlphaList<Any?>()
                                beat.notes.forEachIndexed { noteIndex, note ->
                                    if (plan.noteLanes[note.id] == plan.lane) {
                                        selected.push(beatNode.array("notes")[noteIndex])
                                    }
                                }
                                displayBeat.set("notes", selected)
                                displayBeat.set("isempty", false)
                                // Turning a whole-note chord lane into a rest must not make it
                                // inherit a different full-bar duration. Keep the original ticks.
                                displayBeat.set("overridedisplayduration", beat.playbackDuration)
                                displayBeats.push(displayBeat)
                            }
                        }
                        displayVoice.set("beats", displayBeats)
                        displayBar.set("voices", AlphaList<Any?>(displayVoice))
                        displayBars.push(displayBar)
                    }
                    displayStaff.set("bars", displayBars)
                    displayStaff.set("showstandardnotation", false)
                    displayStaff.set("shownumbered", true)
                    displayStaff.set("showtablature", false)
                    expandedStaves.push(displayStaff)
                }
            }
            trackNode.set("staves", expandedStaves)
        }
        return JsonConverter.jsObjectToScore(encoded, settings)
    }

    private data class Lane(val voiceIndex: Int, val lane: Int, val noteLanes: Map<Double, Int>)

    private fun restRhythm(score: Score, bar: Bar): Voice {
        val duration = bar.masterBar.calculateDuration()
        fun complete(voice: Voice) = !voice.isEmpty && voice.beats.length > 0.0 &&
            abs(voice.calculateDuration() - duration) < 1e-6
        return bar.voices.firstOrNull(::complete)
            ?: score.tracks.asSequence().flatMap { it.staves.asSequence() }
                .mapNotNull { it.bars.getOrNull(bar.index.toInt()) }
                .flatMap { it.voices.asSequence() }.firstOrNull(::complete)
            ?: error("第 ${bar.index.toInt() + 1} 小节缺少完整节奏，无法生成正确时值的简谱休止符")
    }

    private fun lanes(staff: Staff): List<Lane> {
        // Importing MusicXML voice 5 creates four empty voices before the real left hand.
        // Only note-bearing voices need independent lanes; retain one rest lane for a silent staff.
        val voices = staff.bars.flatMap { bar ->
            bar.voices.toList().filter { voice -> voice.beats.any { it.notes.length > 0.0 } }
                .map { it.index.toInt() }
        }.distinct().sorted().ifEmpty {
            listOf(staff.bars.flatMap { it.voices.toList() }.firstOrNull { !it.isEmpty }?.index?.toInt() ?: 0)
        }
        return voices.flatMap { voiceIndex ->
            val beats = staff.bars.flatMap { it.voices.getOrNull(voiceIndex)?.beats?.toList().orEmpty() }
            val count = beats.maxOfOrNull { it.notes.length.toInt() }?.coerceAtLeast(1) ?: 1
            val assigned = mutableMapOf<Double, Int>()
            for (beat in beats) {
                val used = mutableSetOf<Int>()
                // A tied pitch must stay on its previous lane even if the chord's order/size changes.
                for (note in beat.notes) {
                    val previousLane = note.tieOrigin?.let { assigned[it.id] } ?: continue
                    check(used.add(previousLane)) { "Two tied notes occupy one numbered lane" }
                    assigned[note.id] = previousLane
                }
                for (note in beat.notes.sortedByDescending { it.realValue }) {
                    if (note.id in assigned) continue
                    val free = (0 until count).first { it !in used }
                    assigned[note.id] = free
                    used.add(free)
                }
            }
            (0 until count).map { Lane(voiceIndex, it, assigned) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.node() = this as AlphaMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun AlphaMap<String, Any?>.array(key: String) = get(key) as AlphaList<Any?>

    private fun <T> AlphaList<T>.getOrNull(index: Int): T? =
        if (index >= 0 && index < length.toInt()) this[index] else null

    private fun AlphaMap<String, Any?>.copyNode() = AlphaMap<String, Any?>().also { copy ->
        for ((key, value) in this) if (key != "id") copy.set(key, value)
    }
}
