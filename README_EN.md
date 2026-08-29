# DSH Mobile

**Run a full AI Agent on your phone — no Root, no Termux, no PC required.**

[![CI](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/android-build.yml/badge.svg)](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/android-build.yml)
![Release](https://img.shields.io/badge/release-v1.2.20-blue)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

[中文](README.md) · [English](#introduction)

---

## Introduction

DSH Mobile is an Android host for the [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) — DeepSeek's open-source Agent framework. A complete Node.js Agent engine runs inside the app sandbox, listening on the `127.0.0.1` loopback — sessions, credentials, and workspaces **stay on your phone**. Install and go; your data never leaves the device.

## ✨ What It Can Do on a Phone

**Full Agent task execution**
Hand the Agent tasks: read/write files, run shell commands, full-text search, manage projects — bionic bash / ripgrep / pnpm / curl ship in the APK with an ELF-verified dependency closure. Real execution power, not a chat-only shell.

**Instant preview of what it builds**
The Agent starts a local HTTP server (with the bundled node) and hands you a `http://127.0.0.1:port` link — tap to preview live, one tap back home. Battle-tested with mini-games, static sites, and API services.

**Scalable Agent capabilities**
Three privilege tiers on demand — Normal (sandbox, default) for everyday use; Shizuku mode grants adb-level commands (process management, system properties, no Root needed); Root mode grants full read/write (double confirmation + automatic backup). Options whose capability isn't ready are grayed out automatically.

- **Operate the phone screen (not blind)**: once the accessibility service is enabled, the Agent can **read screen content** (texts + coordinates) and tap precisely by text or coordinates — automating other apps
- **Task-completion push**: the Agent sends an Android system notification when a long task finishes — never miss a background job

**Strictly local data**
The engine listens on `127.0.0.1` only; sessions, credentials, and workspaces live in the app's private storage — switch phones or uninstall, and your data goes exactly where you decide.

**Self-healing**
Broken plugin configs roll back to the last healthy snapshot; the engine restarts with exponential backoff after crashes; a foreground service keeps long tasks alive against system reclamation.

**Extension Center: one-tap environments**
The built-in Extension Center offers **18 environment extensions** — Python, Go, Rust, Clang, OpenJDK, Git, Ruby, PHP, Perl, Lua, SQLite, FFmpeg, ImageMagick, OpenSSH, ADB, Vim and more — one-tap download with red/yellow/green state management and a live inline progress bar. Direct China-mirror access (TUNA → USTC → BFSU → Termux official auto-failover), automatic dependency-closure resolution, SHA-256 verification and atomic publishing.

**Cross-compiling on the phone**
The Clang / Go / Rust / Java / Ruby toolchains are verified on real devices: kernel headers (ndk-sysroot) and CPATH / LIBRARY_PATH / RUSTFLAGS / GOTMPDIR are injected automatically — `clang hello.c -o hello && ./hello` just works; combined with name-based `psx`/`killx` process management and a binary-safe `curl`, the Agent does real development work on the phone. **Parallel services stay under 400 MB of memory.**

**Agent self-extension**
The Agent doesn't just use the Extension Center — it acts on its own: it installs environments through the local bridge and activates them automatically (then reminds you to restart the engine). In Root mode it has even bootstrapped the Android SDK command-line tools by itself.

## ⛔ Limits (please be aware)

- **No desktop environment**: Linux GUI desktop apps can't run; visual outputs are previewed via local HTTP + the built-in WebView
- **Bundled toolchain is node/bash only**: Clang/Python/Go and 16 more environments come through the built-in **Extension Center** (one tap), or the Agent can install them itself (verified in Root mode with the Android SDK)
- **Screen injection is "blind"**: the accessibility service injects gestures but never reads screen content (privacy-first design), so complex UI automation is limited
- **Long tasks are not immortal**: the foreground service maximally avoids system reclamation, but a force-stop or extreme battery saver can still interrupt (the engine auto-restarts; in-flight tasks must be re-dispatched)
- **Escalation carries risk**: in Root mode the AI has full-device read/write and misoperations can damage the system — see the disclaimer below

## 🆚 How It Compares

| | Termux + manual setup | Termux snapshot repack | **DSH Mobile** |
|---|---|---|---|
| Install experience | Install Termux, configure, install deps | Install & go | **Install & go** |
| Runtime | Live environment (extensible) | Dead snapshot, frozen at build | **Self-built bionic closure, collected & verified by CI** |
| License compliance | — | ⚠️ Snapshot repacks GPL components; compliance questionable | **MIT/BSD/ISC/Zlib components only** |
| Background reliability | Depends on Termux session keep-alive | Watchdog brute force | **specialUse foreground service + exponential-backoff supervisor** |
| Privilege tiers | None | None | **Three-tier modes + su gate + Shizuku adb bridge** |
| Build engineering | — | No CI; not reproducible from source | **Dual-arch CI: runtime collection → closure checks → 16KB alignment guard → APK** |
| Environment extensions | manual install, extensible | dead snapshot, not extensible | **18 one-tap extensions + agent self-install, official icons** |

## Privilege Modes

| | Normal | Shizuku | Root |
|---|---|---|---|
| Engine identity | App sandbox | App sandbox | uid 0, full device |
| Agent power | In-sandbox commands | + adb-level commands (`shz`) | Full read/write |
| Prerequisite | None | Install & start [Shizuku](https://shizuku.rikka.app/) | Rooted device |
| Safeguards | su gate blocks escalation | su gate blocks escalation | Double high-risk confirmation + automatic pre-start backup |

Normal mode is the default; options whose capability isn't ready are grayed out and non-clickable; switching modes restarts the engine automatically.

## 📦 Installation

**Download a Release (recommended)**: grab the APK from [Releases](https://github.com/Soodok/Deepseek-Harness-Local-Android/releases) (pick `arm64-v8a` for phones; latest is **v1.2.20**), then install with unknown sources allowed. Any v1.0.0+ build can be installed over the top.

**Build from source** (JDK 17 + Android SDK, NDK r26+, CMake 3.22.1):

```bash
./scripts/collect-termux-runtime.sh app/src/main/assets/runtime.zip aarch64
gradle assembleDebug -Pabi=arm64-v8a
```

Alternatively, fork the repo and run the **android-build** workflow on GitHub Actions for a cloud build.

**Requirements**: Android 8.0+ · arm64-v8a / x86_64 · ~300MB free storage.

## 🚀 Quick Start

1. On first launch, pick the display orientation and privilege mode (choose **Normal** if unsure)
2. Wait for runtime extraction (real progress) and engine startup
3. Start chatting and hand the Agent tasks; tap the gear for settings; tap any `127.0.0.1` link the Agent gives you to preview its work

## ❓ FAQ

**Does it require Root?** No. Normal mode covers the vast majority of use cases; Root/Shizuku are optional advanced tiers.

**Is my data uploaded?** The engine, sessions, and workspace are all local; whether data leaves the device depends on the model service endpoint you configure.

**Why is the APK ~70MB?** It bundles the complete Node.js runtime and dependency closure (~69MB) — the price of "no Termux required".

**How does the Agent show me a web page?** Ask it to start a local HTTP server and give you a `http://127.0.0.1:port` link; tap to preview.

**Why can't I enable touch injection?** Android requires accessibility services to be enabled manually in system settings; the in-app button just takes you there.

## ⚠️ Disclaimer

> **Please read this section carefully before use.**

1. This software is provided "as is", without warranty of any kind, express or implied. The author shall not be liable for any direct or indirect damages arising from the use, misuse, or inability to use this software.
2. **In Root mode, the engine runs with the highest privilege (uid 0), and commands generated by the AI have full read/write access to the entire device.** The AI may produce erroneous, unexpected, or destructive actions — including but not limited to deleting system files, corrupting partitions, or rendering the device unbootable. **Any device damage, data loss, or warranty void resulting from such actions is the sole responsibility of the user; the author bears no liability whatsoever.**
3. In Shizuku mode the Agent can perform adb-level operations, which carry similar risks of accidental damage; use your own judgment.
4. Use this software only on devices **you own or are explicitly authorized to control**. Consequences of using it on unauthorized devices or for unlawful purposes rest entirely with the user.
5. Root and Shizuku modes are optional. Without them, the Agent is strictly confined to the app sandbox. **If you are unwilling to accept any risk, stay in Normal mode.**

**Installing this app or enabling a high-privilege mode constitutes your acknowledgment that you have read, understood, and accepted all of the above.**

## Feedback

Found a bug or have a feature request? Open an [Issue](https://github.com/Soodok/Deepseek-Harness-Local-Android/issues). For crash reports, please attach the `logcat` output or the engine log available in the app.

## License

[MIT](LICENSE). Runtime components retain their original licenses (MIT / BSD / ISC / Zlib); `@deepseek-ai/dsh` is owned by DeepSeek AI.

This is an independent community project, not affiliated with DeepSeek.
