package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import app.dsh.mobile.R
import java.io.File

/**
 * Linux 模式下的 DSH 引擎启动器。
 *
 * ## 与原生模式的根本差异
 *
 * | | 原生模式（现状） | Linux 模式（本类） |
 * |---|---|---|
 * | node | `filesDir/engine/bin/node`（bionic 动态） | rootfs 里的 musl node（`apk add nodejs`） |
 * | dsh 代码 | `filesDir/engine/lib/node_modules/...` | **同一份**，通过 bind 挂进容器 |
 * | 进程 | 直接 execve | `dsh_alpine` 容器内 execve |
 * | 工具链 | 无（要装扩展） | 完整 Linux（apk / gcc / python / git / ffmpeg） |
 *
 * ## 为什么引擎代码不复制进 rootfs
 *
 * 引擎的 `node_modules` 有 **355MB**。复制进 rootfs 意味着：
 * - 磁盘翻倍（355MB × 2）
 * - 升级引擎时要同步两边，容易不一致
 * - 首装时间从 1 秒涨到十几秒
 *
 * 改用 `-m /eng:<宿主路径>` bind 映射（实测已验证：容器内 `ls /eng/node_modules/
 * @deepseek-ai/dsh/lib/bin.js` 可见、`node --version` 可跑）。代价是 bind 映射
 * 是**字符串前缀替换**而非真挂载，所以 engine 路径下不能再有指向别处的绝对软链
 * —— 引擎目录里没有软链（`lib/` 是实体目录），已验证。
 *
 * ## 回退策略
 *
 * 本类只负责「构造 Linux 模式的启动参数」。失败时由调用方决定是否回退到原生
 * 模式 —— 我们的立场是**绝不静默降级**：回退必须在 UI 上明确标记，否则用户会
 * 以为自己正在用完整 Linux 环境。
 */
class LinuxEngineLauncher(private val ctx: Context) {

    private val env = LinuxEnvironmentManager.get(ctx)

    /** Linux 模式下要 bind 进容器的宿主目录（guest 路径 -> 宿主绝对路径） */
    private data class Bind(val guest: String, val host: File)

