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
    // 必须 > guardian 两阶段所需（5 回滚 + 5 归档 = 10 次连续真死），
    // 否则第 8 次就进 Failed 停机、安全模式在数学上永远无法触发（实测事故）
    const val MAX_RESTART = 14

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
     * Android 适配覆盖层（v1.2.36）：写成 YAML patch 文件，由启动参数 `--patch` 注入。
     *
     * 为什么必须覆盖沙箱模式：dsh 的沙箱后端是 Linux landlock / macOS seatbelt，
     * **Android 上两者都不存在** → sandbox-policy 默认 `read-only`：
     *   - read-only/workspace-write 下 bash 工具直接拒绝执行（模拟器实测原文：
     *     `sandbox mode "workspace-write" ... refusing to run the command unconfined;
     *      no sandbox backend usable on host`）→ AI 一条命令都跑不了；
     * 本项目的安全边界是 App 自身的权限模式（Normal 沙箱 / Shizuku ADB 级 / Root）
     * 与 su 闸门（applySuGate），而非引擎内部沙箱，故置 danger-full-access。
     * 用户在 WebUI 仍可逐会话选择更严格模式。
     *
     * 只覆盖 id 精确匹配的插件；上游升级若改 id，未知条目会被忽略（不阻断启动）。
     */
    fun ensureAndroidOverlay(ctx: android.content.Context): File {
        val f = File(engineRoot(ctx), "android-overlay.yml")
        // NOTE: keep this file free of CJK text — the i18n gate scans Kotlin string
        // literals and would flag it (explanations live in the Kotlin comments below).
        //
        // Why all three rows are pinned here instead of relying on DSH_PERMISSION_MODE
        // alone: permission-presets derives its default from the *composed* pair
        // (sandbox mode + approval policy) and throws "composed sandbox and approval
        // defaults match no preset" when the pair names no table entry. A partially
        // applied permission stack (e.g. sandbox resolved but approval left at the
        // schema default 'ask') yields exactly {danger-full-access, ask} — which is
        // not in the table — and the entry then fails to activate on every boot.
        // Pinning the trio makes the composed pair match by construction.
        //
        // ⚠️ A patch REPLACES the targeted row's whole `config` (applyEntryPatches
        // does `target[key] = value`), so each row below restates every key it owns:
        //   · permission needs the full presets table — the plugin's own schema
        //     default only carries workspace-write + danger-full-access, and losing
        //     `read-only` would remove a preset the WebUI offers.
        //   · sandbox-policy needs workspaceRoot (schema marks it required).
        val body = """
            |# [dsh-android] Android compatibility overlay (auto-generated, do not edit)
            |# Sandbox backends (landlock/seatbelt) do not exist on Android, and any
            |# confined mode would make the AI's shell tool refuse to run every command
            |# ("no sandbox backend usable on host"). The security boundary is the app's
            |# permission mode (Normal/Shizuku/Root) plus the su gate, not an engine-side
            |# sandbox. The user can still pick a stricter per-session preset in the WebUI.
            |#
            |# DSH_PERMISSION_MODE=danger-full-access is also exported (see buildEnv) so the
            |# upstream expressions agree with this layer; these rows are the authority.
            |- id: sandbox-policy
            |  config:
            |    mode: danger-full-access
            |    workspaceRoot: !!js process.cwd()
            |
            |- id: approval
            |  config:
            |    policy: never
            |
            |- id: permission
            |  config:
            |    defaultPreset: danger-full-access
            |    presets:
            |      read-only:
            |        sandbox: read-only
            |        approval: ask
            |        name: read-only
            |        description: Read-only file access; every write requires approval.
            |      workspace-write:
            |        sandbox: workspace-write
            |        approval: ask
            |        name: workspace-write
            |        description: Write inside the workspace and permitted temporary directories; wider retries require approval.
            |      danger-full-access:
            |        sandbox: danger-full-access
            |        approval: never
            |        name: danger-full-access
            |        description: Full file access without approval prompts.
            |
            |# Registers DSH_ANDROID_PRIV_MODE as a managed shell variable. The value
            |# cannot arrive by process inheritance: shell-env rebuilds the DSH_*
            |# namespace per shell call and injects only registered contributions.
            |# A relative name is resolved against this overlay's directory.
            |- insert:
            |    - id: android-priv-mode
            |      name: ./android-plugins/priv-mode.mjs
            |""".trimMargin()
        runCatching {
            if (!f.isFile || f.readText() != body) f.writeText(body)
        }
        // The overlay references the plugin by relative path, so the plugin file must
        // sit beside it. Deploy from assets (kept in sync by the same idempotent check).
        runCatching {
            val src = ctx.assets.open("android-plugins/priv-mode.mjs").use { it.readBytes() }
            val dst = File(f.parentFile, "android-plugins/priv-mode.mjs")
            dst.parentFile?.mkdirs()
            val text = src.toString(Charsets.UTF_8)
            if (!dst.isFile || dst.readText() != text) dst.writeText(text)
        }.onFailure { Log.w(TAG, "priv-mode plugin deploy failed: ${it.message}") }
        return f
    }

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
        applyAgentGates(ctx, root)
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
            // 取值统一小写（normal/shizuku/root），与 AGENTS 种子里 AI 读到的文档一致。
            // 此前注入 .name（ROOT）、种子写 lowercase（root）→ AI 按文档写
            // `[ "$DSH_ANDROID_PRIV_MODE" = "root" ]` 永远不成立，静默走错分支。
            "DSH_ANDROID_PRIV_MODE=${privMode.name.lowercase()}",
            // Android 标准环境（init 对普通进程的设定）。aapt / apksigner / zipalign 靠它
            // 判断「是否运行在 Android 上」，缺失时直接报 "ANDROID_DATA not set" 并退出
            // （扩展中心主推 android-buildtools，AI 又被种子引导使用这些工具 → 开箱即坏）。
            // 引擎进程环境会被所有子 shell 与扩展二进制继承，一处修复全局生效。
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "ANDROID_STORAGE=/storage",
            // 权限三件套的单一驱动源（v1.2.52）：上游 dsh-base 用这一个变量同时决定
            // sandbox-policy.mode、approval.policy 与默认预设，保证三者组合一致。
            // Android 没有 landlock/seatbelt，任何受限模式都会让 shell 工具拒绝执行
            // （"no sandbox backend usable on host"），故固定 danger-full-access；
            // 真实安全边界是 App 的权限模式（Normal/Shizuku/Root）与 su 闸门。
            "DSH_PERMISSION_MODE=danger-full-access",
        )
        // Perl/Ruby：编译期 @INC/$LOAD_PATH 硬编码 Termux 前缀（重写 shebang 碰不到），
        // 注入扩展内的库路径（Agent 实测注入后 json/openssl 等模块恢复正常）
        val perlLibs = extRoots.flatMap { ext ->
            File(ext, "lib/perl5").takeIf { it.isDirectory }
                ?.listFiles()?.filter { it.isDirectory }?.map { it.absolutePath } ?: emptyList()
        }
        val rubyLibs = extRoots.flatMap { ext ->
            // 真实布局 lib/ruby/<ver>/{,aarch64-linux-android}（Agent 实测：只注入
            // lib/ruby 不够 —— json.rb 在版本子目录下，且 json/ext/parser 在 arch 子目录）
            File(ext, "lib/ruby").takeIf { it.isDirectory }?.listFiles()
                ?.filter { it.isDirectory }
                ?.flatMap { ver ->
                    listOfNotNull(
                        ver.absolutePath,
                        File(ver, "aarch64-linux-android").takeIf { it.isDirectory }?.absolutePath,
                    )
                } ?: emptyList()
        }
        if (perlLibs.isNotEmpty()) env.add("PERL5LIB=" + perlLibs.joinToString(":"))
        if (rubyLibs.isNotEmpty()) env.add("RUBYLIB=" + rubyLibs.joinToString(":"))
        // git：编译期硬编码的系统级 gitconfig 指向 Termux 前缀 → 跳过（实测修复 git init）
        env.add("GIT_CONFIG_NOSYSTEM=1")
        // git 子命令查找路径（2026-10-05，Issue 反馈「git-remote-https 找不到」的根因）：
        // git 找 git-<cmd> 不靠 PATH，而靠编译期写死的 GIT_EXEC_PATH。Termux 包把它指向
        // /data/data/com.termux/files/usr/libexec/git-core —— 扩展装到别处后该目录不存在，
        // 于是 git-remote-https/http、git-upload-pack 等全部找不到（ELF 二进制内的路径
        // rewriteTermuxPaths 明确跳过，改不了）。GIT_EXEC_PATH 是官方支持的重定位机制
        // （git 文档 --exec-path："can also be controlled by setting the GIT_EXEC_PATH
        // environment variable"），一处注入即修复全部子命令。
        extRoots.firstOrNull { it.name == "git" }?.let { ext ->
            File(ext, "libexec/git-core").takeIf { it.isDirectory }?.let {
                env += "GIT_EXEC_PATH=${it.absolutePath}"
            }
            // 模板与系统配置同样硬编码 Termux 前缀：缺模板时 git init/clone 会告警或行为异常
            File(ext, "share/git-core/templates").takeIf { it.isDirectory }?.let {
                env += "GIT_TEMPLATE_DIR=${it.absolutePath}"
            }
            // 证书：git 经 libcurl 走 HTTPS，Termux 的 libcurl 编译期指向 Termux 证书路径。
            // 扩展闭包含 ca-certificates（git → openssl → ca-certificates），指向实装位置即可
            // （引擎侧 cert.pem 只在 runtime 根，扩展不共享）
            listOf("etc/tls/cert.pem", "etc/ssl/certs/ca-certificates.crt", "etc/ca-certificates.crt")
                .map { File(ext, it) }.firstOrNull { it.isFile }?.let {
                    env += "GIT_SSL_CAINFO=${it.absolutePath}"
                    env += "CURL_CA_BUNDLE=${it.absolutePath}"
                }
        }
        // ImageMagick：内置配置路径指向 Termux 前缀 → colors.xml 找不到，每次运行刷
        // "UnableToOpenConfigureFile `colors.xml'" 警告（Agent 实测：加此变量即干净 ✓）
        extRoots.firstOrNull { it.name == "imagemagick" }?.let { ext ->
            val etc = File(ext, "etc/ImageMagick-7")
            if (etc.isDirectory) env.add("MAGICK_CONFIGURE_PATH=" + etc.absolutePath)
        }
        // Python 扩展：Termux 二进制编译期 prefix 硬编码 /data/data/com.termux/files/usr，
        // 装进扩展根后找不到 stdlib，须显式指 PYTHONHOME=<扩展根>。
        // ⚠️ 只认扩展 id=python：imagemagick/lib 里是完整 stdlib 副本（连 os.py 都有，
        // 目录名/os.py 判据全被骗——PYTHONHOME 错指 imagemagick 实测事故）
        extRoots.firstOrNull { it.name == "python" }?.let { env += "PYTHONHOME=$it" }
        // 包管理器镜像（2026-10-05，Issue 反馈「pnpm 连不上 GitHub」）：
        // runtime 内没有任何 .npmrc，corepack/npm 默认走 registry.npmjs.org —— 国内直连常
        // 超时或被重置。三个变量覆盖三条路径，缺一不可：
        //   · COREPACK_NPM_REGISTRY —— corepack 拉取 pnpm/yarn 本体（corepack.cjs 内读取）
        //   · npm_config_registry    —— npm / pnpm 自身的包解析（小写环境变量是 npm 的规范形式）
        //   · NPM_CONFIG_REGISTRY    —— 大写别名，部分工具只认大写
        // 用户可通过设置里已导出的同名环境变量覆盖（种子文档也据此告诉 AI 如何换源）。
        listOf("COREPACK_NPM_REGISTRY", "npm_config_registry", "NPM_CONFIG_REGISTRY")
            .filterNot { System.getenv(it)?.isNotBlank() == true }
            .forEach { env += "$it=$NPM_REGISTRY_MIRROR" }
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
     *   覆盖系统 /system/bin/su。已 root 且投过权也不放行（符合「只有切 Root 才允许」）。
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
    private fun applyAgentGates(ctx: android.content.Context, root: File) {
        val bindir = File(root, "bin").apply { mkdirs() }
        try {
            // ── 门脚本：从 assets/gates 部署（v1.2.50）─────────────────────────
            // 此前 notify/scr/say 内嵌在 Kotlin 字符串里，每次调用 `exec node -e fetch`
            // 都要冷启一个 Node（实测 ~150ms/次 + 数十 MB 峰值内存；内存高水位时
            // scr dump 挂 >60s）。现改为 assets 里的纯 bash 脚本（配 _dsh_http.sh
            // 共享 HTTP 客户端），零 Node 冷启 + 内建超时。
            // 放在 assets 而非 Kotlin 字符串：避免 bash/Kotlin 双层转义（易错且难维护）。
            // 门脚本 shebang 用 #!@DSH_BASH@ 占位符：部署时替换为引擎 bash 的实际路径。
            // 必须用 bash（/dev/tcp 是 bash 特性；/system/bin/sh 是 toybox，不支持）。
            val bashPath = File(root, "bin/bash").absolutePath
            val libDir = File(root, "lib").absolutePath
            val broken = mutableListOf<String>()
            // psx/killx（v1.2.52 恢复）：v1.2.50 把门脚本从 Kotlin 字符串搬到 assets 时
            // 漏掉了这两个，但种子仍在教 agent 使用 → agent 照着调用得到 command not found
            // （Agent 审计 N8 实测）。它们解决的是真问题：agent 常以 `bash -c '... pkill -f X'`
            // 形式执行，完整命令行含 pattern 会命中自己并自杀，必须按 comm 匹配。
            listOf("_dsh_http.sh", "notify", "scr", "say", "psx", "killx").forEach { name ->
                val dst = File(bindir, name)
                runCatching {
                    val text = ctx.assets.open("gates/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                    // ⚠️ CRLF 剥离（v1.2.52 事故）：门脚本曾被以 CRLF 打进 APK，
                    // 首行变成 `#!/system/bin/sh\r` → 内核按字面找解释器，报
                    // "bad interpreter: No such file or directory"（误导性地像文件丢失）。
                    // 三个门脚本 100% 失效，而 notify/scr/say 是种子要求 agent 必用的能力。
                    // 根因是工作区文件在 .gitattributes 生效前就已以 CRLF 检出；此处
                    // 无条件剥离，作为不依赖构建环境行尾的兜底防线。
                    val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
                    dst.writeText(
                        normalized.replace("@DSH_BASH@", bashPath).replace("@DSH_LIBDIR@", libDir),
                    )
                }.onFailure { Log.w(TAG, "agent gate $name deploy failed: ${it.message}") }
            }
            listOf("notify", "scr", "say", "psx", "killx").forEach { File(bindir, it).setExecutable(true, false) }
            File(bindir, "_dsh_http.sh").setReadable(true, false)

            // 部署后自检（v1.2.52）：逐个校验首行不含 \r 且指向存在的解释器。
            // 失败不静默——写进 engine.log 并给出明确原因，避免「看起来像文件丢了」的误判。
            listOf("_dsh_http.sh", "notify", "scr", "say", "psx", "killx").forEach { name ->
                val f = File(bindir, name)
                val head = runCatching {
                    f.inputStream().use { ins ->
                        val buf = ByteArray(128)
                        val n = ins.read(buf)
                        String(buf, 0, maxOf(n, 0), Charsets.UTF_8).lineSequence().first()
                    }
                }.getOrNull().orEmpty()
                when {
                    !f.isFile -> broken += "$name (missing)"
                    head.contains('\r') -> broken += "$name (CRLF in shebang: ${head.take(30)})"
                    head.startsWith("#!") && !File(head.removePrefix("#!").trim()).isFile ->
                        broken += "$name (interpreter missing: ${head.removePrefix("#!").trim()})"
                }
            }
            if (broken.isNotEmpty()) {
                Log.w(TAG, "agent gates BROKEN: ${broken.joinToString("; ")}")
            } else {
                Log.i(TAG, "agent gates: notify/scr/say/_dsh_http.sh deployed (bridge :3083)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "agent gates: ${e.message}")
        }
    }

    private const val TAG = "EngineConfig"

    /**
     * npm 生态默认镜像。corepack / npm / pnpm 在 runtime 内无任何 .npmrc，
     * 默认 registry.npmjs.org 在国内网络下经常超时（实测反馈「pnpm 连不上」）。
     * 淘宝源为国内通用镜像，可被进程环境变量覆盖（见 buildEnv 的 filterNot 守卫）。
     */
    private const val NPM_REGISTRY_MIRROR = "https://registry.npmmirror.com"
}
