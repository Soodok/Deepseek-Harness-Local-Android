package app.dsh.mobile.engine.cap

import android.content.Context
import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.LinuxContainerRunner
import app.dsh.mobile.engine.LinuxEnvironmentManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 任务化执行：把「发一条命令等结果」升级成「提交任务 → 轮询进度 → 取产物」。
 *
 * ## 为什么要有这一层（而不是让调用方自己 nohup）
 *
 * `/dev/linux/exec` 的硬上限是 120 秒，且返回体里要带上**全量** stdout/stderr。
 * 真实编译跑几分钟、输出几百 KB，两条都不成立。调用方自己 `nohup ... &` 再轮询
 * 也不好用：它拿不到退出码、拿不到结束时刻、日志要和别的任务混在同一个文件里、
 * 容器重启后无从判断哪个任务还在跑。
 *
 * 这一层做的就是把这四件事补上：**持久化的任务目录 + 独立的 stdout/stderr +
 * 明确的终态 + 可取走的产物**。
 *
 * ## 目录布局
 *
 * ```
 * filesDir/jobs/<jobId>/
 *   meta.json     任务元数据与最终状态（原子写：tmp → rename）
 *   stdout.log    标准输出（追加写）
 *   stderr.log    标准错误（追加写）
 *   result.json   退出码/耗时/结束时刻（终态时写）
 * ```
 *
 * 放在 `filesDir` 而不是 `dsh-home`：后者会被用户通过 LAN/备份外置，
 * 混进几百个编译日志不好清理。
 *
 * ## 并发模型
 *
 * 用**固定 2 线程**的池而不是每任务一个线程。理由不是性能，是稳定性：
 * [LinuxContainerRunner] 内部已经有 3 个容器进程的信号量，但如果我们在这一层
 * 不限并发，提交 50 个任务就会堆出 50 个线程再加 50 个等待信号量的容器调用，
 * Android 12+ 的 phantom process killer 会先杀 rootfs 进程。2 线程 + 队列
 * 让「排队」这件事变得可见（`state: queued`），而不是让用户看到一堆 running。
 *
 * ## 取消
 *
 * 取消是**协作式**的：置 `cancelled` 标志，任务线程在 [LinuxContainerRunner]
 * 返回后检查并标记终态；真正的进程终止靠 runner 的 timeout 与它自己的清理。
 * 不承诺「点了立刻死」——承诺「点了之后不会再被算作成功」，这比假装能杀更诚实。
 */
object JobsCap {

    /** 单个任务的硬超时上限：20 分钟。编译 SQLite 这种量级够用，也防呆跑。 */
    const val JOB_TIMEOUT_DEFAULT_MS = 300_000L
    const val JOB_TIMEOUT_MAX_MS = 1_200_000L

    /** 同时执行的任务数。理由见类注释「并发模型」。 */
    const val WORKERS = 2

    /** 保留的历史任务数：超出后按开始时间删最老的（连同目录）。 */
    const val MAX_KEPT = 50

    /** 单任务 stdout/stderr 各自的上限，超了就截断并标记（防磁盘被一条 `yes` 写满）。 */
    const val MAX_LOG_BYTES = 8L * 1024 * 1024

    /**
     * 取产物时的单文件上限。
     *
     * 定在 8 MiB 而不是 64 MiB，因为 bridge 的响应只能走 JSON 字符串
     * （`app/src/main/java/app/dsh/mobile/engine/Route.kt` 的 `handle` 返回
     * `Pair<Int, String>`），二进制要 base64 塞进去，膨胀 33%。8 MiB 的产物
     * 编出来约 10.7 MB 响应，是这条链路能承受的上限。更大的产物走
     * `/dev/open-file`（直接交给系统的文件选择器）或 peersync 同步。
     */
    const val MAX_ARTIFACT_BYTES = 8L * 1024 * 1024

    private val pool = Executors.newFixedThreadPool(WORKERS) { r ->
        Thread(r, "dsh-job").apply { isDaemon = true }
    }
    private val running = ConcurrentHashMap<String, AtomicBoolean>()
    private val rnd = SecureRandom()

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

