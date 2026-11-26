@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.openmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/** Application shell. The host supplies the real score renderer through [scorePanel]. */
@Composable
fun OpenMusicApp(
    state: OpenMusicUiState,
    onAction: (UiAction) -> Unit,
    scorePanel: @Composable (Modifier) -> Unit,
) {
    OpenMusicTheme {
        BackHandler(enabled = state.screen != AppScreen.TODAY && state.summary == null && state.error == null) {
            onAction(UiAction.Navigate(if (state.screen == AppScreen.PRACTICE) AppScreen.LIBRARY else AppScreen.TODAY))
        }
        val snackbars = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.let { message ->
                snackbars.showSnackbar(message)
                onAction(UiAction.DismissMessage)
            }
        }
        val inPractice = state.screen == AppScreen.PRACTICE
        Scaffold(
            containerColor = PageBackground,
            contentWindowInsets = if (inPractice) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            snackbarHost = { SnackbarHost(snackbars) },
            topBar = {
                if (!inPractice) AppHeader(state, onAction)
            },
            bottomBar = {
                if (!inPractice) MainNavigation(state.screen, onAction)
            },
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                when (state.screen) {
                    AppScreen.TODAY -> TodayPage(state, onAction)
                    AppScreen.LIBRARY -> LibraryPage(state, onAction)
                    AppScreen.TRAINING -> TrainingPage(state, onAction)
                    AppScreen.HISTORY -> HistoryPage(state, onAction)
                    AppScreen.DEVICES -> DevicesPage(state, onAction)
                    AppScreen.SETTINGS -> SettingsPage(state, onAction)
                    AppScreen.PRACTICE -> PracticePage(state, onAction, scorePanel)
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
        state.error?.let { error ->
            AlertDialog(
                onDismissRequest = { onAction(UiAction.DismissError) },
                title = { Text("请检查导入或设备状态") },
                text = { Text(error, modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = {
                    TextButton(onClick = { onAction(UiAction.DismissError) }) { Text("知道了") }
                },
            )
        }
        state.summary?.let { SummaryDialog(it, onAction) }
    }
}

@Composable
private fun AppHeader(state: OpenMusicUiState, onAction: (UiAction) -> Unit) {
    Surface(color = PageBackground) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = PracticeBlue, shape = RoundedCornerShape(12.dp)) {
                Text("♪", color = Color.White, fontSize = 27.sp,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("OpenMusic", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("自己的练习室", color = MutedInk, fontSize = 12.sp)
            }
            TextButton(onClick = { onAction(UiAction.Navigate(AppScreen.DEVICES)) }) {
                Text(if (state.connectedDeviceId != null) "MIDI 已连接" else "设备")
            }
            TextButton(onClick = { onAction(UiAction.Navigate(AppScreen.SETTINGS)) }) { Text("设置") }
        }
    }
}

@Composable
private fun MainNavigation(screen: AppScreen, onAction: (UiAction) -> Unit) {
    val entries = listOf(
        Triple(AppScreen.TODAY, "今日", "练"),
        Triple(AppScreen.LIBRARY, "曲库", "谱"),
        Triple(AppScreen.TRAINING, "训练", "习"),
        Triple(AppScreen.HISTORY, "记录", "记"),
    )
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
        entries.forEach { (destination, label, icon) ->
            NavigationBarItem(
                selected = screen == destination,
                onClick = { onAction(UiAction.Navigate(destination)) },
                icon = { Text(icon, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun TodayPage(state: OpenMusicUiState, onAction: (UiAction) -> Unit) {
    val current = state.selectedScore ?: state.library.firstOrNull()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageTitle("从一个乐句开始", "放慢速度，把注意力留给声音。") }
        item {
            Surface(color = SoftBlue, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (current != null) "继续练习" else "准备第一份曲谱", color = PracticeBlue,
                        style = MaterialTheme.typography.labelLarge)
                    Text(current?.title ?: "导入自己的练习曲", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        current?.let { "${it.measureCount} 个演奏小节 · ${state.bpm} BPM · ${state.mode.label}" }
                            ?: "使用标准 ScoreIR 曲谱，也可以从内置短谱开始。",
                        color = MutedInk,
                    )
                    Button(
                        onClick = {
                            if (state.selectedScore != null) onAction(UiAction.Navigate(AppScreen.PRACTICE))
                            else if (current != null) onAction(UiAction.SelectScore(current.id))
                            else onAction(UiAction.ImportScore)
                        },
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 13.dp),
                    ) { Text(if (current != null) "进入练习" else "导入 ScoreIR") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile("已保存练习", "${state.history.size}", "次", Modifier.weight(1f))
                MetricTile("累计练习", formatMinutes(state.history.sumOf { it.durationSeconds }), "分钟", Modifier.weight(1f))
                MetricTile("我的曲库", "${state.library.size}", "份", Modifier.weight(1f))
            }
        }
        item {
            InfoCard(
                title = state.inputLabel,
                detail = if (state.canEvaluate) "演奏输入已启用，跟拍和测评可记录音符与起音偏差。"
                else "当前记录练习时长。连接电钢琴后可启用自动反馈。",
                accent = state.canEvaluate,
            ) {
                TextButton(onClick = { onAction(UiAction.Navigate(AppScreen.DEVICES)) }) { Text("检查设备") }
            }
        }
        item { SectionTitle("开始之前") }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HelpLine("01", "选一个短片段", "用 A–B 循环集中练习一段乐句。")
                    HelpLine("02", "从舒适的速度开始", "分手练习后，再把两个声部合在一起。")
                    HelpLine("03", "看具体问题", "练习结束后查看错音和节奏，再回到相应小节。")
                }
            }
        }
    }
}

@Composable
private fun LibraryPage(state: OpenMusicUiState, onAction: (UiAction) -> Unit) {
    var toDelete by remember { mutableStateOf<ScoreItemUi?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { PageTitle("我的曲库", "标准化曲谱，在手机上离线练习。") }
                Button(onClick = { onAction(UiAction.ImportScore) }, enabled = !state.busy) { Text("＋ 导入") }
            }
        }
        item {
            InfoCard("导入 ScoreIR", "支持 JSON / .scoreir，以及含 score.json 的 .scorepack。导入时检查版本、音高、时值和结构。") {
                TextButton(onClick = { onAction(UiAction.ExportExample) }) { Text("导出格式示例") }
            }
        }
        if (state.library.isEmpty()) {
            item { EmptyCard("曲库准备好了", "导入一份 ScoreIR，或等待内置练习载入。") }
        }
        items(state.library, key = { it.id }) { score ->
            ScoreCard(score, selected = score.id == state.selectedScore?.id,
                onOpen = { onAction(UiAction.SelectScore(score.id)) },
                onDelete = if (score.builtIn) null else { { toDelete = score } })
        }
    }
    toDelete?.let { score ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("移除这份曲谱") },
            text = { Text("将从曲库移除“${score.title}”。原始导入文件仍由你保存。") },
            confirmButton = { TextButton(onClick = {
                onAction(UiAction.DeleteScore(score.id)); toDelete = null
            }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("保留") } },
        )
    }
}

