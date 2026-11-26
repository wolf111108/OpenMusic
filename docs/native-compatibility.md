# 原生渲染依赖与 16 KB 页面检查

OpenMusic 使用官方发布的 `net.alphatab:alphaTab:1.8.4`，并显式固定其渲染依赖：

```kotlin
implementation("net.alphatab:alphaTab:1.8.4")
implementation("net.alphatab:alphaSkia-android:3.5.147")
implementation("net.alphatab:alphaSkia:3.5.147")
```

alphaSkia 的两项依赖需要一起更新，确保 Java 包装层与 JNI 库属于同一版本。alphaTab 1.8.4 的官方 POM 仍声明 alphaSkia 3.4.135；项目使用上述两个显式依赖覆盖为 3.5.147。3.4.135 的 64 位 ELF 仅按 4 KB 对齐；修改 APK 的 ZIP 对齐无法改变库内部的 ELF 布局。

## 简谱八度点修正

历史版本 alphaTab 1.6.1 对低于基准音、相差整数八度的音符多计算一个下加点，例如 C3 应为单下加点，却显示为双下加点。项目升级至包含该修正的官方 1.8.4：其简谱实现使用 `floor((displayValue - oneNoteValue) / 12)` 计算八度距离，C3 相对 C4 的结果为 −1。OpenMusic 按标准 Maven 坐标引用官方发布包，保留 MPL-2.0 许可证与上游归属。

## 已完成的静态验证

检查对象直接下载自 Maven Central 的原始发布文件，日期为 2026-11-26：

| 对象 | 结果 |
| --- | --- |
| alphaSkia 3.4.135 的 `arm64-v8a` 与 `x86_64` 库 | 每个 `PT_LOAD` 的 `p_align` 均为 `0x1000`，未达到 16 KB 要求 |
| alphaSkia 3.5.147 的相同两个 ABI | 每个 `PT_LOAD` 的 `p_align` 均为 `0x4000`；`p_offset` 与 `p_vaddr` 在模 16384 下相等 |
| alphaSkia 3.5.147 的 `GNU_RELRO` | RELRO 占其所在 LOAD 段的整个末尾，后续可写数据位于另一个 LOAD 段，满足官方检查条件 |
| 新旧 `alphaSkia` JAR 的公开 Java 签名 | 逐类使用 `javap -public` 比较，没有删除或变更旧签名 |
| 新旧 Android AAR 内 `classes.jar` 的公开 Java 签名 | 相同检查通过 |
| Java class 文件版本 | 两版均为 major 61，即 Java 17；alphaSkia 包装层没有新增 Kotlin 编译器依赖 |

3.5.147 Android AAR 的 SHA-256：

```text
449494608f74dc6c8f7b29067658f5d61548332d2f5c9b30e75cc06bddddf867
```

alphaSkia 官方版本规则将同一主版本下的次版本变化定义为向后兼容。上述字节码检查进一步覆盖了本次替换涉及的旧公开方法和字段。这里验证的是发布文件结构与接口；实际渲染结果仍需运行测试。

## 复核方法

解压 AAR 后，分别检查其 `jni/arm64-v8a/libalphaskiajni.so` 与 `jni/x86_64/libalphaskiajni.so`：

```bash
readelf -lW libalphaskiajni.so
```

Windows 可使用 Android NDK 内的 `llvm-readelf.exe -lW`。逐一检查所有 `LOAD` 段的 Align 列，而非只查看第一段；同时按 Android 官方文档检查 `GNU_RELRO` 与后续可写数据的边界。

APK 打包完成后，再使用 Android SDK Build Tools 的独立检查确认 ZIP 内原生库的对齐：

```text
zipalign -c -P 16 -v 4 app-debug.apk
```

这项检查与 ELF 检查分别覆盖 APK 容器和原生库两个层面。AGP 8.5.1 及以上支持相应的原生库打包对齐；项目的具体 AGP 版本由根构建文件固定。

## 仍需在设备上验证

静态检查不能代替实际 16 KB 内核上的安装、启动与谱面渲染。设备或模拟器准备好后，使用 `adb shell getconf PAGE_SIZE` 确认返回 `16384`，再运行 APK，检查五线谱、简谱、换行滚动和暂停恢复，并按 [设备验收](device-validation.md) 完成 MIDI 与音频测试。现有 ELF 验证不等同于已经完成这些运行测试。

## 来源

- [Android：支持 16 KB 页面大小](https://developer.android.com/guide/practices/page-sizes)
- [alphaSkia 官方仓库与版本规则](https://github.com/CoderLine/alphaSkia)
- [alphaTab 1.8.4 发布 POM](https://repo.maven.apache.org/maven2/net/alphatab/alphaTab/1.8.4/alphaTab-1.8.4.pom)
- [alphaTab 1.8.4 官方源码包](https://repo.maven.apache.org/maven2/net/alphatab/alphaTab/1.8.4/alphaTab-1.8.4-sources.jar)
- [alphaSkia-android 3.5.147 AAR](https://repo.maven.apache.org/maven2/net/alphatab/alphaSkia-android/3.5.147/alphaSkia-android-3.5.147.aar)
- [alphaSkia 3.5.147 JAR](https://repo.maven.apache.org/maven2/net/alphatab/alphaSkia/3.5.147/alphaSkia-3.5.147.jar)
