package app.dsh.mobile.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 引擎启动失败归因器（v1.5.3）。
 *
 * ## 为什么需要
 *
 * 用户反馈「有的时候引擎会莫名其妙启动失败」，而排查时发现原因是五花八门：
 * 端口被占、插件没正确加载、结构文件读不了、还有一堆连现象都描述不出来的。
 * 问题不在「修不了」，在**没有任何一层把失败原因结构化地记下来**：
 *
 * - [EngineSupervisor.State.Failed] 只带一句 `reason: String`，全凭那一处
 *   调用点手写，措辞各不一致；
 * - `failureSignature` 是 `"<退出码>:<hash>"` —— 哈希把可读信息全丢了，
 *   它服务于「判断两次崩溃是否同源」，不适合给人看；
 * - 用户看到的是「Failed to start 14 times in a row」，既不知道因为什么，
 *   也不知道下一步该点哪里。
 *
 * 本类的职责是**只做归因**：把已经散落在各处的证据（日志尾部、端口探测、
 * 文件可读性、进程退出原因）收敛成一个 [Cause] + 一组 [Fix]。
 * 它**不执行修复** —— 修复由调用方按 [Fix] 逐条决定，这样可以：
 *
 * 1. 先把「看得懂」这件事做好，修不修、修几条由界面决定；
 * 2. 归因逻辑可以纯静态测（喂日志文本即可），不需要真的把引擎弄坏；
 * 3. 未来 AI 要接管修复时，它读到的就是同一份结构化结果，不会两套判断。
 *
 * ## 归因顺序（先具体后笼统）
 *
 * 检查顺序刻意按「证据强度」排，不是按代码顺序：
 * 1. 有确定的端口证据 → PORT_*（端口问题最容易被误判成别的原因）
 * 2. 有架构/缺文件证据 → RUNTIME_*
 * 3. 日志里明说插件没激活 → PLUGIN_*
 * 4. 配置文件解析失败 → PROFILE_*
 * 5. 权限/存储 → STORAGE_*
 * 6. 系统给的退出原因（OOM/被杀）→ SYSTEM_*
 * 7. 都没有 → UNKNOWN（仍要带上可复制的一行摘要，不能又是「未知」俩字）
 *
 * ## 一处刻意的克制
 *
 * `PLUGIN_INACTIVE` 的判据是日志里的
 * `startup failed: N required plugins did not activate`。**只认这一句**，
 * 不去猜「哪个插件可能有问题」—— 引擎日志里没有插件名时，猜出来的东西
 * 比不给更糟（用户会照着去删一个无辜的插件）。
 */
object EngineTriage {

    private const val TAG = "EngineTriage"

    /**
     * 失败原因。每个取值都对应**一种确定的处置**，不含「大概是 xx」这种模糊档。
     *
     * 新增取值时必须同时给出 [fixesFor] 的分支与五语文案，否则界面上会出现
     * 「有原因但没有可点的操作」—— 那比只显示未知还让人困惑。
     */
    enum class Cause(val id: String) {
        /** 3080 被**别的应用**的进程占着（不是我们自己的残留） */
        PORT_FOREIGN("port_foreign"),

        /** 3080 被**本应用**上一次留下的孤儿进程占着（可以安全杀掉） */
        PORT_ORPHAN("port_orphan"),

        /** 端口被占，但分不清是谁 —— 保守按「可清场」处理，UI 会提示风险 */
        PORT_UNKNOWN_OWNER("port_unknown_owner"),

        /** 引擎需要的关键插件没有激活（日志明写了 did not activate） */
        PLUGIN_INACTIVE("plugin_inactive"),

        /** 配置文件解析失败（package.json / cordis.patch.yml / .credentials.yaml） */
        PROFILE_CORRUPT("profile_corrupt"),

        /** 运行时缺文件（node 或入口不存在） */
        RUNTIME_MISSING("runtime_missing"),

        /** 运行时架构不匹配（APK 里带的是别的 ABI） */
        RUNTIME_ARCH("runtime_arch"),

        /** 数据目录不可写 / EACCES / 属主错乱 */
        STORAGE_DENIED("storage_denied"),

        /** 系统因低内存杀掉了进程 */
        SYSTEM_OOM("system_oom"),

        /** 系统因其他原因杀掉了进程（省电、用户手动、厂商清理） */
        SYSTEM_KILLED("system_killed"),

