pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // Flutter adds a project repository; keep the host repositories authoritative.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // 真 PTY 终端（对齐 DSHA）：Termux 拆出的 terminal-view / terminal-emulator
        // （com.termux.termux-app，Apache 2.0）发布在 JitPack。
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
    }
}

rootProject.name = "dsh-android"
apply(from = file("flutter_ui/.android/include_flutter.groovy"))
include(":app")
