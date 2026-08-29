package app.dsh.mobile.engine

import android.util.Log
import java.io.File

/**
 * 引擎静态配置与目录拓扑。
 *
 * 目录约定（全部位于 app 私有存储内，targetSdk 28 下可执行）：
 *   filesDir/
 *     engine/          运行时根（node + node_modules，升级时整体替换）
 *       bin/node       bionic 编译的 Node 二进制（Termux 构建产物）
 *       lib/           动态库（libuv/openssl 等）
 *     dsh-home/        $DSH_HOME（凭证、会话、配置）
 *     workspaces/      默认工作区根
 *     tmp/             TMPDIR
 *
 * 升级引擎时绝不能触碰 dsh-home 与 workspaces —— 会话数据是用户资产。
 */
object EngineConfig {

    const val DEFAULT_PORT = 3080
    const val HEALTH_TIMEOUT_MS = 45_000L
    const val HEALTH_INTERVAL_MS = 500L

    /** web listen 后的稳定观察窗：挺过此窗才判定 Healthy 并快照 last-good */
    const val STABLE_WINDOW_MS = 3_000L

    /** 崩溃退避序列（毫秒），连续健康后重置 */
    val BACKOFF_STEPS = longArrayOf(2_000, 4_000, 8_000, 16_000, 32_000)
    const val MAX_RESTART = 8

    fun engineRoot(ctx: android.content.Context): File =
        File(ctx.filesDir, "engine").apply { mkdirs() }

    fun nodeBin(ctx: android.content.Context): File =
        File(engineRoot(ctx), "bin/node")

