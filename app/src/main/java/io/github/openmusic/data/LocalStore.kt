package io.github.openmusic.data

import android.content.Context
import android.util.AtomicFile
import io.github.openmusic.core.CompiledScore
import io.github.openmusic.core.EvaluationEvent
import io.github.openmusic.core.ScoreCodec
import io.github.openmusic.core.ScoreCompiler
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

data class StoredScore(val id: String, val json: String, val compiled: CompiledScore, val builtIn: Boolean)

@Serializable
data class SessionRecord(
    val id: String,
    val scoreId: String,
    val scoreTitle: String,
    val scoreSha256: String,
    val startedAt: Long,
    val mode: String,
    val hands: String,
    val bpm: Int,
    val startMeasure: Int,
    val endMeasure: Int,
    val inputSource: String,
    val inputOffsetMs: Int,
    val durationSeconds: Double,
    val evaluated: Boolean,
    val outcome: String,
    val hits: Int,
    val misses: Int,
    val extras: Int,
    val early: Int,
    val late: Int,
    val meanAbsoluteTimingMs: Double?,
    val difficultMeasures: List<Int>,
    val rawEvents: List<RecordedInput>,
    val evaluationEvents: List<EvaluationEvent> = emptyList(),
    val evaluatorVersion: String = "0.1.0",
)

@Serializable
data class RecordedInput(
    val kind: String,
    val pitch: Int,
    val velocity: Int,
    val seconds: Double,
    val timestampNanos: Long,
    val elapsedSeconds: Double = 0.0,
    val channel: Int = 0,
    val value: Int = 0,
)

class LocalStore(private val context: Context) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val scores = File(context.filesDir, "scores").apply { mkdirs() }
    private val records = File(context.filesDir, "sessions").apply { mkdirs() }
    val preferences = context.getSharedPreferences("openmusic", Context.MODE_PRIVATE)

    fun loadScores(): Pair<List<StoredScore>, List<String>> {
        val loaded = mutableListOf<StoredScore>()
        val errors = mutableListOf<String>()
        context.assets.list("scores").orEmpty().filter { it.endsWith(".json") }.sorted().forEach { name ->
            runCatching {
                val text = context.assets.open("scores/$name").use { ScoreImport.read(it) }
                loaded += StoredScore("builtin:$name", text, ScoreCompiler.compile(ScoreCodec.parse(text)), true)
            }.onFailure { errors += "$name：${it.message}" }
        }
        scores.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }.forEach { file ->
            runCatching {
                val text = file.inputStream().use { ScoreImport.read(it) }
                loaded += StoredScore(file.nameWithoutExtension, text, ScoreCompiler.compile(ScoreCodec.parse(text)), false)
            }.onFailure { errors += "${file.name}：${it.message}" }
        }
        return loaded to errors
    }

    fun importScore(text: String): StoredScore {
        val compiled = ScoreCompiler.compile(ScoreCodec.parse(text))
        val id = "import-" + sha256(text).take(24)
        writeAtomic(File(scores, "$id.json"), text)
        return StoredScore(id, text, compiled, false)
    }

    fun deleteScore(id: String) {
        require(id.matches(Regex("import-[a-f0-9]{24}"))) { "只可删除导入曲谱。" }
        check(File(scores, "$id.json").delete()) { "曲谱删除失败。" }
    }

    @Synchronized
    fun saveSession(record: SessionRecord) {
        writeAtomic(File(records, "${record.id}.json"), json.encodeToString(record))
    }

    fun loadSessions(): List<SessionRecord> = records.listFiles().orEmpty()
        .filter { it.extension == "json" }
        .mapNotNull { runCatching { json.decodeFromString<SessionRecord>(it.readText()) }.getOrNull() }
        .sortedByDescending { it.startedAt }

    fun exportSessions(): String = json.encodeToString(loadSessions())

    private fun writeAtomic(file: File, text: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    companion object {
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