@Composable
private fun TrainingPage(state: OpenMusicUiState, onAction: (UiAction) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageTitle("每次聚焦一个目标", "内置短谱与自己的曲谱共用同一套练习工具。") }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HelpLine("拍", "节奏与均匀度", "使用跟拍练习，留意休止符和每次起音。")
                    HelpLine("音", "识谱与找键", "连接 MIDI 后切换“弹对前进”，逐组确认音符。")
                    HelpLine("手", "分手与协调", "选择左手或右手，再通过双手模式合练。")
                }
            }
        }
        item { SectionTitle("内置练习") }
        val builtIns = state.library.filter { it.builtIn }
        if (builtIns.isEmpty()) {
            item { EmptyCard("准备练习素材", "内置短谱载入后会显示在这里，也可以从曲库选择自己的曲子。") }
        }
        items(builtIns, key = { it.id }) { score ->
            ScoreCard(score, selected = false, onOpen = { onAction(UiAction.SelectScore(score.id)) })
        }
        item {
            InfoCard("一种实用的练习顺序", "先听示范，再慢速跟拍；遇到困难就缩短片段、分手练习。每次完成后查看具体小节。")
        }
    }
}

@Composable
private fun HistoryPage(state: OpenMusicUiState, onAction: (UiAction) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { PageTitle("练习记录", "保留速度、模式和输入条件，理解每一次练习。") }
                OutlinedButton(onClick = { onAction(UiAction.ExportHistory) }, enabled = state.history.isNotEmpty()) { Text("导出") }
            }
        }
        if (state.history.isEmpty()) {
            item { EmptyCard("从第一次练习开始", "完成或结束一轮练习后，这里会保存时长与实际采集到的结果。") }
        }
        items(state.history, key = { it.id }) { session ->
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(session.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        StatusPill(session.outcomeLabel)
                    }
                    Text("${session.timestampLabel} · ${session.modeLabel} · ${session.bpm} BPM · ${formatDuration(session.durationSeconds)}", color = MutedInk, fontSize = 13.sp)
                    if (session.evaluated) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("命中 ${session.hits}", color = PracticeGreen)
                            Text("漏音 ${session.misses}", color = PracticeAmber)
                            Text("多弹 ${session.extras}", color = MutedInk)
                            session.meanAbsoluteTimingMs?.let { Text("平均偏差 ${it.roundToInt()} ms", color = MutedInk) }
                        }
                    } else {
                        Text("本次仅记录练习时长 · 自动判分未启用", color = MutedInk, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DevicesPage(state: OpenMusicUiState, onAction: (UiAction) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageTitle("连接你的电钢琴", "Yamaha P-45 · USB MIDI") }
        item {
            InfoCard("连接方式", "P-45 USB TO HOST（USB-B）→ 数据线 → 手机／平板 USB-C。REDMI K Pad 优先使用短边 HOST 接口。琴声可继续由钢琴自身播放。")
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusPill(state.midiStatus, positive = state.connectedDeviceId != null)
                    if (!state.midiSupported) {
                        Text("当前系统未声明 MIDI 支持。跟谱、示范和手动练习仍可使用。", color = PracticeAmber)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onAction(UiAction.RefreshMidi) }, enabled = state.midiSupported) { Text("刷新设备") }
                        OutlinedButton(onClick = { onAction(UiAction.DisconnectMidi) }, enabled = state.connectedDeviceId != null) { Text("断开") }
                    }
                }
            }
        }
        if (state.midiDevices.isEmpty()) {
            item { EmptyCard("等待 MIDI 设备", "连接数据线，打开电钢琴电源，再点击刷新。设备出现后点击连接。") }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToggleRow("测试键盘", "用屏幕按键验证输入；开启后使用模拟输入源。", state.simulatorEnabled) {
                        onAction(UiAction.SetSimulatorEnabled(it))
                    }
                    if (state.simulatorEnabled) SimulatorKeyboard(state, onAction)
                }
            }
        }
        items(state.midiDevices, key = { it.id }) { device ->
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, fontWeight = FontWeight.SemiBold)
                        Text("MIDI 设备 ${device.id}", color = MutedInk, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { onAction(UiAction.ConnectMidi(device.id)) }, enabled = state.connectedDeviceId != device.id) {
                        Text(if (state.connectedDeviceId == device.id) "已连接" else "连接")
                    }
                }
            }
        }
        item {
            InfoCard("连接后按下任意琴键", "检查音名、力度、松键和踏板事件。判分精度以这套手机、钢琴和输出设备的实测为准。") {
                TextButton(onClick = { onAction(UiAction.Navigate(AppScreen.SETTINGS)) }) { Text("输入偏移设置") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最近 MIDI 事件", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onAction(UiAction.ExportMidiLog) }, enabled = state.midiLog.isNotEmpty()) { Text("导出日志") }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = Ink) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (state.midiLog.isEmpty()) Text("等待琴键事件…", color = Color(0xFFC1CDE0), fontFamily = FontFamily.Monospace)
                    state.midiLog.takeLast(32).asReversed().forEach { line ->
                        Text(line, color = Color(0xFFE1EBF8), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(state: OpenMusicUiState, onAction: (UiAction) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageTitle("练习设置", "数据保存在本机，按自己的设备调整。") }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("输入时间偏移")
                    Text("${state.inputOffsetMs} ms", color = PracticeBlue, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                    Text("判定采用：输入时间 − 预期时间 − 此偏移。正值用于补偿整体偏晚的输入。", color = MutedInk)
                    Slider(
                        value = state.inputOffsetMs.toFloat().coerceIn(-500f, 500f),
                        onValueChange = { onAction(UiAction.SetInputOffset((it / 5).roundToInt() * 5)) },
                        valueRange = -500f..500f, steps = 199, enabled = !state.running,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onAction(UiAction.SetInputOffset((state.inputOffsetMs - 5).coerceAtLeast(-500))) }, enabled = !state.running) { Text("−5 ms") }
                        OutlinedButton(onClick = { onAction(UiAction.SetInputOffset(0)) }, enabled = !state.running) { Text("归零") }
                        OutlinedButton(onClick = { onAction(UiAction.SetInputOffset((state.inputOffsetMs + 5).coerceAtMost(500))) }, enabled = !state.running) { Text("＋5 ms") }
                    }
                    Text("人工跟拍含个人反应偏差；更换输出设备后请重新检查。", color = MutedInk, fontSize = 12.sp)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("导出与接口")
                    Text("未来的识谱工具只需输出标准 ScoreIR。格式校验和练习在本机完成。", color = MutedInk)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onAction(UiAction.ExportExample) }) { Text("导出 ScoreIR 示例") }
                        OutlinedButton(onClick = { onAction(UiAction.ExportHistory) }, enabled = state.history.isNotEmpty()) { Text("导出练习记录") }
                        OutlinedButton(onClick = { onAction(UiAction.ExportMidiLog) }, enabled = state.midiLog.isNotEmpty()) { Text("导出 MIDI 日志") }
                    }
                }
            }
        }
        item {
            InfoCard("关于 OpenMusic", "个人乐器练习工具 · ScoreIR 导入、曲谱、节拍与 MIDI 反馈。麦克风识谱和 PDF／图片转谱接口留待后续实现。")
        }
    }
}