    /**
     * 检查 Linux 模式是否可用（不启动任何东西）。
     *
     * 逐项检查，每项失败都给出**可操作的原因**，而不是笼统的「不可用」。
     */
    fun readiness(): Readiness {
        val runner = EngineConfig.deployLinuxRunner(ctx)
            ?: return Readiness.Unavailable("Linux runner missing from APK (build configuration issue)")
        if (!EngineConfig.isFixedBaseLinuxRunner(runner)) {
            return Readiness.Unavailable("Linux runner is not a fixed-base ET_EXEC; refusing to run (would crash randomly)")
        }

        val st = env.status()
        when (st.state) {
            LinuxEnvironmentManager.State.NOT_DEPLOYED ->
                return Readiness.NeedsDeploy(st)
            LinuxEnvironmentManager.State.BROKEN ->
                return Readiness.Unavailable("Linux environment broken: ${st.detail}")
            LinuxEnvironmentManager.State.DEPLOYING ->
                return Readiness.Unavailable("Linux environment is being deployed")
            LinuxEnvironmentManager.State.READY -> Unit
        }

        // rootfs 里必须有 node（DSH 引擎依赖）。没装时给出可操作的提示，
        // 而不是等到 spawn 后才报 "node: not found"。
        //
        // ⚠️ 这一项**必须用静态检查**，不能起容器进程去跑 `command -v node`。
        //
        // 原来确实是这么干的（LinuxContainerRunner.run("/bin/busybox",
        // ["sh","-c","command -v node"], 30_000)），后果是**每次冷启都多一次
        // 完整的容器 fork**：fork dsh_alpine → 打开 rootfs → 载入 musl interp
        // （723KB）→ 载入 busybox → 跑 shell → 退出 → wait4。
        // 实测这段稳定吃 200~280ms，而它要回答的问题只是「node 在不在」。
        //
        // 现在直接看文件：rootfs 宿主路径下有没有 usr/bin/node（或 bin/node）。
        // 这与 `command -v node` 在实践上等价 —— rootfs 是确定性布局
        // （apk add nodejs 只会落在 usr/bin/node），PATH 也是固定的容器默认值。
        // 真正的「能不能跑」由 spawn 后引擎自己的输出证明，不需要前置探测。
        val option = env.activeOption()
        val rootfs = env.rootfsDir(option)
        // node 位置随发行版不同：
        //   Alpine - apk add nodejs 落在 usr/bin/node
        //   Ubuntu - Node 官方预编译包解到 /opt/node/bin/node（apt 源里没有 22）
        val nodeRel = when (option.nodeStrategy) {
            LinuxRootfsCatalog.NodeStrategy.APK_PACKAGE -> listOf("usr/bin/node", "bin/node")
            LinuxRootfsCatalog.NodeStrategy.OFFICIAL_TARBALL ->
                listOf(LinuxRootfsCatalog.GLIBC_NODE_PATH.trimStart('/'))
        }
        val hasNode = nodeRel.any { File(rootfs, it).isFile }
        if (!hasNode) {
            return Readiness.NeedsPackages(
                ctx.getString(R.string.linux_err_need_node)
            )
        }

        // 发行版配置（hosts / resolv.conf / 镜像源）：**幂等**，内容不一致才写。
        //
        // ★必须在这里也写一次，不能只靠 LinuxContainerRunner.ensureReady()。
        //   那条路径只有 LinuxActivity 会走，引擎冷启根本不经过它 —— 后果是
        //   musl 侧一直用着旧的公共 DNS（被 Android 拦掉 UDP 53）、glibc 侧没有
        //   `options use-vc`，两边联网全废（apk add / apt install / pip / npm
        //   全失败），而用户从 App 里完全看不出异常（apk update 会拿预置索引
        //   假装成功）。
        //
        // 同步执行是安全的：writeDistroConfig 只做「读-比较-可能写」几个小文件，
        // 实测 O(1)，不联网。
        runCatching { env.writeDistroConfigPublic(option) }
            .onFailure { android.util.Log.w("LinuxEngine", "distro config failed: " + it.message) }

        // ★兼容层也必须在这里跑（v1.6.14 修复，第二次踩同一个坑）。
        //
        // 链条：冷启只走 readiness() → status()，**从不调 ensureDeployed()**
        // （ensureDeployed 只有 LinuxActivity 的「部署/修复」按钮会走）。
        // 所以把 installLinuxCompatShims 挂在 ensureDeployed 里等于永远不执行 ——
        // 无论挂在解包后还是挂在 READY 提前返回前，都一样。
        //
        // 症状（真机实测）：容器内 $root/bin/rg 不存在 → 引擎的 grep/glob
        // 工具报 "ripgrep launch failed"；门脚本 shebang 找不到 /system/bin/sh。
        //
        // 幂等（标记文件 + 「目标存在且可执行」双判据），每次冷启跑一次无代价。
        // 编译缓存 GC：清掉属于其他 node 版本的缓存目录（v1.6.15）。
        // 只在 READY 分支跑 —— 冷启才需要，且必须早于 spawn。
        // ★顺序不能反：先 prune 再 seed。
        //   prune 会删掉「版本/arch 不匹配」的目录 —— 如果先 seed，刚解出来的
        //   包可能被 prune 当成陈旧清掉（引擎升级 node 后正是这种情形），
        //   白解 1281 个文件还没收益。先 prune 则 seed 只看当前版本要不要补。
        runCatching { env.pruneCompileCache() }
            .onFailure { android.util.Log.w("LinuxEngine", "cache prune failed: " + it.message) }

        // ★预置缓存**不在这里解** —— 它是部署阶段的活，不是冷启阶段的活。
        //
        // 最初版本把 seedCompileCache() 挂在这里，实测发现是**净亏**：
        // 解 1281 个文件要 1577 / 4006 / 3169 ms（三轮），而它换来的收益
        // 只有 840.5 ms。挂在冷启路径上等于每次首启多等 1.5 秒。
        //
        // 正确位置是 [LinuxEnvironmentManager.ensureDeployed] —— 用户那时
        // 本来就在等 8.5 MB rootfs 解包（1~3 秒），多解这 3 MB 感知不到。
        // 这里只做 prune（纯 listFiles + 删除，微秒级）。
        runCatching { env.installCompatShimsPublic() }
            .onFailure { android.util.Log.w("LinuxEngine", "compat shims failed: " + it.message) }

        // ★基础包**不在这里装**：ensureDeployed() 已在部署阶段同步装完并写
        // `.dsh-bootstrap` 标记。冷启路径只做纯本地判断，一次联网都不做。
        // 详见 LinuxContainerRunner.ensureReady() 里同一段注释的代价说明。
        return Readiness.Ready(st)
    }

