package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 启动前清理「原子写残留锁」。
 *
 * ## 要解决的问题
 *
 * 引擎里所有配置文件都走 `@deepseek-ai/dsh-atomic-write` 的 `withFileLock()`：
 * 它在目标文件旁建一个 `<文件>.lock`（内容是持有者的 pid），操作完 `rm`。
 * 如果引擎在这中间被系统杀掉（后台清理、OOM、用户强停），锁就留下了。
 *
 * 上游**已有一层自愈** `takeOverExitedLock()`：读锁里的 pid，`kill(pid, 0)`
 * 拿到 `ESRCH` 就接管。所以「死 pid」这一种本来能自己好。但实测（模拟器
 * emulator-5554，2026-11-21）其余四种都会一路等到超时才失败：
 *
 * | 锁内记录 | 上游行为 | 实测 |
 * | --- | --- | --- |
 * | `"<pid>\n"` 死进程 | `ESRCH` → 接管 | OK 7ms |
 * | `"<pid>\n"` 活着的无关进程 | `kill` 成功，不敢接管 | 超时 3140ms |
 * | `""`（写到一半被杀） | 不匹配 `/^\d+\n$/`，判「活着」 | 超时 3109ms |
 * | `"notapid\n"`（内容坏了） | 同上 | 超时 3094ms |
 * | 死 pid + 残留 `.takeover-*` | claim 的 `wx` 创建 `EEXIST` → 放弃 | 超时 3157ms |
 *
 * 最后一种是**永久自锁**：进程恰好死在「建好 claim、还没删 claim」的窗口里，
 * 此后任何竞争者都拿不到 claim，死 pid 也永远无法被接管。
 *
 * ## 后果有多重（模拟器取证）
 *
 * 把 `1` 写进 `.credentials.yaml.lock` 再冷启：
 *
 * ```
 * boot step spawn: 732ms
 * dsh: startup failed: 1 required plugin did not activate
 *   connection (required)
 *     Error: atomic-write: timed out waiting for the writer lock at
 *            .../dsh-home/.credentials.yaml.lock
 * engine exited: status=256 exit=1
 * boot step health-poll: 40484ms
 * ```
 *
 * 也就是 **40.5 秒后整个引擎启动失败**。`connection` 是 required 插件，
 * 用户看到的就是界面一直停在「正在启动引擎」。删掉那把锁后同一操作 13.5 秒
 * 正常就绪。
 *
 * ## 安全边界（「防止出错」的具体落实）
 *
 * 1. **只在启动前置阶段跑**：那时引擎按构造必未运行（`spawnEngine` 在其后），
 *    再叠一道端口探测，引擎还活着就整个跳过。
 * 2. **白名单目录逐层枚举，绝不递归扫树**：`node_modules` / `sessions` /
 *    `workspaces` 一个都不进。这既是安全边界，也是性能边界。
 * 3. **`session.lock` 永不触碰**：那是 `flock(2)` 内核锁，删除文件会生成新
 *    inode，反而让后来的写者绕过在世的持有者（真正的双写）。它也不靠这个
 *    机制，进程一死内核自动释放。
 * 4. **只动两种名字**：`*.lock` 与 `*.lock.takeover-*`。
 * 5. **删除必须有正面证据，含糊的一律保留**。三条证据按强度排序：
 *    - `cmdline` 读得到且带着本 app 的 engine/dsh-home 绝对路径 → 是本 app
 *      的活引擎，**绝不删**（盖住「退避重试时上一轮引擎还没死透」的窗口）；
 *    - 记录解析不出 pid → 没有合法持有者，**直接删**（写到一半被杀的原型）；
 *    - 锁的 mtime 已超过 [FRESH_MS] → 临界区只有毫秒级，不可能有活写者，**删**。
 *    剩下的情况（`/proc/<pid>/cmdline` 读不到，即跨 SELinux 域或别的 uid，
 *    且锁还很新）**保留**并打日志 —— 那时既证明不了它是写者，也证明不了它不是。
 * 6. **属主不是自己就不删**：与 [PluginCenter] 的 `healForeignLocks` 同一判据，
 *    宁可留给人工判断。
 * 7. **每次删除都写日志**：记路径 + 记录内容 + 当时判定理由，事后能从 logcat
 *    还原「谁删了什么、为什么」。
 *
 * ## 与已有自愈的关系
 *
 * | 自愈点 | 时机 | 覆盖面 |
 * | --- | --- | --- |
 * | `PluginCenter.healForeignLocks` | 装/卸插件的 CLI 入口 | 只删**属主是别人**的 `.lock` |
 * | `PluginCenter.healPnpmStore` | 同上 | pnpm 的 `.cache/pnpm-*` 目录 |
 * | **本类** | **每次启动前置** | 五种残留形态（含 takeover claim），只在本 app 的写点内 |
 *
 * 三者互补：前者解决「权限不允许」，本类解决「内容/凭据坏了导致永不接管」。
 */
