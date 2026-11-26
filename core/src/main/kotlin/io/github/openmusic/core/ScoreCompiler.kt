package io.github.openmusic.core

data class TempoPoint(val quarter: Double, val bpm: Double, val seconds: Double)

/** All public seconds are score time at 1x. Playback speed is an explicit conversion parameter. */
class TempoMap(points: List<Pair<Double, Double>>) {
    val points: List<TempoPoint>
    init {
        require(points.isNotEmpty())
        val sorted = points.sortedBy { it.first }.associate { it.first to it.second }.toList()
        require(sorted.first().first == 0.0)
        val result = mutableListOf<TempoPoint>()
        sorted.forEach { (quarter, bpm) ->
            require(quarter.isFinite() && quarter >= 0 && bpm.isFinite() && bpm > 0)
            val prev = result.lastOrNull()
            val seconds = if (prev == null) 0.0 else prev.seconds + (quarter - prev.quarter) * 60.0 / prev.bpm
            if (prev == null || prev.bpm != bpm) result += TempoPoint(quarter, bpm, seconds)
        }
        this.points = result
    }
    fun secondsAt(quarter: Double, speed: Double = 1.0): Double {
        require(speed.isFinite() && speed > 0 && quarter.isFinite())
        val point = points[floorIndex(quarter) { it.quarter }]
        return (point.seconds + (quarter - point.quarter) * 60.0 / point.bpm) / speed
    }
    fun quarterAt(seconds: Double, speed: Double = 1.0): Double {
        require(speed.isFinite() && speed > 0 && seconds.isFinite())
        val scoreSeconds = seconds * speed
        val point = points[floorIndex(scoreSeconds) { it.seconds }]
        return point.quarter + (scoreSeconds - point.seconds) * point.bpm / 60.0
    }
    fun bpmAt(quarter: Double): Double = points[floorIndex(quarter) { it.quarter }].bpm
    private fun floorIndex(value: Double, property: (TempoPoint) -> Double): Int {
        var low = 0; var high = points.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (property(points[mid]) <= value) low = mid else high = mid - 1
        }
        return low
    }
}

internal data class ExpandedMeasure(val sourceIndex: Int, val occurrence: Int, val start: Qn, val duration: Qn)
internal data class RawNote(
    val ids: MutableList<String>,
    val sourceIds: MutableList<String>,
    val event: ScoreEvent,
    val start: Qn,
    var duration: Qn,
    val occurrenceIndex: Int,
) { val end: Qn get() = start + duration }

object ScoreCompiler {
    fun compile(document: ScoreDocument): CompiledScore {
        ScoreCodec.validate(document)
        val expanded = expand(document)
        val tempo = compileTempo(document, expanded)
        val rawNotes = mutableListOf<RawNote>()
        val ties = mutableMapOf<List<String>, RawNote>()
        expanded.forEachIndexed { occurrenceIndex, occurrence ->
            document.measures[occurrence.sourceIndex].events.sortedWith(compareBy<ScoreEvent> { Qn.parse(it.onsetQn) }.thenBy { it.id }).forEach { event ->
                if (event.kind != "note") return@forEach
                val start = occurrence.start + Qn.parse(event.onsetQn)
                val duration = Qn.parse(event.durationQn)
                val key = listOf(event.partId, event.staffId, event.voice.toString(), event.pitch!!.midi.toString())
                val id = "${event.id}@${occurrence.occurrence}"
                when (event.tie) {
                    "stop", "continue" -> {
                        val previous = ties[key] ?: fail("${event.id}：延音线结束/延续没有对应起点")
                        if (previous.end != start) fail("${event.id}：延音线两端时值不连续")
                        previous.duration += duration
                        previous.ids += id
                        previous.sourceIds += event.id
                        if (event.tie == "stop") ties.remove(key)
                    }
                    else -> {
                        if (ties.containsKey(key)) fail("${event.id}：前一条延音线尚未结束")
                        val note = RawNote(mutableListOf(id), mutableListOf(event.id), event, start, duration, occurrenceIndex)
                        rawNotes += note
                        if (rawNotes.size > 120000) fail("展开后音符数超过 120000")
                        if (event.tie == "start") ties[key] = note
                    }
                }
            }
        }
        if (ties.isNotEmpty()) fail("延音线缺少结束音符：${ties.values.first().event.id}")
        // Keep each staff's duration before combining shared physical keys. Hand selection
        // must precede deduplication, otherwise a left-hand tie can extend a right-hand note.
        val sourceNotes = rawNotes.map { raw ->
            val startSeconds = tempo.secondsAt(raw.start.toDouble())
            CompiledNote(
                id = raw.ids.first(),
                sourceIds = raw.sourceIds.distinct(),
                partId = raw.event.partId,
                staffId = raw.event.staffId,
                staffIds = setOf(raw.event.staffId),
                voice = raw.event.voice,
                midiPitch = raw.event.pitch!!.midi,
                onsetQn = raw.start.toDouble(),
                durationQn = raw.duration.toDouble(),
                onsetSeconds = startSeconds,
                durationSeconds = tempo.secondsAt(raw.end.toDouble()) - startSeconds,
                velocity = raw.event.velocity,
                measureOccurrenceIndex = raw.occurrenceIndex,
            )
        }.sortedWith(compareBy<CompiledNote> { it.onsetQn }.thenBy { it.midiPitch })
        val notes = mergeSimultaneousKeys(sourceNotes, tempo)
        val measures = expanded.map { e ->
            val startSeconds = tempo.secondsAt(e.start.toDouble())
            MeasureOccurrence(document.measures[e.sourceIndex].id, e.sourceIndex, e.occurrence, e.start.toDouble(), e.duration.toDouble(), startSeconds, tempo.secondsAt((e.start + e.duration).toDouble()) - startSeconds)
        }
        val groups = notes.groupBy { it.onsetQn }.map { (qn, notesAtTime) -> OnsetGroup("group_${notesAtTime.first().id}", qn, tempo.secondsAt(qn), notesAtTime) }
        val total = expanded.last().let { (it.start + it.duration).toDouble() }
        val warnings = buildList {
            if (document.measures.any { m -> m.events.any { !it.reviewed } }) add("包含尚未人工确认的音符；建议先对照来源校对。")
            if (notes.any { it.midiPitch !in 21..108 }) add("部分音符超出 P-45 的 88 键范围。")
        }
        return CompiledScore(document, notes, groups, measures, tempo, total, tempo.secondsAt(total), MusicXmlWriter.write(document, expanded, tempo), warnings, sourceNotes)
    }

