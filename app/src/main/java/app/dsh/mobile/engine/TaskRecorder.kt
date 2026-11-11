package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import kotlin.random.Random

/**
 * 轨迹录制器（M1 / F1.1）—— 只管「如实记下来」，不剪枝、不判断、不入库。
 *
 * ## 职责边界
 * - **写**：一次任务一个 `files/traces/<traceId>.jsonl`，逐行追加并立即 flush
 *   （进程被杀也不丢已经写完的部分 —— 这正是录制器的全部价值）。
 * - **读**：list() / get() 供 /trace/list、/trace/get 使用。
 * - **清**：clear() 删全部、pruneOld() 按 TTL 7 天 + 最多 200 个文件淘汰。
 * - **不做**：剪枝（TracePruner）、入库（MemoryStore）、判断这一步好不好 ——
 *   一旦录制器开始「聪明」，原始证据就没了，剪枝规则再改也没法重跑。
 *
 * ## 三条硬约束（都会直接毁掉这个模块的价值，改动时别绕过）
 * 1. **录制是旁路**：任何 IO 失败只 Log.w，绝不向上抛。点错一次屏幕的代价，
 *    远大于丢一条轨迹 —— 无障碍动作不能因为录制失败而失败。
 * 2. **热路径只追加**：step() 一次 = 一次 encode + 一次 write + flush，
 *    **不重读整文件**（实测目标 < 5ms）。整文件读只发生在 get()/list()。
 * 3. **状态自己管**：所有写状态都在同一把锁里，外部可以在任意线程调 ——
 *    无障碍回调、循环执行器、HTTP 端点都可能同时进来。
 *
 * ## 时间口径（契约 §4）
 * 全部 System.currentTimeMillis() 的 epoch ms，不用单调时钟：轨迹要跨进程/跨设备比对。
 * 头行的 startedAt 与每步 verify.ms 是墙钟真实值；step 的 ts 由「现在 - ms」反推动作
 * 发起时刻（调用方给的是耗时，不是起点）。
 */
object TaskRecorder {

    private const val TAG = "TaskRecorder"
    private const val EXT = ".jsonl"

    /** 所有写状态共用这把锁：录制是低频操作（一步一次），锁竞争不是瓶颈，顺序才是。 */
    private val lock = Any()

    @Volatile private var curId: String? = null
    @Volatile private var out: Writer? = null
    @Volatile private var startAt = 0L
    @Volatile private var stepNo = 0
    /** 单文件行数已到上限（TraceContract.MAX_LINES_PER_FILE）：后续步骤不再落盘，日志只说一次。 */
    @Volatile private var capped = false
    @Volatile private var headGoal = ""
    @Volatile private var headPkg = ""
    /** 连续失败计数：磁盘满/只读时每一步都会失败，前几条记日志，之后静默，别把 logcat 淹了。 */
    private var failures = 0

    // ===== 生命周期 =====

    /**
     * 开始一条新轨迹，返回 traceId（格式 t_<epochMillis>_<4位随机>，契约 §1）。
     *
     * 上一次没 end 就重开（任务被打断、模型重规划、上一个任务崩了）时，先把旧轨迹
     * 补一条 ok=false / reason=interrupted 的尾行再开新的：否则那条文件永远停在
     * 「无尾行」状态，剪枝时无法区分「任务失败」和「进程被杀」。
     *
     * 返回的 id 即使落盘失败也是有效的（此时 isRecording() 为 false）——
     * 调用方拿它做日志关联仍然有意义，不该因为磁盘问题把任务拦下来。
     */
    fun begin(ctx: Context, goal: String, pkg: String): String = begin(ctx, goal, pkg, 0, 0, 0)

    /**
     * 同上，但由调用方给分辨率。
     *
     * 契约 §2.1 的 device 字段要求 w/h/density；三个参数传 0 时退化为
     * Context.resources.displayMetrics（应用可见区域，够用且不会崩）。
     * 之所以留这个重载：displayMetrics 在多窗口/分屏下拿到的是窗口尺寸而不是屏幕尺寸，
     * 真要精确值的调用方（AgentBridge 手上有真实 metrics）可以直接给。
     */
    fun begin(ctx: Context, goal: String, pkg: String, w: Int, h: Int, density: Int): String {
        val now = System.currentTimeMillis()
        val tid = newId(ctx, now)
        synchronized(lock) {
            if (out != null) sealLocked(false, "interrupted")
            curId = tid
            startAt = now
            headGoal = goal
            headPkg = pkg
            stepNo = 0
            capped = false
            failures = 0
            val (dw, dh, dd) = if (w > 0 && h > 0) Triple(w, h, density) else metricsOf(ctx)
            val opened = runCatching {
                val dir = dirOf(ctx)
                if (!dir.isDirectory && !dir.mkdirs()) error("cannot create " + dir.absolutePath)
                val head = TraceCodec.encodeHead(TraceDoc(tid, now, pkg, goal), dw, dh, dd)
                val writer = OutputStreamWriter(FileOutputStream(File(dir, tid + EXT), true), Charsets.UTF_8)
                try {
                    writer.write(head)
                    writer.write("\n")
                    writer.flush()
                } catch (e: Throwable) {
                    // 头行写不进去就别留着这个句柄：半截文件比没有文件更难查
                    runCatching { writer.close() }
                    throw e
                }
                writer
            }
            opened.onSuccess { out = it }
                .onFailure {
                    out = null
                    Log.w(TAG, "begin failed id=" + tid + ": " + it.message)
                }
            // 清理放在 begin：一次任务只做一次，不在热路径上。
            // 刚开的新文件 startedAt 最新，永远不会被自己扫到；pruneLocked 还会按名字跳过它。
            pruneLocked(ctx)
        }
        return tid
    }