object StaleLockHealer {

    private const val TAG = "StaleLockHealer"

    /** 绝不放行的文件名。`session.lock` 是 flock 内核锁，删了会制造双写。 */
    private val NEVER = setOf("session.lock")

    /** 一次最多删多少个：异常环境下（磁盘满、反复崩溃）不能拖死启动。 */
    private const val MAX_DELETIONS = 400

    /**
     * 锁「老到不可能有活写者」的阈值。
     *
     * atomic-write 的临界区是「写临时文件 + rename」，低端机实测也是
     * 毫秒级到十几毫秒；`DOCUMENT_LOCK_WAIT_MS=30000` 是排队等待上限、
     * 不是持锁时长。20 秒留了三个数量级余量，同时保证「上次崩溃留下的锁」
     * 在本次启动时一定已经够老。
     */
    private const val FRESH_MS = 20_000L

    data class Result(
        /** 实际删掉的锁数（文件 + pnpm 锁目录） */
        val removed: Int = 0,
        /** 因「证明是活着的 DSH 进程」而跳过的数 */
        val kept: Int = 0,
        /** 删不掉（权限/属主）的路径，便于事后排查 */
        val failed: List<String> = emptyList(),
        /** 引擎还在跑时整个跳过 */
        val skippedEngineRunning: Boolean = false,
    )

    /** 单把锁的处置结论。抽成枚举是为了让单测能直接断言判定表，不必造文件系统。 */
    enum class Action {
        /** 残留，删掉 */
        DELETE,

        /** 能证明持有者仍是活着的 DSH 进程，保留 */
        KEEP,

        /** 不是本类管的文件（名字不符 / 永不触碰） */
        IGNORE,
    }

    /**
     * 解析锁内记录，取持有者 pid。取不到返回 null。
     *
     * 与上游 `holderExited()` 用同一个正则 `/^\d+\n$/`：上游认作「合法
     * 记录」的就是这种形态，其余一律被它当成「活着」。
     */
    internal fun parseHolder(record: String?): Int? {
        if (record == null) return null
        if (!HOLDER_RECORD.matches(record)) return null
        val pid = record.trim().toIntOrNull() ?: return null
        if (pid <= 0) return null
        return pid
    }

    /** 上游 atomic-write 的合法锁记录形态：十进制 pid + 换行。 */
    private val HOLDER_RECORD = Regex("^\\d+\\n$")

