# 验证记录

## 当前构建配置

当前依赖已升级为 alphaTab 1.8.4、AGP 8.10.1、Kotlin / Compose 编译插件 2.2.20 和 AndroidX Core KTX 1.17.0；编译使用 Android SDK Platform 36，`targetSdk` 保持 35。Gradle 8.11.1、Build-Tools 35.0.0、JDK 17 与显式固定的 alphaSkia 3.5.147 保持原版本。最新测试与构建结果以对应提交的报告为准。

## 本地构建

以下结果对应上面的当前配置。执行环境为 Linux x86_64、Microsoft OpenJDK 17.0.21、Android SDK Platform 36 / Build-Tools 35.0.0、Gradle 8.11.1。

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

已完成 debug APK 与测试 APK 构建。Android Lint 为 **0 个错误、1 个 `OldTargetApi` 提示**：项目编译使用 API 36，运行行为目标保持已覆盖的 API 35，后续提升目标版本时另行验证相应行为变化。最终 APK 经 `zipalign -c -P 16 4` 检查通过，arm64 与 x86_64 的全部原生库 LOAD 段均已检查 16 KB 对齐。

**50 项单元测试全部通过**：核心 21 项、导入 5 项、MIDI 字节流 5 项、播放计划 6 项、真实 alphaTab 时值对齐 3 项、简谱完整音线 4 项、实际八度字形 1 项、历史流式归档 5 项。覆盖 ScoreIR 格式与音乐语义、原设计示例兼容性、精确分数、速度变化、弱起、反复、延音、分手同键、选段、判分、等待模式、导入容器、MIDI 分包、预备拍、真实 alphaTab 导入后的逐音时间映射，以及大记录读取和完整导出。简谱测试逐音检查全部音高、绝对起止、延音引用与小节时长，包含和弦变位、缺失声部的小节，以及一拍弱起休止的实际显示时值。

`NumberedOctaveGlyphTest` 调用官方 alphaTab 的真实排版与绘制逻辑，通过记录画布检查 MIDI 36、48、52、55、60、62、72 的八度点数量与上下位置，同时确认原音高精确不变。该回归覆盖旧版 C3 误显示两个下加点的问题。

首个代码提交 `4bfb6f4` 已从 GitHub 全新克隆，用仓库自带 Wrapper 完成全部构建、当时的 39 项单元测试及 Lint，验证仓库包含可恢复的完整构建输入。后续版本继续核对本地与远程 Git 文件树，并由 GitHub Actions 使用仓库内 Wrapper 构建。

## Android 运行检查

`SmokeTest` 提供 3 条真实 Activity 与原生渲染器检查。运行需要 Android 设备或带硬件加速的模拟器；当前本地构建环境没有可用 KVM。GitHub Actions 的 Android 15 / API 35 模拟器任务负责运行这些检查，并上传截图及日志；编译阶段使用 Platform 36。检查包含全部 53 个样例音符、双手谱面可见区域、横竖屏记谱切换、游标推进与暂停，以及横竖屏测试键盘触摸输入。对应提交的远程编译和运行状态见 [Actions](https://github.com/wolf111108/OpenMusic/actions)。

## 硬件与环境边界

- Windows 构建脚本随仓库提供；本轮实际编译运行于 Linux 环境。
- P-45、Xiaomi 15 与 REDMI K Pad 的 USB MIDI 组合需要按 [设备验证清单](device-validation.md) 进行真机验收。
- 原生库 ELF 和 APK 对齐检查已完成；16 KB 页内核上的实际启动需要相应设备或系统镜像。
- 音频输出延迟、长时间同步与个人输入校准以真实连接路径测量结果为准。

识谱、PDF/JPG 转换、麦克风识音与演奏力度／踏板评分属于后续范围。
