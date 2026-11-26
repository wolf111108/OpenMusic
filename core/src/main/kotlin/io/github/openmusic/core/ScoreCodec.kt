package io.github.openmusic.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.math.BigInteger

class ScoreValidationException(val issues: List<String>) : IllegalArgumentException(issues.joinToString("\n"))

/** Bounded, strict reader. Producers can use the shipped JSON Schema before import. */
object ScoreCodec {
    const val MAX_INPUT_BYTES = 2 * 1024 * 1024
    val json: Json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

    fun parse(text: String): ScoreDocument {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) fail("文件超过 2 MiB 上限")
        checkDepth(text)
        val document = try {
            val element = json.parseToJsonElement(text)
            if (element !is JsonObject || "schema_version" !in element) fail("缺少必填 schema_version")
            json.decodeFromJsonElement<ScoreDocument>(element)
        }
        catch (e: Exception) { fail("ScoreIR JSON 格式错误：${e.message?.take(500)}") }
        validate(document)
        return document
    }

    fun encode(document: ScoreDocument): String = json.encodeToString(document)

    fun validate(document: ScoreDocument) {
        val issues = mutableListOf<String>()
        fun error(text: String) { if (issues.size < 80) issues += text }
        var notationDivisions = BigInteger.ONE
        var divisionsExceeded = false
        fun checkDivisions(value: Qn) {
            if (divisionsExceeded) return
            notationDivisions = notationDivisions / notationDivisions.gcd(value.denominator) * value.denominator
            if (notationDivisions > BigInteger.valueOf(1_000_000)) {
                divisionsExceeded = true
                error("时值分母组合过大；请将谱面量化为常用时值（最小公共分母上限 1000000）")
            }
        }
        fun id(value: String, context: String) {
            if (!value.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) error("$context：ID 应为 1–100 位字母、数字、下划线、点或短横线")
        }
        fun notationText(value: String, context: String, maxLength: Int = 200) {
            if (value.length > maxLength || value.codePoints().anyMatch { cp ->
                cp != 9 && cp != 10 && cp != 13 && cp !in 0x20..0xD7FF && cp !in 0xE000..0xFFFD && cp !in 0x10000..0x10FFFF
            }) error("$context：文字过长或含无效控制字符")
        }
        if (document.schemaVersion != "0.1.0") error("不支持 schema_version=${document.schemaVersion}；当前支持 0.1.0")
        id(document.scoreId, "score_id")
        if (document.metadata.title.isBlank() || document.metadata.title.length > 200) error("曲名长度应为 1–200 字")
        notationText(document.metadata.title, "曲名")
        notationText(document.metadata.composer, "作曲者")
        if (document.parts.size !in 1..8) error("乐部数量应为 1–8")
        if (document.measures.size !in 1..2048) error("小节数量应为 1–2048")
        if (document.parts.map { it.id }.toSet().size != document.parts.size) error("乐部 ID 重复")
        val staffIds = mutableSetOf<String>()
        document.parts.forEach { part ->
            id(part.id, "乐部")
            notationText(part.name, "乐部名称")
            notationText(part.instrument, "乐器名称")
            if (part.staves.size !in 1..2) error("${part.id}：当前支持每乐部 1–2 个谱表")
            if (part.staves.map { it.index }.sorted() != (1..part.staves.size).toList()) error("${part.id}：谱表 index 应从 1 连续编号")
            part.staves.forEach { staff ->
                id(staff.id, "谱表")
                if (!staffIds.add(staff.id)) error("谱表 ID ${staff.id} 重复；请使用全曲唯一 ID")
                if (staff.clef !in setOf("treble", "bass", "alto", "tenor")) error("${staff.id}：暂不支持 clef=${staff.clef}")
            }
        }
        val partById = document.parts.associateBy { it.id }
        val measureIds = mutableSetOf<String>()
        val eventIds = mutableSetOf<String>()
        var eventCount = 0
        val lengths = mutableMapOf<String, Qn>()
        document.measures.forEachIndexed { mi, measure ->
            val label = "第 ${mi + 1} 小节 (${measure.id})"
            id(measure.id, label)
            if (!measureIds.add(measure.id)) error("$label：小节 ID 重复")
            if (measure.number < 0) error("$label：number 应大于或等于 0")
            if (measure.timeSignature.numerator !in 1..32 || measure.timeSignature.denominator !in setOf(1, 2, 4, 8, 16)) error("$label：拍号不在支持范围内")
            if (measure.keySignatureFifths !in -7..7) error("$label：调号应为 -7 到 7")
            if (measure.tempoBpm != null && (!measure.tempoBpm.isFinite() || measure.tempoBpm !in 20.0..300.0)) error("$label：tempo_bpm 应为 20–300 个四分音符/分")
            val baseLength = Qn.of(measure.timeSignature.numerator.toLong() * 4, measure.timeSignature.denominator.coerceAtLeast(1).toLong())
            val parsed = mutableListOf<Triple<ScoreEvent, Qn, Qn>>()
            measure.events.forEach { event ->
                eventCount++
                val el = "$label / ${event.id}"
                id(event.id, el)
                if (!eventIds.add(event.id)) error("$el：音符 ID 重复")
                val part = partById[event.partId]
                if (part == null) error("$el：乐部 ${event.partId} 不存在")
                else if (part.staves.none { it.id == event.staffId }) error("$el：谱表 ${event.staffId} 不属于该乐部")
                if (event.voice !in 1..4) error("$el：当前支持每谱表 1–4 个声部")
                if (event.kind !in setOf("note", "rest")) error("$el：kind 仅支持 note / rest")
                if (event.tie !in setOf("none", "start", "stop", "continue")) error("$el：tie 值无效")
                if (event.velocity !in 1..127) error("$el：velocity 应为 1–127")
                event.fingering?.let { notationText(it, "$el 指法", 20) }
                if (event.confidence != null && (!event.confidence.isFinite() || event.confidence !in 0.0..1.0)) error("$el：confidence 应为 0–1")
                event.source?.let { source ->
                    if (source.page < 1) error("$el：source.page 从 1 开始")
                    val b = source.bboxNorm
                    if (b.isNotEmpty() && (b.size != 4 || b.any { !it.isFinite() || it !in 0.0..1.0 } || (b.size == 4 && (b[2] <= 0 || b[3] <= 0 || b[0] + b[2] > 1.0000001 || b[1] + b[3] > 1.0000001)))) error("$el：bbox_norm 应为归一化的 [x,y,width,height]")
                }
                if (event.kind == "note") {
                    val pitch = event.pitch
                    if (pitch == null) error("$el：音符缺少 pitch")
                    else {
                        val semitones = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)
                        val semitone = semitones[pitch.step]
                        if (semitone == null || pitch.alter !in -2..2 || pitch.midi !in 0..127 || pitch.octave !in -1..9) error("$el：音高范围或音名无效")
                        else if ((pitch.octave + 1) * 12 + semitone + pitch.alter != pitch.midi) error("$el：音高拼写与 MIDI 编号不一致")
                    }
                } else if (event.pitch != null || event.tie != "none") error("$el：休止符应省略 pitch 并使用 tie=none")
                try {
                    val onset = Qn.parse(event.onsetQn)
                    val duration = Qn.parse(event.durationQn)
                    checkDivisions(onset)
                    checkDivisions(duration)
                    if (onset < Qn.ZERO || duration <= Qn.ZERO || duration > Qn.of(128)) error("$el：起点应非负，时值应为 (0,128] 四分音符")
                    else parsed += Triple(event, onset, duration)
                } catch (e: IllegalArgumentException) { error("$el：${e.message}") }
            }
            val length = try {
                when {
                    measure.durationQn != null -> Qn.parse(measure.durationQn)
                    measure.isPickup -> parsed.maxOfOrNull { it.second + it.third } ?: Qn.ZERO
                    else -> baseLength
                }
            } catch (e: IllegalArgumentException) { error("$label：duration_qn ${e.message}"); baseLength }
            if (length <= Qn.ZERO || length > baseLength || (!measure.isPickup && length != baseLength)) error("$label：普通小节应与拍号时长一致；弱起小节需正时值且不超过拍号")
            checkDivisions(length)
            lengths[measure.id] = length
            parsed.forEach { (event, onset, duration) -> if (onset + duration > length) error("$label / ${event.id}：音符越过小节边界；请拆分并用延音线连接") }
            parsed.groupBy { Triple(it.first.partId, it.first.staffId, it.first.voice) }.values.forEach { voice ->
                var previousEnd = Qn.ZERO
                voice.groupBy { it.second }.toSortedMap().forEach { (onset, chord) ->
                    if (onset < previousEnd) error("$label：同一声部存在重叠；同时起音的和弦除外，独立时值请拆分声部")
                    if (chord.size > 1 && chord.any { it.first.kind == "rest" }) error("$label：休止符与其他事件在同一声部重叠")
                    if (chord.mapNotNull { it.first.pitch?.midi }.distinct().size != chord.count { it.first.kind == "note" }) error("$label：同一声部和弦含重复琴键")
                    if (chord.map { it.third }.distinct().size > 1) error("$label：同一声部和弦内时值应一致；独立时值请拆分 voice，保证谱面与演奏一致")
                    previousEnd = chord.maxOf { it.second + it.third }
                }
            }
        }
        if (eventCount > 30000) error("音符/休止符总数超过 30000")
        val indices = document.measures.mapIndexed { i, m -> m.id to i }.toMap()
        val intervals = mutableListOf<IntRange>()
        var expandedCount = document.measures.size
        document.playbackStructure.repeats.forEach { repeat ->
            val start = indices[repeat.startMeasureId]
            val end = indices[repeat.endMeasureId]
            if (start == null || end == null || end < start) error("反复区间引用无效：${repeat.startMeasureId} → ${repeat.endMeasureId}")
            else {
                val range = start..end
                if (intervals.any { it.first <= range.last && range.first <= it.last }) error("当前版本仅支持互不重叠的简单反复；嵌套反复、一二房请先展开")
                intervals += range
                expandedCount += (end - start + 1) * (repeat.times - 1)
            }
            if (repeat.times !in 2..8) error("反复 times 应为 2–8 次总遍数")
        }
        if (expandedCount > 8192) error("反复展开后超过 8192 小节")
        val tempoKeys = mutableSetOf<Pair<String, Qn>>()
        document.playbackStructure.tempoChanges.forEach { change ->
            val length = lengths[change.measureId]
            if (length == null) error("速度变化引用未知小节 ${change.measureId}")
            try {
                val onset = Qn.parse(change.onsetQn)
                checkDivisions(onset)
                if (onset < Qn.ZERO || (length != null && onset >= length)) error("速度变化应位于小节内部：${change.measureId}")
                if (!tempoKeys.add(change.measureId to onset)) error("同一位置重复定义速度变化：${change.measureId}")
            } catch (e: IllegalArgumentException) { error("速度变化时值错误：${e.message}") }
            if (!change.tempoBpm.isFinite() || change.tempoBpm !in 20.0..300.0) error("tempo_bpm 应为 20–300 个四分音符/分")
        }
        if (issues.isNotEmpty()) throw ScoreValidationException(issues)
    }

    private fun checkDepth(text: String) {
        var depth = 0; var quoted = false; var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 40) fail("JSON 嵌套超过 40 层")
                '}', ']' -> depth--
            }
        }
    }
    private fun fail(message: String): Nothing = throw ScoreValidationException(listOf(message))
}