@Composable
private fun PracticePage(
    state: OpenMusicUiState,
    onAction: (UiAction) -> Unit,
    scorePanel: @Composable (Modifier) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showSeek by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 600.dp
        val narrow = maxWidth < 700.dp
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 4.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAction(UiAction.Navigate(AppScreen.TODAY)) }) { Text("‹ 返回") }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(state.selectedScore?.title ?: "选择一份曲谱", fontSize = if (compact) 17.sp else 21.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!compact) Text(state.transportLabel, color = MutedInk, fontSize = 12.sp)
                }
                StatusPill(if (state.canEvaluate) state.inputLabel else "仅跟谱", positive = state.canEvaluate)
            }
            if (state.selectedScore == null) {
                EmptyCard("还没有选择曲谱", "从曲库选择一首曲子，或导入 ScoreIR。")
                Button(onClick = { onAction(UiAction.Navigate(AppScreen.LIBRARY)) }) { Text("前往曲库") }
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PracticeModeUi.entries.forEach { mode ->
                        FilterChip(selected = state.mode == mode, onClick = { onAction(UiAction.SetMode(mode)) },
                            label = { Text(mode.label) }, enabled = !state.running)
                    }
                    Spacer(Modifier.width(8.dp))
                    NotationUi.entries.forEach { notation ->
                        FilterChip(selected = state.notation == notation,
                            onClick = { onAction(UiAction.SetNotation(notation)) }, label = { Text(notation.label) })
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepButton("−", "降低速度", enabled = !state.running && state.bpm > 30) { onAction(UiAction.SetBpm((state.bpm - 5).coerceAtLeast(30))) }
                    Text("${state.bpm}", fontWeight = FontWeight.Bold, fontSize = 23.sp, color = Ink)
                    Text("BPM", fontSize = 11.sp, color = MutedInk)
                    StepButton("＋", "提高速度", enabled = !state.running && state.bpm < 240) { onAction(UiAction.SetBpm((state.bpm + 5).coerceAtMost(240))) }
                    Text("·", color = MutedInk)
                    HandsUi.entries.forEach { hands ->
                        FilterChip(selected = state.hands == hands, onClick = { onAction(UiAction.SetHands(hands)) },
                            label = { Text(hands.label) }, enabled = !state.running)
                    }
                    OutlinedButton(onClick = { showSettings = true }) { Text("${state.startMeasure}–${state.endMeasure} 小节 · 设置") }
                    if (state.loop) StatusPill("循环 ${state.iteration}")
                }
                Surface(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    shape = RoundedCornerShape(if (compact) 12.dp else 20.dp),
                    color = Color.White, border = BorderStroke(1.dp, Color(0xFFE0E8F3)),
                ) {
                    scorePanel(Modifier.fillMaxSize())
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (state.canEvaluate) SoftBlue else Color(0xFFEAF0F7))
                    .padding(horizontal = 12.dp, vertical = if (compact) 5.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val feedback = if (state.mode == PracticeModeUi.WAIT && !state.canEvaluate)
                        "弹对前进需要输入：连接 MIDI，或在设置中开启测试键盘。" else state.feedback
                    Text(feedback, color = Ink, fontSize = if (compact) 12.sp else 14.sp,
                        maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!narrow) Text(state.transportLabel, color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
                }
                if (state.simulatorEnabled) SimulatorKeyboard(state, onAction)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
                    Button(onClick = { onAction(UiAction.PlayPause) },
                        enabled = !state.busy && (state.mode != PracticeModeUi.WAIT || state.canEvaluate),
                        contentPadding = PaddingValues(horizontal = if (narrow) 18.dp else 28.dp, vertical = 12.dp)) {
                        Text(if (state.running) "Ⅱ  暂停" else "▶  开始")
                    }
                    OutlinedButton(onClick = { onAction(UiAction.Restart) }) { Text("重来") }
                    Column(Modifier.weight(1f).clickable(enabled = !state.running) { showSeek = true }.padding(horizontal = 6.dp)) {
                        Text("第 ${state.currentMeasure} 小节 · ${formatBeat(state.currentBeat)} 拍", color = MutedInk,
                            fontSize = if (narrow) 11.sp else 13.sp, maxLines = 1)
                        LinearProgressIndicator(
                            progress = { if (state.totalSeconds > 0) (state.positionSeconds / state.totalSeconds).toFloat().coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(3.dp),
                        )
                    }
                    if (!narrow) Text("${formatDuration(state.positionSeconds)} / ${formatDuration(state.totalSeconds)}", color = MutedInk, fontSize = 12.sp)
                    TextButton(onClick = { onAction(UiAction.Stop) }) { Text("结束") }
                }
            }
        }
    }
    if (showSettings) PracticeSettingsDialog(state, onAction) { showSettings = false }
    if (showSeek) SeekDialog(state, onAction) { showSeek = false }
}