    fun jobsRoot(ctx: Context): File = File(ctx.filesDir, "jobs").apply { mkdirs() }

    fun jobDir(ctx: Context, id: String): File = File(jobsRoot(ctx), id)

    private fun newId(): String {
        val hex = ByteArray(6).also { rnd.nextBytes(it) }
            .joinToString("") { b -> "%02x".format(b) }
        return "job-" + nowIso().replace("-", "").replace(":", "").substring(0, 15)
            .replace("T", "-").replace(".", "") + "-" + hex
    }

    private fun deny(): Pair<Int, String> = 403 to JSONObject()
        .put("ok", false)
        .put("error", "capability disabled")
        .put("cap", CapabilityGate.LINUX)
        .put("hint", "enable it in Settings > Permissions")
        .toString()

    private fun err(msg: String, hint: String? = null): String = JSONObject()
        .put("ok", false)
        .put("error", msg)
        .also { if (hint != null) it.put("hint", hint) }
        .toString()

    // ==================== 元数据读写 ====================

    /**
     * 原子写 meta.json。
     *
     * 必须先写 tmp 再 rename：任务线程与查询线程并发读写同一个 meta 时，
     * 直接覆写会让查询方读到半截 JSON —— 表现是「任务好好的，但列表里它就是消失」。
     */
    private fun writeMeta(dir: File, meta: JSONObject) {
        dir.mkdirs()
        val tmp = File(dir, "meta.json.tmp")
        tmp.writeText(meta.toString())
        val dst = File(dir, "meta.json")
        if (!tmp.renameTo(dst)) {
            dst.writeText(meta.toString())
            tmp.delete()
        }
    }

    private fun readMeta(dir: File): JSONObject? = runCatching {
        JSONObject(File(dir, "meta.json").readText())
    }.getOrNull()

    private fun updateMeta(dir: File, block: (JSONObject) -> Unit): JSONObject? {
        val m = readMeta(dir) ?: return null
        block(m)
        m.put("updated_at", nowIso())
        writeMeta(dir, m)
        return m
    }

    // ==================== POST /dev/jobs ====================

