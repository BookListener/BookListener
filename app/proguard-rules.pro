# ============ Media3 / ExoPlayer ============
# media3 库自带 consumer 规则，此处再加全量 keep 兜底（R8 误删会导致运行时 NoClassDefFoundError）
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ============ androidx.media（MediaStyle 通知，media3-session 的 runtime 依赖） ============
-keep class androidx.media.** { *; }
-dontwarn androidx.media.**

# ============ EncryptedSharedPreferences / Tink ============
# security-crypto 底层 Tink 通过反射/注解加载，混淆删除会直接闪退
-keep class androidx.security.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn androidx.security.**
-dontwarn com.google.crypto.tink.**

# ============ Kotlin 协程（Main dispatcher 通过反射实例化 HandlerDispatcher） ============
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ============ Room 生成的 DAO 实现通过反射/注解调用 ============
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclasseswithmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.**

# ============ OkHttp / Okio ============
-dontwarn okhttp3.**
-dontwarn okio.**

# ============ Compose / Kotlin 反射 ============
# R8 full mode 下 compose 官方 consumer 弱规则不足，LocalLifecycleOwner 等
# CompositionLocal 提供/读取不一致会崩 IllegalStateException。
# 只 keep 关键运行时包（runtime / ui.platform / lifecycle.compose），不恢复全量。
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.platform.** { *; }
-keep class androidx.lifecycle.compose.** { *; }
-dontwarn androidx.compose.**
-dontwarn androidx.lifecycle.compose.**
