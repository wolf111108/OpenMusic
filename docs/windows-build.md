# Windows 构建与安装

## 环境

| 项目 | 仓库配置 |
|---|---|
| Android 最低版本 | Android 8.0，API 26 |
| compileSdk / targetSdk | 35 / 35 |
| Android Gradle Plugin | 8.9.2 |
| Gradle Wrapper | 8.11.1，附带发行包 SHA-256 校验值 |
| Kotlin / Compose 编译插件 | 2.1.21 |
| Java 字节码目标 | 17 |
| Compose BOM | 2025.04.00 |
| alphaTab Android | 1.6.1 |
| 应用 ID | `io.github.openmusic` |

版本由仓库构建文件固定。安装 [Android Studio](https://developer.android.com/studio)，在 **SDK Manager** 中安装：

- Android SDK Platform 35。
- Android SDK Build-Tools 35.0.0。
- Android SDK Platform-Tools。

Gradle 运行环境建议 JDK 17。Android Studio 的 JBR 17／21 也适用；在 **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** 中选中对应 JDK。项目自带 Wrapper，直接使用 `gradlew.bat`。首次同步需要访问 Google Maven、Maven Central 和 Gradle 下载服务器。

构建工具与 Java 的对应关系以 [AGP 8.9 官方说明](https://developer.android.com/build/releases/past-releases/agp-8-9-0-release-notes) 和 [Gradle 8.11.1 兼容性表](https://docs.gradle.org/8.11.1/userguide/compatibility.html) 为准。

## 拉取和同步

```powershell
git clone https://github.com/wolf111108/OpenMusic.git
cd OpenMusic
```

在 Android Studio 中使用 **Open** 打开包含 `settings.gradle.kts` 的根目录。同步成功后选择 `app` 运行配置。IDE 通常会写入本机的 `local.properties` 来保存 SDK 路径。

纯命令行环境可以设置当前 PowerShell 会话：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --version
```

也可在根目录创建本机 `local.properties`，Windows 路径使用正斜线：

```properties
sdk.dir=C:/Users/YourName/AppData/Local/Android/Sdk
```

## 一键构建

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1
```

脚本会按以下顺序寻找 JDK：显式 `-JdkPath`、当前有效 `JAVA_HOME`、常见位置的 Android Studio JBR、`PATH` 中的完整 JDK。脚本保留当前有效的 `JAVA_HOME`，结束后恢复由脚本临时调整的环境变量。SDK 优先交给 `local.properties` 或已设置的 SDK 环境变量，缺省时查找 `%LOCALAPPDATA%\Android\Sdk`。

```powershell
# 指定 JDK；替换为自己的实际安装目录
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -JdkPath "C:\Program Files\Java\jdk-17"

# 构建成功后安装到已连接、已授权调试的 Android 设备
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -Install

# 日常只生成 APK
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -SkipTests
```

脚本调用失败时返回 Gradle 的失败退出码。SDK 组件及许可由 Android Studio 的 SDK Manager 安装和确认。

## 单独执行命令

```powershell
# 核心语义、编译和评分测试
.\gradlew.bat :core:test

# Android 模块本地单元测试
.\gradlew.bat :app:testDebugUnitTest

# APK 和 Android 静态检查
.\gradlew.bat :app:assembleDebug :app:lintDebug

# 一次执行常用检查
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

结果位置：

| 内容 | 路径 |
|---|---|
| debug APK | `app\build\outputs\apk\debug\app-debug.apk` |
| 核心测试报告 | `core\build\reports\tests\test\index.html` |
| Android 本地测试报告 | `app\build\reports\tests\testDebugUnitTest\index.html` |
| Lint 报告 | `app\build\reports\lint-results-debug.html` |

测试命令覆盖可自动验证的逻辑；USB MIDI、Android 音频设备和实际屏幕表现按 [真机清单](device-validation.md) 单独验收。

### 模拟器界面测试

在 Android Studio Device Manager 启动一个 Android 模拟器，再执行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

`SmokeTest` 包含三条真实 Activity 流程：从今日进入原创样例并切换五线谱／简谱、示范游标前进后暂停、设备页测试键盘按下／松开并推进等待模式。测试同时检查 alphaTab 的已载入曲谱、渲染完成事件和小节边界，保存手机竖屏与横屏截图。等待依据实际状态和有限超时，完整执行结果以本次测试报告为准。

Gradle 会在卸载测试应用前收集 `additionalTestOutputDir`，文件保存在以下目录的设备子文件夹中：

```text
app\build\outputs\connected_android_test_additional_output\
```

截图目录同时包含渲染元数据 `.txt`；界面测试报告位于 `app\build\reports\androidTests\connected\debug\index.html`。CI 使用模拟器执行同一命令并收集这些文件。通过其他 runner 直接运行且缺少输出参数时，截图回退到应用的外部 `screenshots` 目录，应在卸载应用前取出。模拟输入验证应用内事件通路，实际 USB 设备仍按真机清单验收。

## 安装到 Xiaomi 15 / REDMI K Pad

### 直接安装 APK

将 `app-debug.apk` 复制到设备，在文件管理器里打开，按系统提示允许对应来源安装。调试构建会自动使用开发机的 debug 签名。

### 使用 USB 调试

在 Android 设备开发者选项中开启 USB 调试，将设备连接到 Windows，接受设备上的 RSA 授权提示；系统若显示额外的「USB 安装」选项，按提示启用。

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
.\gradlew.bat :app:installDebug
```

同一台电脑重复构建可以覆盖安装并保留应用数据。更换电脑后出现签名冲突时，先导出需要保留的记录和原始曲谱，再处理旧安装。卸载应用会删除其私有目录；导出的记录 JSON 当前供归档与分析使用。

USB 口连接 P45 时手机／平板作为主机。首次测试可先完成 APK 安装，再将连接电脑的线换成电钢琴数据线。

## 常见问题

| 现象 | 检查方法 |
|---|---|
| `JAVA_HOME is not set` 或找不到 Java | 通过 `-JdkPath` 指定含 `bin\java.exe`、`bin\javac.exe` 的完整 JDK |
| JDK 版本与 Gradle 不兼容 | 将 Gradle JDK 设为 JDK 17／JBR 21，使用仓库内 Wrapper |
| `SDK location not found` | 在 Android Studio 打开项目，或设置 `ANDROID_HOME`／`local.properties` |
| 缺少 `android-35`／Build-Tools | 在 SDK Manager 安装上面列出的组件后重试 |
| 依赖下载失败 | 检查当前网络、代理以及 IDE 与命令行的代理配置；保留仓库依赖版本 |
| `adb devices` 显示 `unauthorized` | 解锁设备并接受 USB 调试授权；重插数据线 |
| debug APK 覆盖安装提示签名冲突 | 确认是否来自另一台电脑的 debug 签名，先导出需要保存的数据 |
| P45 已连接但应用没有设备 | 使用支持数据的线材、确认主机口和 P45 电源，按设备清单检查 |

个人编译安装可使用 debug APK。正式长期分发时通过 Android Studio 的 **Generate Signed Bundle / APK** 配置自己保管的签名，并保持后续版本签名一致。