    /**
     * 提交一个任务。立即返回 jobId（202 语义），执行在后台。
     *
     * @param body `{"cmd":"make -j2","workdir":"/ws","timeout_ms":600000,"label":"build"}`
     */
    fun submit(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LINUX)) return deny()
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to err("invalid json")
        val cmd = obj.optString("cmd").ifBlank { null }
            ?: return 400 to err("missing cmd", "e.g. {\"cmd\":\"make -j2\"}")
        val timeout = obj.optLong("timeout_ms", JOB_TIMEOUT_DEFAULT_MS)
            .coerceIn(1_000L, JOB_TIMEOUT_MAX_MS)
        val workdir = obj.optString("workdir").ifBlank { null }
        val label = obj.optString("label").ifBlank { "job" }.take(60)

        val env = LinuxEnvironmentManager.get(ctx)
        val opt = env.activeOption()
        if (!env.isDeployed(opt)) {
            return 409 to JSONObject()
                .put("ok", false)
                .put("error", "container not deployed")
                .put("hint", "call POST /dev/linux/deploy first")
                .toString()
        }

        // 提交前先按上限清理，避免「磁盘满了才发现」
        prune(ctx)

        val id = newId()
        val dir = jobDir(ctx, id).apply { mkdirs() }
        val meta = JSONObject()
            .put("id", id)
            .put("label", label)
            .put("cmd", cmd)
            .put("workdir", workdir ?: "")
            .put("timeout_ms", timeout)
            .put("state", "queued")
            .put("created_at", nowIso())
            .put("updated_at", nowIso())
            .put("family", opt.family.id)
            .put("flavor", opt.flavor.id)
            .put("cancel_requested", false)
        writeMeta(dir, meta)

        val cancelled = AtomicBoolean(false)
        running[id] = cancelled
        CapabilityGate.audit(ctx, "dev.jobs.submit:" + label + ":" + cmd.take(100))

        pool.execute {
            runJob(ctx, id, cmd, workdir, timeout, cancelled)
        }

        return 202 to JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("state", "queued")
            .put("poll", "GET /dev/jobs/get?id=" + id)
            .put("log", "GET /dev/jobs/log?id=" + id)
            .toString()
    }

    /**
     * 任务体。**所有异常都必须落成终态**，否则任务会永远停在 running ——
     * 那是最糟的失败模式：调用方一直轮询，看不出来它已经死了。
     */
    private fun runJob(
        ctx: Context,
        id: String,
        cmd: String,
        workdir: String?,
        timeout: Long,
        cancelled: AtomicBoolean,
    ) {
        val dir = jobDir(ctx, id)
        try {
            if (cancelled.get()) {
                finish(dir, -1, 0L, "cancelled", "cancelled before start", 0L, 0L)
                return
            }
            updateMeta(dir) { it.put("state", "running").put("started_at", nowIso()) }

            val started = System.currentTimeMillis()
            val runner = LinuxContainerRunner.get(ctx)
            val r = runner.run(
                guestPath = "/bin/sh",
                args = listOf("-c", cmd),
                timeoutMs = timeout,
                workdir = workdir,
            )
            val ms = System.currentTimeMillis() - started

            val outLen = writeLog(File(dir, "stdout.log"), r.stdout)
            val errLen = writeLog(File(dir, "stderr.log"), r.stderr)

            val state = when {
                cancelled.get() -> "cancelled"
                r.timedOut -> "timeout"
                r.exitCode == 0 -> "success"
                else -> "failed"
            }
            finish(dir, r.exitCode, ms, state, null, outLen, errLen)
        } catch (t: Throwable) {
            // 任务体里任何 Throwable（含 OOM、SecurityException）都要落终态
            runCatching {
                finish(dir, -1, 0L, "error", t.javaClass.simpleName + ": " + (t.message ?: ""), 0L, 0L)
            }
        } finally {
            running.remove(id)
        }
    }

    private fun finish(
        dir: File,
        exitCode: Int,
        durationMs: Long,
        state: String,
        errText: String?,
        outBytes: Long,
        errBytes: Long,
    ) {
        val result = JSONObject()
            .put("exit_code", exitCode)
            .put("duration_ms", durationMs)
            .put("state", state)
            .put("finished_at", nowIso())
            .also { if (errText != null) it.put("error", errText) }
        File(dir, "result.json").writeText(result.toString())
        updateMeta(dir) { m ->
            m.put("state", state)
            m.put("exit_code", exitCode)
            m.put("duration_ms", durationMs)
            m.put("finished_at", nowIso())
            m.put("stdout_bytes", outBytes)
            m.put("stderr_bytes", errBytes)
            if (errText != null) m.put("error", errText)
        }
    }

    /**
     * 写日志并返回写入字节数。
     *
     * 超 [MAX_LOG_BYTES] 就**截断并追加标记**，而不是继续写。`yes` 一条命令
     * 能在几秒内写满磁盘，而磁盘满了会让整个 App 一起坏 —— 一条调试任务
     * 不该有这种权力。
     */
    private fun writeLog(f: File, text: String): Long {
        if (text.isEmpty()) return 0L
        val bytes = text.toByteArray()
        if (f.length() + bytes.size <= MAX_LOG_BYTES) {
            f.appendText(text)
            return bytes.size.toLong()
        }
        val room = (MAX_LOG_BYTES - f.length()).coerceAtLeast(0L)
        if (room > 0) {
            f.appendText(String(bytes, 0, room.toInt()))
        }
        f.appendText("\n[truncated: log exceeded $MAX_LOG_BYTES bytes]\n")
        return room
    }

    // ==================== GET /dev/jobs ====================

    fun list(ctx: Context, query: String?): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LINUX)) return deny()
        val limit = Regex("[?&]limit=(\\d+)").find(query ?: "")
            .let { it?.groupValues?.get(1)?.toIntOrNull() } ?: 20
        val n = limit.coerceIn(1, MAX_KEPT)

        val dirs = jobsRoot(ctx).listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.name }
            ?.take(n) ?: emptyList()

        val arr = JSONArray()
        for (d in dirs) {
            val m = readMeta(d) ?: continue
            arr.put(summary(m))
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("jobs", arr)
            .put("count", arr.length())
            .put("workers", WORKERS)
            .toString()
    }

    /** 单个任务详情。`id` 必填。 */
    fun get(ctx: Context, query: String?): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LINUX)) return deny()
        val id = Regex("[?&]id=([A-Za-z0-9._-]+)").find(query ?: "")
            ?.groupValues?.get(1) ?: return 400 to err("missing id")
        val dir = jobDir(ctx, id)
        val m = readMeta(dir) ?: return 404 to err("job not found")
        return 200 to summary(m).toString()
    }

    /**
     * 日志尾部。默认读 stdout 的末尾 200 行。
     *
     * **只读尾部**：调用方在轮询进度，每次读全量日志会把几百 KB 反复搬过
     * bridge（Android 上这是跨进程 + JSON 转义），而且没意义。
     */
    fun log(ctx: Context, query: String?): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LINUX)) return deny()
        val q = query ?: ""
        val id = Regex("[?&]id=([A-Za-z0-9._-]+)").find(q)?.groupValues?.get(1)
            ?: return 400 to err("missing id")
        val which = Regex("[?&]stream=(stdout|stderr)").find(q)?.groupValues?.get(1) ?: "stdout"
        val lines = (Regex("[?&]lines=(\\d+)").find(q)?.groupValues?.get(1)?.toIntOrNull() ?: 200)
            .coerceIn(1, 2000)

        val dir = jobDir(ctx, id)
        if (!dir.isDirectory) return 404 to err("job not found")
        val f = File(dir, "$which.log")
        if (!f.isFile) {
            return 200 to JSONObject()
                .put("ok", true)
                .put("id", id)
                .put("stream", which)
                .put("text", "")
                .put("bytes", 0)
                .toString()
        }
        val tail = tailLines(f, lines)
        return 200 to JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("stream", which)
            .put("text", tail)
            .put("bytes", f.length())
            .put("truncated_at", MAX_LOG_BYTES)
            .toString()
    }

    /** 读文件末尾 N 行。用 RandomAccessFile 反向扫，不把整个日志装进内存。 */
    private fun tailLines(f: File, n: Int): String {
        val len = f.length()
        if (len == 0L) return ""
        val maxBack = minOf(len, 512L * 1024)
        java.io.RandomAccessFile(f, "r").use { raf ->
            val start = (len - maxBack).coerceAtLeast(0L)
            raf.seek(start)
            val buf = ByteArray(maxBack.toInt())
            raf.readFully(buf)
            val text = String(buf)
            val parts = text.split("\n")
            // 起点落在行中间时第一段是残行，丢掉（除了从文件头开始的情况）
            val usable = if (start > 0 && parts.size > 1) parts.drop(1) else parts
            return usable.takeLast(n).joinToString("\n")
        }
    }

    // ==================== POST /dev/jobs/cancel ====================

    fun cancel(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LINUX)) return deny()
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to err("invalid json")
        val id = obj.optString("id").ifBlank { null }
            ?: return 400 to err("missing id")
        val dir = jobDir(ctx, id)
        val m = readMeta(dir) ?: return 404 to err("job not found")
        val state = m.optString("state")
        if (state == "success" || state == "failed" || state == "timeout" ||
            state == "cancelled" || state == "error"
        ) {
            return 409 to JSONObject()
                .put("ok", false)
                .put("error", "job already finished")
                .put("state", state)
                .toString()
        }
        running[id]?.set(true)
        updateMeta(dir) { it.put("cancel_requested", true) }
        CapabilityGate.audit(ctx, "dev.jobs.cancel:$id")
        return 200 to JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("state", "cancel-requested")
            .put("note", "cooperative: the task is marked cancelled when the current command returns")
            .toString()
    }

    // ==================== GET /dev/jobs/artifact ====================

    /**
     * 取任务产物（编译出来的二进制之类）。
     *
     * 路径解析**必须**限定在该任务的目录内：`..` 能穿出去就是任意文件读取，
     * 而这组能力只应由「已配对的本机」调用。规范化后比对前缀，不是在字符串里找 `..`。
     *
     * 返回 base64：bridge 的响应体只能是字符串（见 [MAX_ARTIFACT_BYTES] 的注释）。
     * 调用方拿 `content_base64` + `sha256` 自己解码校验 —— 带 sha256 是因为
     * JSON 里塞 base64 一旦被中间层改过（换行、截断），拿到手的二进制会静默损坏。
     */
    fun artifact(ctx: Context, query: String?): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.LINUX)) return deny()
        val q = query ?: ""
        val id = Regex("[?&]id=([A-Za-z0-9._-]+)").find(q)?.groupValues?.get(1)
            ?: return 400 to err("missing id")
        val rel = Regex("[?&]path=([^&]+)").find(q)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.ifBlank { null }
            ?: return 400 to err("missing path", "path is relative to the job directory")
        val dir = jobDir(ctx, id)
        if (!dir.isDirectory) return 404 to err("job not found")

        val base = dir.canonicalFile
        val target = File(base, rel).canonicalFile
        if (!target.path.startsWith(base.path + File.separator)) {
            return 400 to err("path escapes the job directory")
        }
        if (!target.isFile) return 404 to err("artifact not found")
        if (target.length() > MAX_ARTIFACT_BYTES) {
            return 413 to JSONObject()
                .put("ok", false)
                .put("error", "artifact larger than " + MAX_ARTIFACT_BYTES + " bytes")
                .put("bytes", target.length())
                .put("hint", "use /dev/open-file or peersync for big artifacts")
                .toString()
        }
        val bytes = FileInputStream(target).use { it.readBytes() }
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { b -> "%02x".format(b) }
        return 200 to JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("path", rel)
            .put("bytes", bytes.size)
            .put("sha256", sha)
            .put("content_base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            .toString()
    }

    // ==================== 内部工具 ====================

    private fun summary(m: JSONObject): JSONObject = JSONObject()
        .put("id", m.optString("id"))
        .put("label", m.optString("label"))
        .put("cmd", m.optString("cmd"))
        .put("state", m.optString("state"))
        .put("exit_code", m.optInt("exit_code", Int.MIN_VALUE))
        .put("duration_ms", m.optLong("duration_ms", 0L))
        .put("created_at", m.optString("created_at"))
        .put("finished_at", m.optString("finished_at"))
        .put("stdout_bytes", m.optLong("stdout_bytes", 0L))
        .put("stderr_bytes", m.optLong("stderr_bytes", 0L))
        .also { if (m.has("error")) it.put("error", m.optString("error")) }

    /**
     * 按 [MAX_KEPT] 清理最老的任务目录。
     *
     * 只删已到终态的：还在跑的任务被删掉目录后，它的 `finish()` 会写进不存在的
     * 目录（mkdirs 会重建），结果是一个「幽灵任务」—— 列表里没有它，但它占着 worker。
     */
    fun prune(ctx: Context) {
        val dirs = jobsRoot(ctx).listFiles { f -> f.isDirectory } ?: return
        if (dirs.size <= MAX_KEPT) return
        val terminal = dirs
            .mapNotNull { d -> readMeta(d)?.let { m -> d to m.optString("state") } }
            .filter { (_, s) -> s in setOf("success", "failed", "timeout", "cancelled", "error") }
            .sortedBy { (d, _) -> d.name }
        var excess = dirs.size - MAX_KEPT
        for ((d, _) in terminal) {
            if (excess <= 0) break
            if (d.deleteRecursively()) excess--
        }
    }
}
