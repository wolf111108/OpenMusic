# ScoreIR 0.1.0 导入契约

本版接受结构化 JSON 乐谱。PDF、照片、音频转谱属于后续独立生产端，当前应用的边界是 **读取 ScoreIR → 校验 → 编译 → 显示与练习**。

- 规范：[`schemas/scoreir-0.1.0.schema.json`](../schemas/scoreir-0.1.0.schema.json)
- 基础样例：[`samples/first_steps.scoreir.json`](../samples/first_steps.scoreir.json)
- 节奏样例：[`samples/rhythm_study.scoreir.json`](../samples/rhythm_study.scoreir.json)
- Kotlin 入口：`ScoreCodec.parse(text)`、`ScoreCompiler.compile(document)`

两份样例均为本项目原创练习素材。

## 1. 文件与版本

推荐文件名 `曲目.scoreir.json`，UTF-8 编码，单文件最大 2 MiB。`schema_version` 必填，当前值为 `0.1.0`。未知字段会明确报错，便于发现生产端拼写错误。后续扩展采用新的版本并由版本化适配器显式升级。

```json
{
  "schema_version": "0.1.0",
  "score_id": "first_score",
  "metadata": {"title": "第一份乐谱", "instrument": "piano"},
  "parts": [{
    "id": "piano",
    "staves": [{"id": "RH", "clef": "treble", "index": 1}]
  }],
  "measures": [{
    "id": "m1", "number": 1,
    "time_signature": {"numerator": 4, "denominator": 4},
    "key_signature_fifths": 0,
    "tempo_bpm": 80,
    "events": [{
      "id": "n1", "kind": "note", "part_id": "piano", "staff_id": "RH", "voice": 1,
      "onset_qn": "0/1", "duration_qn": "4/1",
      "pitch": {"step": "C", "alter": 0, "octave": 4, "midi": 60},
      "tie": "none"
    }]
  }],
  "playback_structure": {"repeats": [], "tempo_changes": []}
}
```

## 2. 字段与音乐语义

| 字段 | 解释 |
|---|---|
| `score_id` | 全曲 ID；更新内容时保留来源版本信息。ID 使用字母、数字、点、下划线、短横线 |
| `parts[].id` | 乐部 ID；例如钢琴一个乐部含两个谱表 |
| `staves[].id/index` | 全曲唯一谱表 ID；每乐部 index 从 1 连续编号 |
| `staves[].clef` | `treble`、`bass`、`alto`、`tenor` |
| `measures[].id/number` | 内部唯一小节 ID 和展示编号；弱起编号可为 0 |
| `time_signature` | 当前小节的拍号，默认 4/4；每个小节明确保存，避免生产端隐式状态歧义 |
| `key_signature_fifths` | 调号的五度数：0 为无升降号，1 为一个升号，−1 为一个降号 |
| `tempo_bpm` | **四分音符每分钟**；省略时继承源谱前一小节末尾速度，整曲初始默认 80 |
| `events[].voice` | 谱表内声部编号 1–4；左右手通常分别在两个谱表 |
| `onset_qn` | 小节内起音位置，单位为四分音符，精确分数字符串 |
| `duration_qn` | 以四分音符为单位的正时值；`1/2` 为八分，`3/2` 为附点四分，`1/3` 为八分三连音 |
| `pitch` | 音高拼写和 MIDI 编号；C4=60。0.1.0 采用实音，生产端需先完成移调换算 |
| `tie` | `none/start/stop/continue`，表示同音延音线；与乐句连奏弧区分 |
| `source` | 可选的页码与 `[x,y,width,height]` 归一化图像位置，供未来校对端使用 |
| `confidence/reviewed` | 可选识别置信度与人工确认状态；未确认内容会在编译结果里给出提示 |

### 和弦、独立声部与休止

同一声部、同一 `onset_qn` 的多个音符形成和弦。相继起音而持续时间重叠的独立旋律应放入不同声部。`kind=rest` 表示休止，省略 `pitch`，使用 `tie=none`。声部内空白区间按静默处理，MusicXML 导出补齐休止。

### 弱起与拍单位

普通小节时长由拍号决定。弱起小节设置 `is_pickup=true`，建议同时设置显式 `duration_qn`。省略时由最晚结束的事件推导。6/8 小节长度是 3 个四分音符，速度字段仍使用四分音符/分；重音分组由播放器显示层实现。

### 延音线

将跨小节音拆成各小节事件，依次使用 `start → continue → stop`。同一个谱表、声部与音高的链必须连续且结束。编译器合并为一个演奏起音，保留所有原始音符 ID。同键的先后重弹使用独立的 `tie=none` 事件。

### 简单反复

```json
{"repeats":[{"start_measure_id":"m2","end_measure_id":"m5","times":2}],"tempo_changes":[]}
```

