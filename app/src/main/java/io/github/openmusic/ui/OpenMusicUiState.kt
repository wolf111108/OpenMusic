package io.github.openmusic.ui

/** UI projections deliberately carry no Android, storage or score-engine dependencies. */
enum class AppScreen { TODAY, LIBRARY, TRAINING, HISTORY, DEVICES, SETTINGS, PRACTICE }
enum class PracticeModeUi(val label: String) {
    FOLLOW("跟拍练习"), WAIT("弹对前进"), ASSESS("测评"), DEMO("示范")
}
enum class NotationUi(val label: String) { STAFF("五线谱"), NUMBERED("简谱") }
enum class HandsUi(val label: String) { BOTH("双手"), RIGHT("右手"), LEFT("左手") }

data class ScoreItemUi(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val instrument: String = "钢琴",
    val measureCount: Int = 1,
    val originalBpm: Int = 80,
    val noteCount: Int = 0,
    val builtIn: Boolean = false,
)

data class MidiDeviceItemUi(val id: Int, val name: String)

data class SessionItemUi(
    val id: String,
    val title: String,
    val timestampLabel: String,
    val modeLabel: String,
    val bpm: Int,
    val durationSeconds: Double,
    val evaluated: Boolean,
    val hits: Int = 0,
    val misses: Int = 0,
    val extras: Int = 0,
    val meanAbsoluteTimingMs: Double? = null,
    val outcomeLabel: String = "已结束",
)

data class EvaluationSummaryUi(
    val title: String,
    val modeLabel: String,
    val sourceLabel: String,
    val durationSeconds: Double,
    val evaluated: Boolean,
    val outcomeLabel: String = "本轮完成",
    val hits: Int = 0,
    val misses: Int = 0,
    val extras: Int = 0,
    val early: Int = 0,
    val late: Int = 0,
    val meanAbsoluteTimingMs: Double? = null,
    /** One-based indexes of expanded playback measures, as used by the A–B controls. */
    val difficultMeasures: List<Int> = emptyList(),
)

data class OpenMusicUiState(
    val screen: AppScreen = AppScreen.TODAY,
    val library: List<ScoreItemUi> = emptyList(),
    val selectedScore: ScoreItemUi? = null,
    val mode: PracticeModeUi = PracticeModeUi.FOLLOW,
    val notation: NotationUi = NotationUi.STAFF,
    val hands: HandsUi = HandsUi.BOTH,
    val bpm: Int = 80,
    val startMeasure: Int = 1,
    val endMeasure: Int = 1,
    val loop: Boolean = false,
    val countIn: Int = 1,
    val metronome: Boolean = true,
    val demoSound: Boolean = false,
    val running: Boolean = false,
    val transportLabel: String = "准备练习",
    val currentMeasure: Int = 1,
    val currentBeat: Double = 1.0,
    val positionSeconds: Double = 0.0,
    val totalSeconds: Double = 0.0,
    val iteration: Int = 1,
    val inputLabel: String = "未接入演奏输入",
    val canEvaluate: Boolean = false,
    val midiSupported: Boolean = true,
    val midiDevices: List<MidiDeviceItemUi> = emptyList(),
    val connectedDeviceId: Int? = null,
    val midiStatus: String = "尚未连接 MIDI",
    val midiLog: List<String> = emptyList(),
    val activeKeys: Set<Int> = emptySet(),
    val feedback: String = "选择片段与速度，从容开始。",
    val history: List<SessionItemUi> = emptyList(),
    val summary: EvaluationSummaryUi? = null,
    val inputOffsetMs: Int = 0,
    val simulatorEnabled: Boolean = false,
    val simulatorOctave: Int = 4,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

sealed interface UiAction {
    data class Navigate(val screen: AppScreen) : UiAction
    data object ImportScore : UiAction
    data class SelectScore(val id: String) : UiAction
    data class DeleteScore(val id: String) : UiAction
    data object ExportExample : UiAction
    data object ExportHistory : UiAction
    data object ExportMidiLog : UiAction
    data class SetMode(val mode: PracticeModeUi) : UiAction
    data class SetNotation(val notation: NotationUi) : UiAction
    data class SetHands(val hands: HandsUi) : UiAction
    data class SetBpm(val bpm: Int) : UiAction
    /** Start and end are inclusive, one-based indexes of expanded playback measures. */
    data class SetRange(val start: Int, val end: Int) : UiAction
    data class SetLoop(val enabled: Boolean) : UiAction
    data class SetCountIn(val bars: Int) : UiAction
    data class SetMetronome(val enabled: Boolean) : UiAction
    data class SetDemoSound(val enabled: Boolean) : UiAction
    data object PlayPause : UiAction
    data object Restart : UiAction
    data object Stop : UiAction
    data class SeekMeasure(val measure: Int) : UiAction
    data object RepeatMistakes : UiAction
    data object RefreshMidi : UiAction
    data class ConnectMidi(val deviceId: Int) : UiAction
    data object DisconnectMidi : UiAction
    data class SetInputOffset(val milliseconds: Int) : UiAction
    data class SetSimulatorEnabled(val enabled: Boolean) : UiAction
    data class SetSimulatorOctave(val octave: Int) : UiAction
    data class SimulatedNote(val pitch: Int, val isOn: Boolean) : UiAction
    data object DismissMessage : UiAction
    data object DismissError : UiAction
    data object DismissSummary : UiAction
}