@ConsistentCopyVisibility
internal data class Qn private constructor(val numerator: BigInteger, val denominator: BigInteger) : Comparable<Qn> {
    operator fun plus(other: Qn): Qn = of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)
    operator fun minus(other: Qn): Qn = of(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)
    override fun compareTo(other: Qn): Int = (numerator * other.denominator).compareTo(other.numerator * denominator)
    fun toDouble(): Double = numerator.toDouble() / denominator.toDouble()
    override fun toString(): String = "$numerator/$denominator"
    companion object {
        val ZERO = of(0)
        fun of(n: Long, d: Long = 1): Qn = of(BigInteger.valueOf(n), BigInteger.valueOf(d))
        fun of(n: BigInteger, d: BigInteger): Qn {
            require(d.signum() > 0) { "分母必须为正数" }
            val gcd = n.gcd(d)
            return Qn(n / gcd, d / gcd)
        }
        fun parse(value: String): Qn {
            require(value.length <= 24 && value.matches(Regex("-?[0-9]+(?:/[0-9]+)?"))) { "时值应为整数或分数字符串，例如 1/3" }
            val parts = value.split('/')
            val n = parts[0].toBigInteger(); val d = parts.getOrElse(1) { "1" }.toBigInteger()
            require(n.abs() <= BigInteger.valueOf(1_000_000) && d <= BigInteger.valueOf(10000)) { "时值分子/分母超过支持范围" }
            return of(n, d)
        }
    }
}
