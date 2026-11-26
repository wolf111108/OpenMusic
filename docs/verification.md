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

## GitHub Actions 构建

功能提交 [`ef3fe9c`](https://github.com/wolf111108/OpenMusic/commit/ef3fe9ccc30d2e70af32fe0a85ed46fddefe34a5) 的 [工作流 45877033615](https://github.com/wolf111108/OpenMusic/actions/runs/45877033615) 已完成远程构建。报告确认核心 21 项和 Android 本地 29 项测试全部通过，debug APK 与测试 APK 均生成成功，最终 APK 对齐检查通过。

远程 Lint 为 **0 个错误、10 个提示**：`OldTargetApi` 1 项、`AndroidGradlePluginVersion` 1 项和 `GradleDependency` 8 项。后两类来自联网检查可更新的依赖版本；本次交付保留已经构建和测试的固定版本。

工作流提供 `OpenMusic-debug-apk` 和 `local-test-reports` 构建产物，可在运行页面下载。测试报告归档下载后已核对 SHA-256，并检查其中的测试汇总与 Lint XML。

## Android 运行检查

同一功能提交 `ef3fe9c` 的 `android-smoke` 任务已在 Android 15 / API 35、Pixel 6 配置的 x86_64 模拟器上完成，**3 项检查全部通过，0 个失败、0 个跳过**：

1. 加载原创双手谱，核对全部 53 个音符，切换五线谱与简谱，并检查横竖屏的原生谱面实际可见区域。
2. 启动示范，确认原生游标前进，再验证暂停状态。
3. 在设备页发送真实触摸按下／松开事件，使用测试键盘在横竖屏「弹对前进」模式中推进目标音。

已下载并核对 `android-smoke-results` 归档的 SHA-256，读取 JUnit XML、谱面测量记录并人工查看截图。横屏简谱首小节高约 664.9 px，视口高 680 px，首小节和四条音线的可见比例均为 100%；低音 C3 显示一个下加点。截图前后的前台窗口均由测试检查，本轮没有遮挡窗口或恢复记录。

仓库中的 [横屏简谱](screenshots/landscape-numbered.png) 与 [竖屏五线谱](screenshots/portrait-staff.png) 直接复制自该次运行的原始 PNG。归档 ID 为 `15146685538`，SHA-256 为 `ebca0f6de859da120bc83d08f5847081ad9222e9021dd629c7ea492452ee4d1a`。本地构建环境没有可用 KVM，运行验证由上述 GitHub Actions 模拟器完成；编译阶段使用 Platform 36。

## 硬件与环境边界

- Windows 构建脚本随仓库提供；本轮实际编译运行于 Linux 环境。
- P-45、Xiaomi 15 与 REDMI K Pad 的 USB MIDI 组合需要按 [设备验证清单](device-validation.md) 进行真机验收。
- 原生库 ELF 和 APK 对齐检查已完成；16 KB 页内核上的实际启动需要相应设备或系统镜像。
- 音频输出延迟、长时间同步与个人输入校准以真实连接路径测量结果为准。

识谱、PDF/JPG 转换、麦克风识音与演奏力度／踏板评分属于后续范围。