        /** 凭据/登录态失效（重试无意义） */
        AUTH_EXPIRED("auth_expired"),

        /** 一次都没起来过，但所有单项检查都正常 */
        UNKNOWN("unknown"),
    }

    /**
     * 一条可执行建议。
     *
     * [kind] 决定「点了会做什么」，必须与界面上的按钮一一对应 —— 不允许出现
     * 界面上有 [kind] 但实现里没有分支的组合（那会让用户点了没反应）。
     */
    data class Fix(
        val kind: Kind,
        /** 是否需要用户先确认（破坏性或有外部影响的操作） */
        val needsConfirm: Boolean = false,
    ) {
        enum class Kind {
            /** 杀掉占着端口的进程再重试 */
            RECLAIM_PORT,

            /** 直接重启引擎（不改任何数据） */
            RESTART_ENGINE,

            /** 跑一次全面体检（见 [SelfCheck]） */
            RUN_SELF_CHECK,

            /** 摘掉导致启动失败的那个插件（带备份，见 [PluginRecovery]） */
            QUARANTINE_PLUGIN,

            /** 回滚到上次可用的配置快照（见 [ProfileGuardian]） */
            ROLLBACK_PROFILE,

            /** 清掉上次换装留下的残渣（见 [RuntimeInstaller]） */
            CLEAN_INSTALL_RESIDUE,

            /** 修文件属主（Root 模式下 root 遗留，见 [Privilege]） */
            FIX_OWNERSHIP,

            /** 重新登录 / 换 API Key（只跳转，不自动改凭据） */
            OPEN_CREDENTIALS,

            /** 重装运行时（最重的一步，必须二次确认） */
            REINSTALL_RUNTIME,
        }
    }

    /**
     * 归因结果。
     *
     * [evidence] 是**给人看的证据**，不是日志原文堆砌 —— 每行都要能被用户
     * 直接抄进反馈里。没有证据时给空列表，不要编。
     */
    data class Diagnosis(
        val cause: Cause,
        /** 证据行（已本地化前缀由调用方拼，这里只放事实） */
        val evidence: List<String> = emptyList(),
        /** 建议动作，按优先级排序 */
        val fixes: List<Fix> = emptyList(),
        /** 一句话摘要（可直接进日志/反馈），永远非空 */
        val summary: String = "",
        /** 是否是一次都没成功启动过（影响文案：新用户 vs 用了一阵突然坏） */
        val neverBooted: Boolean = false,
    )

    /** 归因时需要的外部事实。显式传入而不是自己到处去查，便于测试与复用。 */
    data class Facts(
        /** 引擎日志尾部（本次启动段），空串表示没有 */
        val logTail: String = "",
        /** 3080 当前是否有人在监听 */
        val portInUse: Boolean = false,
        /** 占着 3080 的进程：pid -> cmdline。空 map 表示查不到 */
        val portOwners: Map<Int, String> = emptyMap(),
        /** 本次是否已经是「架构不匹配」这个确定性失败 */
        val archMismatch: Boolean = false,
        /** 运行时关键文件是否都在（node 可执行 + 引擎入口） */
        val runtimeIntact: Boolean = true,
        /** 运行时具体缺了什么（[runtimeIntact] 为 false 时填） */
        val runtimeMissing: List<String> = emptyList(),
        /** 配置文件解析失败的路径（空表示都能解析） */
        val profileBroken: List<String> = emptyList(),
        /** 数据目录是否可写 */
        val storageWritable: Boolean = true,
        /** 系统给的进程退出原因（[ApplicationExitInfo] 归一后的描述，空表示拿不到） */
        val systemExitReason: String = "",
        /** 系统退出原因是否指向低内存 */
        val systemExitOutOfMemory: Boolean = false,
        /** 是否一次都没成功启动过 */
        val neverBooted: Boolean = false,
        /** 上一次安装/换装是否留下残渣 */
        val installResidue: Boolean = false,
        /** 是否有可回滚的 last-good 快照 */
        val hasLastGoodProfile: Boolean = false,
        /** 是否已经处于安全模式（配置被隔离） */
        val inSafeMode: Boolean = false,
        /**
         * 自动体检的结果（v1.5.3，B 阶段）。
         *
         * 引擎起不来时自动跑一次 [SelfCheck] —— 它覆盖的 8 项（运行时/存储/软链/
         * 注册表/会话/插件配置/换装残渣/磁盘）里，有几项是日志**看不出来**的
         * （软链指向失效、注册表路径坏、磁盘快满）。而且用户不必自己去翻
         * 「设置→维护→全面体检」—— 那时场景早过去了。
         *
         * null 表示没跑（未启用或跑失败），不是「跑了但全好」。
         */
        val selfCheck: SelfCheck.Report? = null,
    )