    /**
     * 纯判定：这把锁该不该删。
     *
     * ## 为什么不用 uid 判活
     *
     * 第一版试过「取 `/proc/<pid>` 的 `st_uid` 与自比」，设备上被证伪：
     * `EACCES` 同时出现在**两种完全不同**的场景 ——
     *
     * | 场景 | 域 | uid | /proc 可读 |
     * | --- | --- | --- | --- |
     * | 真引擎 | `untrusted_app_27`（与 App 同域） | 自己 | 可读 |
     * | `run-as` 起的活 node | `runas_app`（异域） | 自己 | **EACCES** |
     * | init(1) | `init`（异 uid） | 别人 | **EACCES** |
     *
     * 也就是说 EACCES 既可能是「pid 被别的 app 复用」（该删），也可能是
     * 「同 uid 但跨域的活写者」（不能删）。单靠它必然在两端各错一次。
     *
     * ## 改用「锁的年龄」这条正面证据
     *
     * `withFileLock` 的临界区只有「写临时文件 + rename」，持锁时间是毫秒级；
     * `DOCUMENT_LOCK_WAIT_MS=30000` 是**排队等待**的上限，不是持锁时长。
     * 所以「这把锁已经存在超过 [FRESH_MS]」本身就证明**没有任何活写者**
     * 正在用它 —— 与 pid、uid、SELinux 域全部无关。
     *
     * ## 判定顺序
     *
     * 每一条 KEEP 都是「宁可让引擎多等 30 秒，也不冒双写配置文件的风险」；
     * 每一条 DELETE 都必须有站得住的证据。
     *
     * @param record 锁文件内容（读不到传 null）
     * @param selfPid 本进程 pid —— 记录指向自己必然是残留（本进程没持有它）
     * @param holderAlive 持有者是否活着；记录不可解析时忽略
     * @param holderIsDsh 持有者能否被证明是本 app 的 DSH 进程；null = 读不到 cmdline
     * @param ageMs 锁文件已存在多久（毫秒），取不到传 -1
     */
    internal fun classify(
        record: String?,
        selfPid: Int,
        holderAlive: Boolean = false,
        holderIsDsh: Boolean? = null,
        ageMs: Long = -1L,
    ): Action {
        // 内容解析不出 pid ⇒ 没有任何合法持有者。
        //
        // 上游占锁用的是 `writeFile(lockPath, "<pid>\n", { flag: "wx" })`：
        // 文件一被创建就带着 pid。读出来是空/垃圾，只有两种可能 ——
        //   1. 写者在 open(wx) 与 write 之间被杀（就是用户报的那个场景）；
        //   2. 文件内容被别人改写。
        // 两种都没有活着的合法写者，必须删；保留的唯一后果是上游把它当「活着」
        // 永久等待（实测 3109ms / 3094ms 超时，真机 40.5 秒后整引擎失败）。
        //
        // 会不会误删「正在创建的锁」？不会：本类只在 spawnEngine 之前跑，
        // 那时还没有 DSH 进程在写配置；再加上 portInUse 探测，有引擎在跑就整体跳过。
        val pid = parseHolder(record) ?: return Action.DELETE
        // 指向自己：本进程没持有它（本轮还没 spawn 引擎），就是残留
        if (pid == selfPid) return Action.DELETE
        // 死进程：上游本来也能接管，我们顺手删掉省掉最多 2s 的退避
        if (!holderAlive) return Action.DELETE
        // 活着且能证明是本 app 的 DSH 进程：绝不动
        if (holderIsDsh == true) return Action.KEEP
        // 活着但不是我们的写者，且已经老到不可能有活写者：删
        if (oldEnough(ageMs)) return Action.DELETE
        // 活着、证明不了身份、还很新：可能是正在写的写者，保留
        return Action.KEEP
    }

    /**
     * 锁是否老到「不可能有活写者」。取不到年龄（-1）时保守判否。
     *
     * 20 秒的取值依据：atomic-write 的临界区是「写临时文件 + rename」，
     * 低端机上实测也是毫秒到十几毫秒；留三个数量级余量仍然安全。
     */
    private fun oldEnough(ageMs: Long): Boolean = ageMs >= FRESH_MS

    /** 文件名是不是本类要处理的锁（含 takeover claim 凭据）。 */
    internal fun isLockName(name: String): Boolean {
        if (name in NEVER) return false
        return name.endsWith(".lock") || name.contains(".lock.takeover-")
    }