`times` 是总演奏遍数。当前支持互不重叠的简单反复。嵌套反复、一二房、D.C./D.S. 请由生产端先展开为线性小节；导入端会拒绝重叠区间。渲染 MusicXML 同样展开，演奏时间与可见谱面共用顺序。

### 小节内部变速

```json
{"measure_id":"m3","onset_qn":"2/1","tempo_bpm":100}
```

放在 `playback_structure.tempo_changes`。同一位置只允许一个定义；小节起点的变化覆盖该小节 `tempo_bpm`。反复返回时恢复源谱该小节的有效速度。

## 3. 编译结果与时钟接口

`CompiledScore` 包含：

- `notes`：规范化演奏目标；同时同键合并，延音线合并，先后同键保持独立。
- `sourceNotes`：延音线已合并、同键仍按谱表分别保存的目标。选择左右手时先筛选它，再合并同键，保留每只手自身的时值；同一 MIDI 输入上的跨乐部同键也只要求一次起音。
- `groups`：按起音位置分组的音符集合。
- `measures`：实际演奏顺序中的小节出现，含原始小节 ID 与反复轮次。
- `tempoMap`：四分音符位置与原速曲内秒的双向映射。
- `musicXml`：确定性生成的 MusicXML；使用实音和展开顺序。
- `warnings`：待人工确认、超出 P-45 键域等非阻塞信息。

`CompiledNote.id` 形如 `n1@2`，表示源音符 n1 第二次出现；`sourceIds` 保留延音和同键合并后的来源列表。

`tempoMap.secondsAt(qn, speed)` 返回给定速度倍率下的秒数；`quarterAt(seconds, speed)` 逆转换。编译结果的 `onsetSeconds/durationSeconds` 均为 **1x 原速曲内时间**。

`PracticeEvaluator.onNoteOn(pitch, timeSeconds)` 接收原速曲内秒。原生 MIDI 时间戳需要由应用会话时钟先完成映射。`playbackSpeed` 用于把墙钟毫秒容差换算到曲内秒；`deltaMs` 仍报告实际毫秒。

## 4. 选段边界

`targetsInRange(score, selectedStaffIds, startQn, endQn)` 是示范播放、等待与评分共同使用的选段函数。

- 选段 A 落在持续音中间时，新的练习轮次在 A 重起该音。
- 持续音在 B 裁切。
- 两个声部共享一个琴键时，保持一个物理起音目标及多个来源 ID。
- 每轮循环创建新的评估器，保留独立记录。

结束完整练习调用 `finish()`，会结算尚未匹配的目标音。用户中止、设备断线等情况调用 `summary()`，保留中断状态，避免将未演奏的后半段全部算作漏音。

## 5. 校验与支持边界

JSON Schema 用于结构检查，核心模块还检查音高拼写、声部重叠、小节越界、延音线配对、反复区间和数据规模。错误包含小节与音符 ID，便于未来转换器定位。

同一声部内、同一起音的和弦使用相同时值；独立时值分配到不同 `voice`。这让 alphaTab 的谱面音值与实际播放保持一致，例如同拍的全音符 C 与四分音符 E 应分别使用两个声部。

当前最多 8 个乐部、每乐部 2 个谱表、每谱表 4 个声部、2048 个源小节；反复展开最多 8192 小节。基础音高范围为 MIDI 0–127，P-45 范围外另给提示。当前导入契约面向钢琴与其他实音基本记谱；吉他弦品、装饰音、自由节拍、微分音、踏板记谱与复杂反复安排在后续版本中。

JSON 文件最多 2 MiB、嵌套最多 40 层；源事件最多 30000 个，展开并连接延音后的源音最多 120000 个。输入分数的分子绝对值最多 1000000、分母最多 10000，全曲约分后分母的最小公倍数最多 1000000；超出范围会返回可读的导入错误。

基础、附点及三连音生成明确的 MusicXML 时值标记；其他分数保留精确播放时值，显示效果仍需按渲染器兼容性核对。界面里的简谱是结构化音高的数字显示，负责揭示音符与节拍，印刷级排版与复杂技法属于后续扩展。

简谱展示将独立声部与和弦音展开为对齐的单音音线，纵向对齐的数字同时演奏。转换仅作用于渲染副本；输入文件继续使用原有谱表、声部与和弦语义，生产端无需为简谱预先拆音线。

## 6. 后续识谱生产端接口

识谱工具只需输出满足此契约的 UTF-8 JSON。推荐生产流程：生成 → JSON Schema 校验 → 核心语义校验 → 源谱对照校对 → 导入应用。图像坐标、来源、置信度与确认状态已预留，当前仓库无需任何模型密钥或识谱服务。

JSON 的 `reviewed=true` 表示生产端确认，并非应用验证音高视觉正确性的证明。确定性结构校验只能检查内部一致性；原始谱面是否被正确读取仍由未来识谱与校对流程负责。
