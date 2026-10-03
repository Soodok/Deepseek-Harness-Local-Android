# DSH Mobile

**The complete Android port of DeepSeek Harness — the official dsh engine running as-is inside the app sandbox. No root, no Termux, no PC needed.**

[![CI](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/android-build.yml/badge.svg)](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/android-build.yml)
![i18n](https://github.com/Soodok/Deepseek-Harness-Local-Android/actions/workflows/i18n-check.yml/badge.svg)
![Release](https://img.shields.io/badge/release-v1.2.47-blue)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

<p align="center">
  <img src="docs/promo/dsh-mobile-github.png" alt="DSH Mobile — DeepSeek Harness. Now in your pocket." width="100%">
</p>

[English](#introduction) · [Deutsch](README.de.md) · [中文](README.zh-CN.md)

---

## Introduction

DSH Mobile is the **complete Android port of [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)**, DeepSeek's open-source Agent framework. A full Node.js Agent engine runs inside the app sandbox, listening on the `127.0.0.1` loopback — sessions, credentials and workspaces **stay on your phone**. Install and go; your data never leaves the device.

## 🎯 What This Is · What This Is Not

**This is**: the **complete Android port of DeepSeek Harness** (`@deepseek-ai/dsh`). The engine is the official upstream code (pinned to `0.2.0-rc.2`) — plugin system, WebUI and tool-calling chain all come from upstream. What this project supplies is the Android-side runtime: a self-built bionic Node.js runtime with a verified dependency closure, foreground-service keep-alive, privilege tiers and the Extension Center. **Anything desktop dsh can do, this can do** — just inside the phone's sandbox.

**This is not**:

- ❌ **Not a self-built AI agent framework** — agent logic, plugin system and context management all belong to DeepSeek Harness. This project is a **port**, not a replacement, and it does not compete with upstream
- ❌ **Not an on-device LLM app** — no model weights, no local inference. Models are reached through the API endpoint you configure, exactly as with desktop dsh
- ❌ **Not a phone-automation agent** — accessibility screen reading/tapping is **one of the tools** handed to the agent, not the product itself

In one line: **looking for a way to run dsh on Android without Termux? This is it.**

## ⚡ Measured Performance

All figures come from real-device / emulator testing, not theory:

| Metric | Measured | Environment |
|---|---|---|
| **Cold start to engine ready** | **< 10 s** | OnePlus 15T (Android 16), real device; ~15 s on tablets |
| **Memory with parallel services** | **< 400 MB** | Engine + multiple toolchains running together |
| **Android compatibility** | **8.0 → 16 all pass** | Emulator matrix (WebView 69 → 133 span) |
| **Toolchains** | **19 one-tap installs** | Python/Go/Rust/Clang/OpenJDK/FFmpeg… |

For reference, Termux-snapshot-based alternatives typically need **several minutes** on first launch (unpack + manual init); DSH Mobile ships its runtime inside the APK — install and go.

## ✨ What It Can Do on a Phone

**Full Agent task execution**
Hand the Agent tasks: read/write files, run shell commands, full-text search, manage projects — bionic bash / ripgrep / pnpm / curl ship inside the APK with an ELF-verified dependency closure. Real execution power, not a chat-only shell.

**Instant preview of what it builds**
The Agent starts a local HTTP server (using the bundled node) and hands you a `http://127.0.0.1:<port>` link — tap to preview it live, one tap back to home. Verified in practice with mini-games, static sites and API services.

**Agent capabilities you scale on demand**
Three privilege tiers, available whenever you need them — Normal (sandbox, default) covers everyday use; Shizuku mode lets the Agent run adb-level commands (process management / system properties, no Root needed); Root mode grants full read/write (double confirmation + automatic backup). Options whose capability isn't ready are grayed out automatically.

**Operate the phone screen (not blind)**
Once you enable the accessibility service, the Agent can **read screen content** (text + coordinates) and tap precisely by text or by coordinates, automating other apps. (Accessibility has to be turned on manually in system settings.)

**Task-completion push**
The Agent sends an Android system notification when a long task finishes — never miss a background job.

**Strictly local data**
The engine listens on `127.0.0.1` only; sessions, credentials and workspaces live in the app's private directory — switch phones or uninstall, and they go exactly where you decide.

**Self-healing when things break**
Broken plugin configs automatically roll back to the last healthy snapshot; the engine restarts with exponential backoff after a crash; a foreground service keeps long tasks from being reclaimed.

**Extension Center: one-tap environments**
The built-in Extension Center offers **19 environment extensions** — Python, Go, Rust, Clang, OpenJDK, Git, Ruby, PHP, Perl, Lua, SQLite, FFmpeg, ImageMagick, OpenSSH, ADB, Vim and more — one-tap download with red/yellow/green state management and a live inline progress bar. Direct China-mirror access (TUNA → USTC → BFSU → Termux official, with automatic failover), automatic dependency-closure resolution, SHA-256 verification and atomic publishing. After install, the shebang interpreter chain is repaired automatically (`sh`/`env`, plus absolute resolution of cross-extension `perl`/`python`), so scripts like `env python3` run even without PATH. Every row carries **⟳ Reinstall** (overwrite re-download that repairs historic partial installs); install logs record entry counts per package and verify that the declared executables are all present. The `bin` dependencies of all 19 extensions are **audited at the ELF level** by `scripts/audit-extension-closures.py` (every `NEEDED` library must be covered by the dependency closure, so gaps in upstream metadata are caught early).

**Cross-compiling on the phone**
The Clang / Go / Rust / Java / Ruby toolchains are verified on real devices: kernel headers (ndk-sysroot) and CPATH / LIBRARY_PATH / RUSTFLAGS / GOTMPDIR are injected automatically, so `clang hello.c -o hello && ./hello` just works; combined with name-based `psx`/`killx` process management and a binary-safe `curl`, the Agent does real development work on the phone. **Parallel services stay under 400 MB of memory.**

**Agent self-extension**
The Agent doesn't just use the Extension Center — it acts on its own: it installs environments through the local bridge during a session and activates them automatically (then reminds you to restart the engine). In Root mode it has even bootstrapped the Android SDK command-line tools by itself.

## ⛔ Limits (please be aware)

- **No desktop environment**: Linux GUI desktop apps can't run; visual output is previewed via local HTTP + the built-in WebView
- **Bundled toolchain is node/bash only**: Clang, Python, Go and 16 more environments come through the built-in **Extension Center** with one tap, or the Agent can download and install them itself (verified in Root mode with the Android SDK)
- **Tap injection is "half-blind"**: screen reading works off the accessibility node tree (text + coordinates + clickability), so it is ineffective on purely graphical or game screens; complex UI automation still has limits
- **Long tasks are not immortal**: the foreground service maximally avoids system reclamation, but a force-stop or extreme battery saver can still interrupt them (the engine auto-restarts; in-flight tasks must be re-dispatched)
- **Escalation carries risk**: in Root mode the AI has full-device read/write and misoperations can damage the system — see the disclaimer below

## 🌟 What the Termux Routes Can't Give You

Same dsh, different delivery — and every one of the following is engineering this project had to build itself. That is also why it is called a **port** rather than a repack:

**Cross-version compatibility, already paid for**
Emulator matrix from Android 8.0 → 16, all green (spanning WebView 69 → 133). Since many Chinese ROMs can never update their WebView, the build injects a polyfill (`Object.hasOwn` / `Array.at` / `replaceChildren` / `replaceAll`) so even an old WebView renders the WebUI correctly; every `.so` is **16 KB page-aligned** so newer-kernel devices work out of the box. On the Termux route, each of these is a per-device gamble.

**Plugins usable out of the box — no compiler-engineering homework first**
dsh's "everything is a plugin" architecture is kept as-is: hot plugin loading and session persistence (JSONL) all work normally. The language environments plugins need (Python / Go / Rust / Clang / OpenJDK…) are filled in by the Extension Center's **19 one-tap installs**, with dependency closures pre-resolved and ELF-verified in CI. On the Termux route, native modules like `sharp` / `koffi` / `node-pty` failing to compile is the norm — the community even maintains prebuilt-module projects just for this.

**The engine heals itself; you don't need to read Linux logs**
Broken plugin configs → automatic rollback to the last healthy snapshot; if rollback doesn't help → the two-stage guardian enters safe mode (archive the bad config, boot with an empty one) with everything recoverable; engine crash → exponential-backoff auto-restart. On the Termux route this whole layer is "read the logs and fix it yourself".

**Stays alive in the background, and notifies you when a task finishes**
A specialUse foreground service plus an exponential-backoff supervisor absorb system reclamation, so long tasks keep running with the screen off or in the background, and a system notification fires on completion. On Termux, surviving aggressive Chinese-ROM battery policies is each user's own mystery.

## 🆚 Routes for Running an Agent on Android

| | Termux, manual setup | Termux, one-click script | proot + Ubuntu | APK snapshot bundle | **DSH Mobile** |
|---|---|---|---|---|---|
| Install experience | Install Termux, configure env, install deps | Script does it for you | Install container + distro | Install and go | **Install and go** |
| Runtime | Live environment (extensible) | Live environment (extensible) | glibc in a container (extensible) | Frozen snapshot | **Self-built bionic closure, collected + verified in CI** |
| License compliance | — | — | — | ⚠️ Snapshot bundles GPL components; compliance is questionable | **Only MIT/BSD/ISC/Zlib components** |
| Background reliability | Depends on Termux session keep-alive | Same | Same | Watchdog brute force | **specialUse foreground service + exponential-backoff supervisor** |
| Privilege tiers | None | None | None | None | **Three-tier modes + su gate + Shizuku adb bridge** |
| Build engineering | — | Partially reproducible | — | No CI; not reproducible from source | **Dual-arch CI: collect → closure checks → 16 KB alignment → APK** |
| First launch | Minutes after manual setup | Minutes after the script | Minutes of container init | Minutes to unpack the snapshot | **Cold start < 10 s** (measured on device) |
| Old WebView support | — | — | — | — | **Polyfill injection** (WebView 69 → 133 tested) |
| Termux dependency | Requires the Termux app | Requires the Termux app | Requires the Termux app | The snapshot *is* Termux | **Zero** (hardcoded paths relocated) |
| Self-healing | Manual repair | Manual repair | Manual repair | Watchdog brute force | **Config rollback + safe mode + storage self-check** |
| Environment extensions | Manual install, extensible | Manual install, extensible | apt, extensible | Frozen snapshot, not extensible | **19 one-tap installs + agent self-install, official icons, three-state management** |
| Cross-version compatibility | Per-device trial and error | Same | Same | Same | **8.0→16 matrix tested + WebView polyfill + 16 KB alignment** |
| Plugins usable out of the box | Fight native modules one by one | Depends on script patch coverage | Same | Frozen at build time | **CI pre-resolved dependency closure + 19 one-tap extensions** |

> These routes are not replacements for one another: Termux-family solutions **build a Linux environment** on Android and run dsh inside it (advantage: live and freely extensible via pkg/apt); this project **packs dsh's runtime into the APK** (advantage: install-and-go, zero external dependency). **Both routes run the same dsh** — pick by how much setup cost you're willing to pay for a live environment.
>
> 📄 A full write-up of all five routes — mechanics, trade-offs and how to choose — is in **[docs/android-agent-routes.md](docs/android-agent-routes.md)** ([中文](docs/android-agent-routes.zh.md)).

## Privilege Modes

| | Normal | Shizuku | Root |
|---|---|---|---|
| Engine identity | App sandbox | App sandbox | uid 0, full device |
| Agent power | In-sandbox commands | + adb-level commands (`shz`) | Full read/write |
| Prerequisite | None | Install & start [Shizuku](https://shizuku.rikka.app/) | Rooted device |
| Safeguards | su gate blocks escalation | su gate blocks escalation | Double high-risk confirmation + automatic pre-start backup |

Normal mode is the default; options whose capability isn't ready are grayed out and non-clickable; switching modes restarts the engine automatically.

## 📦 Installation

**Download a Release (recommended)**: grab the APK from [Releases](https://github.com/Soodok/Deepseek-Harness-Local-Android/releases) (pick `arm64-v8a` for phones; latest is **v1.2.47**), then install with unknown sources allowed. Any v1.0.0+ build can be installed over the top.

**Build from source** (JDK 17 + Android SDK, NDK r26+, CMake 3.22.1):

```bash
./scripts/collect-termux-runtime.sh app/src/main/assets/runtime.zip aarch64
gradle assembleDebug -Pabi=arm64-v8a
```

Alternatively, fork the repo and run the **android-build** workflow on GitHub Actions for a cloud build.

**Requirements**: Android 8.0+ · arm64-v8a / x86_64 · **700 MB+ free storage recommended** (APK ~190 MB plus ~450 MB unpacked runtime).

## 🚀 Quick Start

1. On first launch, pick the display orientation and the privilege mode (choose **Normal** if unsure)
2. Wait for the runtime to unpack (real progress) and the engine to start
3. Start chatting and hand the Agent tasks; tap the gear icon for settings; tap any `127.0.0.1` link the Agent gives you to preview its work

## ❓ FAQ

**Does it require Root?** No. Normal mode covers the vast majority of use cases; Root/Shizuku are optional advanced tiers.

**How does this relate to the Termux approach?** Parallel routes, not replacements. The Termux route **builds a Linux environment** on Android (manual setup / one-click script / proot + Ubuntu) and runs dsh inside it; this project **packs dsh's runtime into the APK** so you install and go, at the cost of a ~190 MB package. **Both run the same dsh** — choose by how much setup cost you're willing to pay for a live environment.

**Does it reimplement the agent itself?** No. Agent logic, plugin system and WebUI all come from the official [`@deepseek-ai/dsh`](https://github.com/deepseek-ai/deepseek-harness); this project only provides the Android-side runtime (bionic Node.js runtime, foreground service, privilege tiers, Extension Center). That is why it is described as a **port**, not a framework.

**Is my data uploaded?** The engine, sessions and workspace are all local; whether data leaves the device depends on the model service endpoint you configure.

**Why is the APK ~190 MB?** It bundles the complete bionic Node.js runtime, the Termux toolchain closure (bash / ripgrep / SONAME libraries) and dsh's entire dependency tree (79 packages as of 0.2.0) — the price of "no Termux required, install and go". Termux-family routes keep all of that external and installed by the user, so their package is smaller but first use takes minutes of setup.

**How does the Agent show me a web page?** Ask it to start a local HTTP server and give you a `http://127.0.0.1:<port>` link; tap to preview.

**Why can't I tap the screen?** Android requires accessibility services to be enabled manually in system settings; the in-app button just takes you there.

## ⚠️ Disclaimer

> **Please read this section carefully before use.**

1. This software is provided "as is", without warranty of any kind, express or implied. The author shall not be liable for any direct or indirect damages arising from the use, misuse or inability to use this software.
2. **In Root mode, the engine runs with the highest privileges (uid 0), and commands generated by the AI have full read/write access to the entire device.** The AI may produce erroneous, unexpected or destructive actions — including but not limited to deleting system files, corrupting partitions or rendering the device unbootable. **Any device damage, data loss or warranty void resulting from such actions is the sole responsibility of the user; the author bears no liability whatsoever.**
3. In Shizuku mode the Agent can perform adb-level operations, which carry a similar risk of accidental damage; please be aware and use your own judgement.
4. Use this software only on devices **you own or are explicitly authorised to control**. Consequences of using it on unauthorised devices or for unlawful purposes rest entirely with the user.
5. Root and Shizuku modes are optional. Without them, the Agent is strictly confined to the app sandbox. **If you are unwilling to accept any risk, stay in Normal mode.**

**Installing this app or enabling a high-privilege mode constitutes your acknowledgement that you have read, understood and accepted all of the above.**

## Feedback

Found a bug or have a feature request? Open an [Issue](https://github.com/Soodok/Deepseek-Harness-Local-Android/issues). For crash reports, please attach the `logcat` output or the engine log available in the app.

## License

[MIT](LICENSE). Runtime components retain their original licenses (MIT / BSD / ISC / Zlib); `@deepseek-ai/dsh` is owned by DeepSeek AI.

This is an independent community project, not affiliated with DeepSeek.