    sealed class Readiness {
        /** 可以直接启动引擎 */
        data class Ready(val status: LinuxEnvironmentManager.Status) : Readiness()

        /** rootfs 尚未部署，需要先调 ensureDeployed */
        data class NeedsDeploy(val status: LinuxEnvironmentManager.Status) : Readiness()

        /** rootfs 就绪但缺少运行时（如 node） */
        data class NeedsPackages(val reason: String) : Readiness()

        /** 环境层面不可用，只能回退原生模式 */
        data class Unavailable(val reason: String) : Readiness()
    }

    /**
     * 构造在 Linux 容器内启动 DSH 引擎的完整 argv 与环境。
     *
     * 产物可直接交给 [EngineProcess] 的同类逻辑（但这里返回裸数据，便于测试与
     * 日志打印，不直接耦合进程管理）。
     *
     * ## argv 结构
     *
     * ```
     * dsh_alpine -m /eng:<engineRoot> -m /sdcard:<externalDir>
     *            <rootfs 宿主路径> <musl interp> /usr/bin/node
     *            --expose-internals /eng/lib/node_modules/@deepseek-ai/dsh/lib/bin.js
     *            web --patch /eng/... --no-open
     * ```
     *
     * ## 几个必须保留的原生侧行为
     *
     * - `--expose-internals`：引擎依赖 `internal/modules/esm/loader`，缺了直接
     *   `Cannot find module`（node-addon-require-builtin 的硬需求）。
     * - patch 顺序：SSH 插件层在前，Android 适配层在后（后应用者生效）。
     * - `cwd` 用 workspaces 目录（同样 bind 进容器）。
     */
    fun buildEngineArgv(
        port: Int,
        bootId: String,
        patchPaths: List<String>,
        /** 编译缓存刷盘器的容器内路径；null = 不用缓存（退回老行为）。 */
        compileCacheFlusher: String? = null,
    ): LaunchSpec {
        val engineRoot = EngineConfig.engineRoot(ctx)
        val workspaces = EngineConfig.workspaces(ctx)
        val option = env.activeOption()
        val rootfs = env.rootfsDir(option)
        val runner = File(File(ctx.filesDir, "linux"), "runner")

        // bind 表：别名 + 宿主路径同名挂载（详见 buildBinds 的注释）
        val binds = buildBinds()

        val argv = buildList {
            add(File(runner, RUNNER_NAME).absolutePath)
            binds.forEach { b ->
                add("-m")
                add("${b.guest}:${b.host.absolutePath}")
            }
            add(rootfs.absolutePath)
            // 动态链接器与 node 路径都跟随当前发行版：
            // 用 musl 的 interp 去载 glibc 的 node 会直接 "LOADER: preload FAIL"。
            add(option.interpPath)
            add(nodePath(option))
            add("--expose-internals")
            // v1.6.2：注入编译缓存刷盘器。NODE_COMPILE_CACHE 懒写盘（就绪时 0 文件，
            // 只有优雅退出才落盘），而 App 停引擎是 kill -9 —— 不注入这个 flusher
            // 缓存一次都写不进去，优化等于不存在。详见 EngineConfig.deployCompileCacheFlusher。
            compileCacheFlusher?.let {
                add("--require")
                add(it)
            }
            // 入口 JS 走 bind 后的 guest 路径
            add("/eng/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
            add("web")
            // patch 参数用 guest 路径：patch 文件在 engine 目录下（已被 /eng bind）
            patchPaths.forEach { p ->
                val rel = runCatching { p.removePrefix(engineRoot.absolutePath).trimStart('/') }
                    .getOrDefault("")
                if (rel.isNotEmpty()) {
                    add("--patch")
                    add("/eng/$rel")
                }
            }
            add("--no-open")
        }

        val envVars = buildList {
            add("PATH=$CONTAINER_PATH")
            add("HOME=/dsh")
            add("TMPDIR=/tmp")
            add("TERM=xterm-256color")
            add("DSH_HOME=/dsh")
            add("DSH_PROFILE=${runCatching { "web" }.getOrDefault("web")}")
            add("DSH_PROFILE_DIR=/dsh/profiles/web")
            add("PORT=$port")
            add("NODE_ENV=production")
            // v1.6.2：Node 模块编译缓存。实测（真机交替 4 轮）有缓存 1449/1442ms
            // vs 无缓存 1617/1622/1606ms，wall 省约 165ms（10%）、CPU 省约 450ms（12%）。
            // 放在 /dsh 下（已是 bind 的家目录），跟着用户数据一起保留。
            compileCacheFlusher?.let {
                // ★按发行版分目录（v1.6.15）。
                //
                // Node 的编译缓存根目录下会按 `node版本-arch-hash` 建子目录，但
                // **所有 node 版本共用同一个根**。这套 App 同时支持 Alpine(musl
                // node22) 与 Ubuntu(glibc node24)，用户来回切换就会在同一根下堆
                // 出多份互不相干的缓存 —— 实测已堆到 6 个目录 / 46MB / 5577 文件，
                // 而当前 node 只认得其中 1 个（13MB / 1532 文件），其余 33MB 全是
                // 每次启动都要扫一遍的死重。
                //
                // 冷启每多扫一份无效缓存就多几百 ms（缓存目录是 flat 结构，Node
                // 启动时要 readdir + 逐个 stat 全部条目）。按发行版分开后，每个根
                // 只留自己那一份，互不干扰。
                // ⚠️ 必须沿用**原路径**：改成子目录会让 Node 认不到已有缓存，
                //    每次启动都全量重建（实测 4.3~7.1 秒，比不改还糟）。
                //    分目录隔离改由 [LinuxEnvironmentManager.pruneCompileCache]
                //    在冷启时清掉其他 node 版本的目录来实现 —— 效果相同，
                //    且不需要迁移缓存。
                add("NODE_COMPILE_CACHE=/dsh/.node-compile-cache")
            }
            // ★v1.6.12：把 DNS 代理端口告诉容器内的 loader。
            //
            // musl 的 getaddrinfo 把 DNS 端口**硬编码成 53**（写 `nameserver ip:port`
            // 会被整个忽略，实测包仍发往 53）。而 53 是特权端口，App uid 绑不上。
            // 所以由 loader 双向改写端口：出程 sendto/sendmsg 把「发往 127.0.0.1:53」
            // 改成这个端口，回程 recvfrom/recvmsg 再把来源端口改回 53（musl 会校验
            // 应答来源，只改出程的话应答会被丢弃）—— 免 root 又不需要特权端口。
            //
            // 代理没起来时传 0/不传：loader 保持原样，行为与之前完全一致。
            LinuxDnsProxy.currentPort().takeIf { it > 1023 }?.let {
                add("DSH_DNS_PORT=$it")
            }
            // ★变量名必须是 DSH_ANDROID_BOOT_ID，与原生路径
            //   （EngineConfig.buildEnv）以及插件侧读的名字完全一致。
            //
            // 踩过的坑：这里曾写成 DSH_BOOT_ID，而 android-plugins/plugin-health.mjs:316
            // 读的是 `process.env.DSH_ANDROID_BOOT_ID`。名字对不上 → 插件写出的
            // marker 里 bootId 恒为空串 → PluginRecovery.readHealth() 的
            // `json.optString("bootId") != expectedBootId` 判定不匹配 → 返回 null
            // → 监督器认为「marker missing or unreadable」，然后**干等满 20 秒**
            // awaitPluginHealth 超时才放行。这就是 Linux 模式冷启多出 20 秒的全部原因
            // （实测：健康检查 1.9 秒就出了结果，却到 20 秒后才 healthy）。
            add("DSH_ANDROID_BOOT_ID=$bootId")
            // ★v1.6.13：设备能力桥的 token。
            //
            // 原生模式由 EngineConfig.buildEnv:1512 注入，但 Linux 模式走的是
            // 这份独立的 envVars —— 之前漏了。后果是容器内所有 `_dsh_http.sh`
            // 调用都不带 `X-DSH-Token` 头，AgentBridge 一律回 403，表现为
            // 「设备能力全废」（截屏/通知/开 App 全部失败）。
            //
            // 空 token 不写：AgentBridge 未启动时 currentToken() 返回 null，
            // 写个空值反而会让 `_dsh_http.sh` 的 `if [ -n "$TOKEN" ]` 通过。
            AgentBridge.currentToken()?.takeIf { it.isNotEmpty() }?.let {
                add("DSH_BRIDGE_TOKEN=$it")
            }
            // ★Web 终端要跑在容器里，而不是宿主 bionic shell。
            //
            // 背景：引擎开终端时走 node-pty → `dsh_pty_exec`（Android NDK/bionic
            // 助手）→ 它 `execv` 出 shell。而 bionic 助手被 loader 判为
            // `guest_is_bionic()`，交给宿主侧 cleaner 代跑，**不带容器 bind 视图** ——
            // 于是 `/bin/sh` 落到宿主的 `/system/bin/sh`：实测
            // `readlink /proc/$$/exe` = `/system/bin/sh`、`[ -d /etc/apk ]` = NO、
            // `PATH` 是 `/product/bin:/apex/...`。症状就是「Linux 模式的终端里
            // apk/gcc 全都不在」。
            //
            // 修法：把这些容器参数交给引擎侧的 node-pty 兼容层
            // （scripts/patch-node-pty-android.py 的 `containerWrap`），让它把
            // 「exec shell」改写成「经 dsh_alpine 进容器再 exec shell」。
            //
            // 为什么不在这边直接改 argv：终端是引擎在对端申请的，App 拿不到那次
            // spawn 的 argv，只能通过环境变量把「怎么进容器」告诉它。
            // 缺任一必需项时兼容层原样直通（老 App / 原生模式行为不变）。
            add("DSH_TERMINAL_RUNNER=${File(runner, RUNNER_NAME).absolutePath}")
            add("DSH_TERMINAL_ROOTFS=${rootfs.absolutePath}")
            add("DSH_TERMINAL_INTERP=${option.interpPath}")
            // bind 表按「每行 guest:host」编码：路径可能带空格，不能用空格分隔。
            add("DSH_TERMINAL_BINDS=${binds.joinToString("\n") { "${it.guest}:${it.host.absolutePath}" }}")
            // ⚠️ 不设 LD_LIBRARY_PATH / LD_PRELOAD：容器内是 musl 运行时，
            // 宿主的 bionic 库路径会让 musl 的 dlopen 走错路径。
        }

        // 宿主侧工作目录：容器进程的 cwd 用 workspaces（guest 也是 /ws）
        return LaunchSpec(
            argv = argv,
            env = envVars,
            cwd = workspaces,
            guestCwd = "/ws",
        )
    }

    /** 启动规格：可直接交给进程管理器 */
    data class LaunchSpec(
        val argv: List<String>,
        val env: List<String>,
        val cwd: File,
        val guestCwd: String,
    )

    /**
     * 在容器内装一个软件包（供插件中心 / 包管理 UI 复用）。
     *
     * 失败时把 `apk` 的原始 stderr 带回去 —— 纪律：跑不通必须贴原始报错，
     * 不能只回「安装失败」。
     */
    fun apkAdd(packages: List<String>, timeoutMs: Long = 300_000L): LinuxContainerRunner.Result {
        if (packages.isEmpty()) return LinuxContainerRunner.Result(0, "", "")
        val option = env.activeOption()
        return when (option.pkgManager) {
            LinuxRootfsCatalog.PackageManager.APK -> LinuxContainerRunner.get(ctx).run(
                guestPath = "/sbin/apk",
                args = listOf("add", "--no-progress") + packages,
                timeoutMs = timeoutMs,
            )

            // apt 侧必须两步（见 LinuxEnvironmentManager.installPackagesApt）
            LinuxRootfsCatalog.PackageManager.APT -> {
                val r = env.installPackagesApt(option, packages, timeoutMs)
                LinuxContainerRunner.Result(if (r.ok) 0 else 1, r.raw, "")
            }
        }
    }

    /** 已装包列表（Alpine: `apk info`；Ubuntu: `dpkg-query`） */
    fun apkList(): List<String> {
        val option = env.activeOption()
        val r = when (option.pkgManager) {
            LinuxRootfsCatalog.PackageManager.APK ->
                LinuxContainerRunner.get(ctx).run("/sbin/apk", listOf("info"), 60_000)

            LinuxRootfsCatalog.PackageManager.APT -> LinuxContainerRunner.get(ctx)
                .run(
                    "/usr/bin/dpkg-query",
                    // 每行输出一个包名。字符串用 $ 转义 + \n：Kotlin 里 "\$" 是字面量 $，
                    // "\n" 是换行，dpkg-query 的 -f 需要真实换行符。
                    listOf("-f", "\${Package}\n", "-W"),
                    60_000,
                )
        }
        return if (!r.ok) emptyList() else r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 把**容器内路径**翻译回宿主路径（找不到对应挂载时返回 null）。
     *
     * ## 为什么需要
     *
     * Linux 模式下引擎自己跑在容器里，`DSH_HOME=/dsh`。于是它上报给 App 的
     * 一切路径都是 guest 视角 —— 典型的就是「打开配置文件」：
     * `settings.prepareDocument()` 返回 `profileContext.patchPath`，而
     * `patchPath = <DSH_HOME>/profiles/web/cordis.patch.yml`，即
     * **`/dsh/profiles/web/cordis.patch.yml`**。
     *
     * App 收到这个路径去 `File.isFile` 必然 false（宿主上没有 `/dsh`），
     * `isOpenablePath` 也会判越界 —— 用户看到的就是「配置文件打不开」。
     *
     * ## 依据只看 bind 表
     *
     * 翻译规则不另立：就是 [buildBinds] 那张表的反向查表。表里已有的别名
     * （`/eng` `/ws` `/dsh`）与「宿主路径同名挂载」两类都覆盖 ——
     * 后者是恒等映射，翻译后仍是原路径。
     *
     * ## 拒绝相对路径
     *
     * 只做绝对路径的 guest→host 翻译；相对路径原样返回 null 交给调用方
     * 报错（容器里的相对路径相对的是容器 cwd，宿主侧没有对应语义）。
     *
     * @param guestPath 容器内绝对路径，如 `/dsh/profiles/web/cordis.patch.yml`
     * @returns 对应的宿主绝对路径；无匹配挂载时 null
     */
    fun hostPathOf(guestPath: String): String? {
        if (!guestPath.startsWith("/")) return null
        // 归一化：去掉重复斜杠与结尾斜杠，避免 `/dsh//x` 之类对不上前缀
        val guest = guestPath.replace(Regex("/{2,}"), "/").let {
            if (it.length > 1) it.trimEnd('/') else it
        }
        // 最长前缀优先：`/dsh` 与 `/dsh/cache` 同时存在时取更具体的那个
        val best = buildBinds()
            .filter { b ->
                guest == b.guest.trimEnd('/') || guest.startsWith(b.guest.trimEnd('/') + "/")
            }
            .maxByOrNull { it.guest.trimEnd('/').length }
            ?: return null
        val tail = guest.removePrefix(best.guest.trimEnd('/')).trimStart('/')
        val hostRoot = best.host.absolutePath
        return if (tail.isEmpty()) hostRoot else "$hostRoot/$tail"
    }

    /** 容器内 node 的绝对路径（跟随发行版） */
    fun nodePath(option: LinuxRootfsCatalog.Option = env.activeOption()): String =
        when (option.nodeStrategy) {
            LinuxRootfsCatalog.NodeStrategy.APK_PACKAGE -> NODE_IN_CONTAINER
            LinuxRootfsCatalog.NodeStrategy.OFFICIAL_TARBALL -> LinuxRootfsCatalog.GLIBC_NODE_PATH
        }

    /**
     * 构造在容器内开一个**交互式登录 shell** 的 argv（供 PTY 会话用）。
     *
     * 与 [buildEngineArgv] 的区别只在命令部分：引擎跑 `node ...`，
     * 终端跑 `/bin/sh -l`。容器前缀（runner + bind 表 + rootfs + interp）完全复用，
     * 所以终端里看到的路径、HOME、工作目录与引擎进程内一致 ——
     * 用户在终端里 `apk add` 装的东西，引擎里的插件也能立刻用到。
     *
     * ## 为什么用 `sh -l` 而不是直接 `/bin/sh`
     *
     * Alpine 的登录 shell 会读 `/etc/profile`，而 `prepare-rootfs.sh` 往
     * `/etc/profile.d/ccache.sh` 写了 ccache 的 PATH 与 CCACHE_DIR。
     * 不带 `-l` 就没有这份 PATH，`ccache` 永远 miss（实测过：`command -v gcc`
     * 返回 `/usr/bin/gcc` 而不是 `/usr/lib/ccache/bin/gcc`）。
     *
     * ## 为什么用 busybox 的 `sh` 而不是 `bash`
     *
     * 极简 rootfs 里**没有 bash**（`/bin/bash` 不存在，busybox 的 sh 才是 ash）。
     * 想要 bash 得用户自己 `apk add bash`；那时 `sh -l` 仍能正常工作，
     * 用户可在终端里 `exec bash -l` 自己切。
     */
    fun buildTerminalArgv(): LaunchSpec {
        val engineRoot = EngineConfig.engineRoot(ctx)
        val workspaces = EngineConfig.workspaces(ctx)
        val option = env.activeOption()
        val rootfs = env.rootfsDir(option)
        val runner = File(File(ctx.filesDir, "linux"), "runner")

        val binds = buildBinds()

        val argv = buildList {
            add(File(runner, RUNNER_NAME).absolutePath)
            binds.forEach { b ->
                add("-m")
                add("${b.guest}:${b.host.absolutePath}")
            }
            add(rootfs.absolutePath)
            add(option.interpPath)
            add("/bin/sh")
            // ★必须显式 cd 到 guest 侧的 /ws，不能只靠宿主 cwd。
            //
            // 原因：Termux 的 TerminalSession 把子进程的 cwd 设成**宿主路径**
            // （`EngineConfig.workspaces(ctx)`）。getcwd 虽然有 bind 反向映射，
            // 但那份映射要求宿主路径与 bind 表里的 host 前缀逐字节相同 ——
            // workspaces 在部分设备上落在 `/storage/emulated/0/Documents/dsh data/...`
            // （带空格的分区路径），与 bind 记录的路径出现差异时映射不中，
            // 提示符就原样显示宿主路径（实测：
            //   `localhost:/storage/emulated/0/Documents/dsh data/workspaces$`）。
            // 显式 chdir 一次即可彻底绕开这个问题，且不依赖宿主路径长什么样。
            add("-c")
            add("cd /ws 2>/dev/null || cd /dsh 2>/dev/null || true; exec /bin/sh -l")
        }

        val envVars = buildList {
            add("PATH=$CONTAINER_PATH")
            add("HOME=/dsh")
            add("TMPDIR=/tmp")
            add("TERM=xterm-256color")
            add("DSH_HOME=/dsh")
            // 关掉宿主 bionic 的库路径：容器内是 musl
        }

        return LaunchSpec(
            argv = argv,
            env = envVars,
            cwd = workspaces,
            guestCwd = "/ws",
        )
    }

    /**
     * 构造容器的 bind 表（引擎与终端**共用**同一份）。
     *
     * 除了「好记的别名」（/eng /ws /dsh），**宿主路径必须按同名再挂一次**。
     *
     * ## 为什么不能只挂别名
     *
     * 上游把绝对路径持久化进配置。`storages/workspace.json` 里存的是
     * ```
     * "path": "/storage/emulated/0/Documents/dshdata/workspaces"
     * ```
     * 这是**宿主**视角。容器只挂 /ws 时，引擎按 registry 去 open 那个路径会
     * `No such file` —— 表现就是「工作区无法选择」、列不出文件、切不了目录。
     * 会话里引用的文件、AI 记下的绝对路径同理，全是宿主视角。
     *
     * ## 为什么要解析软链
     *
     * `dsh-home/workspaces` 是软链 →
     * `/storage/emulated/0/Documents/dshdata/workspaces`，而 workspace.json
     * 存的正是**解析后**的那个。只挂软链本身不够（第一版就漏了这条，
     * 工作区依然打不开）。
     *
     * ## 为什么不做字符串改写
     *
     * 改 workspace.json 是一次性迁移：用户新加的工作区、会话里存的路径、
     * 插件自己记的路径还会再引入宿主路径 —— 治不完。挂载是让两种视角
     * **结构上**都成立。
     */
    private fun buildBinds(): List<Bind> {
        val engineRoot = EngineConfig.engineRoot(ctx)
        val workspaces = EngineConfig.workspaces(ctx)
        val dshHome = EngineConfig.dshHome(ctx).takeIf { it.isDirectory }
        return buildList {
            add(Bind("/eng", engineRoot))
            add(Bind("/ws", workspaces))
            dshHome?.let { add(Bind("/dsh", it)) }

            // 宿主侧「根」按同名挂载。只枚举具体目录治不完：workspace.json 里
            // 存的是任意宿主绝对路径（本次实测就有 /storage/emulated/0、
            // /storage/emulated/0/Download/123云盘、/data/user/0/app.dsh.mobile/files
            // 三种），而容器的 /storage 与 /data 是 rootfs 里的空骨架 ——
            // 表现为目录「存在但列出 0 项」，新建/打开都失败（不是 ENOENT 而是
            // 空目录，比报错更难定位）。
            //
            // 把两个根整体挂进去，所有宿主路径在容器内结构上都成立。
            val samePath = LinkedHashSet<File>()
            fun consider(f: File?) {
                if (f == null || !f.isDirectory) return
                samePath += f
                runCatching { f.canonicalFile }.getOrNull()?.let { samePath += it }
            }
            consider(workspaces)
            consider(dshHome)
            consider(EngineConfig.publicFilesRoot(ctx))
            // 两个宿主根：外置存储与私有数据目录
            //
            // ★必须挂「真正的根」，不能只挂叶子（v1.6.14 修复）。
            //   之前挂的是 /data/user/0/app.dsh.mobile/files，于是容器内
            //   /data/user 这级**不存在** —— 插件对 /data/user 做 lstat 直接
            //   ENOENT，hardssh 三个子插件全部 import 失败，
            //   plugin health 判 degraded（真机实测）。
            //   把 /data/user 整根挂进去，下面所有原有路径都自然成立。
            consider(File("/storage/emulated/0"))
            consider(File("/data/user"))
            // /data/data 是 /data/user/0 的兼容软链（部分插件按老路径找），
            // 单独挂一次避免再踩同类 "父级不存在" 的坑。
            consider(File("/data/data"))
            val mounted = setOfNotNull(engineRoot.absolutePath, workspaces.absolutePath, dshHome?.absolutePath)
            for (f in samePath) {
                if (f.absolutePath in mounted) continue
                add(Bind(f.absolutePath, f))
            }
        }
    }

    companion object {
        private const val TAG = "LinuxEngine"

        /** 容器内 node 的路径（`apk add nodejs` 装到这里） */
        const val NODE_IN_CONTAINER = "/usr/bin/node"

        /** 运行器文件名，与 EngineConfig 保持一致 */
        private const val RUNNER_NAME = "dsh_alpine"

        /** 容器内 PATH，与 LinuxContainerRunner 保持一致 */
        private const val CONTAINER_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        @Volatile
        private var instance: LinuxEngineLauncher? = null

        fun get(ctx: Context): LinuxEngineLauncher =
            instance ?: synchronized(this) {
                instance ?: LinuxEngineLauncher(ctx.applicationContext).also { instance = it }
            }
    }
}