@Composable
private fun PracticeSettingsDialog(state: OpenMusicUiState, onAction: (UiAction) -> Unit, onDismiss: () -> Unit) {
    var from by remember(state.startMeasure) { mutableStateOf(state.startMeasure.toString()) }
    var to by remember(state.endMeasure) { mutableStateOf(state.endMeasure.toString()) }
    val maxMeasure = state.selectedScore?.measureCount ?: 1
    val start = from.toIntOrNull()
    val end = to.toIntOrNull()
    val valid = start != null && end != null && start in 1..maxMeasure && end in start..maxMeasure
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("片段与练习设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("按展开后的演奏小节选择片段，共 $maxMeasure 小节。", color = MutedInk, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = from, onValueChange = { if (it.length <= 6) from = it.filter(Char::isDigit) },
                        label = { Text("起点 A") }, singleLine = true, enabled = !state.running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = to, onValueChange = { if (it.length <= 6) to = it.filter(Char::isDigit) },
                        label = { Text("终点 B") }, singleLine = true, enabled = !state.running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                if (!valid) Text("请输入 1～$maxMeasure 范围内的起止小节。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                ToggleRow("循环这个片段", "每轮回到起点，并重新预备拍。", state.loop, !state.running) { onAction(UiAction.SetLoop(it)) }
                ToggleRow("节拍器", if (state.mode == PracticeModeUi.WAIT) "等待模式暂停持续节拍推进。" else "跟随同一播放时间轴。", state.metronome, !state.running) { onAction(UiAction.SetMetronome(it)) }
                ToggleRow("曲谱示范音", "练习时可关闭，保留电钢琴自身声音。", state.demoSound, !state.running) { onAction(UiAction.SetDemoSound(it)) }
                Text("预备拍", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..2).forEach { bars ->
                        FilterChip(selected = state.countIn == bars, onClick = { onAction(UiAction.SetCountIn(bars)) },
                            label = { Text(if (bars == 0) "关闭" else "$bars 小节") }, enabled = !state.running)
                    }
                }
                HorizontalDivider()
                ToggleRow("测试键盘", "屏幕按键为模拟输入，供功能验证。", state.simulatorEnabled, !state.running) { onAction(UiAction.SetSimulatorEnabled(it)) }
                if (state.running) Text("暂停后可修改片段、速度和练习参数。", color = PracticeAmber, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!state.running && valid) onAction(UiAction.SetRange(start!!, end!!))
                onDismiss()
            }, enabled = state.running || valid) { Text("完成") }
        },
    )
}

