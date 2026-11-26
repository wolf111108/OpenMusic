# 验证记录

## 本地构建

执行环境：Linux x86_64、Microsoft OpenJDK 17.0.21、Android SDK 35 / Build-Tools 35.0.0、Gradle 8.11.1。

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

已完成 debug APK 与测试 APK 构建，Android Lint 无问题。最终 APK 经 `zipalign -c -P 16 4` 检查通过。

本次 **39 项单元测试全部通过**：核心 20 项、导入 5 项、MIDI 字节流 5 项、播放计划 6 项、真实 alphaTab 时值对齐 3 项。覆盖 ScoreIR 格式与音乐语义、原设计示例兼容性、精确分数、速度变化、弱起、反复、延音、分手同键、选段、判分、等待模式、导入容器、MIDI 分包、预备拍，以及真实 alphaTab 导入后的逐音时间映射。后续提交的结果以对应构建的 JUnit 报告为准。

## Android 运行检查

`SmokeTest` 提供 3 条真实 Activity 与原生渲染器检查，编译已通过。运行需要 Android 设备或带硬件加速的模拟器；当前本地构建环境没有可用 KVM。GitHub Actions 的 Android 15 模拟器任务负责运行这些检查，并上传截图及日志。对应提交的远程运行状态见 [Actions](https://github.com/wolf111108/OpenMusic/actions)。

## 硬件与环境边界

- Windows 构建脚本随仓库提供；本次编译执行于上面的 Linux 环境。
- P-45、Xiaomi 15 与 REDMI K Pad 的 USB MIDI 组合需要按 [设备验证清单](device-validation.md) 进行真机验收。
- 原生库 ELF 和 APK 对齐检查已完成；16 KB 页内核上的实际启动需要相应设备或系统镜像。
- 音频输出延迟、长时间同步与个人输入校准以真实连接路径测量结果为准。

识谱、PDF/JPG 转换、麦克风识音与演奏力度／踏板评分属于后续范围。
