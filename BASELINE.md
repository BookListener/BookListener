# BookListener 构建基线（BASELINE）

> 更新时间：2026-10-05・状态：
>
> **稳定基线**
> 规则：未经人工确认，版本号只递增第三位（patch）；人工确认后才升第二位 / 第一位。

## 1. 当前稳定版本



| 项           | 值                                                   |
| ----------- | --------------------------------------------------- |
| versionName | **1.4.3**                                           |
| versionCode | **8**                                               |
| release 包   | `app-release-v1.4.3.apk`（4.72 MB）                   |
| debug 包     | `app-debug-v1.4.1.apk`（21.56 MB，代码与 v1.4.3 相同，未重出包） |
| 用户实测        | **不闪退**（v1.4.3 已确认；v1.4.1 全量 keep 亦确认过）             |

## 2. 编译工具链（本机，不可回退）



* JDK：Temurin **21.0.12.1+1** `C:\Users\e\AppData\Local\Android\Tools\jdk-21.0.12.1+1`（用户明确否决 JDK17）

* Gradle：**8.7** `C:\Users\e\AppData\Local\Android\Tools\gradle-8.7`（腾讯镜像，官方源超时）

* Android SDK：`C:\Users\e\AppData\Local\Android\Sdk`（platforms;android-34、build-tools;34.0.0）

* 构建脚本：`C:\Users\e\AppData\Local\Android\build.ps1`（debug）/ `build_release.ps1`（R8+shrinkResources，临时 debug 签名）/ `build_ut.ps1`（testDebugUnitTest）

* 核心依赖：AGP 8.5.0・Kotlin 1.9.24・compose-bom 2024.06.00・media3 1.4.0・androidx.media:media:1.7.0・room 2.7.0・okhttp 4.12.0・coroutines 1.8.1・security-crypto 1.1.0-alpha06・compileSdk 34 /minSdk 26 /targetSdk 34

## 3. ProGuard / R8 keep 基线（本文件是唯一权威，勿凭记忆改）



```
# Media3（反射/注解多，全量）
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# androidx.media（MediaStyle 通知）
-keep class androidx.media.** { *; }
-dontwarn androidx.media.**

# EncryptedSharedPreferences / Tink（反射加载）
-keep class androidx.security.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn androidx.security.**
-dontwarn com.google.crypto.tink.**

# Kotlin 协程（Main dispatcher 反射）
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Room（kapt 反射）
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclasseswithmembers class * { @androidx.room.* <methods>; }
-dontwarn androidx.room.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Compose 精准 keep（R8 full mode 下官方弱规则不足，勿删！）
# 删掉会导致 IllegalStateException: CompositionLocal LocalLifecycleOwner not present
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.platform.** { *; }
-keep class androidx.lifecycle.compose.** { *; }
-dontwarn androidx.compose.**
-dontwarn androidx.lifecycle.compose.**
```

## 4. 体积演进与闪退史（防止回退到坏配置）



| 版本         | 体积          | 结论                                                              |
| ---------- | ----------- | --------------------------------------------------------------- |
| v1.4.0     | 3.86 MB     | **闪退**：缺 androidx.media/ Tink /coroutines keep                  |
| v1.4.1     | 9.46 MB     | 稳定：全量 keep，用户确认不闪退                                              |
| v1.4.2     | 4.47 MB     | **闪退**：删 compose keep 过度，CompositionLocal 混淆损坏                  |
| **v1.4.3** | **4.72 MB** | **稳定基线**：compose 精准 keep（runtime/ui.platform/lifecycle.compose） |

## 5. 验证手段（改 keep 后必做）



1. 构建 release：`& C:\Users\e\AppData\Local\Android\build_release.ps1`

2. aapt 验版本：`build-tools\34.0.0\aapt.exe dump badging app-release.apk`

3. 解压全部 classes\*.dex，检索关键类必须 True：

   `MediaSessionService / ExoPlayer / Aead / EncryptedSharedPreferences / RoomDatabase / MediaStyle / Composer / AndroidCompositionLocals_androidKt / getLocalLifecycleOwner`

4. 真机实测（唯一最终裁决）

## 6. 崩溃兜底（无需 adb）



* `CrashLogger` 在 MainActivity.onCreate 首行安装，未捕获异常写入

  `Android/data/com.booklistener/files/crash_log.txt`

* 用户闪退后回传该文件即可定位（release 栈为 R8 混淆名，需对照本基线反推）

## 7. 已知正确路径（勿重试死路）



* 注释含 `/*` 序列 → Kotlin 嵌套注释吞文件尾（全仓规避）

* kotlin-stdlib 被传递到 2.1.x 与 1.9.24 冲突 → resolutionStrategy force 4 个 stdlib 到 1.9.24

* [services.gradle.org](https://services.gradle.org) 超时 → 腾讯镜像 gradle-8.7-bin.zip

* 单测中 `onAllNodesWithContentDescription(".*")` 是精确匹配非正则（ui-test 1.6.8）

* release 签名仍为 debug 占位（正式发布需 release keystore，用户未要求）