    /** 封尾并关闭当前轨迹。没有在录时什么都不做。 */
    fun end(ok: Boolean, reason: String) {
        synchronized(lock) { sealLocked(ok, reason) }
    }

    fun isRecording(): Boolean = out != null

    /** 当前在录的 traceId；没在录返回 null（契约要求：调用方用它判断要不要记）。 */
    fun currentTraceId(): String? = if (out != null) curId else null

    // ===== 热路径 =====

    /**
     * 记一步。
     *
     * 没 begin 时**静默忽略**（不抛、不写、不日志）：录制是旁路，调用方到处都能调它，
     * 不应该要求每个调用点先判断 isRecording()。
     *
     * @param endpoint 动作类型，取值见 TraceEndpoint（tap|gesture|input|key|scroll|launch|...）
     * @param args 端点原始参数，**调用方负责脱敏**（ScreenMask），录制器不做二次加工
     * @param beforeFp / @param afterFp 动作前后指纹（SceneMemory.fingerprint，必须含 pkg:）
     * @param status 判定结果，取值见 TraceStatus
     * @param via 判定依据，取值见 TraceVia
     * @param ms 从动作发起到判定完成的墙钟耗时
     * @return 无返回值：写失败也只会让这条轨迹少一行，绝不影响动作本身
     */
    fun step(
        endpoint: String,
        args: Map<String, String>,
        beforeFp: Set<String>,
        afterFp: Set<String>,
        anchor: TraceAnchor?,
        status: String,
        via: String,
        ms: Long,
        retries: Int = 0,
        caller: String = "loop",
        protected: Boolean = false,
    ) {
        // 快路径：没在录就直接走人，连锁都不碰（这一步是最热的调用点）
        if (out == null) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val writer = out ?: return
            if (capped) return
            if (stepNo >= TraceContract.MAX_LINES_PER_FILE) {
                // 跑飞的循环会无限写。到顶就停止落盘，但**不封尾**：
                // 让 end() 仍能补上尾行，这条轨迹照样能读、能剪（只是末尾缺失）。
                capped = true
                Log.w(TAG, "trace " + curId + " reached " + TraceContract.MAX_LINES_PER_FILE + " lines, dropping further steps")
                return
            }
            val entry = TraceStep(
                stepNo,
                // 契约 §2.2 的 ts 是「动作发起时刻」，而调用方给的是动作耗时（发起 -> 判定完成），
                // 所以发起时刻 = 现在 - ms。直接记 now 会把长动作的起点系统性推后，
                // 长按/输入这类几百毫秒的动作在时间轴上就错位了。
                now - if (ms > 0) ms else 0L,
                endpoint,
                args,
                beforeFp,
                afterFp,
                anchor,
                TraceVerify(status = status, via = via, ms = ms, retries = retries),
                caller,
                protected,
            )
            val line = runCatching { TraceCodec.encodeStep(entry) }.getOrElse {
                failures++
                if (failures <= 3) Log.w(TAG, "encode step failed: " + it.message)
                return
            }
            runCatching {
                writer.write(line)
                writer.write("\n")
                // 每行都 flush：进程被杀时，用户/上级工具要看到「已经发生过的动作」，
                // 缓冲到 8KB 再落盘等于丢掉崩溃前最后几十步 —— 那是排障最值钱的部分。
                writer.flush()
            }.onFailure {
                failures++
                if (failures <= 3) Log.w(TAG, "step write failed: " + it.message)
            }
            stepNo++
        }
    }

    // ===== 读 =====

    /**
     * 列出轨迹（只读头行），按 startedAt 倒序。
     *
     * 为什么不用 _index.json：200 个文件各读一行也就几毫秒，而索引多一份就要多维护
     * 一处一致性（写完没更新、被外部删了、跨进程并发写）。契约 §1 里它本就是可选项，
     * 这里直接扫目录，**目录就是索引**，永远不会与真相不一致。
     */
    fun list(ctx: Context, limit: Int = 50): List<TraceDoc> {
        if (limit <= 0) return emptyList()
        val files = runCatching { dirOf(ctx).listFiles() }.getOrNull() ?: return emptyList()
        val out = ArrayList<TraceDoc>(minOf(files.size, limit * 2))
        for (f in files) {
            if (!f.isFile || !f.name.endsWith(EXT)) continue
            val head = readHead(f) ?: continue
            out.add(
                TraceDoc(
                    traceId = head.traceId.ifEmpty { f.name.removeSuffix(EXT) },
                    startedAt = head.startedAt,
                    pkg = head.pkg,
                    goal = head.goal,
                    schema = head.schema,
                )
            )
        }
        return out.sortedByDescending { it.startedAt }.take(limit)
    }

    /**
     * 完整读回一条轨迹（含 steps 与尾行）。
     *
     * 读不出来返回 null（不存在/名字非法/IO 失败）—— 端点据此回 404/500 都由调用方定。
     * 坏行跳过而不是整体失败：一条轨迹里有 4000 行，为了一行坏 JSON 把另外 3999 行丢掉，
     * 等于把唯一的原始证据也丢了。
     */
    /**
     * 只数「事件行」条数，不做 JSON 解析（/trace/list 用）。
     *
     * 为什么不让 list() 直接返回 steps：list 只读每个文件的**头行**（200 个小文件也快），
     * 为了显示一个数字把整条轨迹读进来，等于把「列表」做成「全量读」。
     */
    fun stepCount(ctx: Context, traceId: String): Int = runCatching {
        val f = fileOf(ctx, traceId)
        if (!f.isFile) return 0
        var n = 0
        f.forEachLine { line -> if (line.contains("\"step\"")) n++ }
        n
    }.getOrDefault(0)

    fun get(ctx: Context, traceId: String): TraceDoc? {
        if (!safeName(traceId)) return null
        val f = fileOf(ctx, traceId)
        if (!f.isFile) return null
        return runCatching {
            var head: TraceHead? = null
            var endOk: Boolean? = null
            var endReason = ""
            var seq = 0
            val steps = ArrayList<TraceStep>()
            f.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    when (val item = TraceCodec.decodeLine(line)) {
                        is TraceHead -> if (head == null) head = item
                        is TraceStep -> {
                            // 老 fixture 没有 index 字段（解码给 -1），按行序补齐：
                            // 剪枝器靠 index 回溯「原来第几步」，缺了它整条证据链就断了
                            steps.add(if (item.index >= 0) item else item.copy(index = seq))
                            seq++
                        }
                        is TraceEnd -> {
                            endOk = item.ok
                            endReason = item.reason
                        }
                        else -> Unit
                    }
                }
            }
            val h = head
            TraceDoc(
                traceId = h?.traceId?.ifEmpty { traceId } ?: traceId,
                startedAt = h?.startedAt ?: f.lastModified(),
                pkg = h?.pkg.orEmpty(),
                goal = h?.goal.orEmpty(),
                steps = steps,
                endOk = endOk,
                endReason = endReason,
                schema = h?.schema ?: TraceContract.SCHEMA,
            )
        }.getOrElse {
            Log.w(TAG, "get failed id=" + traceId + ": " + it.message)
            null
        }
    }

    // ===== 清理 =====

    /** 删全部轨迹（含正在录的那条）。先关句柄再删，否则某些文件系统上删不掉打开中的文件。 */
    fun clear(ctx: Context) {
        synchronized(lock) { closeLocked() }
        runCatching {
            val files = dirOf(ctx).listFiles() ?: return@runCatching
            var n = 0
            for (f in files) {
                if (f.isFile && runCatching { f.delete() }.getOrDefault(false)) n++
            }
            Log.i(TAG, "cleared " + n + " trace files")
        }.onFailure { Log.w(TAG, "clear failed: " + it.message) }
    }

    /**
     * 按 TTL 7 天 + 最多 200 个文件淘汰（契约 §1）。
     *
     * 决策在 [TraceRetention]（纯函数、有 JVM 断言），这里只负责「读每个文件的 startedAt
     * 与删文件」。读不出头行的坏文件用 lastModified() 兜底 —— 否则一个坏文件永久占着名额。
     */
    fun pruneOld(ctx: Context) {
        synchronized(lock) { pruneLocked(ctx) }
    }

    // ===== 内部 =====

    private fun dirOf(ctx: Context): File = File(ctx.filesDir, TraceContract.DIR)

    private fun fileOf(ctx: Context, traceId: String): File = File(dirOf(ctx), traceId + EXT)

    /**
     * traceId 白名单校验。
     *
     * 端点会把 URL 里的 id 直接传进来，不校验就等于把 filesDir 交给调用方
     * （../../shared_prefs/... 能读也能删）。轨迹 id 只会是 [0-9a-zA-Z_-]，
     * 别的字符一律当非法，宁可 404。
     */
    private fun safeName(id: String): Boolean {
        if (id.isEmpty() || id.length > 64) return false
        for (c in id) {
            val ok = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == '-'
            if (!ok) return false
        }
        return true
    }

    /** 头行渲染成一行。读不出（空文件/坏 JSON/第一条不是头行）返回 null，由调用方决定跳过。 */
    private fun readHead(f: File): TraceHead? = runCatching {
        val line = f.bufferedReader(Charsets.UTF_8).use { it.readLine() }
        if (line == null) null else TraceCodec.decodeLine(line) as? TraceHead
    }.getOrElse { null }

    /** 写尾行 + flush + close。失败只记账（磁盘满时尾声丢失，但前面的步骤还在）。 */
    private fun sealLocked(ok: Boolean, reason: String) {
        val writer = out ?: run {
            curId = null
            return
        }
        out = null
        val id = curId
        runCatching {
            writer.write(TraceCodec.encodeEnd(System.currentTimeMillis(), ok, reason))
            writer.write("\n")
            writer.flush()
            writer.close()
        }.onFailure {
            failures++
            Log.w(TAG, "end write failed: " + it.message)
            runCatching { writer.close() }
        }
        Log.i(TAG, "trace sealed id=" + id + " ok=" + ok + " reason=" + reason + " steps=" + stepNo)
        curId = null
        capped = false
    }

    /** 静默关闭（clear 用）：不写尾行，因为文件马上就删。 */
    private fun closeLocked() {
        val writer = out ?: return
        out = null
        curId = null
        capped = false
        runCatching {
            writer.flush()
            writer.close()
        }.onFailure { Log.w(TAG, "close failed: " + it.message) }
    }

    private fun pruneLocked(ctx: Context) {
        runCatching {
            val dir = dirOf(ctx)
            val files = dir.listFiles() ?: return@runCatching
            val active = curId?.let { it + EXT }
            val pairs = ArrayList<Pair<String, Long>>(files.size)
            for (f in files) {
                if (!f.isFile || !f.name.endsWith(EXT)) continue
                // 正在录的这条永远不参与淘汰：它是「最新」的，任何按时间排序都轮不到它，
                // 但时钟回拨/文件被 touch 过时这个保护才真正救命。
                if (f.name == active) continue
                pairs.add(f.name to (readHead(f)?.startedAt ?: f.lastModified()))
            }
            val doomed = TraceRetention.toDelete(pairs, System.currentTimeMillis())
            var n = 0
            for (name in doomed) {
                if (runCatching { File(dir, name).delete() }.getOrDefault(false)) n++
            }
            if (n > 0) Log.i(TAG, "pruned " + n + " traces, kept " + (pairs.size - n))
        }.onFailure { Log.w(TAG, "prune failed: " + it.message) }
    }

    /**
     * 生成 traceId：t_<epochMillis>_<4位随机>（契约 §1）。
     *
     * 随机部分用 4 位十六进制，同一毫秒内重开几乎不会撞；万一撞了（或目录读不到），
     * 就以确定性递增再试 64 次，仍然保持 4 位格式 —— 绝不退化成写同一个文件。
     */
    private fun newId(ctx: Context, now: Long): String {
        val dir = dirOf(ctx)
        val prefix = "t_" + now + "_"
        repeat(8) {
            val rand = Random.nextInt(0x10000).toString(16).padStart(4, '0')
            val candidate = prefix + rand
            if (!File(dir, candidate + EXT).exists()) return candidate
        }
        val seed = Random.nextInt(0x10000)
        repeat(64) { i ->
            val candidate = prefix + ((seed + i) and 0xFFFF).toString(16).padStart(4, '0')
            if (!File(dir, candidate + EXT).exists()) return candidate
        }
        // 理论上到不了这里（64 次全撞 = 同一毫秒内有 64 条同前缀轨迹）。
        // 兜底也不破坏契约：仍然 4 位，只是不再保证唯一 —— 而后面的写入是追加模式，
        // 万一真撞了，宁可两条轨迹拼在一起，也不要抛异常打断任务。
        return prefix + Random.nextInt(0x10000).toString(16).padStart(4, '0')
    }

    /** 应用可见的屏幕指标。只是头行元数据，取不到就给 0，不参与任何逻辑。 */
    private fun metricsOf(ctx: Context): Triple<Int, Int, Int> = runCatching {
        val dm = ctx.resources.displayMetrics
        Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }.getOrElse { Triple(0, 0, 0) }
}
