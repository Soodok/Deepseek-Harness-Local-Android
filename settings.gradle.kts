pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // 真 PTY 终端（对齐 DSHA）：Termux 拆出的 terminal-view / terminal-emulator
        // （com.termux.termux-app，Apache 2.0）发布在 JitPack。
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "dsh-android"
apply(from = file("flutter_ui/.android/include_flutter.groovy"))
include(":app")
