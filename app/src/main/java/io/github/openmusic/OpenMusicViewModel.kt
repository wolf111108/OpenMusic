package io.github.openmusic

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import io.github.openmusic.core.*
import io.github.openmusic.data.*
import io.github.openmusic.platform.*
import io.github.openmusic.practice.PracticeTimeline
import io.github.openmusic.practice.PracticeAudioFocus
import io.github.openmusic.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

data class RenderState(val musicXml: String = "", val positionQn: Double = 0.0)
data class ExportRequest(val name: String, val text: String, val mimeType: String = "application/json")

class OpenMusicViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val midi = MidiInputController(application)
    private val audio = NativeAudioTransport()
    private val focus = PracticeAudioFocus(application) { pause() }
    private val mutableState = MutableStateFlow(OpenMusicUiState())
    val state: StateFlow<OpenMusicUiState> = mutableState.asStateFlow()
    private val mutableRender = MutableStateFlow(RenderState())
    val render: StateFlow<RenderState> = mutableRender.asStateFlow()
    private val exports = MutableSharedFlow<ExportRequest>(extraBufferCapacity = 4)
    val exportRequests = exports.asSharedFlow()
    private var scores = listOf<StoredScore>()
    private var selected: StoredScore? = null
    private var timeline: PracticeTimeline? = null
    private var evaluator: PracticeEvaluator? = null
    private var waiting: WaitingMatcher? = null
    private var waitingJob: Job? = null
    private var loopJob: Job? = null
    private var waitingRestRemaining = 0.0
    private var waitingRestStartNs = 0L
    private var finishAfterRest = false
    private var currentPlan: AudioPlan? = null
    private var waitingReady = false
    private var takeActive = false
    private var inputForTake = false
    private var inputSource = "无演奏输入"
    private var takeWallStart = 0L
    private var takeMonotonicStart = 0L
    private var pausedPlanSeconds = 0.0
    private var playedWallSeconds = 0.0
    private var lastResumeNs = 0L
    private var waitingCorrect = 0
    private var waitingExtra = 0
    private val waitingMistakes = mutableSetOf<Int>()
    private val rawEvents = mutableListOf<RecordedInput>()
    private var previousConnection: Int? = null
    private var loaded = false

    init {
        mutableState.update { it.copy(inputOffsetMs = store.preferences.getInt("inputOffsetMs", 0)) }
        viewModelScope.launch { midi.state.collect { device ->
            val wasConnected = previousConnection
            previousConnection = device.connectedDeviceId
            mutableState.update { s -> s.copy(
                midiSupported = device.supported,
                midiDevices = device.devices.map { MidiDeviceItemUi(it.id, it.name) },
                connectedDeviceId = device.connectedDeviceId,
                midiStatus = device.error ?: device.connectedName?.let { "已连接 $it" } ?: "选择电钢琴设备后连接",
                midiLog = device.log,
                canEvaluate = device.connectedDeviceId != null || s.simulatorEnabled,
                inputLabel = when { s.simulatorEnabled -> "测试键盘输入"; device.connectedName != null -> device.connectedName!!; else -> "未接入演奏输入" },
                activeKeys = if (wasConnected != null && device.connectedDeviceId == null && !s.simulatorEnabled) emptySet() else s.activeKeys,
            ) }
            if (takeActive && wasConnected != null && device.connectedDeviceId == null && !state.value.simulatorEnabled) {
                finishTake(false, "设备中断", showSummary = true)
                mutableState.update { it.copy(activeKeys = emptySet(), message = "MIDI 连接中断，本轮已保存为中断记录。") }
            }
        } }
        viewModelScope.launch { midi.events.collect { event ->
            if (!state.value.simulatorEnabled) receiveInput(event.kind, event.pitch, event.velocity, event.timestampNanos, event.channel, event.value)
        } }
        viewModelScope.launch { audio.state.collect { position ->
            if (position.error != null && state.value.running) {
                pause(); mutableState.update { it.copy(error = "音频输出异常：${position.error}") }
            } else updatePosition(position)
        } }
        viewModelScope.launch { reloadLibrary() }
    }

    private suspend fun reloadLibrary(selectId: String? = selected?.id) {
        mutableState.update { it.copy(busy = true) }
        val result = withContext(Dispatchers.IO) { store.loadScores() }
        scores = result.first
        mutableState.update { it.copy(library = scores.map(::scoreUi), busy = false) }
        val preferred = selectId ?: store.preferences.getString("selectedScore", null)
        val chosen = scores.firstOrNull { it.id == preferred } ?: scores.firstOrNull()
        if (chosen != null) choose(chosen, openPractice = false)
        if (result.second.isNotEmpty()) mutableState.update { it.copy(error = "部分曲谱未能载入：\n${result.second.take(8).joinToString("\n")}") }
        refreshHistory()
        loaded = true
    }

    fun importScore(uri: Uri) {
        finishIfActive("导入曲谱")
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val content = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { ScoreImport.read(it) } ?: error("无法打开选择的文件。")
                    store.importScore(content)
                }
            }.onSuccess { imported ->
                scores = scores.filterNot { it.id == imported.id } + imported
                mutableState.update { it.copy(library = scores.map(::scoreUi), busy = false, message = "已导入 ${imported.compiled.document.metadata.title}") }
                choose(imported, true)
            }.onFailure { mutableState.update { s -> s.copy(busy = false, error = "导入失败：${it.message}\n\n请选择 ScoreIR JSON 或含 score.json 的 .scorepack。") } }
        }
    }

    fun exportTo(uri: Uri, request: ExportRequest) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: error("无法写入所选位置。")
                output.use { it.write(request.text.toByteArray(Charsets.UTF_8)) }
            } }.onSuccess { mutableState.update { it.copy(message = "已导出 ${request.name}") } }
                .onFailure { e -> mutableState.update { it.copy(error = "导出失败：${e.message}") } }
        }
    }

    fun onAction(action: UiAction) {
        when (action) {
            is UiAction.Navigate -> { if (action.screen != AppScreen.PRACTICE) pause(); mutableState.update { it.copy(screen = action.screen) } }
            UiAction.ImportScore -> Unit // SAF launcher belongs to Activity.
            is UiAction.SelectScore -> scores.firstOrNull { it.id == action.id }?.let { finishIfActive("切换曲谱"); choose(it, true) }
            is UiAction.DeleteScore -> viewModelScope.launch {
                finishIfActive("删除曲谱")
                runCatching { withContext(Dispatchers.IO) { store.deleteScore(action.id) } }
                    .onSuccess { reloadLibrary() }
                    .onFailure { e -> mutableState.update { it.copy(error = e.message) } }
            }
            UiAction.ExportExample -> scores.firstOrNull { it.builtIn }?.let { exports.tryEmit(ExportRequest("openmusic-example.scoreir.json", it.json)) }
            UiAction.ExportHistory -> viewModelScope.launch { exports.emit(ExportRequest("openmusic-history.json", withContext(Dispatchers.IO) { store.exportSessions() })) }
            UiAction.ExportMidiLog -> exports.tryEmit(ExportRequest("openmusic-midi-log.txt", state.value.midiLog.joinToString("\n"), "text/plain"))
            is UiAction.SetMode -> changeSettings { it.copy(mode = action.mode) }
            is UiAction.SetNotation -> mutableState.update { it.copy(notation = action.notation) }
            is UiAction.SetHands -> changeSettings { it.copy(hands = action.hands) }
            is UiAction.SetBpm -> changeSettings { it.copy(bpm = action.bpm.coerceIn(20, 300)) }
            is UiAction.SetRange -> {
                val size = selected?.compiled?.measures?.size ?: 1
                val a = action.start.coerceIn(1, size)
                changeSettings { it.copy(startMeasure = a, endMeasure = action.end.coerceIn(a, size)) }
            }
            is UiAction.SetLoop -> { if (!action.enabled) cancelLoop(); mutableState.update { it.copy(loop = action.enabled) } }
            is UiAction.SetCountIn -> changeSettings { it.copy(countIn = action.bars.coerceIn(0, 2)) }
            is UiAction.SetMetronome -> changeSettings { it.copy(metronome = action.enabled) }
            is UiAction.SetDemoSound -> changeSettings { it.copy(demoSound = action.enabled) }
            UiAction.PlayPause -> if (state.value.running) pause() else if (takeActive) resume() else startTake()
            UiAction.Restart -> { finishIfActive("重新开始"); startTake() }
            UiAction.Stop -> finishTake(false, "手动结束", true)
            is UiAction.SeekMeasure -> {
                val size = selected?.compiled?.measures?.size ?: 1
                val index = action.measure.coerceIn(1, size)
                changeSettings { it.copy(startMeasure = index, endMeasure = max(index, it.endMeasure), message = "起点已定位到演奏小节 $index") }
            }
            UiAction.RepeatMistakes -> {
                val mistakes = state.value.summary?.difficultMeasures.orEmpty()
                if (mistakes.isNotEmpty()) {
                    finishIfActive("重练错误")
                    mutableState.update { it.copy(startMeasure = mistakes.min(), endMeasure = mistakes.max(), summary = null, screen = AppScreen.PRACTICE, loop = true) }
                    resetPosition(); startTake()
                }
            }
            UiAction.RefreshMidi -> midi.refresh()
            is UiAction.ConnectMidi -> { finishIfActive("切换输入"); midi.connect(action.deviceId) }
            UiAction.DisconnectMidi -> { finishIfActive("断开输入"); midi.disconnect() }
            is UiAction.SetInputOffset -> {
                val offset = action.milliseconds.coerceIn(-500, 500)
                changeSettings { it.copy(inputOffsetMs = offset) }
                store.preferences.edit { putInt("inputOffsetMs", offset) }
            }
            is UiAction.SetSimulatorEnabled -> {
                finishIfActive("切换输入")
                mutableState.update { it.copy(simulatorEnabled = action.enabled, activeKeys = emptySet(), canEvaluate = action.enabled || it.connectedDeviceId != null,
                    inputLabel = if (action.enabled) "测试键盘输入" else midi.state.value.connectedName ?: "未接入演奏输入") }
            }
            is UiAction.SetSimulatorOctave -> {
                state.value.activeKeys.forEach { waiting?.noteOff(it) }
                mutableState.update { it.copy(simulatorOctave = action.octave.coerceIn(1, 7), activeKeys = emptySet()) }
            }
            is UiAction.SimulatedNote -> if (state.value.simulatorEnabled) receiveInput(if (action.isOn) MidiEventKind.NOTE_ON else MidiEventKind.NOTE_OFF, action.pitch, 80, System.nanoTime())
            UiAction.DismissMessage -> mutableState.update { it.copy(message = null) }
            UiAction.DismissError -> mutableState.update { it.copy(error = null) }
            UiAction.DismissSummary -> mutableState.update { it.copy(summary = null) }
        }
    }

    private fun choose(score: StoredScore, openPractice: Boolean) {
        selected = score
        store.preferences.edit { putString("selectedScore", score.id) }
        val item = scoreUi(score)
        mutableRender.value = RenderState(score.compiled.musicXml, 0.0)
        mutableState.update { it.copy(selectedScore = item, startMeasure = 1, endMeasure = item.measureCount,
            bpm = item.originalBpm, screen = if (openPractice) AppScreen.PRACTICE else it.screen,
            running = false, summary = null, iteration = 1, feedback = score.compiled.warnings.joinToString("\n").ifEmpty { "先听示范，再选段慢练。" }) }
        resetPosition()
    }

    private fun changeSettings(change: (OpenMusicUiState) -> OpenMusicUiState) {
        finishIfActive("设置变更")
        mutableState.update(change)
        resetPosition()
    }

    private fun selectedStaves(score: CompiledScore): Set<String> = score.document.parts.flatMap { it.staves }.filter {
        when (state.value.hands) { HandsUi.BOTH -> true; HandsUi.RIGHT -> it.index == 1; HandsUi.LEFT -> it.index > 1 }
    }.map { it.id }.toSet()

    private fun newTimeline(): PracticeTimeline? {
        val c = selected?.compiled ?: return null
        val s = state.value
        return PracticeTimeline(c, s.startMeasure - 1, s.endMeasure - 1, s.bpm, selectedStaves(c), s.countIn)
    }

    private fun resetPosition() {
        if (selected == null) return
        val t = newTimeline() ?: return
        pausedPlanSeconds = 0.0
        mutableRender.update { it.copy(positionQn = t.startQn) }
        mutableState.update { it.copy(positionSeconds = 0.0, totalSeconds = t.musicSeconds,
            currentMeasure = it.startMeasure, currentBeat = 1.0, transportLabel = "准备练习", running = false) }
    }

    private fun startTake() {
        cancelLoop()
        if (!loaded || selected == null) return
        if (state.value.mode == PracticeModeUi.WAIT && !state.value.canEvaluate) {
            mutableState.update { it.copy(message = "等待模式需要 MIDI 或测试键盘输入。") }; return
        }
        val t = newTimeline() ?: return
        if (selectedStaves(t.score).isEmpty() || t.targets.isEmpty()) {
            mutableState.update { it.copy(error = "当前声部与片段中没有可练习音符，请调整左右手或范围。") }; return
        }
        if (!focus.acquire()) { mutableState.update { it.copy(message = "当前音频正被其他应用占用，请稍后开始练习。") }; return }
        timeline = t
        evaluator = PracticeEvaluator(t.score, t.selectedStaffIds, t.startQn, t.endQn, playbackSpeed = t.speed)
        waiting = WaitingMatcher(t.targets.groupBy { it.onsetQn }.map { (_, notes) -> OnsetGroup("waiting_${notes.first().id}", notes.first().onsetQn, notes.first().onsetSeconds, notes) })
        inputForTake = state.value.canEvaluate && state.value.mode != PracticeModeUi.DEMO
        inputSource = if (state.value.simulatorEnabled) "测试键盘" else midi.state.value.connectedName ?: "无演奏输入"
        rawEvents.clear(); waitingCorrect = 0; waitingExtra = 0; waitingMistakes.clear()
        playedWallSeconds = 0.0; takeWallStart = System.currentTimeMillis(); takeMonotonicStart = System.nanoTime(); lastResumeNs = takeMonotonicStart
        pausedPlanSeconds = 0.0; waitingReady = false; takeActive = true
        waitingRestRemaining = 0.0; waitingRestStartNs = 0L; finishAfterRest = false
        val plan = t.plan(state.value.metronome, state.value.demoSound || state.value.mode == PracticeModeUi.DEMO, state.value.mode == PracticeModeUi.WAIT)
        // Keep accepting the final onset through its full matching window, including calibration
        // and a small callback-delivery grace. The cursor stays at B during this silent tail.
        val endWithInputWindow = if (inputForTake && state.value.mode != PracticeModeUi.WAIT) {
            t.planSecondsAt(t.targets.last().onsetSeconds) + 0.35 + max(0, state.value.inputOffsetMs) / 1000.0 + MIDI_DELIVERY_GRACE
        } else plan.durationSeconds
        currentPlan = plan.copy(durationSeconds = max(plan.durationSeconds, endWithInputWindow))
        mutableState.update { it.copy(running = true, summary = null, transportLabel = if (t.countInSeconds > 0) "预备拍" else "练习中",
            feedback = if (inputForTake) "已启用音符与起音检测" else "跟随谱面练习，本轮记录用时", positionSeconds = 0.0, totalSeconds = t.musicSeconds) }
        audio.start(currentPlan!!)
    }

    private fun pause() {
        cancelLoop()
        if (!takeActive || !state.value.running) return
        val now = System.nanoTime()
        playedWallSeconds += (now - lastResumeNs) / 1e9
        if (waitingJob?.isActive == true) waitingRestRemaining = max(0.0, waitingRestRemaining - (now - waitingRestStartNs) / 1e9)
        waitingRestStartNs = 0L
        mutableState.update { it.copy(running = false, transportLabel = "已暂停") }
        audio.pause(); pausedPlanSeconds = audio.state.value.seconds
        waitingJob?.cancel(); waitingJob = null
        focus.release()
    }

    private fun resume() {
        if (timeline == null || !takeActive) return
        if (!focus.acquire()) { mutableState.update { it.copy(message = "当前音频正被其他应用占用，请稍后继续。") }; return }
        lastResumeNs = System.nanoTime()
        mutableState.update { it.copy(running = true, transportLabel = if (state.value.mode == PracticeModeUi.WAIT) "等待当前音符" else "练习中") }
        if (state.value.mode == PracticeModeUi.WAIT && waitingReady) {
            waitRestThen(waitingRestRemaining, finishAfterRest)
        } else currentPlan?.let { audio.start(it, pausedPlanSeconds) }
    }

    private fun updatePosition(position: AudioPosition) {
        val t = timeline ?: return
        if (!takeActive || !state.value.running || position.timestampNanos < takeMonotonicStart) return
        if (state.value.mode == PracticeModeUi.WAIT) {
            if (!waitingReady && !position.running && position.seconds >= max(0.02, t.countInSeconds) - 0.005) {
                waitingReady = true
                val initialRest = max(0.0, (waiting?.currentGroup?.onsetSeconds ?: t.startScoreSeconds) - t.startScoreSeconds) / t.speed
                waitRestThen(initialRest)
            }
            return
        }
        val qn = t.qnAt(position.seconds)
        mutableRender.update { it.copy(positionQn = qn) }
        val measureIndex = t.score.measures.indexOfLast { it.startQn <= qn + 1e-7 }.coerceIn(t.startIndex, t.endIndex)
        mutableState.update { it.copy(currentMeasure = measureIndex + 1, currentBeat = t.beatAt(measureIndex, qn),
            positionSeconds = (position.seconds - t.countInSeconds).coerceIn(0.0, t.musicSeconds),
            transportLabel = when { position.seconds < t.countInSeconds -> "预备拍"; position.seconds >= t.durationSeconds -> "判分收尾"; else -> "练习中" }) }
        if (inputForTake && position.seconds >= t.countInSeconds) {
            val misses = evaluator?.advance(t.scoreSecondsAt(position.seconds) - (state.value.inputOffsetMs / 1000.0 + MIDI_DELIVERY_GRACE) * t.speed).orEmpty()
            if (misses.isNotEmpty() && state.value.mode != PracticeModeUi.ASSESS) mutableState.update { it.copy(feedback = "漏音 ${misses.size} 个 · 练习后可重练对应小节") }
        }
        if (!position.running && position.seconds >= (currentPlan?.durationSeconds ?: t.durationSeconds) - 0.005) finishTake(true, "完成", !state.value.loop)
    }

    private fun receiveInput(kind: MidiEventKind, pitch: Int, velocity: Int, timestampNs: Long, channel: Int = 0, value: Int = 0) {
        mutableState.update { it.copy(activeKeys = when (kind) { MidiEventKind.NOTE_ON -> it.activeKeys + pitch; MidiEventKind.NOTE_OFF -> it.activeKeys - pitch; else -> it.activeKeys }) }
        if (kind == MidiEventKind.NOTE_OFF) waiting?.noteOff(pitch)
        if (!takeActive || !state.value.running || !inputForTake || timestampNs < lastResumeNs) return
        val t = timeline ?: return
        val planSeconds = audio.positionAt(timestampNs) ?: audio.state.value.seconds
        val scoreSeconds = if (state.value.mode == PracticeModeUi.WAIT) waiting?.currentGroup?.onsetSeconds ?: t.endScoreSeconds
            else t.scoreSecondsAt(planSeconds) - state.value.inputOffsetMs / 1000.0 * t.speed
        if (rawEvents.size >= 100000) {
            finishTake(false, "输入记录已达上限", true)
            mutableState.update { it.copy(message = "本轮已保存 100000 条输入记录，请开始新一轮练习。") }; return
        }
        rawEvents += RecordedInput(kind.name, pitch, velocity, scoreSeconds, timestampNs,
            playedWallSeconds + (timestampNs - lastResumeNs).coerceAtLeast(0) / 1e9, channel, value)
        if (kind != MidiEventKind.NOTE_ON) return
        if (state.value.mode == PracticeModeUi.WAIT) {
            if (!waitingReady || waitingJob?.isActive == true) return
            val matcher = waiting ?: return
            val previous = matcher.currentGroup
            val result = matcher.noteOn(pitch, timestampNs / 1e9)
            if (result.accepted) {
                waitingCorrect++
                mutableState.update { it.copy(feedback = "音符已匹配，继续当前音组") }
            } else {
                waitingExtra++
                previous?.notes?.firstOrNull()?.measureOccurrenceIndex?.let { waitingMistakes += it + 1 }
                mutableState.update { it.copy(feedback = "请检查当前目标音，收到 MIDI $pitch") }
            }
            if (result.complete) {
                val lastSound = t.targets.maxOfOrNull { it.endSeconds } ?: t.endScoreSeconds
                waitRestThen(max(0.0, t.endScoreSeconds - lastSound) / t.speed, finish = true)
            }
            else if (result.groupCompleted) {
                val previousEnd = previous?.notes?.maxOfOrNull { it.endSeconds } ?: 0.0
                val silence = max(0.0, (matcher.currentGroup?.onsetSeconds ?: previousEnd) - previousEnd) / t.speed
                waitRestThen(silence)
            }
            return
        }
        if (scoreSeconds < t.startScoreSeconds - 0.35 * t.speed) return
        val event = evaluator?.onNoteOn(pitch, scoreSeconds) ?: return
        if (state.value.mode != PracticeModeUi.ASSESS) mutableState.update { it.copy(feedback = when (event.kind) {
            EvaluationKind.CORRECT -> "音符匹配 · 起音偏差 ${event.deltaMs?.toInt() ?: 0} ms"
            EvaluationKind.EARLY -> "提前 ${abs(event.deltaMs ?: 0.0).toInt()} ms"
            EvaluationKind.LATE -> "延后 ${abs(event.deltaMs ?: 0.0).toInt()} ms"
            EvaluationKind.EXTRA -> "额外音符 MIDI $pitch · 练习后可回看"
            EvaluationKind.MISSED -> "漏音 · 练习后可回看"
        }) }
    }

    private fun waitRestThen(seconds: Double, finish: Boolean = false) {
        waitingJob?.cancel()
        waitingRestRemaining = seconds
        finishAfterRest = finish
        fun advance() {
            waitingRestRemaining = 0.0; waitingRestStartNs = 0L; waitingJob = null
            if (takeActive && state.value.running) {
                if (finishAfterRest) finishTake(true, "完成", !state.value.loop) else updateWaitingCursor()
            }
        }
        if (seconds <= 0.02) { advance(); return }
        mutableState.update { it.copy(transportLabel = "休止 ${"%.1f".format(seconds)} 秒") }
        waitingRestStartNs = System.nanoTime()
        waitingJob = viewModelScope.launch { delay((seconds * 1000).toLong()); advance() }
    }

    private fun updateWaitingCursor() {
        val group = waiting?.currentGroup ?: return
        val t = timeline ?: return
        mutableRender.update { it.copy(positionQn = group.onsetQn) }
        val index = group.notes.first().measureOccurrenceIndex
        mutableState.update { it.copy(currentMeasure = index + 1, currentBeat = t.beatAt(index, group.onsetQn),
            positionSeconds = (group.onsetSeconds - t.startScoreSeconds) / t.speed, transportLabel = "等待当前音符",
            feedback = "目标音：${group.notes.map { n -> n.midiPitch }.joinToString("、")}") }
    }

    private fun cancelLoop() { loopJob?.cancel(); loopJob = null }
    private fun finishIfActive(outcome: String) { cancelLoop(); if (takeActive) finishTake(false, outcome, false) }

    private fun finishTake(completed: Boolean, outcome: String, showSummary: Boolean) {
        cancelLoop()
        if (!takeActive) { audio.stop(); focus.release(); mutableState.update { it.copy(running = false) }; return }
        val score = selected ?: return
        val s = state.value
        if (s.running) playedWallSeconds += (System.nanoTime() - lastResumeNs) / 1e9
        takeActive = false; waitingJob?.cancel(); waitingJob = null; audio.stop(); focus.release()
        val waitMode = s.mode == PracticeModeUi.WAIT
        val evaluated = inputForTake
        val summary = if (!evaluated || waitMode) null else if (completed) evaluator?.finish() else evaluator?.summary()
        val events = if (evaluated && !waitMode) evaluator?.events.orEmpty() else emptyList()
        val targetsById = evaluator?.targets.orEmpty().associateBy { it.id }
        val difficulties = if (!evaluated) emptyList() else if (waitMode) waitingMistakes.toList().sorted() else events.filter { it.kind != EvaluationKind.CORRECT }
            .map { e -> targetsById[e.noteId]?.measureOccurrenceIndex?.plus(1)
                ?: (score.compiled.measures.indexOfLast { it.startSeconds <= e.timeSeconds }.coerceIn(s.startMeasure - 1, s.endMeasure - 1) + 1) }
            .distinct().sorted()
        val hits = if (waitMode) waiting?.let { matcher -> matcher.groups.take(matcher.index).sumOf { it.notes.size } } ?: 0 else summary?.matched ?: 0
        val misses = if (waitMode) 0 else summary?.missed ?: 0
        val extras = if (waitMode) waitingExtra else summary?.extra ?: 0
        val mean = if (waitMode || !evaluated || hits == 0) null else summary?.meanAbsoluteErrorMs
        val result = EvaluationSummaryUi(score.compiled.document.metadata.title, s.mode.label, inputSource, playedWallSeconds, evaluated, outcome,
            if (evaluated) hits else 0, if (evaluated) misses else 0, if (evaluated) extras else 0,
            if (evaluated && !waitMode) summary?.early ?: 0 else 0, if (evaluated && !waitMode) summary?.late ?: 0 else 0, mean, difficulties)
        val record = SessionRecord(UUID.randomUUID().toString(), score.id, result.title, LocalStore.sha256(score.json), takeWallStart, s.mode.name, s.hands.name,
            s.bpm, s.startMeasure, s.endMeasure, inputSource, s.inputOffsetMs, playedWallSeconds, evaluated, outcome,
            result.hits, result.misses, result.extras, result.early, result.late, mean, difficulties, rawEvents.toList(), events)
        if (completed) timeline?.let { t ->
            mutableRender.update { it.copy(positionQn = t.endQn) }
            mutableState.update { it.copy(currentMeasure = t.endIndex + 1, positionSeconds = t.musicSeconds) }
        }
        mutableState.update { it.copy(running = false, transportLabel = outcome, summary = if (showSummary) result else null,
            feedback = if (completed) "本轮已完成，可查看记录或重练。" else "本轮已保存：$outcome") }
        viewModelScope.launch { runCatching { withContext(Dispatchers.IO) { store.saveSession(record) }; refreshHistory() }
            .onFailure { e -> mutableState.update { it.copy(error = "记录保存失败：${e.message}") } } }
        if (completed && s.loop) {
            mutableState.update { it.copy(iteration = it.iteration + 1) }
            loopJob = viewModelScope.launch { delay(300); loopJob = null
                if (!takeActive && state.value.loop && state.value.screen == AppScreen.PRACTICE) startTake() }
        }
    }

    private suspend fun refreshHistory() {
        val records = withContext(Dispatchers.IO) { store.loadSessions() }
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        mutableState.update { it.copy(history = records.map { r -> SessionItemUi(r.id, r.scoreTitle, formatter.format(Date(r.startedAt)),
            PracticeModeUi.entries.firstOrNull { mode -> mode.name == r.mode }?.label ?: r.mode, r.bpm, r.durationSeconds, r.evaluated,
            r.hits, r.misses, r.extras, r.meanAbsoluteTimingMs, r.outcome) }) }
    }

    private fun scoreUi(score: StoredScore): ScoreItemUi = score.compiled.let { c -> ScoreItemUi(score.id, c.document.metadata.title,
        "${c.measures.size} 演奏小节 · ${c.notes.size} 起音${if (c.warnings.isNotEmpty()) " · 含校对提示" else ""}", c.document.metadata.instrument,
        c.measures.size, c.tempoMap.points.first().bpm.toInt(), c.notes.size, score.builtIn) }

    fun onBackground() = pause()
    fun rendererError(message: String) { pause(); mutableState.update { it.copy(error = "谱面渲染失败：$message") } }
    override fun onCleared() { cancelLoop(); waitingJob?.cancel(); focus.release(); audio.close(); midi.close(); super.onCleared() }

    companion object { private const val MIDI_DELIVERY_GRACE = 0.08 }
}