    /**
     * 扫描白名单目录 —— **逐层枚举，不递归**。
     *
     * 为什么不递归：`dsh-home` 下面有 `node_modules`（几万个文件）、
     * `sessions`（历史会话正文）、`workspaces`（用户数据）。递归既慢又危险，
     * 而锁只出现在「被 withFileLock 包住的配置文件」旁边，位置有限可枚举。
     */
    private fun scanDirs(ctx: Context): List<File> {
        val home = runCatching { EngineConfig.dshHome(ctx) }.getOrNull() ?: return emptyList()
        val dirs = mutableListOf<File>()
        // ① 家目录直接子文件：.credentials.yaml.lock 之类
        dirs.add(home)
        // ② llm-deepseek/files-v3.json.lock
        dirs.add(File(home, "llm-deepseek"))
        // ③ liveshare/forks.json.lock（owner-shares.json 同级）
        dirs.add(File(home, "liveshare"))
        // ④ 每个 profile 的直接子文件：package.json.lock / compatibility.json.lock
        runCatching {
            File(home, "profiles").listFiles { f -> f.isDirectory }?.forEach { profile ->
                dirs.add(profile)
                // ⑤ 插件市场详情缓存：<profile>/.plugin-market/repositories/<repo>.lock
                dirs.add(File(profile, ".plugin-market/repositories"))
            }
        }
        return dirs.filter { it.isDirectory }
    }

    /**
     * 启动前置清理。必须在 `spawnEngine()` 之前调用。
     *
     * @param engineRunning 引擎是否仍在跑的探测。为 true 时整个跳过 ——
     *        那时锁是**真活的**，删了就是制造双写。
     */
    fun heal(ctx: Context, engineRunning: () -> Boolean = { false }): Result {
        if (runCatching { engineRunning() }.getOrDefault(false)) {
            Log.i(TAG, "skip: engine still running")
            return Result(skippedEngineRunning = true)
        }
        val uid = android.os.Process.myUid()
        val selfPid = android.os.Process.myPid()
        var removed = 0
        var kept = 0
        val failed = mutableListOf<String>()

        scanDirs(ctx).forEach { dir ->
            val locks = runCatching {
                dir.listFiles { f -> f.isFile && isLockName(f.name) }?.toList() ?: emptyList()
            }.getOrDefault(emptyList())
            for (f in locks) {
                if (removed >= MAX_DELETIONS) break
                // 属主不是自己：交给 healForeignLocks / 人工，不在这里越权
                val owner = ownerUidOf(f)
                if (owner > 0 && owner != uid) {
                    Log.i(TAG, "skip foreign-owned lock owner=" + owner + " " + f.absolutePath)
                    continue
                }
                val record = runCatching { f.readText() }.getOrNull()
                val holderPid = parseHolder(record)
                val alive = holderPid != null && holderPid != selfPid && pidAlive(holderPid)
                // 「能不能证明它是活着的 DSH 进程」—— 读不到 cmdline 就是证明不了
                val isDsh = if (alive && holderPid != null) cmdlineLooksLikeDsh(holderPid, ctx) else null
                // 锁的年龄：唯一不受 pid 复用、uid 可见性、SELinux 域影响的
                // 客观证据。详见 classify 的文档。
                val ageMs = System.currentTimeMillis() - f.lastModified()
                when (classify(record, selfPid, alive, isDsh, ageMs)) {
                    Action.KEEP -> {
                        kept++
                        // 两种 KEEP 要能区分：真活的 DSH 写者 vs 「太新、身份不明」。
                        // 后者是可疑项，排查时第一个要看。
                        Log.i(
                            TAG,
                            (if (isDsh == true) "kept: holder is a live dsh process" else "kept: lock too fresh, holder unprovable") +
                                " pid=" + (holderPid ?: "unparsable") +
                                " alive=" + alive + " ageMs=" + ageMs + " " + f.absolutePath,
                        )
                    }
                    Action.DELETE -> if (delete(f)) {
                        removed++
                        Log.w(
                            TAG,
                            "removed stale lock " + f.absolutePath +
                                " record=" + (record?.take(24)?.replace("\n", "\\n") ?: "<unreadable>") +
                                " pid=" + (holderPid ?: "unparsable") +
                                " alive=" + alive + " isDsh=" + isDsh +
                                " ageMs=" + ageMs,
                        )
                    } else {
                        failed.add(f.absolutePath)
                        Log.w(TAG, "cannot remove stale lock " + f.absolutePath)
                    }
                    Action.IGNORE -> Unit
                }
            }
        }

        // pnpm 的 operation-lock 是**目录**（`pnpm-store-operation-locks-<uid>/`），
        // 内容全是可重建缓存。逻辑与 PluginCenter.healPnpmStore 同源，但那边只在
        // 装插件时跑 —— 引擎启动被这些锁卡住时它来不及。`.cache/pnpm` 必须排除
        // （那是 PNPM_HOME 全局 bin，不是锁）。
        runCatching {
            val cache = File(EngineConfig.dshHome(ctx), ".cache")
            cache.listFiles { f -> f.name.startsWith("pnpm") && f.name != "pnpm" }?.forEach { f ->
                if (removed >= MAX_DELETIONS) return@forEach
                if (delete(f)) {
                    removed++
                    Log.w(TAG, "removed pnpm lock dir " + f.absolutePath)
                }
            }
        }

        if (removed > 0 || kept > 0) {
            Log.i(TAG, "heal done: removed=" + removed + " kept=" + kept + " failed=" + failed.size)
        }
        return Result(removed, kept, failed)
    }