@Composable
private fun SeekDialog(state: OpenMusicUiState, onAction: (UiAction) -> Unit, onDismiss: () -> Unit) {
    var destination by remember { mutableStateOf(state.currentMeasure.toString()) }
    val number = destination.toIntOrNull()
    val valid = number != null && number in state.startMeasure..state.endMeasure
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定位小节") },
        text = {
            OutlinedTextField(value = destination, onValueChange = { if (it.length <= 6) destination = it.filter(Char::isDigit) },
                label = { Text("${state.startMeasure}～${state.endMeasure}") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = { TextButton(onClick = { number?.let { onAction(UiAction.SeekMeasure(it)) }; onDismiss() }, enabled = valid) { Text("定位") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SimulatorKeyboard(state: OpenMusicUiState, onAction: (UiAction) -> Unit) {
    val currentOnAction by rememberUpdatedState(onAction)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFE9EEF5)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("测试键盘 · 模拟输入", color = PracticeAmber, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { onAction(UiAction.SetSimulatorOctave((state.simulatorOctave - 1).coerceAtLeast(1))) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) { Text("低八度") }
            Text("C${state.simulatorOctave}", fontSize = 12.sp)
            TextButton(onClick = { onAction(UiAction.SetSimulatorOctave((state.simulatorOctave + 1).coerceAtMost(7))) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) { Text("高八度") }
        }
        val first = (state.simulatorOctave + 1) * 12
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (first..first + 12).forEach { pitch ->
                val black = pitch % 12 in listOf(1, 3, 6, 8, 10)
                val pressed = pitch in state.activeKeys
                Box(
                    Modifier.width(43.dp).height(42.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (pressed) PracticeBlue else if (black) Ink else Color.White)
                        .semantics {
                            contentDescription = "模拟琴键 ${noteName(pitch)}"
                            stateDescription = if (pressed) "按下" else "松开"
                        }
                        .pointerInput(pitch) {
                            detectTapGestures(onPress = {
                                currentOnAction(UiAction.SimulatedNote(pitch, true))
                                try { tryAwaitRelease() } finally { currentOnAction(UiAction.SimulatedNote(pitch, false)) }
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(noteName(pitch), color = if (black || pressed) Color.White else Ink, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun SummaryDialog(summary: EvaluationSummaryUi, onAction: (UiAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(UiAction.DismissSummary) },
        title = { Text(summary.outcomeLabel) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(summary.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("${summary.modeLabel} · ${summary.sourceLabel} · ${formatDuration(summary.durationSeconds)}", color = MutedInk)
                if (summary.evaluated) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile("命中", summary.hits.toString(), "音", Modifier.weight(1f))
                        MetricTile("漏音", summary.misses.toString(), "音", Modifier.weight(1f))
                        MetricTile("多弹", summary.extras.toString(), "音", Modifier.weight(1f))
                    }
                    summary.meanAbsoluteTimingMs?.let { deviation ->
                        Text("平均起音绝对偏差 ${deviation.roundToInt()} ms", fontWeight = FontWeight.SemiBold)
                        Text("提前 ${summary.early} 次 · 延后 ${summary.late} 次", color = MutedInk)
                    }
                    if (summary.difficultMeasures.isNotEmpty()) {
                        Text("值得再练的小节", fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            summary.difficultMeasures.take(24).forEach { measure ->
                                AssistChip(onClick = {
                                    onAction(UiAction.DismissSummary)
                                    onAction(UiAction.SeekMeasure(measure))
                                }, label = { Text("第 $measure 小节") })
                            }
                        }
                    }
                } else {
                    InfoCard("已记录练习时长", "本轮自动判分未启用。演奏反馈会在接入 MIDI 或明确开启测试输入后记录。")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (summary.evaluated && summary.difficultMeasures.isNotEmpty()) onAction(UiAction.RepeatMistakes)
                else { onAction(UiAction.DismissSummary); onAction(UiAction.Restart) }
            }) { Text(if (summary.evaluated && summary.difficultMeasures.isNotEmpty()) "重练错误片段" else "再练一次") }
        },
        dismissButton = { TextButton(onClick = { onAction(UiAction.DismissSummary) }) { Text("完成") } },
    )
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MutedInk, fontSize = 13.sp)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
}

@Composable
private fun StatusPill(label: String, positive: Boolean = false) {
    Surface(color = if (positive) SoftGreen else SoftBlue, shape = RoundedCornerShape(20.dp)) {
        Text(label, color = if (positive) PracticeGreen else PracticeBlue, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MetricTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = Color.White) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = MutedInk, fontSize = 11.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(unit, color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, detail: String, accent: Boolean = false, actions: (@Composable () -> Unit)? = null) {
    Surface(color = if (accent) SoftGreen else Color(0xFFEBF0F7), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (accent) PracticeGreen else Ink)
            Text(detail, color = MutedInk, fontSize = 13.sp, lineHeight = 20.sp)
            actions?.invoke()
        }
    }
}

@Composable
private fun EmptyCard(title: String, detail: String) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("♪", color = PracticeBlue, fontSize = 36.sp)
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(detail, color = MutedInk, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ScoreCard(score: ScoreItemUi, selected: Boolean, onOpen: () -> Unit, onDelete: (() -> Unit)? = null) {
    Surface(onClick = onOpen, color = Color.White, shape = RoundedCornerShape(20.dp),
        border = if (selected) BorderStroke(1.dp, PracticeBlue.copy(alpha = .4f)) else null) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = SoftBlue, shape = RoundedCornerShape(14.dp)) {
                Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) { Text("谱", color = PracticeBlue, fontSize = 23.sp) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(score.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (score.subtitle.isNotBlank()) Text(score.subtitle, color = MutedInk, fontSize = 12.sp, maxLines = 2)
                Text("${score.instrument} · ${score.measureCount} 小节 · ${score.originalBpm} BPM", color = MutedInk, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (score.builtIn) StatusPill("内置") else Text("ScoreIR", color = MutedInk, fontSize = 11.sp)
                if (onDelete != null) TextButton(onClick = onDelete) { Text("移除", fontSize = 12.sp) }
                else Text("进入 ›", color = PracticeBlue, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun HelpLine(symbol: String, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(color = SoftBlue, shape = CircleShape) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Text(symbol, color = PracticeBlue, fontSize = 12.sp) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MutedInk, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun StepButton(label: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape,
        color = if (enabled) SoftBlue else Color(0xFFEDF0F4),
        modifier = Modifier.size(36.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 20.sp, color = if (enabled) PracticeBlue else MutedInk.copy(alpha = .5f))
        }
    }
}

private fun formatMinutes(seconds: Double): String = "${(seconds.coerceAtLeast(0.0) / 60).roundToInt()}"
private fun formatDuration(seconds: Double): String {
    val value = if (seconds.isFinite()) seconds.coerceAtLeast(0.0).toLong() else 0L
    return "%d:%02d".format(Locale.ROOT, value / 60, value % 60)
}
private fun formatBeat(beat: Double): String = if (beat.isFinite()) String.format(Locale.ROOT, "%.1f", beat) else "1.0"
private fun noteName(pitch: Int): String {
    val names = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    return names[Math.floorMod(pitch, 12)] + (pitch / 12 - 1)
}