    // ── 日志特征串 ──────────────────────────────────────────────────────────
    //
    // ⚠️ 这些是**日志匹配模式**，不是 UI 文案，所以按 i18n 门禁的规矩：
    //    英文照写，中文一律用 Unicode 转义（check-i18n 禁止 Kotlin 里出现 CJK 字面量）。

    private val PORT_MARKERS = listOf(
        "eaddrinuse",
        "address already in use",
    )

    private val PLUGIN_INACTIVE_MARKERS = listOf(
        "required plugins did not activate",
        "required plugin did not activate",
        "did not activate",
    )

    /**
     * 日志里的凭据/鉴权失败特征。
     *
     * ⚠️ **数字状态码不能用裸子串匹配。**
     *
     * 实测真题（2026-11-07，模拟器）：引擎日志第一行是
     * ```
     * ---- engine start 1794011937629 ----
     * ```
     * 里面正好含 `401` 这三个字符 —— 于是 `text.contains("401")` **恒为真**，
     * 每一次启动失败都被判成「登录态失效」，真正的端口冲突被掩盖，用户被引导去
     * 「重新登录」（做完了照样起不来）。
     *
     * 修法：状态码走 [AUTH_STATUS_CODES]，用「前后都不是数字」的边界匹配。
     * 字符串标记保持裸子串 —— 它们足够长，误伤概率低。
     */
    private val AUTH_MARKERS = listOf(
        "unauthorized", "authentication failed", "authentication required",
        "invalid api key", "invalid_api_key", "incorrect api key",
        "token expired", "invalid token", "token is invalid",
        // 中文特征串转义（同 EngineSupervisor.AUTH_FAILURE_MARKERS 的理由）
        "token \u8fc7\u671f", "\u4ee4\u724c\u5df2\u8fc7\u671f",
        "login required", "not logged in",
        "\u8bf7\u5148\u767b\u5f55", "\u767b\u5f55\u5df2\u5931\u6548",
    )

    /**
     * 裸数字状态码。**必须**按数字边界匹配，理由见 [AUTH_MARKERS]。
     */
    private val AUTH_STATUS_CODES = listOf("401", "403")

    private val STORAGE_MARKERS = listOf(
        "eacces", "permission denied", "read-only file system", "erofs",
        // 「没有权限访问…」的中文日志（转义）
        "\u6743\u9650\u4e0d\u8db3", "\u65e0\u6cd5\u5199\u5165",
    )

    private val CORRUPT_CONFIG_MARKERS = listOf(
        "unexpected token", "json parse", "syntaxerror", "yaml parse",
        "failed to parse", "is not valid json", "invalid yaml",
        "cannot parse", "unexpected end of",
    )

    /**
     * 归因主入口。**不抛异常**（任何内部失败都退化成 UNKNOWN + 证据行）。
     */
    /**
     * 归因主入口 —— **纯函数**：只吃 [Facts]，不碰 Context、不读磁盘。
     *
     * 这一点是刻意的：采集事实（[collectFacts]）要 Context 且会 IO，而归因不该。
     * 分开之后，同一份 Facts 永远得到同一个 Diagnosis，行为和设备无关 ——
     * 这台设备上「端口被占」和那台设备上「端口被占」走的是同一条分支，
     * 不会因为某台机器 /proc 读不到就换了结论。
     */
    fun diagnose(facts: Facts): Diagnosis = runCatching {
        diagnoseInternal(facts)
    }.getOrElse { e ->
        Log.w(TAG, "diagnose failed: ${e.message}")
        Diagnosis(
            cause = Cause.UNKNOWN,
            evidence = listOf("triage error: ${e.javaClass.simpleName}: ${e.message}"),
            fixes = listOf(Fix(Fix.Kind.RUN_SELF_CHECK), Fix(Fix.Kind.RESTART_ENGINE)),
            summary = "UNKNOWN (triage error)",
            neverBooted = facts.neverBooted,
        )
    }