    internal fun length(measure: ScoreMeasure): Qn = when {
        measure.durationQn != null -> Qn.parse(measure.durationQn)
        measure.isPickup -> measure.events.maxOfOrNull { Qn.parse(it.onsetQn) + Qn.parse(it.durationQn) } ?: Qn.ZERO
        else -> Qn.of(measure.timeSignature.numerator.toLong() * 4, measure.timeSignature.denominator.toLong())
    }

    internal fun expand(document: ScoreDocument): List<ExpandedMeasure> {
        val indices = document.measures.mapIndexed { i, m -> m.id to i }.toMap()
        val repeats = document.playbackStructure.repeats.associateBy { indices.getValue(it.startMeasureId) }
        val result = mutableListOf<ExpandedMeasure>()
        val counts = IntArray(document.measures.size)
        var cursor = Qn.ZERO
        fun append(index: Int) {
            val length = length(document.measures[index])
            result += ExpandedMeasure(index, ++counts[index], cursor, length)
            cursor += length
        }
        var index = 0
        while (index < document.measures.size) {
            val repeat = repeats[index]
            if (repeat == null) { append(index); index++ }
            else {
                val end = indices.getValue(repeat.endMeasureId)
                kotlin.repeat(repeat.times) { for (mi in index..end) append(mi) }
                index = end + 1
            }
        }
        return result
    }

    private fun compileTempo(document: ScoreDocument, expanded: List<ExpandedMeasure>): TempoMap {
        var current = 80.0
        val sourceStart = mutableListOf<Double>()
        val changes = document.playbackStructure.tempoChanges.groupBy { it.measureId }
        document.measures.forEach { measure ->
            current = measure.tempoBpm ?: current
            val local = changes[measure.id].orEmpty().sortedBy { Qn.parse(it.onsetQn) }
            sourceStart += local.firstOrNull { Qn.parse(it.onsetQn) == Qn.ZERO }?.tempoBpm ?: current
            current = local.lastOrNull()?.tempoBpm ?: current
        }
        val points = mutableListOf<Pair<Double, Double>>()
        expanded.forEach { e ->
            val measure = document.measures[e.sourceIndex]
            points += e.start.toDouble() to sourceStart[e.sourceIndex]
            changes[measure.id].orEmpty().sortedBy { Qn.parse(it.onsetQn) }.forEach { c -> points += (e.start + Qn.parse(c.onsetQn)).toDouble() to c.tempoBpm }
        }
        return TempoMap(points)
    }
    private fun fail(message: String): Nothing = throw ScoreValidationException(listOf(message))
}

/** One MIDI keyboard emits one Note On for a shared key, including unison parts. */
internal fun mergeSimultaneousKeys(notes: List<CompiledNote>, tempo: TempoMap): List<CompiledNote> =
    notes.groupBy { it.midiPitch to it.onsetQn }.values.map { overlap ->
        val first = overlap.first()
        val end = overlap.maxOf { it.endQn }
        first.copy(
            sourceIds = overlap.flatMap { it.sourceIds }.distinct(),
            staffIds = overlap.flatMap { it.staffIds }.toSet(),
            durationQn = end - first.onsetQn,
            durationSeconds = tempo.secondsAt(end) - first.onsetSeconds,
            velocity = overlap.maxOf { it.velocity },
        )
    }.sortedWith(compareBy<CompiledNote> { it.onsetQn }.thenBy { it.midiPitch })
