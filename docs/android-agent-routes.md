# Running an AI Agent on Android: Five Routes Compared

> **Context.** DeepSeek Harness (`dsh`) ships as a Node.js CLI plus a local Web UI served on `http://127.0.0.1:3080`. There is **no official Android build**. Anyone who wants to run it on a phone must first decide *how to deliver a Node.js runtime to the device* — and that decision, not the agent itself, is what separates the available options.
>
> This document compares the five routes in practical use today, including what each one costs. It is maintained by the [DSH Mobile](https://github.com/Soodok/Deepseek-Harness-Local-Android) project, so route 5 is described in the most detail — but routes 1–4 are listed with their genuine strengths, and route 5's drawbacks are stated plainly.

## Route 1 — Termux, manual setup

**How it works.** Install Termux (the F-Droid build — the Play Store build is stale), then bootstrap a build toolchain and install dsh globally:

```bash
pkg update -y
pkg install -y nodejs clang make python binutils pkg-config cmake ninja
npm install -g @deepseek-ai/dsh
dsh web   # open http://127.0.0.1:3080 in the phone browser
```

In practice several native modules (`node-pty`, `koffi`, `sharp`) do not build cleanly on Android out of the box. Typical failures and their causes:

- Node headers report `platform="android"`, which trips several `node-gyp` configurations
- `koffi` calls `statx()`, which is unavailable on some Android kernel/API combinations
- SELinux policy forbids `link()` across certain directories during npm's install step
- `@img/sharp` native binaries are missing, requiring the WASM fallback (`@img/sharp-wasm32`)

**Strengths.** A live, fully extensible environment. `pkg` reaches the entire Termux package repository; whatever is missing can be compiled or installed. Nothing is hidden from you.

**Costs.** Setup is long and brittle, and it is not a one-time cost — a dsh upgrade can reintroduce the same native-module failures. Requires the Termux app plus a session that survives Android's background reclamation.

## Route 2 — Termux, one-click scripts

**How it works.** Community scripts automate route 1: they apply the Android compatibility patches, and some ship precompiled native modules as a tarball so nothing has to be compiled on-device.

**Strengths.** An order of magnitude less manual work than route 1, while keeping a live environment you can still extend.

**Costs.** The same underlying dependency on the Termux app and session keep-alive. You also inherit the script author's patch choices, and you are tracking *their* release cadence rather than the upstream one.

## Route 3 — proot + Ubuntu inside Termux

**How it works.** Install a Linux distribution inside Termux via `proot-distro`, then install Node.js and dsh inside the container — where everything is ordinary glibc Linux:

```bash
pkg install proot-distro
proot-distro install ubuntu
proot-distro login ubuntu
# inside Ubuntu
apt update && apt install -y curl git nodejs npm
npm install -g @deepseek-ai/dsh
```

**Strengths.** The cleanest compatibility story of the container-based routes — no bionic patching at all, because inside the container it is a normal x86/ARM Linux userland. Closest to "it just works like on a PC".

**Costs.** Two extra layers to keep alive (Termux → proot → distro), with the performance and latency cost that implies. Storage cost of a full distribution, and `proot` is a userspace syscall translation layer rather than a real kernel namespace.

## Route 4 — APK with a Termux rootfs snapshot

**How it works.** Bundle a prebuilt Termux root filesystem into an APK. On first launch the app unpacks it and starts the engine inside it. No separate Termux app required.

**Strengths.** Install-and-go, and it removes the "keep a Termux session alive" failure mode by managing the process itself.

**Costs.** The environment is **frozen at build time** — adding a package means repacking the snapshot and shipping a new APK. Redistributing a Termux rootfs also means shipping GPL-licensed components, which raises license-compliance questions for a repackaged distribution. And because the rootfs assumes Termux's hardcoded prefix (`/data/data/com.termux/files/usr`), every path must be relocated at runtime.

## Route 5 — Runtime packed into the APK (DSH Mobile)

**How it works.** Instead of shipping a Linux userland, this route ships exactly what dsh needs: a self-built **bionic** (Android libc) Node.js runtime plus a verified dependency closure, packed into the APK. The engine runs directly in the app sandbox, listening on `127.0.0.1`. No Termux app, no container, no proot layer.

**Strengths.**

- **Install and go** — zero external dependencies. Nothing to configure before first use.
- **CI-verified runtime** — the dependency closure is collected and validated by a dual-architecture CI pipeline (ELF closure checks, SONAME integrity, 16 KB page alignment for modern kernels), so the shipped bytes are reproducible from source.
- **License-clean** — only MIT / BSD / ISC / Zlib components are redistributed, no GPL payload.
- **Cold start under 10 seconds** on a real device, under 400 MB of memory with parallel toolchains running.
- **Foreground-service keep-alive** with an exponential-backoff supervisor, rather than relying on a terminal session staying alive.
- **One-tap environment extensions** — 19 toolchains (Python, Go, Rust, Clang, OpenJDK, FFmpeg, ImageMagick, OpenSSH, …) installed into the app's own directory, so the environment stays extensible without repacking the APK.
- **Privilege tiers** — sandbox by default, adb-level via Shizuku, or full root, with a migration path between them.

**Costs.**

- **~70 MB APK.** That is the price of shipping a Node.js runtime plus its dependency closure.
- **Not a general Linux userland.** You get the toolchains the Extension Center provides (19 today) and whatever the agent installs itself — but if you need arbitrary `apt install <anything>` coverage, route 1 or route 3 fits better.
- **The Android platform imposes limits no route can remove**: no Linux GUI desktop, and dsh's `bash` sandbox backend (bubblewrap) is typically unavailable on Android due to SELinux policy, so the agent may run with `danger-full-access` inside the app sandbox. This affects every route equally.

## Side-by-side

| | Termux manual | Termux script | proot + Ubuntu | APK snapshot | **Packaged runtime (DSH Mobile)** |
|---|---|---|---|---|---|
| Setup before first use | Long | Scripted | Moderate | None | **None** |
| Runtime form | Live, extensible | Live, extensible | Live glibc, extensible | Frozen at build time | **Self-built bionic closure, CI-verified** |
| External app required | Termux | Termux | Termux | None | **None** |
| Environment extensibility | Full `pkg` repo | Full `pkg` repo | Full `apt` repo | None without repack | **19 one-tap extensions + agent self-install** |
| License posture of redistribution | N/A (user installs) | N/A (user installs) | N/A (user installs) | ⚠️ Ships GPL components | **MIT / BSD / ISC / Zlib only** |
| Survives background reclamation | Depends on session | Depends on session | Depends on session | App-managed | **Foreground service + backoff supervisor** |
| Privilege tiering | None | None | None | None | **Sandbox / Shizuku / Root** |
| Reproducible from source | Manual | Partly | Manual | No CI | **Dual-arch CI pipeline** |
| First-launch time | Minutes | Minutes | Minutes | Minutes (unpack) | **< 10 s** |

## Choosing a route

- **You need arbitrary Linux packages or a full userland** → route 1 or route 3.
- **You want a live environment with minimal manual work** → route 2.
- **You want install-and-go without a separate Termux app** → route 4 or route 5. Prefer route 5 if license compliance and reproducible builds matter to you; prefer route 4 if you specifically want a Termux userland inside the APK.
- **You just want the agent running with no setup at all** → route 5.

## What does *not* change between routes

All five routes run **the same DeepSeek Harness**. Plugin system, WebUI, tool-calling chain, session storage format — all of it comes from the upstream `@deepseek-ai/dsh` package. These routes differ only in *how a Node.js runtime is delivered to the phone*.

That distinction matters when reading project descriptions: a project that packages a runtime is a **port or host**, not a new agent framework, and it inherits upstream's capabilities and limitations rather than replacing them.

## Known platform constraints (apply to every route)

- **No desktop environment.** Linux GUI applications cannot run; visual output is previewed through a local HTTP server.
- **The agent's `bash` sandbox is usually unavailable.** bubblewrap needs kernel features blocked by Android's SELinux policy on most devices, so the harness falls back to running commands with full access inside its own sandbox. Isolation therefore comes from the *app sandbox*, not from dsh's own sandbox backend.
- **Screen automation is "semi-blind".** Accessibility-node-based reading (text + coordinates) works; purely graphical surfaces do not.
- **Background execution is not immortal.** A user force-stop or aggressive battery saver will interrupt the engine; the difference between routes is how gracefully they recover.