    /**
     * 判断日志里是否真有「凭据/鉴权失败」的证据：字符串标记 + 数字状态码（边界匹配）。
     *
     * 公开给 [EngineSupervisor.looksLikeAuthFailure] 复用 —— 之前两处各写一份判定，
     * 于是同一个 `contains("401")` 的 bug 存在了两份，得修两遍。现在只有一份真源。
     */
    fun authFailureIn(log: String): Boolean =
        AUTH_MARKERS.any { log.contains(it) } ||
            AUTH_STATUS_CODES.any { code -> hasStatusCode(log, code) }

    /** 数字状态码的边界匹配：前一个字符和后一个字符都不能是数字。 */
    private fun hasStatusCode(log: String, code: String): Boolean {
        var from = 0
        while (true) {
            val i = log.indexOf(code, from)
            if (i < 0) return false
            // 边界要排除**字母和数字**，不只是数字：
            // 排除数字是为了 `1794011937629`（时间戳含 401）；
            // 排除字母是为了 `401abc`、`x403y` 这类哈希/ID 片段。
            val beforeOk = i == 0 || !log[i - 1].isLetterOrDigit()
            val end = i + code.length
            val afterOk = end >= log.length || !log[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = i + 1
        }
    }

    private fun diagnoseInternal(facts: Facts): Diagnosis {
        val log = facts.logTail.lowercase()

        // ── 1. 端口（证据最强，优先判） ────────────────────────────────────
        //
        // 判据分两类：
        //   a) 实测端口有人监听 —— 硬证据
        //   b) 日志出现 EADDRINUSE —— 软证据（可能已自行恢复）
        // 两者只要有一个成立就归到端口类，因为「先清场」对空闲端口是无害 no-op，
        // 漏判的代价（引擎永远起不来）远大于误判。
        val portEvidence = facts.portInUse || PORT_MARKERS.any { log.contains(it) }
        if (portEvidence) {
            val selfOwned = facts.portOwners.filter { (_, cmd) ->
                // 判定「是不是我们自己的残留」：cmdline 里出现本应用的包名，
                // 或出现引擎的 node 路径特征。用包名而不是 uid —— root 模式下
                // 孤儿是 uid 0，跟本应用不同 uid，但 cmdline 仍然指向我们的文件。
                cmd.contains(PKG) || cmd.contains("engine/bin/node") || cmd.contains("dsh")
            }
            val cause = when {
                facts.portOwners.isEmpty() -> Cause.PORT_UNKNOWN_OWNER
                selfOwned.isNotEmpty() -> Cause.PORT_ORPHAN
                else -> Cause.PORT_FOREIGN
            }
            val evidence = buildList {
                if (facts.portInUse) add("port ${EngineConfig.DEFAULT_PORT} is listening")
                facts.portOwners.entries.take(3).forEach { (pid, cmd) ->
                    add("pid $pid: ${cmd.take(120)}")
                }
                if (facts.portOwners.isEmpty()) add("cannot read /proc — owner unknown")
            }
            return Diagnosis(
                cause = cause,
                evidence = evidence,
                // 三种端口原因的处置差别只在「要不要警告」：
                // 外部进程被我们杀掉是有争议的（用户可能正跑着别的服务器），
                // 所以 PORT_FOREIGN 的按钮带确认；自家孤儿直接清。
                fixes = listOf(
                    Fix(Fix.Kind.RECLAIM_PORT, needsConfirm = cause == Cause.PORT_FOREIGN),
                    Fix(Fix.Kind.RESTART_ENGINE),
                ),
                summary = "${cause.id}: ${facts.portOwners.size} owner(s) on ${EngineConfig.DEFAULT_PORT}",
                neverBooted = facts.neverBooted,
            )
        }

        // ── 2. 运行时（缺文件 / 架构） ────────────────────────────────────
        if (facts.archMismatch) {
            return Diagnosis(
                cause = Cause.RUNTIME_ARCH,
                evidence = listOf("bundled runtime ABI != device ABI"),
                // 架构不匹配重试无意义，也不该重装（APK 里带的就是错的），
                // 只能提示换正确的 APK —— 所以这里**不给任何自动修复按钮**。
                fixes = emptyList(),
                summary = "RUNTIME_ARCH",
                neverBooted = facts.neverBooted,
            )
        }
        if (!facts.runtimeIntact) {
            return Diagnosis(
                cause = Cause.RUNTIME_MISSING,
                evidence = facts.runtimeMissing.map { "missing: $it" },
                fixes = buildList {
                    if (facts.installResidue) add(Fix(Fix.Kind.CLEAN_INSTALL_RESIDUE))
                    add(Fix(Fix.Kind.RESTART_ENGINE))
                    // 重装放最后且必须确认 —— 它最重
                    add(Fix(Fix.Kind.REINSTALL_RUNTIME, needsConfirm = true))
                },
                summary = "RUNTIME_MISSING: ${facts.runtimeMissing.joinToString(",")}",
                neverBooted = facts.neverBooted,
            )
        }

        // ── 3. 凭据（重试无意义，必须收敛） ────────────────────────────────
        //
        // 放在插件之前判：凭据失效的日志里也可能带一堆插件相关的噪声行，
        // 而「重新登录」是确定有效的处置，优先级高于摘插件。
        if (authFailureIn(log)) {
            return Diagnosis(
                cause = Cause.AUTH_EXPIRED,
                evidence = listOf("log matches credential/authentication failure markers"),
                fixes = listOf(Fix(Fix.Kind.OPEN_CREDENTIALS)),
                summary = "AUTH_EXPIRED",
                neverBooted = facts.neverBooted,
            )
        }

        // ── 4. 插件没激活 ────────────────────────────────────────────────
        if (PLUGIN_INACTIVE_MARKERS.any { log.contains(it) }) {
            val evidence = buildList {
                add("log reports required plugin(s) did not activate")
                // 把那一行原文带上（截断），这是用户最需要的信息
                facts.logTail.lineSequence()
                    .firstOrNull { it.contains("did not activate", ignoreCase = true) }
                    ?.let { add(it.trim().take(200)) }
                if (facts.hasLastGoodProfile) add("a last-good profile snapshot exists")
            }
            return Diagnosis(
                cause = Cause.PLUGIN_INACTIVE,
                evidence = evidence,
                fixes = buildList {
                    add(Fix(Fix.Kind.QUARANTINE_PLUGIN, needsConfirm = true))
                    if (facts.hasLastGoodProfile) {
                        add(Fix(Fix.Kind.ROLLBACK_PROFILE, needsConfirm = true))
                    }
                    add(Fix(Fix.Kind.RUN_SELF_CHECK))
                },
                summary = "PLUGIN_INACTIVE",
                neverBooted = facts.neverBooted,
            )
        }

        // ── 5. 配置文件坏了 ──────────────────────────────────────────────
        if (facts.profileBroken.isNotEmpty() || CORRUPT_CONFIG_MARKERS.any { log.contains(it) }) {
            val evidence = buildList {
                facts.profileBroken.forEach { add("unparseable: $it") }
                if (facts.profileBroken.isEmpty()) {
                    add("log matches config parse-error markers")
                }
                if (facts.hasLastGoodProfile) add("a last-good profile snapshot exists")
            }
            return Diagnosis(
                cause = Cause.PROFILE_CORRUPT,
                evidence = evidence,
                fixes = buildList {
                    if (facts.hasLastGoodProfile) {
                        add(Fix(Fix.Kind.ROLLBACK_PROFILE, needsConfirm = true))
                    }
                    add(Fix(Fix.Kind.RUN_SELF_CHECK))
                    add(Fix(Fix.Kind.RESTART_ENGINE))
                },
                summary = "PROFILE_CORRUPT: ${facts.profileBroken.size} file(s)",
                neverBooted = facts.neverBooted,
            )
        }

        // ── 6. 存储/权限 ─────────────────────────────────────────────────
        if (!facts.storageWritable || STORAGE_MARKERS.any { log.contains(it) }) {
            return Diagnosis(
                cause = Cause.STORAGE_DENIED,
                evidence = buildList {
                    if (!facts.storageWritable) add("app data dir is not writable")
                    STORAGE_MARKERS.firstOrNull { log.contains(it) }?.let {
                        add("log matches storage/permission marker: $it")
                    }
                },
                fixes = buildList {
                    add(Fix(Fix.Kind.FIX_OWNERSHIP, needsConfirm = true))
                    add(Fix(Fix.Kind.RUN_SELF_CHECK))
                },
                summary = "STORAGE_DENIED",
                neverBooted = facts.neverBooted,
            )
        }

        // ── 7. 系统杀进程 ────────────────────────────────────────────────
        //
        // 放在最后是因为它的证据来自系统 API（可能拿不到），而前六类的证据
        // 都是我们直接观测的。系统原因拿到时它也很有价值：能解释「为什么
        // 日志里什么都没有」。
        if (facts.systemExitReason.isNotEmpty()) {
            val cause = if (facts.systemExitOutOfMemory) Cause.SYSTEM_OOM else Cause.SYSTEM_KILLED
            return Diagnosis(
                cause = cause,
                evidence = listOf("system exit reason: ${facts.systemExitReason}"),
                fixes = listOf(
                    Fix(Fix.Kind.RESTART_ENGINE),
                    Fix(Fix.Kind.RUN_SELF_CHECK),
                ),
                summary = cause.id,
                neverBooted = facts.neverBooted,
            )
        }

        // ── 7.5 自动体检暴露出来的问题（B 阶段） ─────────────────────────
        //
        // 放在系统原因之后、兜底之前：它比「什么都没有」有价值，但比前面六类
        // 都弱 —— 前面六类是我们直接观测到的失败原因，而体检报的是「环境有毛病」，
        // 不一定就是这次起不来的直接原因。所以只在前面都没命中时才用它。
        facts.selfCheck?.let { report ->
            val bad = report.items.filter {
                it.status == SelfCheck.Item.Status.WARN || it.status == SelfCheck.Item.Status.FAIL
            }
            if (bad.isNotEmpty()) {
                // 按体检项的 id 前缀反推最贴近的 cause，让界面上的修复按钮对得上
                val ids = bad.map { it.id }
                val cause = when {
                    ids.any { it.startsWith("runtime") } -> Cause.RUNTIME_MISSING
                    ids.any { it.startsWith("storage") } -> Cause.STORAGE_DENIED
                    ids.any { it.startsWith("profile") } -> Cause.PROFILE_CORRUPT
                    ids.any { it == "install.residue" } -> Cause.RUNTIME_MISSING
                    else -> Cause.UNKNOWN
                }
                return Diagnosis(
                    cause = cause,
                    evidence = buildList {
                        add("self-check found ${bad.size} issue(s)")
                        bad.take(5).forEach { add("${it.id}: ${it.detail.ifBlank { it.title }}") }
                    },
                    fixes = buildList {
                        if (ids.any { it == "install.residue" }) {
                            add(Fix(Fix.Kind.CLEAN_INSTALL_RESIDUE, needsConfirm = true))
                        }
                        if (ids.any { it.startsWith("storage") } && ids.any { it.contains("owner") }) {
                            add(Fix(Fix.Kind.FIX_OWNERSHIP, needsConfirm = true))
                        }
                        if (facts.hasLastGoodProfile) {
                            add(Fix(Fix.Kind.ROLLBACK_PROFILE, needsConfirm = true))
                        }
                        add(Fix(Fix.Kind.RESTART_ENGINE))
                    },
                    summary = "SELF_CHECK: ${bad.size} issue(s) [${bad.take(3).joinToString(",") { it.id }}]",
                    neverBooted = facts.neverBooted,
                )
            }
        }

        // ── 8. 兜底 ─────────────────────────────────────────────────────
        return Diagnosis(
            cause = Cause.UNKNOWN,
            evidence = buildList {
                add("no failure marker matched — raw log tail follows")
                // 给最后 3 行非空日志，让用户/开发者至少有个抓手
                facts.logTail.lineSequence()
                    .filter { it.isNotBlank() }
                    .toList()
                    .takeLast(3)
                    .forEach { add(it.trim().take(160)) }
                if (isEmpty()) add("no log output at all")
            },
            fixes = listOf(
                Fix(Fix.Kind.RUN_SELF_CHECK),
                Fix(Fix.Kind.RESTART_ENGINE),
                Fix(Fix.Kind.REINSTALL_RUNTIME, needsConfirm = true),
            ),
            summary = if (facts.neverBooted) "UNKNOWN (never booted)" else "UNKNOWN",
            neverBooted = facts.neverBooted,
        )
    }

    private const val PKG = "app.dsh.mobile"

    // ── 事实采集 ──────────────────────────────────────────────────────────
    //
    // 上面是纯函数（喂 Facts 出 Diagnosis），便于静态测；下面是真正去系统里
    // 取 Facts 的实现。两边分开是为了让探针能在没有设备的情况下测归因逻辑。

    /**
     * 从设备采集事实。
     *
     * 每项都独立 try-catch：任何一项取不到都只是「该项证据缺失」，
     * 不能让整次归因失败（那会退化成 UNKNOWN，比缺一项证据糟糕得多）。
     */
    fun collectFacts(
        ctx: Context,
        logSinceOffset: Long = 0L,
        neverBooted: Boolean = false,
        hasLastGoodProfile: Boolean = false,
        inSafeMode: Boolean = false,
        runSelfCheck: Boolean = false,
    ): Facts {
        val portInUse = runCatching { portListening() }.getOrDefault(false)
        val owners = if (portInUse) runCatching { portOwners() }.getOrDefault(emptyMap()) else emptyMap()

        val runtimeMissing = runCatching { runtimeMissingFiles(ctx) }.getOrDefault(emptyList())
        val profileBroken = runCatching { unparseableConfigs(ctx) }.getOrDefault(emptyList())
        val residue = runCatching { hasInstallResidue(ctx) }.getOrDefault(false)
        val storageWritable = runCatching { storageOk(ctx) }.getOrDefault(true)
        val exit = runCatching { lastExitReason(ctx) }.getOrNull()

        return Facts(
            logTail = runCatching { readLogTail(ctx, logSinceOffset) }.getOrDefault(""),
            portInUse = portInUse,
            portOwners = owners,
            runtimeIntact = runtimeMissing.isEmpty(),
            runtimeMissing = runtimeMissing,
            profileBroken = profileBroken,
            storageWritable = storageWritable,
            systemExitReason = exit?.first.orEmpty(),
            systemExitOutOfMemory = exit?.second ?: false,
            neverBooted = neverBooted,
            installResidue = residue,
            hasLastGoodProfile = hasLastGoodProfile,
            inSafeMode = inSafeMode,
            // 体检放在最后跑：它最慢（要扫目录、可能触发特权修复），
            // 前面几项已经能给出大部分结论了。
            selfCheck = if (runSelfCheck) {
                runCatching { SelfCheck.run(ctx) }.getOrNull()
            } else null,
        )
    }

    /** 3080 是否有人在监听（连一下比读 /proc 可靠：容器/root 域都适用） */
    private fun portListening(): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(
                java.net.InetSocketAddress("127.0.0.1", EngineConfig.DEFAULT_PORT),
                200,
            )
            true
        }
    }.getOrElse { e ->
        // ConnectException = 明确拒绝 = 空闲；其余（超时/未知）保守按占用
        e !is java.net.ConnectException
    }

    /**
     * 占着 3080 的进程（pid -> cmdline）。
     *
     * 走 `/proc/net/tcp{,6}` 找 LISTEN 的 inode，再扫 `/proc/<pid>/fd` 匹配。
     * 这是免 root 唯一能用的办法（`lsof`/`ss -p` 在多数设备上没有或需要 root）。
     * 拿不到就返回空 map —— 调用方会归到 PORT_UNKNOWN_OWNER，仍可清场。
     */
    private fun portOwners(): Map<Int, String> = runCatching {
        val portHex = String.format("%04X", EngineConfig.DEFAULT_PORT)
        val inodes = mutableSetOf<String>()
        for (f in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val file = File(f)
            if (!file.canRead()) continue
            file.forEachLine { line ->
                val cols = line.trim().split(Regex("\\s+"))
                // sl local_address rem_address st ... inode
                // st == 0A 表示 LISTEN；local_address 形如 0100007F:0C08
                if (cols.size >= 10 && cols[3] == "0A") {
                    val local = cols[1]
                    if (local.endsWith(":$portHex", ignoreCase = true)) {
                        inodes += cols[9]
                    }
                }
            }
        }
        if (inodes.isEmpty()) return@runCatching emptyMap()

        val out = mutableMapOf<Int, String>()
        val proc = File("/proc")
        proc.list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            val fdDir = File(proc, "$name/fd")
            val hit = runCatching {
                fdDir.list()?.any { fd ->
                    val target = runCatching {
                        java.nio.file.Files.readSymbolicLink(File(fdDir, fd).toPath()).toString()
                    }.getOrNull().orEmpty()
                    target.startsWith("socket:[") &&
                        target.removePrefix("socket:[").removeSuffix("]") in inodes
                } ?: false
            }.getOrDefault(false)
            if (hit) {
                val cmd = runCatching {
                    File(proc, "$name/cmdline").readText().replace('\u0000', ' ').trim()
                }.getOrDefault("(cmdline unreadable)")
                out[pid] = cmd
            }
        }
        out
    }.getOrDefault(emptyMap())

    /** 读本次启动段之后的日志尾部（最多 16KB，够覆盖一段启动堆栈） */
    private fun readLogTail(ctx: Context, since: Long): String = runCatching {
        val f = File(EngineConfig.engineRoot(ctx), "engine.log")
        if (!f.isFile) return@runCatching ""
        val len = f.length()
        val from = since.coerceIn(0L, len)
        if (len <= from) return@runCatching ""
        java.io.RandomAccessFile(f, "r").use { raf ->
            val start = maxOf(from, len - 16_384)
            raf.seek(start)
            val buf = ByteArray((len - start).toInt())
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    }.getOrDefault("")

    /** 运行时关键文件是否都在；返回缺失项（空 = 完整） */
    private fun runtimeMissingFiles(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        val node = EngineConfig.nodeBin(ctx)
        val entry = EngineConfig.dshEntry(ctx)
        if (!node.isFile) out += "node: ${node.absolutePath}"
        else if (!node.canExecute()) out += "node not executable: ${node.absolutePath}"
        if (!entry.isFile) out += "entry: ${entry.absolutePath}"
        return out
    }

    /** 哪些配置文件解析不了（只做「能不能读成合法 JSON」，不校验字段语义） */
    private fun unparseableConfigs(ctx: Context): List<String> {
        val home = EngineConfig.dshHome(ctx)
        val candidates = listOf(
            File(home, "profiles/web/package.json"),
            File(home, "profiles/web/cordis.patch.yml"),
        )
        val out = mutableListOf<String>()
        for (f in candidates) {
            if (!f.isFile) continue
            // yml 不做解析（没有 yaml 库，且解析失败的形式太多）；只查能否读取
            val readable = runCatching { f.readText().isNotEmpty() }.getOrDefault(false)
            if (!readable) { out += f.absolutePath; continue }
            if (f.name.endsWith(".json")) {
                val ok = runCatching { org.json.JSONObject(f.readText()) }.isSuccess
                if (!ok) out += f.absolutePath
            }
        }
        return out
    }

    /**
     * 上次换装是否留下残渣。
     *
     * 判定与 [SelfCheck] 的 `install.residue` 一致：`engine.staging` 目录或
     * `cacheDir/runtime.zip` 还在。这里只测存在性，不测大小 —— 大小对
     * 「要不要清」的决策没有影响，但读目录会慢。
     */
    private fun hasInstallResidue(ctx: Context): Boolean = runCatching {
        File(EngineConfig.engineRoot(ctx).parentFile, "engine.staging").exists() ||
            File(ctx.cacheDir, "runtime.zip").exists()
    }.getOrDefault(false)

    private fun storageOk(ctx: Context): Boolean = runCatching {
        val home = EngineConfig.dshHome(ctx)
        home.isDirectory && home.canWrite()
    }.getOrDefault(true)

    /**
     * 上一次进程退出的系统原因（API 30+）。
     *
     * 这是**唯一能解释「日志里什么都没有」的证据**：低内存被杀、被厂商清理、
     * 用户手动停止，日志里都不会留任何一行。返回 (描述, 是否 OOM)。
     */
    private fun lastExitReason(ctx: Context): Pair<String, Boolean>? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching {
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return null
        val reason = when (info.reason) {
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
            android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
            android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
            android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
            else -> "reason=${info.reason}"
        }
        // 只有「我们自己的进程」才有解释力：退出原因里 status/description
        // 通常指向 App 进程，而引擎是子进程 —— 但 OOM 是全机性的，仍然有效。
        val desc = buildString {
            append(reason)
            if (info.status != 0) append(" status=${info.status}")
            runCatching { info.description }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                append(" (").append(it.take(80)).append(")")
            }
        }
        val oom = info.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY ||
            runCatching { info.description }.getOrNull()
                ?.contains("low memory", ignoreCase = true) == true
        return desc to oom
    }

    /** 给界面/日志用的一行摘要（不含本地化，供日志与反馈复制） */
    fun summarize(d: Diagnosis): String = buildString {
        append(d.cause.id)
        if (d.neverBooted) append(" (never booted)")
        if (d.evidence.isNotEmpty()) {
            append(" | ")
            append(d.evidence.joinToString(" ; ") { it.replace('\n', ' ') })
        }
        if (d.fixes.isNotEmpty()) {
            append(" | fix: ")
            append(d.fixes.joinToString(",") { it.kind.name.lowercase() })
        }
    }
}
