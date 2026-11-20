// dsh-android 根构建脚本
plugins {
    // AGP 8.6.1：Material 1.14.0 会引入 androidx.core 1.16.0，后者在 AAR 元数据里
    // 硬性要求 AGP >= 8.6.0（低于它 checkDebugAarMetadata 直接失败）。
    // CI 用 Gradle 8.9，满足 AGP 8.6 要求的 8.7+。
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
