package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Agent 上下文种子（m1.35）：把「Android 环境说明书」预写入 `$DSH_HOME/AGENTS.md`。
 *
 * 动机：dsh Agent 冷启动到一个陌生沙箱时，会花大量 token 自行探索环境
 * （uname、which、ls /、试探 apt…），且探索结论常常错误（把 bionic 当 glibc、
 * 试图 apt install）。dsh-agent-instructions 原生发现 `$DSH_HOME/AGENTS.md`
 * （user-global 层，无条件注入每个会话），故在此预置一份高密度环境事实，
 * 让 Agent 从第一轮就带着正确世界观干活。
 *
 * 幂等策略（保护用户/AI 的手工编辑）：
 *  - 文件不存在           → 写入最新模板
 *  - 存在且含本种子标记    → 版本旧则升级覆盖
 *  - 存在但无标记（被改过）→ 绝不覆盖
 */
object AgentContextSeed {

    private const val TAG = "AgentContextSeed"
    private const val FILE_NAME = "AGENTS.md"
    private const val MARKER_PREFIX = "<!-- dsh-android AGENTS seed v"
    /** 当前模板版本：改文案必须同步递增，旧版才会被升级覆盖 */
    private const val SEED_VERSION = 14

    fun ensure(ctx: Context) {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val existing = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        if (existing != null && !existing.contains(MARKER_PREFIX)) {
            Log.i(TAG, "AGENTS.md exists without seed marker (user-authored); leaving untouched")
            return
        }
        if (existing != null && containsVersion(existing, SEED_VERSION)) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(render(ctx))
            Log.i(TAG, "AGENTS.md seeded (v$SEED_VERSION, ${file.length()} bytes)")
        }.onFailure { Log.w(TAG, "seed AGENTS.md failed: ${it.message}") }
    }

    private fun containsVersion(text: String, v: Int): Boolean =
        text.contains("$MARKER_PREFIX$v ")

    private fun render(ctx: Context): String {
        val mode = Privilege.getMode(ctx).name.lowercase()
        val shz = if (mode == "shizuku") """
- shz: run `shz <command>` to execute a command as the adb identity (uid 2000) — process management (`shz "ps -A"`, `shz "am force-stop <pkg>"`), system properties, package queries. Only available in Shizuku mode.
""" else ""
        val active = ExtensionManager.activeRoots(ctx).joinToString(", ") { it.name }
            .ifEmpty { "none yet" }
        return """$MARKER_PREFIX$SEED_VERSION — managed by DSH Mobile. Edits below this line are preserved until the app upgrades this seed.
# Environment: Android (read this first — do not re-explore)

You are running inside the DSH Mobile app on Android. This file is a complete, verified map of your host — trust it and start the user's task immediately. Do NOT probe the environment: skip `uname`, `cat /etc/os-release`, `which -a`, `ls /`, `apt-get`, and any "let me see what's available" sweeps entirely; every fact you could discover is already below.

## Platform facts
- Kernel: Linux (Android). libc is **bionic**, NOT glibc/musl. Userland is a Termux-built toolchain.
- There is **no**: systemd, sudo-as-you-know-it, apt/dpkg, xdg-open, X11/Wayland, Python (unless you install it), gcc/clang toolchain.
- SELinux is enforcing: writes outside the app sandbox and `link()` syscalls are denied. Atomic publish = write temp + `rename()`.
- System utilities live in `/system/bin` (toybox: ls/cp/mv/rm/grep/find/sed/tar/ps with limited flags). Our toolchain lives in `bin/` and takes PATH precedence.

## Working environment (already mapped — do not re-explore)
- Your cwd starts at `${'$'}HOME` (the app's dsh-home). Inside: `profiles/` (config), `sessions/` (conversation data), `storages/` (tool state). Your work products belong here too.
- The Node runtime and its npm packages (`lib/node_modules`) live in the sibling engine directory — `which node` shows the path if you ever need it; you never need to go there directly.
- Beyond `${'$'}HOME` and the engine directory there is nothing to discover: the rest of the filesystem is the read-only Android system image (`/system`, `/vendor`, `/data` of other apps is inaccessible). Exploring it yields nothing useful.

## Toolchain (preinstalled, on PATH)
- `node` — the engine itself is node; same binary for your scripts.
- `bash` — bionic build (readline/ncurses closure complete); POSIX-ish, no bash-specific loadables from /system.
- `rg` — ripgrep, native arm64. Use it for search; faster than `grep -r` on toybox.
- `pnpm` / `corepack` — package management works offline via the bundled corepack shim.
- `curl` — binary-safe wrapper around node fetch: -s/-sS/-I/--json/-L/-X/-H*/-d/-o/--max-time; `-o` writes raw bytes (safe for binaries).
- `psx <pattern>` / `killx <pattern>` — list/kill processes matched **by command NAME (comm) only**. **NEVER use `pkill -f` or `ps | grep <full-cmdline>` + kill**: your own bash -c / node -e command line contains the pattern and kills itself (SIGKILL / no output).
- `say [-f] <text>` — speak text aloud via system TTS (voice output; -f interrupts current speech). Long texts are truncated to 4000 chars. Use for task-completion announcements when the user may not be looking at the screen.
$shz
## Extension Center (on-demand runtimes & tools, China-direct)
- Python / Git / OpenJDK-17 / Clang / Go / Rust / Ruby / PHP / Lua / Perl / FFmpeg / ImageMagick / OpenSSH / adb / aapt+apksigner+gradle / vim are **NOT preinstalled but installable on demand** from Termux mirrors — no GitHub dependency, China-direct fast.
- Check what exists: `curl -s http://127.0.0.1:3083/ext/list` → JSON array of {id, name, category, state, version, installing}; state: red=not installed, yellow=installed but inactive, green=activated. Always check here before claiming a tool is missing.
- Install on demand: `curl -s -X POST http://127.0.0.1:3083/ext/install -d '{"id":"python"}'` → HTTP 202 started (200 = already green, 409 = installing). The app resolves the full dependency closure, verifies SHA-256, installs, activates and pushes a system notification when done. Poll /ext/list until state=green. Reinstall a broken/legacy-layout extension with `force:true` (wipes that extension dir, incl. anything hand-installed inside it), then remind the user to restart the engine.
- After a fresh activation, new binaries enter PATH only after an engine restart — remind the user to open Settings and tap the restart item (Chinese UI: 设置 → 重启引擎; English UI: Settings → Restart engine).
- Currently activated: $active.
## Building Android apps on the phone (toolchain gaps you must fill)
The Extension Center's `android-buildtools` ships `aapt`, `apksigner`, `gradle` and **`d8`** (DEX compiler). `javac` comes with the `openjdk-17` / `openjdk-21` extensions. Two things are **not** installable from the Termux repo and you must handle them yourself:

1. **`android.jar` is missing** — compiling anything that touches `android.*` (Activity, Context, View…) fails with "package android.app does not exist" without it. It is **not** in the Termux repo, and the device's `/system/framework/framework.jar` is **DEX**, not class files, so it cannot serve as a javac classpath.
   **Fix**: download the official SDK platform package and trim it —
   `python3 <repo>/scripts/extract-android-stub.py 36 ~/android.jar`
   (downloads `platform-36_r01.zip` from dl.google.com → **~9.9MB**, keeps the `.class` stubs plus `resources.arsc`, verified with both javac and aapt). Put the result on `CLASSPATH` / `javac -cp`.
   ⚠️ Needs the **v1.2.52+** version of `extract-android-stub.py` (older revisions dropped `resources.arsc`, so the jar worked for javac but failed at the aapt step). If your checkout predates that, `git pull` first, or pass `-I /system/framework/framework-res.apk` to aapt instead.
   ⚠️ `aapt -I` needs the framework **resource table** to resolve attributes like `android:versionCode`; the trimmed jar keeps `resources.arsc` for exactly this reason. If you have an older trimmed jar without it, use `-I /system/framework/framework-res.apk` for the aapt step instead (both work).
2. **`d8` needs a JDK on PATH** — it is a Java program (`share/java/d8.jar`) and its wrapper calls `java`. The JDK's own `bin/` entries are created by dpkg post-install scripts, which the extension installer does **not** run; the engine already scans `lib/jvm/*/bin` (see PATH), so call the binary by absolute path if `java` is not found. **Do not hard-code an extension id or JVM version** — which JDK is present varies (`openjdk-17` is the catalog entry; `android-buildtools` also bundles `java-21-openjdk`). Discover it:
   ```sh
   JAVA=${'$'}(ls -d ${'$'}PREFIX/extensions/*/lib/jvm/*/bin/java 2>/dev/null | head -1)
   "${'$'}JAVA" -cp ${'$'}PREFIX/extensions/android-buildtools/share/java/d8.jar com.android.tools.r8.D8 …
   ```
   (once a JDK extension is active, `java`/`javac` are on PATH — try plain `javac` first.)

**Full local APK build chain (each step verified on-device):**
```sh
# 1) Java source → class files  (needs android.jar from step above)
javac -cp ~/android.jar -d out/ src/com/example/App.java
# 2) class files → DEX
java -cp ${'$'}PREFIX/extensions/android-buildtools/share/java/d8.jar      com.android.tools.r8.D8 --output out-dex/ --lib ~/android.jar out/com/example/*.class
# 3) package + align + sign
aapt package -f -M AndroidManifest.xml -I ~/android.jar -F app-unsigned.apk
#    -I must carry the framework RESOURCE TABLE (resources.arsc) or every
#    android:xxx attribute fails with "No resource identifier found for
#    attribute 'versionCode' in package 'android'". The jar from step above
#    keeps resources.arsc; if you use an older trimmed jar, pass
#    -I /system/framework/framework-res.apk here instead.
#    (add out-dex/classes.dex into the apk, then:)
zipalign -f 4 app-unsigned.apk app-aligned.apk
apksigner sign --ks my.keystore --out app-signed.apk app-aligned.apk
```
For Gradle projects, `gradle assembleDebug` works too — Gradle will still need `android.jar` for the compile task, and it downloads its own dependencies over the network.

## LAN access (implement it yourself when asked)
The user may ask to reach the Web UI from another device on the LAN. **Upstream dsh deliberately refuses this** and the raw loopback address will not work through a naive port-forward, for four separate reasons you must all handle:

1. **`--host 0.0.0.0` is refused by dsh on purpose** (`dsh-web-app/lib/startup.js` errors out: it would expose remote code execution to the network). The server always binds `127.0.0.1`. Do not try to bypass this.
2. **`crypto.randomUUID()` does not exist outside a secure context.** A LAN IP is not a secure context, so the frontend cannot generate RPC ids → the realtime channel never connects → the page loads but nothing works.
3. **`connection.isLoopbackHostname(pageLocation.hostname)` gates settings features** (model config, file buttons). False over LAN → those UI parts are hidden.
4. **`/api` enforces same-origin**: after proxying, `Origin` and `Host` must both point at the upstream or requests get 403.

**What to do**: write a small reverse proxy (a few dozen lines, node built-ins only — `http`, `net`, `fs`) that listens on `0.0.0.0:<PORT>` and forwards to `127.0.0.1:3080`, and that:
- injects a `crypto.randomUUID` polyfill into HTML `<head>` (implement it with `crypto.getRandomValues`, which *is* available in insecure contexts);
- rewrites JS: `isLoopbackHostname(pageLocation.hostname)` → `isLoopbackHostname(() => true)`. Match the **bare function call** — upstream code is `isLoopbackHostname(pageLocation.hostname)` with no dot prefix (an earlier version of this note said `connection.isLoopbackHostname(...)`, and a regex written for that form silently matches nothing); allow an optional leading dot to cover both. Keep the function name in the replacement — replacing the whole call yields a syntax error;
- sets upstream `Host`/`Origin` to `127.0.0.1:3080`, and forwards WebSocket upgrades (`server.on('upgrade')`);
- strips `accept-encoding` upstream and rewrites only `identity` responses, then sets `content-length` and deletes `transfer-encoding`;
- reads the **latest** `?token=` from the engine log (`<engineRoot>/engine.log`, tail ~64KB, take the last match) and appends it to the first `/` request so the browser gets its session cookie;
- optionally enforces HTTP Basic auth, and always tells the user to set a password.

Run it with the engine's own node (`node <script>`), then tell the user the LAN URL `http://<phone-ip>:<PORT>`. **Always warn**: this exposes an agent that can read/write files and run commands — trusted networks only, set a password, stop it when done.

## Extension troubleshooting (self-heal first, report second)
Extensions live in `${'$'}PREFIX/extensions/<id>/{bin,lib}` — PATH/LD_LIBRARY_PATH already cover every **activated** one. When something an extension provides misbehaves, match the exact error and fix it yourself:

1. **`command not found`** — not installed (red) or installed-but-inactive (yellow). `curl -s -X POST http://127.0.0.1:3083/ext/install -d '{"id":"<id>"}'`, poll `/ext/list` until green, then make it live: restart the engine YOURSELF via the accessibility bridge — `scr dump` then `scr tap-text` with the **top toolbar button's label as it actually appears on screen** — English UI shows "Restart", Chinese UI shows 重启. Always dump first and use the label you observe; never assume one language. If `scr` reports the service is disabled, hand the user that one step. New PATH entries only apply after this restart.
2. **`CANNOT LINK EXECUTABLE: library "libX.so" not found`** — the extension's `lib/` is missing a runtime dependency.
   a. First try one forced reinstall: `{"id":"<id>","force":true}` — the app re-pulls the full dependency closure.
   b. Still missing — fetch that library from the Termux mirror yourself. Package index: `https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz` (phones are aarch64; emulators x86_64). A `.deb` is an `ar` archive wrapping `data.tar.xz`; `python3` (python extension) is the easiest extractor (`lzma`+`tarfile`, ~10 lines), or `xz -dc | tar -x` once archivers is installed. Extract only `data/data/com.termux/files/usr/lib/<lib>` into `${'$'}PREFIX/extensions/<id>/lib/`, then re-run the tool to confirm.
3. **`bad interpreter: No such file or directory` / `env: xxx: not found`** — a shebang points at a missing interpreter. Locate the real one (`ls ${'$'}PREFIX/extensions/*/bin`) and rewrite line 1 to that absolute path (`sed -i '1s|.*|#!/abs/path|' <file>`). The app already auto-repairs the common shim cases (sh/env/perl/python) on every start; anything still broken needs this.
4. **`扩展目录发布失败…` during install/reinstall** — the target extension dir holds leftovers the app cannot delete (typically root-owned, from Root-mode runs). Do NOT retry in a loop. Tell the user precisely: switch to Root mode and retry once (the app then cleans with `su`), or delete `${'$'}PREFIX/extensions/<id>` with a root-capable file manager. (Newer builds fall back to a merge-publish: if `/ext/list` shows the version, the extension is usable — mention any stale-file warning instead of declaring failure.)
5. **Check evidence before acting** — `curl -s http://127.0.0.1:3083/ext/list` (state/version/installing) and `${'$'}PREFIX/engine.log` (per-install entry counts, `bins 缺失` warnings, `boot step` timings).

**Reporting rule**: never just say "it doesn't work". Report the exact command + exact error, what you already tried and its outcome, then either the fix you applied or the single next action for the user (one line, no menus).

## Privilege mode: `$mode` (managed var DSH_ANDROID_PRIV_MODE)
- normal: sandboxed app uid. Everything under ${'$'}HOME works; system-level changes are impossible by design.
- shizuku: same sandbox + the `shz` bridge above.
- root: the engine itself runs as uid 0 — full device access, but stay inside ${'$'}HOME unless the user asks otherwise; breaking the host breaks your own workspace.

**git under `root` mode** (two traps, both one flag away):
1. **Ownership check** — repos the app created are owned by the app uid, so root-run git refuses with `fatal: detected dubious ownership in repository`. Pass `-c safe.directory='*'` (or the specific path) to the command: `git -c safe.directory='*' log`.
2. **No writable global config** — in root mode `${'$'}HOME` may resolve to `/`, so `git config --global …` fails with `could not lock config file //.gitconfig: Read-only file system`. Use per-command identity instead (`git -c user.name=… -c user.email=… commit`), or export `HOME=${'$'}DSH_HOME` for the session before running git.

## GUI / preview
There is no display server. To show the user anything visual, start a web server **with node** — the built-in `node:http` module or a pure-JS framework installed via `pnpm` — bound to a loopback port (e.g. `node server.js` listening on 127.0.0.1:3000), then reply with the plain URL `http://127.0.0.1:<port>`; the app's WebView opens it as a live preview when the user taps it. Never reach for `python -m http.server` or other interpreters' servers — there is no Python/PHP/busybox httpd here; **node is the only first-class server runtime**.

## Notifications & screen control (Android powers, use them)
- `notify <message>` — push an Android system notification. **You MUST call this when a long task finishes** (or when you need the user's attention while they may be away): `notify 构建完成，测试全部通过`.
- `scr dump` — read the current phone screen: JSON of visible texts with coordinates and clickability. Requires the user to have enabled the accessibility service in system settings (returns an error otherwise).
- `scr tap <x> <y>` — tap the phone screen at pixel coordinates.
- `scr tap-text <text>` — find a node containing that text and tap it.
Typical flow: `scr dump` → pick a target → `scr tap-text "<the label you saw in the dump>"`. **Never hardcode a dialog label**: system dialogs (e.g. the accessibility-permission prompt) are localized by the *system* locale, independent of the app language — Chinese systems show 允许/确定, English ones show Allow/OK. Dump first, then match what is actually there. Use it to operate other apps when the user asks you to automate something on the phone.

## Working agreements
- Start the user's task now. This file has already answered "where am I".
- Never install glibc/native binaries (npm rebuild, prebuilt .so for linux-x64) — they cannot run here; prefer pure-JS packages or the bundled tools.
- Need a language/tool you don't see on PATH? Check `/ext/list` and install via `/ext/install` (see Extension Center above). Only if neither the list nor PATH has it, say so plainly instead of improvising package installs.
"""
    }
}
