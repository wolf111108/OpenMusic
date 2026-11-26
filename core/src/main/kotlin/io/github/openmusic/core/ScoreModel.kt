package io.github.openmusic.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** OpenMusic ScoreIR 0.1.0. The JSON schema in schemas/ is the producer contract. */
@Serializable
data class ScoreDocument(
    @SerialName("schema_version") val schemaVersion: String = "0.1.0",
    @SerialName("score_id") val scoreId: String,
    val metadata: ScoreMetadata,
    val parts: List<ScorePart>,
    val measures: List<ScoreMeasure>,
    val annotations: List<JsonElement> = emptyList(),
    @SerialName("playback_structure") val playbackStructure: PlaybackStructure = PlaybackStructure(),
)

@Serializable
data class ScoreMetadata(
    val title: String,
    val instrument: String = "piano",
    val composer: String = "",
    @SerialName("source_type") val sourceType: String = "scoreir",
    @SerialName("source_sha256") val sourceSha256: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val transcriber: String = "",
)

@Serializable
data class ScorePart(val id: String, val instrument: String = "piano", val name: String = "", val staves: List<ScoreStaff>)

@Serializable
data class ScoreStaff(val id: String, val clef: String = "treble", val index: Int = 1)

@Serializable
data class TimeSignature(val numerator: Int = 4, val denominator: Int = 4)

@Serializable
data class ScoreMeasure(
    val id: String,
    val number: Int,
    @SerialName("time_signature") val timeSignature: TimeSignature = TimeSignature(),
    @SerialName("key_signature_fifths") val keySignatureFifths: Int = 0,
    @SerialName("tempo_bpm") val tempoBpm: Double? = null,
    @SerialName("is_pickup") val isPickup: Boolean = false,
    /** Explicit duration for a pickup, in quarter notes; regular bars use the time signature. */
    @SerialName("duration_qn") val durationQn: String? = null,
    val events: List<ScoreEvent>,
)

@Serializable
data class Pitch(val step: String, val alter: Int = 0, val octave: Int, val midi: Int)

@Serializable
data class ScoreSource(val page: Int = 1, @SerialName("bbox_norm") val bboxNorm: List<Double> = emptyList())

@Serializable
data class ScoreEvent(
    val id: String,
    val kind: String = "note",
    @SerialName("part_id") val partId: String,
    @SerialName("staff_id") val staffId: String,
    val voice: Int = 1,
    @SerialName("onset_qn") val onsetQn: String,
    @SerialName("duration_qn") val durationQn: String,
    val pitch: Pitch? = null,
    /** none, start, stop, continue. Ties are distinct from phrasing slurs. */
    val tie: String = "none",
    val source: ScoreSource? = null,
    val confidence: Double? = null,
    val reviewed: Boolean = true,
    val fingering: String? = null,
    val velocity: Int = 80,
)

@Serializable
data class PlaybackStructure(
    val repeats: List<RepeatRegion> = emptyList(),
    @SerialName("tempo_changes") val tempoChanges: List<TempoChange> = emptyList(),
)

@Serializable
data class RepeatRegion(
    @SerialName("start_measure_id") val startMeasureId: String,
    @SerialName("end_measure_id") val endMeasureId: String,
    /** Total passes, not additional repeats. */
    val times: Int = 2,
)

@Serializable
data class TempoChange(
    @SerialName("measure_id") val measureId: String,
    @SerialName("onset_qn") val onsetQn: String = "0/1",
    /** Quarter notes per minute, regardless of time-signature denominator. */
    @SerialName("tempo_bpm") val tempoBpm: Double,
)

data class MeasureOccurrence(
    val sourceMeasureId: String,
    val sourceMeasureIndex: Int,
    val occurrence: Int,
    val startQn: Double,
    val durationQn: Double,
    val startSeconds: Double,
    val durationSeconds: Double,
) {
    val endQn: Double get() = startQn + durationQn
    val endSeconds: Double get() = startSeconds + durationSeconds
}

data class CompiledNote(
    val id: String,
    val sourceIds: List<String>,
    val partId: String,
    val staffId: String,
    val staffIds: Set<String>,
    val voice: Int,
    val midiPitch: Int,
    val onsetQn: Double,
    val durationQn: Double,
    val onsetSeconds: Double,
    val durationSeconds: Double,
    val velocity: Int,
    val measureOccurrenceIndex: Int,
) {
    val endQn: Double get() = onsetQn + durationQn
    val endSeconds: Double get() = onsetSeconds + durationSeconds
}

data class OnsetGroup(val id: String, val onsetQn: Double, val onsetSeconds: Double, val notes: List<CompiledNote>)

data class CompiledScore(
    val document: ScoreDocument,
    val notes: List<CompiledNote>,
    val groups: List<OnsetGroup>,
    val measures: List<MeasureOccurrence>,
    val tempoMap: TempoMap,
    val totalQuarterNotes: Double,
    val durationSeconds: Double,
    val musicXml: String,
    val warnings: List<String> = emptyList(),
    /** Ties are joined, but staff/voice identities remain separate until practice selection. */
    val sourceNotes: List<CompiledNote> = notes,
) {
    fun measureAt(qn: Double): MeasureOccurrence? = measures.lastOrNull { it.startQn <= qn + 1e-9 }
}
