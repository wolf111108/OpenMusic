# Third-party notices

OpenMusic 使用以下第三方组件。各组件及其资源遵循自己的许可证；本文件只记录第三方声明，项目自身的授权由仓库所有者单独决定。

## 运行时组件

| 组件 | 使用版本 | 用途 | 许可证与来源 |
|---|---|---|---|
| Kotlin 标准库 | 2.2.20 | Kotlin 运行时 | Apache-2.0 · [JetBrains/Kotlin](https://github.com/JetBrains/kotlin/blob/v2.2.20/license/LICENSE.txt) |
| kotlinx.coroutines | 1.10.2 | 协程与状态流 | Apache-2.0 · [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt) |
| kotlinx.serialization | 1.8.1 | JSON 序列化 | Apache-2.0 · [Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization/blob/v1.8.1/LICENSE.txt) |
| AndroidX / Jetpack Compose | Compose BOM 2025.04.00；Core 1.17.0；Activity 1.10.1；Lifecycle 2.9.0 | Android UI、生命周期、工具库 | Apache-2.0 · [AndroidX 源码与许可证](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |
| alphaTab Android | 1.8.4 | 原生乐谱导入与显示 | MPL-2.0 · [项目源码](https://github.com/CoderLine/alphaTab) · [发布 POM](https://repo.maven.apache.org/maven2/net/alphatab/alphaTab/1.8.4/alphaTab-1.8.4.pom) |
| alphaSkia / alphaSkia-android | 3.5.147，显式固定以满足 16 KB ELF 对齐 | 原生图形渲染 | BSD-3-Clause · [发布 POM](https://repo.maven.apache.org/maven2/net/alphatab/alphaSkia/3.5.147/alphaSkia-3.5.147.pom) |
| Bravura | 字体 1.38，随 alphaTab 1.8.4 AAR 分发 | 乐谱字体 | SIL Open Font License 1.1 · [Steinberg Bravura](https://github.com/steinbergmedia/bravura) |
| Sonivox soundfonts | 随 alphaTab 1.8.4 AAR 分发 | 上游随包资源 | Apache-2.0；见下方实际包内声明 |

AndroidX 和 alphaTab 的间接依赖由 Gradle 解析。完整解析结果可用以下命令查看：

```powershell
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath
```

OpenMusic 原样引用 Maven Central 官方发布的 alphaTab Android 1.8.4。alphaTab 完整源代码可从上面的项目入口与 [1.8.4 sources JAR](https://repo.maven.apache.org/maven2/net/alphatab/alphaTab/1.8.4/alphaTab-1.8.4-sources.jar) 获取；许可证原文为 [Mozilla Public License 2.0](https://www.mozilla.org/en-US/MPL/2.0/)。该版本包含低于基准音整数八度处的简谱八度点修正。

alphaTab 1.8.4 的 POM 声明 alphaSkia 3.4.135；本项目同时显式固定 alphaSkia 与 alphaSkia-android 为官方 3.5.147，以使用满足 16 KB ELF 对齐要求的原生库，详见 [兼容性记录](docs/native-compatibility.md)。

## alphaTab 随包资源

声明基于 Maven Central 的 `alphaTab-1.8.4.aar` 实际内容：

- `assets/Bravura.otf`：字体元数据包含以下版权说明及完整 SIL OFL 1.1 文本。
- `assets/sonivox.sf2`、`assets/sonivox.sf3`：上游附带音色资源；相应的 `assets/LICENSE` 声明见下方。

OpenMusic 谱面使用 Bravura 字体。节拍器与示范音由本项目代码生成 PCM；应用当前播放流程未载入 Sonivox soundfonts。上述音色文件仍随依赖的 Android 资源合并进入 APK。

### Bravura

> Copyright © 2020, Steinberg Media Technologies GmbH (http://www.steinberg.net/), with Reserved Font Name "Bravura".
>
> This Font Software is licensed under the SIL Open Font License, Version 1.1.

保留名为 `Bravura`。本项目没有修改这些字体。完整许可证保留在字体元数据中，亦可查阅 [SIL Open Font License 1.1](https://openfontlicense.org/open-font-license-official-text/)；上游当前文本见 [Bravura LICENSE.txt](https://github.com/steinbergmedia/bravura/blob/master/LICENSE.txt)。

### Sonivox 包内声明

```text
Copyright (c) 2004-2006 Sonic Network Inc.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## 构建与测试工具

| 组件 | 版本 | 许可证 |
|---|---|---|
| Gradle Wrapper / Gradle | 8.11.1 | [Apache-2.0](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE) |
| Android Gradle Plugin | 8.10.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| Kotlin / Compose 编译插件 | 2.2.20 | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/v2.2.20/license/LICENSE.txt) |
| JUnit | 4.13.2 | [EPL-1.0](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) |

这些构建与测试工具通过各自发行包提供许可证。`samples/` 中的两份乐谱为本项目原创练习素材，乐谱导入功能读取的用户文件保持各自来源信息。
