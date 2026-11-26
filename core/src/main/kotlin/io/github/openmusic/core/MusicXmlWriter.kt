package io.github.openmusic.core

import java.math.BigInteger

/** The XML contains the expanded playback order, so renderer tick and practice time agree. */
internal object MusicXmlWriter {
    fun write(document: ScoreDocument, measures: List<ExpandedMeasure>, tempo: TempoMap): String {
        var divisions = BigInteger.ONE
        fun include(qn: Qn) {
            divisions = divisions / divisions.gcd(qn.denominator) * qn.denominator
            if (divisions > BigInteger.valueOf(1_000_000)) throw ScoreValidationException(listOf("时值分母组合过大；请将谱面量化为常用时值"))
        }
        document.measures.forEach { m -> include(ScoreCompiler.length(m)); m.events.forEach { include(Qn.parse(it.onsetQn)); include(Qn.parse(it.durationQn)) } }
        document.playbackStructure.tempoChanges.forEach { include(Qn.parse(it.onsetQn)) }
        fun ticks(qn: Qn): String = (qn.numerator * (divisions / qn.denominator)).toString()
        val out = StringBuilder(4096)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<score-partwise version=\"4.0\">\n")
        out.append("<work><work-title>${xml(document.metadata.title)}</work-title></work>\n")
        if (document.metadata.composer.isNotBlank()) out.append("<identification><creator type=\"composer\">${xml(document.metadata.composer)}</creator></identification>\n")
        out.append("<part-list>\n")
        document.parts.forEach { part -> out.append("<score-part id=\"P_${part.id}\"><part-name>${xml(part.name.ifBlank { part.instrument })}</part-name></score-part>\n") }
        out.append("</part-list>\n")
        document.parts.forEachIndexed { partIndex, part ->
            out.append("<part id=\"P_${part.id}\">\n")
            measures.forEachIndexed { displayIndex, expanded ->
                val measure = document.measures[expanded.sourceIndex]
                out.append("<measure number=\"${displayIndex + 1}\" id=\"m_p${partIndex + 1}_${measure.id}_${expanded.occurrence}\"${if (measure.isPickup) " implicit=\"yes\"" else ""}>\n")
                out.append("<attributes><divisions>$divisions</divisions><key><fifths>${measure.keySignatureFifths}</fifths></key><time><beats>${measure.timeSignature.numerator}</beats><beat-type>${measure.timeSignature.denominator}</beat-type></time>")
                if (part.staves.size > 1) out.append("<staves>${part.staves.size}</staves>")
                part.staves.sortedBy { it.index }.forEach { staff ->
                    val sign = when (staff.clef) { "bass" -> "F"; "alto", "tenor" -> "C"; else -> "G" }
                    val line = when (staff.clef) { "bass", "tenor" -> 4; "alto" -> 3; else -> 2 }
                    out.append("<clef number=\"${staff.index}\"><sign>$sign</sign><line>$line</line></clef>")
                }
                out.append("</attributes>\n")
                if (partIndex == 0) {
                    val bpm = tempo.bpmAt(expanded.start.toDouble())
                    out.append("<direction placement=\"above\"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>${number(bpm)}</per-minute></metronome></direction-type><sound tempo=\"${number(bpm)}\"/></direction>\n")
                    document.playbackStructure.tempoChanges.filter { it.measureId == measure.id && Qn.parse(it.onsetQn) > Qn.ZERO }.sortedBy { Qn.parse(it.onsetQn) }.forEach { change ->
                        out.append("<direction><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>${number(change.tempoBpm)}</per-minute></metronome></direction-type><offset>${ticks(Qn.parse(change.onsetQn))}</offset><sound tempo=\"${number(change.tempoBpm)}\"/></direction>\n")
                    }
                }
                var firstLane = true
                part.staves.sortedBy { it.index }.forEach { staff ->
                    val events = measure.events.filter { it.partId == part.id && it.staffId == staff.id }
                    val voices = events.map { it.voice }.distinct().sorted().ifEmpty { listOf(1) }
                    voices.forEach { voice ->
                        if (!firstLane) out.append("<backup><duration>${ticks(expanded.duration)}</duration></backup>\n")
                        firstLane = false
                        val xmlVoice = (staff.index - 1) * 4 + voice
                        var cursor = Qn.ZERO
                        events.filter { it.voice == voice }.groupBy { Qn.parse(it.onsetQn) }.toSortedMap().forEach { (onset, simultaneous) ->
                            if (onset > cursor) rest(out, onset - cursor, xmlVoice, staff.index, ::ticks)
                            val ordered = simultaneous.sortedWith(compareByDescending<ScoreEvent> { Qn.parse(it.durationQn) }.thenBy { it.pitch?.midi ?: 0 })
                            ordered.forEachIndexed { chordIndex, event ->
                                val duration = Qn.parse(event.durationQn)
                                out.append("<note id=\"n_${event.id}_${expanded.occurrence}\">")
                                if (chordIndex > 0) out.append("<chord/>")
                                if (event.kind == "rest") out.append("<rest/>") else {
                                    val pitch = event.pitch!!
                                    out.append("<pitch><step>${pitch.step}</step>")
                                    if (pitch.alter != 0) out.append("<alter>${pitch.alter}</alter>")
                                    out.append("<octave>${pitch.octave}</octave></pitch>")
                                }
                                out.append("<duration>${ticks(duration)}</duration>")
                                if (event.tie == "stop" || event.tie == "continue") out.append("<tie type=\"stop\"/>")
                                if (event.tie == "start" || event.tie == "continue") out.append("<tie type=\"start\"/>")
                                out.append("<voice>$xmlVoice</voice>")
                                out.append(notation(duration))
                                out.append("<staff>${staff.index}</staff>")
                                if (event.tie != "none" || event.fingering != null) {
                                    out.append("<notations>")
                                    if (event.tie == "stop" || event.tie == "continue") out.append("<tied type=\"stop\"/>")
                                    if (event.tie == "start" || event.tie == "continue") out.append("<tied type=\"start\"/>")
                                    event.fingering?.let { out.append("<technical><fingering>${xml(it)}</fingering></technical>") }
                                    out.append("</notations>")
                                }
                                out.append("</note>\n")
                            }
                            cursor = onset + ordered.maxOf { Qn.parse(it.durationQn) }
                        }
                        if (cursor < expanded.duration) rest(out, expanded.duration - cursor, xmlVoice, staff.index, ::ticks)
                    }
                }
                out.append("</measure>\n")
            }
            out.append("</part>\n")
        }
        return out.append("</score-partwise>\n").toString()
    }

    private fun rest(out: StringBuilder, duration: Qn, voice: Int, staff: Int, ticks: (Qn) -> String) {
        out.append("<note><rest/><duration>${ticks(duration)}</duration><voice>$voice</voice>${notation(duration)}<staff>$staff</staff></note>\n")
    }

    private fun notation(duration: Qn): String {
        val types = listOf("whole", "half", "quarter", "eighth", "16th", "32nd", "64th", "128th")
        for ((index, name) in types.withIndex()) {
            val base = Qn.of(4, 1L shl index)
            if (duration == base) return "<type>$name</type>"
            if (duration == Qn.of(base.numerator * BigInteger.valueOf(3), base.denominator * BigInteger.valueOf(2))) return "<type>$name</type><dot/>"
            if (duration == Qn.of(base.numerator * BigInteger.valueOf(7), base.denominator * BigInteger.valueOf(4))) return "<type>$name</type><dot/><dot/>"
            if (duration == Qn.of(base.numerator * BigInteger.valueOf(2), base.denominator * BigInteger.valueOf(3))) return "<type>$name</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes><normal-type>$name</normal-type></time-modification>"
        }
        // MusicXML duration remains authoritative for uncommon subdivisions.
        return ""
    }
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun number(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
