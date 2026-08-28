# DSH Mobile

**An Android local engine host for DeepSeek Harness (dsh) — turning your phone into a self-contained AI Agent machine.**

[![CI](https://github.com/Soodok/dsh-android/actions/workflows/android-build.yml/badge.svg)](https://github.com/Soodok/dsh-android/actions/workflows/android-build.yml)
![Release](https://img.shields.io/badge/version-v0.1.0--m1.34.0-blue)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![Arch](https://img.shields.io/badge/arch-arm64__v8a%20%7C%20x86__64-9cf)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

English · [中文](README.md)

---

## Overview

DSH Mobile runs [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) — DeepSeek's open-source Agent framework (August 2026) — **fully on an ordinary Android phone**: no Root required, no Termux, no PC.

- The engine runs as a native bionic Node.js process inside the app's private sandbox, serving over the `127.0.0.1` loopback only;
- Sessions, credentials, and workspaces **never leave the device**;
- A complete POSIX toolchain (bash / ripgrep / pnpm / curl) is bundled, giving the Agent real command execution and file search capabilities;
- A three-tier privilege model (Normal / Shizuku / Root) plus accessibility-based touch injection let you scale the Agent's power on demand.

> Current status (v0.1.0-m1.34.0): **verified end-to-end on an arm64 physical device** — engine healthy, bash/rg operational, session persistence working, full cordis plugin tree activated, all three privilege modes functional, production-grade UI.

## Key Features

| Feature | Description |
|---|---|
| **Local engine** | Full dsh engine + Node.js runtime bundled in the APK, listening on `127.0.0.1:3080` — zero cloud dependency |
| **No Termux needed** | Self-built bionic closure runtime (clean licensing: MIT/BSD/ISC/Zlib components only), works out of the box |
| **Three-tier privilege model** | Normal (sandbox) / Shizuku (adb-level) / Root (full device); chosen at first launch, switchable anytime in the Permission Center |
| **Permission Center** | A dedicated settings page managing privilege mode, capability status (su / Shizuku), and the accessibility toggle; unavailable options are grayed out and non-clickable |
| **su gate** | In non-Root modes a deny-shim `su` is injected at the front of the engine's PATH — even if the device has granted su to the app, the Agent cannot self-escalate |
| **shz escalation channel** | In Shizuku mode the Agent can run `shz <cmd>` through a local bridge to execute commands as the adb identity (keep-alive, process management, etc.) |
| **Accessibility touch injection** | Gesture synthesis via `AccessibilityService.dispatchGesture` — the Agent can simulate taps and swipes on screen |
| **Plugin config self-healing** | ProfileGuardian's three-layer protection: last-good snapshot → same-signature crash rollback → safe mode as last resort; a broken config can never lock the user out |
| **Runtime integrity check** | After overlay upgrades the engine directory is verified (entry point / dependency libraries / package file count); any gap forces a reinstall, eliminating half-installed states |
| **Process supervisor** | `specialUse` foreground service + exponential-backoff auto-restart + user-triggered hot restart; extraction shows real percentage progress |
| **One-tap preview** | Tapping any `http://127.0.0.1:<port>` link the Agent outputs enters preview mode with a one-tap return to home |
| **Display adaptation** | Portrait viewport zoom (50%–150% smooth slider), landscape desktop mode (1280px viewport), hideable toolbar with a freely draggable handle |

## Privilege Model

| | Normal | Shizuku | Root |
|---|---|---|---|
| Engine process identity | App sandbox uid | App sandbox uid | Escalated wholesale via `su` (uid 0) |
| Agent command boundary | Inside sandbox | Inside sandbox + `shz` (adb-level) | Full device |
| Accessibility tapping | Available | Available | Available |
| Prerequisites | None | [Shizuku](https://shizuku.rikka.app/) service running and authorized | Device rooted (`su` detected) |
| Safety mechanisms | su gate shim active | su gate shim active | tar backup of user assets before start + double high-risk confirmation |
| Mode switch | Instant (engine auto-restarts) | Instant (engine auto-restarts) | Instant (engine auto-restarts) |

> Capability probes are shown live on the onboarding page and in the Permission Center: Root detection is a lightweight `stat` (never triggers a Magisk prompt); Shizuku presents three states (authorized / awaiting grant / not running). Options whose capability is not ready are **grayed out and non-clickable**.

## System Architecture

```
┌──────────────────────────────────────────────────────┐
│ UI Layer                                              │
│   MainActivity (WebView shell · official WebUI)       │
│   SettingsActivity (Permission Center · MIUI cards)   │
│   OnboardingActivity (first launch)  AboutActivity    │
├──────────────────────────────────────────────────────┤
│ Service Layer                                         │
│   EngineService (specialUse foreground service)       │
│     └── EngineSupervisor state machine                │
│         Idle → Installing → Starting → Healthy        │
│            ↖ Backoff(exponential) ← Crash/SafeMode    │
├──────────────────────────────────────────────────────┤
│ Capability Layer                                      │
│   Privilege (mode store / probes / Shizuku binder)    │
│   ShizukuHttpBridge (127.0.0.1:3082 shz bridge)       │
│   DshAccessibilityService (gesture injection)         │
│   ProfileGuardian (snapshot / rollback / safe mode)   │
├──────────────────────────────────────────────────────┤
│ Process Layer                                         │
│   EngineProcess                                       │
│     ├── dsh_pty.c (JNI fork + PTY + execve)           │
│     ├── log pump → logcat + engine.log                │
│     └── exit listener → backoff restart               │
├──────────────────────────────────────────────────────┤
│ Runtime Layer                                         │
│   RuntimeInstaller (assets/runtime.zip                │
│     → integrity check → extract → chmod → stamp)      │
│   bionic runtime (Termux builds, ~69MB closure)       │
│     bin/: node bash rg pnpm curl                      │
│     lib/: openssl icu sqlite readline ncurses         │
│           iconv pcre2 c-ares zlib libc++              │
│     lib/node_modules/: @deepseek-ai/dsh + deps        │
└──────────────────────────────────────────────────────┘
```

## Getting Started

### Option 1: Download a Release (recommended)

Grab the APK for your architecture from [Releases](https://github.com/Soodok/dsh-android/releases) (`arm64-v8a` for physical devices) and install with unknown sources allowed. First launch opens the onboarding page to pick display orientation and privilege mode.

### Option 2: GitHub Actions cloud build

1. Fork this repository;
2. Run the **android-build** workflow from the Actions tab (dual-architecture matrix with runtime collection, closure verification, and 16KB alignment checks);
3. Download the APK from Artifacts.

### Option 3: Local build

Requirements: JDK 17, Android SDK (NDK r26+, CMake 3.22.1), Gradle 9.x, Git Bash (to run the collection script on Windows).

```bash
# 1. Install SDK components
sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"

# 2. Generate the runtime bundle (bionic closure collected from Termux repos)
./scripts/collect-termux-runtime.sh app/src/main/assets/runtime.zip aarch64

# 3. Build
gradle assembleDebug -Pabi=arm64-v8a

# 4. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Non-rooted device note**: some vendor ROMs (e.g. ColorOS) refuse adb-initiated installs. Push the APK to `/data/local/tmp/` and install via the device's file manager, or use `su -c pm install -r` on rooted devices.

### First launch

1. **Onboarding**: choose the default orientation (portrait / landscape desktop layout) and privilege mode (Normal / Shizuku / Root);
2. **Engine initialization**: the runtime is extracted on first launch (with real progress), then the engine turns Healthy;
3. **Permission Center**: tap the gear icon to open settings — switch privilege modes anytime (engine restarts automatically), view capability status, or enable accessibility tapping.

## Engineering Decision Record

Every decision below was forged by a real on-device incident. This is the core asset distinguishing this project from "it runs on my machine":

1. **targetSdk 28 (deliberate)** — Android 10+'s W^X policy is enforced per targetSdk; a sideloaded app targeting 28 may legally `execve` binaries in its private storage (the same policy Termux uses). If ever removed, the migration path is jniLibs `lib*.so` masquerading — packaging layer only.
2. **16KB memory page alignment** — 2025+ flagship kernels use 16384-byte pages; any ELF with `p_align=4096` fails to dlopen on them. CMake pins `-Wl,-z,max-page-size=16384`, and CI asserts alignment on every `.so` inside the APK.
3. **Exact SONAME aliases** — the Android linker looks up NEEDED entries verbatim (e.g. `libreadline.so.8`); versioned filenames inside .deb packages won't match. Android's SELinux also forbids `link()`/symlinks, so aliases must be real `cp` copies. CI asserts every library.
4. **specialUse foreground service** — sidesteps Android 14's 6-hour kill limit on `dataSync` services so long Agent tasks are never time-boxed by the system.
5. **No hard links on Android** — SELinux denies `link()` for `app_data_file` by default; all "temp file + atomic publish" logic degrades to `rename()` (same-directory rename is equally atomic).
6. **Explicit dependency closure** — `apt-get download` does not resolve dependencies; the runtime manifest is manually cross-checked against the Termux Packages index (bash→readline→ncurses, bash→libiconv, ripgrep→pcre2) with CI verifying the actual files.
7. **su gate (tiered escalation)** — su authorization is recorded per calling uid: once a device grants su, any shell can escalate. A deny-shim `su` is injected at the front of the engine's PATH and **only Root mode removes it**, so "the device granted su" no longer means "the Agent can always use su".
8. **Shizuku official trio** — `api + provider + aidl` are all mandatory: the Provider performs the binder handshake (without it the grant dialog never appears), aidl's `IShizukuService.newProcess` provides process execution, and api wraps authorization.
9. **No upstream WebUI rewrite** — the dsh web UI is under rapid upstream iteration; the native shell stays a container plus native settings pages, keeping protocol maintenance at zero.
10. **WebView zoom via viewport meta** — `setInitialScale` gets clamped by the page's own meta; widening the layout mathematically introduces horizontal scroll. The only reliable approach is rewriting the meta to `width=device-width, initial-scale=R, min=max=R, user-scalable=no`.
11. **Dual runtime integrity check** — "version matches" does not mean "extraction completed": a failed overlay upgrade leaves hollow directories. The installer verifies entry file + key SONAMEs + package file count and forces a reinstall on any gap.
12. **Root pollution self-healing** — files written by the Root-mode engine become root-owned; switching back to a lower privilege mode then hits EACCES. The supervisor detects and `chown`s user assets back to the app uid before any non-Root start.

## Verified Capabilities

Verification environment: Android 16 physical device (arm64-v8a) · v0.1.0-m1.34.0

| Item | Status | Notes |
|---|---|---|
| Engine end-to-end | Pass | `engine healthy on :3080`, dual node processes resident, full W^X allow chain |
| Session persistence | Pass | `session.jsonl.zstd` generated (incl. subagent sessions), atomic rename publish |
| bash / ripgrep | Pass | SONAME closure complete; rg glob/grep verified; pnpm/corepack 11.x/0.35 EXIT=0 |
| cordis plugins | Pass | Full plugin tree activated; `/api/loader` observability endpoint for diagnostics |
| Three-tier privilege model | Pass | Root su escalation + asset backup; Shizuku grant dialog + `shz` adb-level execution; su gate interception verified |
| Accessibility tapping | Pass | Gesture-injection service enableable (manual opt-in in system settings, per Android security model) |
| ProfileGuardian | Pass | Broken-config auto-rollback / safe-mode quarantine; no engine restart loops |
| 16KB page alignment | Pass | Verified on an Android 16 (16KB-page kernel) device + CI guard |
| UI completeness | Pass | Permission Center / page zoom / landscape desktop / one-tap preview / draggable toolbar handle / About page |
| APK size | 67.3 MB | After M1b dependency-closure slimming (down 72% from the initial ~245MB) |
| write tool sandboxing | Partial | `link()` degraded to rename; `sandbox-local` has no Android backend yet (fail-closed in restricted mode) |

## Project Structure

```
dsh-android/
├── app/src/main/
│   ├── cpp/                    # dsh_pty.c — JNI fork+PTY+execve
│   ├── java/app/dsh/mobile/
│   │   ├── engine/             # Supervisor / Process / Config / Privilege
│   │   │                       # RuntimeInstaller / ProfileGuardian / ShizukuHttpBridge
│   │   ├── service/            # EngineService (specialUse foreground service)
│   │   ├── MainActivity.kt     # WebView shell (viewport zoom / preview chrome / draggable handle)
│   │   ├── SettingsActivity.kt # Permission Center (MIUI-style grouped cards)
│   │   ├── OnboardingActivity.kt / AboutActivity.kt
│   │   └── DshAccessibilityService.kt
│   ├── assets/runtime/         # runtime.zip drop-in (injected at build time)
│   └── res/                    # layouts / icons / accessibility service declaration
├── scripts/
│   └── collect-termux-runtime.sh   # Termux closure collection + Android patches (shared by CI & local)
└── .github/workflows/
    └── android-build.yml       # dual-arch matrix: collect → closure check → 16KB guard → build → Release
```

## Roadmap

- [x] M0 — Skeleton: Gradle + PTY JNI + supervisor + WebView shell
- [x] M0.5 — W^X exemption proven (emulator execve of bionic node)
- [x] M1 — Full dsh engine integration + bionic compatibility patch suite
- [x] M1.5 — arm64 physical-device end-to-end (engine / toolchain / sessions / plugins)
- [x] M1b — Runtime dependency-closure slimming (APK 118MB → 67.3MB)
- [x] M1c — Native UI system: onboarding / Permission Center / page zoom / one-tap preview / MIUI style
- [x] M1d — Privilege system: three-tier modes / su gate / Shizuku shz bridge / accessibility tapping
- [ ] M2 — node-pty bridging onto the in-house libdshpty.so; session resume (dsh session resume)
- [ ] M3 — Alpine proot live environment (Agent self-installs via `apk add`)
- [ ] M4 — Independent port management service (unified preview for Agent-built web services) / Share Target / Quick Settings Tile

## Privacy & Security

- **Data never leaves the device**: the engine listens on the `127.0.0.1` loopback only; sessions, credentials, and workspaces live in the app's private storage;
- **Least privilege by default**: Normal mode is the default; elevating requires an explicit user choice (Root demands double confirmation + automatic backup);
- **Controlled escalation**: the su gate ensures "the device granted su" does not mean "the Agent can always use su";
- **Accessibility is write-only**: the gesture service declares action capability only and never reads screen content (`canRetrieveWindowContent=false`), and must be enabled manually by the user in system settings;
- **Be aware**: in Root mode the engine runs as uid 0 with full-device read/write — enable it only on your own device and only if you understand the implications.

## Acknowledgements

- [DeepSeek Harness (dsh)](https://github.com/deepseek-ai/deepseek-harness) — the Agent engine wrapped by this project (MIT)
- [Termux](https://github.com/termux/termux-packages) — source of the bionic toolchain and runtime components
- [Shizuku](https://github.com/RikkaApps/Shizuku) — the adb-level API framework
- Everyone who filed crash reports during the on-device incident marathons

## License

[MIT](LICENSE). Runtime components retain their original licenses (MIT / BSD / ISC / Zlib); `@deepseek-ai/dsh` is owned by DeepSeek AI.

This is an independent community project, not affiliated with DeepSeek.