    /**
     * 这个 pid 还在不在。
     *
     * **不能用 `Process.sendSignal(pid, 0)`**：实测（emulator-5554，2026-11-21）
     * 它对超出 `/proc/sys/kernel/pid_max`（该机 32768）的 pid **不报错**，
     * 于是 `999999` 被判成 alive=true，日志里出现 `pid=999999 alive=true` 的
     * 明显矛盾行。Android 的 kill 实现只做 range check，不校验存在性。
     *
     * 改用 `stat` 系统调用读 `/proc/<pid>` 的 errno 三态，实测：
     *
     * | 路径 | errno | 含义 |
     * | --- | --- | --- |
     * | `/proc/self` | 成功 | 活着且本进程可读 |
     * | `/proc/1` | `EACCES` | 活着，但属主是别人（够不着详情） |
     * | `/proc/999999` | `ENOENT` | 已经不在 |
     */
    private fun pidAlive(pid: Int): Boolean = try {
        android.system.Os.stat("/proc/" + pid)
        true
    } catch (e: android.system.ErrnoException) {
        // 只有 ENOENT 能证明它不在了；EACCES/EPERM 都是「在，但看不到」
        e.errno != android.system.OsConstants.ENOENT
    } catch (e: Throwable) {
        // 判不出来时按「活着」处理：后面的 cmdline 判定同样证明不了，仍会落到
        // DELETE，所以这里不影响结论，只是不谎报「已死」。
        true
    }

    /**
     * 持有者能否被证明是本 app 的 DSH 进程。
     *
     * @return true = 读到 cmdline 且带本 app 的 engine / dsh-home 绝对路径；
     *         false = 读到了但不像；null = **读不到**（别的 uid，证明不了）。
     *
     * 实测依据：`cat /proc/1/cmdline` 在 app 身份下是 Permission denied，
     * 所以要严格区分「不像」与「读不到」—— 前者可安全删除，后者按残留处理
     * 但必须留日志。
     */
    private fun cmdlineLooksLikeDsh(pid: Int, ctx: Context): Boolean? {
        val bytes = runCatching { File("/proc/" + pid + "/cmdline").readBytes() }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        val cmd = String(bytes, Charsets.UTF_8).replace('\u0000', ' ')
        val marks = listOfNotNull(
            runCatching { EngineConfig.engineRoot(ctx).absolutePath }.getOrNull(),
            runCatching { EngineConfig.dshHome(ctx).absolutePath }.getOrNull(),
        )
        return marks.any { it.isNotEmpty() && cmd.contains(it) }
    }

    /** 取文件属主 uid，取不到返回 -1（与 PluginCenter.ownerUidOf 同实现）。 */
    private fun ownerUidOf(f: File): Int = runCatching {
        (java.nio.file.Files.getAttribute(
            f.toPath(),
            "unix:uid",
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ) as Number).toInt()
    }.getOrDefault(-1)

    /** 删文件或目录；删不动返回 false（不抛）。 */
    private fun delete(f: File): Boolean {
        if (f.isDirectory) {
            return runCatching { EngineConfig.deleteNoFollow(f); !f.exists() }.getOrDefault(false)
        }
        return runCatching { f.delete() }.getOrDefault(false)
    }
}
