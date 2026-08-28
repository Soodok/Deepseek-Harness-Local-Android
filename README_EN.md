# DSH Mobile

**Run a full AI Agent on your phone — no Root, no Termux, no PC required.**

[![CI](https://github.com/Soodok/dsh-android/actions/workflows/android-build.yml/badge.svg)](https://github.com/Soodok/dsh-android/actions/workflows/android-build.yml)
![Release](https://img.shields.io/badge/release-v1.0.0-blue)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

[中文](README.md) · [English](#introduction)

---

## Introduction

DSH Mobile is an Android host for the [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) — DeepSeek's open-source Agent framework. A complete Node.js Agent engine runs inside the app sandbox, listening on the `127.0.0.1` loopback — sessions, credentials, and workspaces **stay on your phone**. Install and go; your data never leaves the device.

## ✨ Highlights

**🔒 Tiered privileges, controlled escalation**
Three runtime modes (Normal / Shizuku / Root), one-tap switch in-app. Featuring the **su gate**: even if the device has granted su to the app, the Agent's escalation attempts are denied on the spot by a deny-shim at the front of the engine's PATH in non-Root modes — "the device granted su" no longer means "the Agent can always use su".

**📡 adb-level power (Shizuku mode)**
Built on the official Shizuku api/provider/aidl trio. Through the built-in `shz` bridge, the Agent executes commands as the adb identity — process management, system properties, package queries — with no Root required.

**🩹 Three-layer config self-healing**
A broken plugin config never locks you out: automatic rollback to the last healthy snapshot → same-signature crash tracking → safe-mode quarantine as the last resort. Every layer was forged by a real on-device incident.

**🛠 Full toolchain, real execution power**
bionic bash + ripgrep + pnpm + curl ship in the APK, with the dependency closure verified against ELF NEEDED entries. The Agent can actually run commands and search files — not a chat-only shell.

**🖥 Engineering-grade reliability**
A `specialUse` foreground service sidesteps Android 14's 6-hour kill limit; the process supervisor auto-restarts with exponential backoff; 16KB memory-page alignment (2025+ flagship kernels) is pinned at build time and guarded by CI checks on every `.so`.

**👁 One-tap preview**
When the Agent builds a web page, it hands you a `http://127.0.0.1:port` link — tap to preview live, one tap back home. Zero protocol learning cost.

## 🆚 How It Compares

| | Termux + manual setup | Termux snapshot repack | **DSH Mobile** |
|---|---|---|---|
| Install experience | Install Termux, configure, install deps | Install & go | **Install & go** |
| Runtime | Live environment (extensible) | Dead snapshot, frozen at build | **Self-built bionic closure, collected & verified by CI** |
| License compliance | — | ⚠️ Snapshot repacks GPL components; compliance questionable | **MIT/BSD/ISC/Zlib components only** |
| Background reliability | Depends on Termux session keep-alive | Watchdog brute force | **specialUse foreground service + exponential-backoff supervisor** |
| Privilege tiers | None | None | **Three-tier modes + su gate + Shizuku adb bridge** |
| Build engineering | — | No CI; not reproducible from source | **Dual-arch CI: runtime collection → closure checks → 16KB alignment guard → APK** |

## Privilege Modes

| | Normal | Shizuku | Root |
|---|---|---|---|
| Engine identity | App sandbox | App sandbox | uid 0, full device |
| Agent power | In-sandbox commands | + adb-level commands (`shz`) | Full read/write |
| Prerequisite | None | Install & start [Shizuku](https://shizuku.rikka.app/) | Rooted device |
| Safeguards | su gate blocks escalation | su gate blocks escalation | Double high-risk confirmation + automatic pre-start backup |

Normal mode is the default; options whose capability isn't ready are grayed out and non-clickable; switching modes restarts the engine automatically.

## 📦 Installation

**Download a Release (recommended)**: grab the APK from [Releases](https://github.com/Soodok/dsh-android/releases) (pick `arm64-v8a` for phones), then install with unknown sources allowed.

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

Found a bug or have a feature request? Open an [Issue](https://github.com/Soodok/dsh-android/issues). For crash reports, please attach the `logcat` output or the engine log available in the app.

## License

[MIT](LICENSE). Runtime components retain their original licenses (MIT / BSD / ISC / Zlib); `@deepseek-ai/dsh` is owned by DeepSeek AI.

This is an independent community project, not affiliated with DeepSeek.