    fun dshEntry(ctx: android.content.Context): File =
        File(engineRoot(ctx), "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

    fun dshHome(ctx: android.content.Context): File =
        File(ctx.filesDir, "dsh-home").apply { mkdirs() }

    fun workspaces(ctx: android.content.Context): File =
        File(ctx.filesDir, "workspaces").apply { mkdirs() }

    fun tmpDir(ctx: android.content.Context): File =
        File(ctx.filesDir, "tmp").apply { mkdirs() }

    /**
     * 组装子进程环境变量。
     * PATH/LD_LIBRARY_PATH/PREFIX 对齐 Termux 布局，保证 bionic 二进制能找到依赖库。
     *
     * OPENSSL_CONF/SSL_CERT_FILE：Termux 编译的 node 将 OpenSSL 目录硬编码为
     * /data/data/com.termux/files/usr/etc/tls；设备共存真实 Termux 时 fopen 命中
     * EACCES → "OpenSSL configuration error" → node 启动即退（m1.1 真机事故根因，
     * 模拟器因无 com.termux 目录呈 ENOENT 静默通过故未暴露）。显式指回自带 etc/tls
     * 与宿主 Termux 彻底隔离。
     */
    fun buildEnv(ctx: android.content.Context, port: Int): Array<String> {
        val root = engineRoot(ctx)
        // 运行权限模式（m1.24）：注入给 AI 侧感知，令其按模式调整执行行为
        val privMode = Privilege.getMode(ctx)
        // m1.29：按模式控制 AI 子进程能否用 su。engine/bin 在 PATH 首位，
        // 非 Root 模式往 engine/bin 放一个「拒绝执行」的 su 遮罩（覆盖 /system/bin/su），
        // Root 模式移除遮罩放行真 su。这样只有切到 Root 模式 AI 才提权。
        applySuGate(root, privMode)
        // m1.30：Shizuku 模式注入 shz 包装器（AI 显式 `shz <adb命令>` 走 ADB 级执行）；
        // 其他模式删除，AI 调 shz 将无命令。
        applyShzGate(root, privMode, port)
        // v1.1.0：notify/scr 包装器（所有模式可用——通知与无障碍是 App 自身能力，
        // 经 AgentBridge 127.0.0.1:3083 转发）。
        applyAgentGates(root)
        // v1.2.0 扩展环境：已激活扩展的 bin/lib 并入 PATH/LD_LIBRARY_PATH
        // （顺序：engine 自带 → 扩展 → 系统，保证 su/notify/scr 闸门优先级不被扩展覆盖）
        val extRoots = ExtensionManager.activeRoots(ctx)
        // JVM 扩展（openjdk 等）：Termux 的 java 入口靠 postinst 建链接（解包器不执行），
        // 本体在 lib/jvm/<ver>/bin —— 整段并入 PATH，java/javac/jar 全工具一次到位
        val jvmBins = extRoots.flatMap { ext ->
            File(ext, "lib/jvm").takeIf { it.isDirectory }
                ?.listFiles()?.mapNotNull { File(it, "bin").takeIf { b -> b.isDirectory } }
                ?: emptyList()
        }
        val env = mutableListOf(
            "PATH=" + (
                listOf(File(root, "bin"), File(root, "usr/bin")) +
                    extRoots.map { File(it, "bin") } + jvmBins +
                    listOf("/system/bin", "/system/xbin")
                ).joinToString(":"),
            "DSH_SHZ_PORT=${ShizukuHttpBridge.port(port)}",
            "LD_LIBRARY_PATH=" + (
                listOf(File(root, "lib"), File(root, "usr/lib")) +
                    extRoots.map { File(it, "lib") }
                ).joinToString(":"),
            "PREFIX=$root",
            "HOME=${dshHome(ctx)}",
            "DSH_HOME=${dshHome(ctx)}",
            "TMPDIR=${tmpDir(ctx)}",
            "PORT=$port",
            "NODE_ENV=production",
            "DSH_ANDROID_PRIV_MODE=${privMode.name}",
        )
        // Python 扩展：Termux 二进制编译期 prefix 硬编码 /data/data/com.termux/files/usr，
        // 装进扩展根后找不到 stdlib，须显式指 PYTHONHOME=<扩展根>。
        // ⚠️ 只认扩展 id=python：imagemagick/lib 里是完整 stdlib 副本（连 os.py 都有，
        // 目录名/os.py 判据全被骗——PYTHONHOME 错指 imagemagick 实测事故）
        extRoots.firstOrNull { it.name == "python" }?.let { env += "PYTHONHOME=$it" }
        // 编译工具链支持（AI 交叉编译清单实测）：
        // - GOTMPDIR：Termux go 的临时目录回退硬编码 /data/data/com.termux（不存在）→ 显式指到引擎 tmp
        // - LIBRARY_PATH：链接期库搜索（rust-lld/clang 的 -lunwind 等命中扩展 lib）
        // - CPATH：头文件搜索——ndk-sysroot 的 asm/types.h 在 include/<triple>/ 子目录，
        //   clang 内置的 $PREFIX 头路径是编译期硬编码、指向已不存在的 Termux 前缀
        // - RUSTFLAGS：rustc 传给 rust-lld 的额外 -L（LIBRARY_PATH 对 lld 不生效）
        tmpDir(ctx).takeIf { it.isDirectory }?.let { env += "GOTMPDIR=$it" }
        val libPaths = (listOf(File(root, "lib")) + extRoots.map { File(it, "lib") })
            .filter { it.isDirectory }.joinToString(":")
        if (libPaths.isNotEmpty()) env += "LIBRARY_PATH=$libPaths"
        val incPaths = extRoots.flatMap { ext ->
            listOf(File(ext, "include"), File(ext, "include/aarch64-linux-android"))
        }.filter { it.isDirectory }.joinToString(":")
        if (incPaths.isNotEmpty()) env += "CPATH=$incPaths"
        extRoots.firstOrNull { it.name == "rust" }?.let {
            env += "RUSTFLAGS=-C link-arg=-L${it.absolutePath}/lib"
        }
        File(root, "etc/tls/openssl.cnf").takeIf { it.isFile }?.let { env += "OPENSSL_CONF=$it" }
        File(root, "etc/tls/cert.pem").takeIf { it.isFile }?.let { env += "SSL_CERT_FILE=$it" }
        return env.toTypedArray()
    }

    /**
     * su 闸门（m1.29）：engine/bin 在 PATH 首位，控制 AI 子进程能否提权。
     * - 非 Root（普通/Shizuku）：写入一个拒绝执行的 su 遮罩 —— AI 调 su 立即报错退出，
     *   覆盖系统 /system/bin/su。已 root 且投过权也不放行（符合"只有切 Root 才允许"）。
     * - Root：删除遮罩，让 AI 走系统真 su（引擎整体已以 root 启动）。
     */
    private fun applySuGate(root: File, mode: PrivMode) {
        val bindir = File(root, "bin").apply { mkdirs() }
        val suShim = File(bindir, "su")
        if (mode == PrivMode.ROOT) {
            if (suShim.exists()) {
                suShim.delete()
                Log.i(TAG, "su gate: ROOT mode, removed su shim (AI can su)")
            }
            return
        }
        // 非 Root：写拒绝遮罩（幂等，总是覆盖成正确内容）
        try {
            suShim.writeText("#!/system/bin/sh\n" +
                "# [dsh-android] su gate: priv mode != ROOT, deny su.\n" +
                "echo 'su: Permission denied (dsh-android: run as Root mode to gain su)' >&2\n" +
                "exit 1\n")
            suShim.setExecutable(true, false)
            if (!suShim.canExecute()) {
                // 某些 ROM 需显式 chmod；setExecutable 失败罕见，写日志即可
                Log.w(TAG, "su gate: chmod failed on su shim")
            }
            Log.i(TAG, "su gate: mode=$mode, su denied via shim in engine/bin")
        } catch (e: Exception) {
            Log.w(TAG, "su gate: write su shim failed: ${e.message}")
        }
    }

    /**
     * shz 闸门（m1.30）：engine/bin 在 PATH 首位，控制 AI 能否用 ADB 级能力。
     * 仅 Shizuku 模式注入 `shz` 包装器：
     *   - shz <cmd>：把 <cmd> 经 HTTP POST 到 ShizukuHttpBridge（127.0.0.1:DSH_SHZ_PORT），
     *     由 Privilege.shizukuExec 以 adb 身份执行并打印输出。
     *   - 其他模式删除 shz，AI 调 shz 报 command not found（无 ADB 能力）。
     */
    private fun applyShzGate(root: File, mode: PrivMode, port: Int) {
        val bindir = File(root, "bin").apply { mkdirs() }
        val shz = File(bindir, "shz")
        if (mode != PrivMode.SHIZUKU) {
            if (shz.exists()) shz.delete()
            return
        }
        try {
            val bridgePort = ShizukuHttpBridge.port(port)
            // 关键：node -e CODE -- "$@" 时 `--` 被 node 消费掉，process.argv=[node, arg1..]，
            // 因此必须 slice(1)。旧版 slice(2) 会丢弃第一个参数（如 shz id 变 shz ），导致
            // bridge 收到空/残缺命令 → empty command / socket hang up。改用 fetch 简化并更稳。
            shz.writeText("#!/system/bin/sh\n" +
                "# [dsh-android] shz: run a command via Shizuku (adb uid) — Shizuku mode only.\n" +
                "# Posts the command to the in-process Android bridge, which executes it with\n" +
                "# IShizukuService.newProcess. Usage: shz <any shell command>\n" +
                "if [ \"$#\" -eq 0 ]; then echo 'usage: shz <command>' >&2; exit 2; fi\n" +
                "exec \"$(dirname \"$0\")/node\" -e '\n" +
                "  const port = Number(process.env.DSH_SHZ_PORT || " + bridgePort + ");\n" +
                "  const cmd = process.argv.slice(1).join(\" \");\n" +
                "  fetch(\"http://127.0.0.1:\" + port + \"/shizuku_exec\", { method: \"POST\", body: cmd })\n" +
                "    .then(async (res) => { const t = await res.text(); process.stdout.write(t); process.exit(res.status === 200 ? 0 : 1); })\n" +
                "    .catch((e) => { console.error(\"shz: \" + e.message); process.exit(2); });\n" +
                "' -- \"$@\"\n")
            shz.setExecutable(true, false)
            Log.i(TAG, "shz gate: mode=SHIZUKU, shz wrapper injected -> :$bridgePort")
        } catch (e: Exception) {
            Log.w(TAG, "shz gate: write shz failed: ${e.message}")
        }
    }

    /**
     * Agent 能力包装器（v1.1.0）：notify / scr，全模式注入。
     * 二者都是 node fetch 到 AgentBridge (127.0.0.1:3083) 的薄包装：
     *  - notify [message]        → POST /notify（任务完成系统通知；AGENTS.md 约定任务完成必调）
     *  - scr dump                → GET  /screen（读屏：可见文本+坐标 JSON）
     *  - scr tap <x> <y>         → POST /tap 坐标点击
     *  - scr tap-text <文本>     → POST /tap 按文本点击（无障碍服务开启才可用）
     */
    private fun applyAgentGates(root: File) {
        val bindir = File(root, "bin").apply { mkdirs() }
        try {
            val notify = File(bindir, "notify")
            notify.writeText("#!/system/bin/sh\n" +
                "# [dsh-android] notify: push an Android system notification (task done).\n" +
                "msg=\"${'$'}*\"\n" +
                "exec \"${'$'}(dirname \"${'$'}0\")/node\" -e '\n" +
                "  const body = JSON.stringify({ title: \"Agent 任务\", body: process.argv[1] || \"任务已完成\" });\n" +
                "  fetch(\"http://127.0.0.1:3083/notify\", { method: \"POST\", headers: {\"content-type\":\"application/json\"}, body })\n" +
                "    .then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(2));\n" +
                "' \"${'$'}msg\"\n")
            notify.setExecutable(true, false)

            val scr = File(bindir, "scr")
            scr.writeText("#!/system/bin/sh\n" +
                "# [dsh-android] scr: screen see & control via the accessibility service.\n" +
                "#   scr dump | scr tap <x> <y> | scr tap-text <text>\n" +
                "case \"${'$'}1\" in\n" +
                "  dump)\n" +
                "    exec \"${'$'}(dirname \"${'$'}0\")/node\" -e '\n" +
                "      fetch(\"http://127.0.0.1:3083/screen\").then(r => r.text()).then(t => { console.log(t); })\n" +
                "        .catch(e => { console.error(\"scr: \" + e.message); process.exit(2); });\n" +
                "    ' ;;\n" +
                "  tap)\n" +
                "    exec \"${'$'}(dirname \"${'$'}0\")/node\" -e '\n" +
                "      const body = JSON.stringify({ x: Number(process.argv[1]), y: Number(process.argv[2]) });\n" +
                "      fetch(\"http://127.0.0.1:3083/tap\", { method: \"POST\", headers: {\"content-type\":\"application/json\"}, body })\n" +
                "        .then(r => { console.log(r.ok ? \"tapped\" : \"tap failed\"); process.exit(r.ok ? 0 : 1); })\n" +
                "        .catch(e => { console.error(\"scr: \" + e.message); process.exit(2); });\n" +
                "    ' -- \"${'$'}2\" \"${'$'}3\" ;;\n" +
                "  tap-text)\n" +
                "    exec \"${'$'}(dirname \"${'$'}0\")/node\" -e '\n" +
                "      const body = JSON.stringify({ text: process.argv[1] });\n" +
                "      fetch(\"http://127.0.0.1:3083/tap\", { method: \"POST\", headers: {\"content-type\":\"application/json\"}, body })\n" +
                "        .then(r => { console.log(r.ok ? \"tapped\" : \"text not found\"); process.exit(r.ok ? 0 : 1); })\n" +
                "        .catch(e => { console.error(\"scr: \" + e.message); process.exit(2); });\n" +
                "    ' -- \"${'$'}2\" ;;\n" +
                "  *) echo \"usage: scr dump | scr tap <x> <y> | scr tap-text <text>\" >&2; exit 2 ;;\n" +
                "esac\n")
            scr.setExecutable(true, false)

            // v1.2.19：curl v2 —— 覆盖 runtime.zip 内置版（PATH 首位 engine/bin 优先）。
            // 修二进制下载损坏（r.text() UTF-8 重编码 → arrayBuffer 原始字节落盘），
            // 补 -sS/-v/-I/--json/-L 兼容；参数解析在 sh、body 只经 env 传递
            val curl = File(bindir, "curl")
            curl.writeText("#!/system/bin/sh\n" +
                "# [dsh-android] curl v2: binary-safe wrapper. -s -sS -v -I --json -L -X -H* -d -o --max-time\n" +
                "URL=\"\"; OUT=\"\"; METHOD=\"\"; DATA=\"\"; SILENT=0; HEAD=0; JSON=0; HDRS=\"\"\n" +
                "while [ ${'$'}# -gt 0 ]; do\n" +
                "  case \"${'$'}1\" in\n" +
                "    -s|--silent) SILENT=1 ;;\n" +
                "    -sS|-SS) SILENT=1 ;;\n" +
                "    -v|--verbose) ;;\n" +
                "    -I|--head) HEAD=1 ;;\n" +
                "    --json) JSON=1 ;;\n" +
                "    -L|--location) ;;\n" +
                "    -X|--request) METHOD=\"${'$'}2\"; shift ;;\n" +
                "    -H|--header) HDRS=\"${'$'}HDRS${'$'}2\\n\"; shift ;;\n" +
                "    -d|--data|--data-raw) DATA=\"${'$'}2\"; [ -z \"${'$'}METHOD\" ] && METHOD=POST; shift ;;\n" +
                "    -o|--output) OUT=\"${'$'}2\"; shift ;;\n" +
                "    --max-time|-m) shift ;;\n" +
                "    -*) ;;\n" +
                "    *) URL=\"${'$'}1\" ;;\n" +
                "  esac\n" +
                "  shift\n" +
                "done\n" +
                "[ -z \"${'$'}URL\" ] && { echo \"curl: no URL\" >&2; exit 2; }\n" +
                "CURLV2_H=\"${'$'}HDRS\" exec \"${'$'}(dirname \"${'$'}0\")/node\" -e '\n" +
                "(async () => {\n" +
                "  const [url, out, method, data, silent, head] = process.argv.slice(1);\n" +
                "  const hs = {};\n" +
                "  (process.env.CURLV2_H || \"\").split(\"\\n\").filter(Boolean).forEach(h => {\n" +
                "    const i = h.indexOf(\":\");\n" +
                "    if (i > 0) hs[h.slice(0, i).trim().toLowerCase()] = h.slice(i + 1).trim();\n" +
                "  });\n" +
                "  if (process.argv[8] === \"1\") { hs[\"content-type\"] = \"application/json\"; hs[\"accept\"] = \"application/json\"; }\n" +
                "  const r = await fetch(url, { method: method || (data ? \"POST\" : (head === \"1\" ? \"HEAD\" : \"GET\")), headers: hs, body: data || undefined, redirect: \"follow\" });\n" +
                "  if (silent !== \"1\") console.error(r.status + \" \" + (r.statusText || \"\"));\n" +
                "  if (out) {\n" +
                "    const buf = Buffer.from(await r.arrayBuffer());\n" +
                "    require(\"fs\").writeFileSync(out, buf);\n" +
                "    if (silent !== \"1\") console.log(\"saved \" + buf.length + \" bytes -> \" + out);\n" +
                "    process.exit(r.ok ? 0 : 22);\n" +
                "  }\n" +
                "  const t = await r.text();\n" +
                "  process.stdout.write(t);\n" +
                "  process.exit(r.ok ? 0 : 22);\n" +
                "})().catch(e => { console.error(\"curl: \" + e.message); process.exit(7); });\n" +
                "' -- \"${'$'}URL\" \"${'$'}OUT\" \"${'$'}METHOD\" \"${'$'}DATA\" \"${'$'}SILENT\" \"${'$'}HEAD\" \"${'$'}JSON\"\n")
            curl.setExecutable(true, false)

            // v1.2.19：psx/killx —— 按进程名（comm）匹配，杜绝 pkill -f 的自匹配误杀
            //（自己的 bash -c / node -e 命令行含目标串 → SIGKILL 自己的实测坑）
            val psx = File(bindir, "psx")
            psx.writeText("#!/system/bin/sh\n" +
                "# [dsh-android] psx: list processes matching by command NAME (comm) only.\n" +
                "# Never matches the full command line, so it can not kill/match itself.\n" +
                "[ -z \"${'$'}1\" ] && { echo \"usage: psx <comm-pattern>\" >&2; exit 2; }\n" +
                "ps -A -o pid,comm | grep -i -- \"${'$'}1\" | grep -v grep\n")
            psx.setExecutable(true, false)

            val killx = File(bindir, "killx")
            killx.writeText("#!/system/bin/sh\n" +
                "# [dsh-android] killx: kill by command NAME (comm) match, self-safe.\n" +
                "# usage: killx <comm-pattern>\n" +
                "[ -z \"${'$'}1\" ] && { echo \"usage: killx <comm-pattern>\" >&2; exit 2; }\n" +
                "me=${'$'}${'$'}\n" +
                "ps -A -o pid,comm | grep -i -- \"${'$'}1\" | grep -v grep | while read pid comm; do\n" +
                "  [ \"${'$'}pid\" != \"${'$'}me\" ] && kill \"${'$'}pid\" 2>/dev/null && echo \"killed ${'$'}pid ${'$'}comm\"\n" +
                "done\n")
            killx.setExecutable(true, false)

            Log.i(TAG, "agent gates: notify/scr/curl/psx/killx wrappers injected (bridge :3083)")
        } catch (e: Exception) {
            Log.w(TAG, "agent gates: ${e.message}")
        }
    }

    private const val TAG = "EngineConfig"
}
