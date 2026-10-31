package app.dsh.mobile.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动过程的可观测记录（v1.4.8）。
 *
 * ## 解决什么问题
 * 之前冷启动只有一句「正在启动引擎…」，用户不知道：
 *   · 已经等了多久（手表上 40 秒和 12 秒的观感都是"卡住了"）；
 *   · 卡在哪个阶段（解压？spawn？等 HTTP？等插件探针？）；
 *   · 每个阶段各花了多少毫秒。
 * 出了问题只能靠 `engine.log` 反推，而那里的 boot step 只有 logcat 版本。
 *
 * ## 做法
 *  · 进程级单例：引擎可能在 Activity 起来之前就启动（前台服务），所以状态必须
 *    独立于界面存活，界面只是"订阅者"；
 *  · 每个阶段的耗时**当场**写进 `engine/boot.log`（独立文件，128KB 环形），
 *    并同步到 [StateFlow] 供启动遮罩实时显示；
 *  · 只存阶段 **key**（ASCII），中文文案由 UI 层按语言映射 —— 引擎层不持有
 *    任何 i18n 资源，日志文件也不会因为语言切换而变样。
 *
 * ## 线程约定
 * 所有写方法内部同步，可从任意线程调用；`step()` 由监督循环串行调用。
 */
object BootTrace {

    /** 启动阶段（粗粒度，用于界面主文案） */
    enum class Stage {
        /** 没有进行中的启动 */
        IDLE,

        /** 属性自愈 / 种子 / 孤儿回收 / 桥 —— 还没开始动运行时 */
        PREPARING,

        /** 正在下载 noruntime 运行时（有确定性的字节进度） */
        DOWNLOADING,

        /** 正在解压运行时（有确定性的文件与字节进度） */
        EXTRACTING,

        /** 运行时就绪，fork 引擎并等待 HTTP 健康 */
        STARTING,

        /** HTTP 已通，等待插件探针 / 加载 WebUI */
        LOADING,

        /** 引擎可用 */
        READY,

        /** 失败 */
        FAILED,
    }

    /** 解压进度：文件数 + 字节数双口径（文件数对用户更直观，字节数更平滑） */
    data class Install(
        val doneFiles: Int = 0,
        val totalFiles: Int = 0,
        val doneBytes: Long = 0,
        val totalBytes: Long = 0,
    ) {
        /** 0f..1f；总字节为 0 时返回 null（无法给出确定进度） */
        val fraction: Float?
            get() = when {
                totalBytes > 0 -> (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
                totalFiles > 0 -> (doneFiles.toDouble() / totalFiles).toFloat().coerceIn(0f, 1f)
                else -> null
            }
    }

    /** 一个已完成的阶段 */
    data class Step(
        /** 稳定 ASCII 键，UI 层负责翻译；未知键直接显示原文 */
        val key: String,
        /** 该阶段自身耗时 */
        val durationMs: Long,
        /** 从启动开始到该阶段结束的累计耗时 */
        val atMs: Long,
    )

    data class Snapshot(
        val stage: Stage = Stage.IDLE,
        /** 本次启动开始时刻（System.currentTimeMillis）；0 = 无记录 */
        val startedAt: Long = 0L,
        /** 已完成的阶段，按发生顺序 */
        val steps: List<Step> = emptyList(),
        val install: Install = Install(),
        /** 结束时刻与总耗时；进行中为 null */
        val finishedAt: Long? = null,
        val totalMs: Long? = null,
        /** 失败原因（已本地化则由调用方给；此处存原文/资源已解析文本） */
        val failure: String? = null,
        /** 本次启动是否复用了已安装的运行时（跳过解压） */
        val runtimeReused: Boolean = false,
        /**
         * 正在进行的阶段键（见 [mark]）；null = 没有在跑的阶段。
         *
         * 存在的理由：`step()` 是**事后**上报（耗时操作完成后才追加一条），
         * 旧实现界面上只能显示「上一个已完成阶段」。当某一阶段本身耗时很长
         * （如首装内置插件要跑 pnpm，最长 120s 超时），用户看到的是**上一个**
         * 阶段名，误以为那一步卡住了 —— 真实案例：`profile-plugins` 慢被显示成
         * 「链接用户数据」。
         */
        val currentStep: String? = null,
    ) {
        val running: Boolean get() = startedAt > 0 && finishedAt == null

        /** 已用毫秒：进行中用当前时刻，结束后用总耗时 */
        fun elapsedMs(now: Long = System.currentTimeMillis()): Long =
            when {
                startedAt <= 0 -> 0L
                totalMs != null -> totalMs
                else -> (now - startedAt).coerceAtLeast(0L)
            }

        /** 最近一次（用于"上次启动"展示）：结束后即本快照 */
        val stepCount: Int get() = steps.size
    }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    private val lock = Any()

    /** 本次启动的日志缓冲（结束时一次性落盘，避免每个阶段都开一次文件） */
    private val buffer = StringBuilder()

    /**
     * 开始一次启动记录。幂等：进行中的记录会被覆盖（监督循环每次尝试都是新的一轮）。
     *
     * @param note 追加到 boot.log 头部的说明（如「冷启动」「用户重启」「插件恢复重启」）
     */
    fun begin(note: String = "") {
        synchronized(lock) {
            buffer.setLength(0)
            buffer.append("=== boot ")
                .append(stamp())
                .append(if (note.isNotEmpty()) " ($note)" else "")
                .append(" ===\n")
            _snapshot.value = Snapshot(
                stage = Stage.PREPARING,
                startedAt = System.currentTimeMillis(),
            )
        }
    }

    /** 切换粗粒度阶段（不结束任何 step） */
    fun stage(stage: Stage) {
        synchronized(lock) {
            val cur = _snapshot.value
            if (cur.stage == stage) return
            _snapshot.value = cur.copy(stage = stage)
        }
    }

    /**
     * 完成一个阶段并记录耗时。
     *
     * @param key 稳定 ASCII 键（见 [Step.key]）；同一键重复出现会各自记一条
     *           （例如插件恢复重启时 `spawn` 会出现两次，这是有意义的）
     */
    fun step(key: String) {
        synchronized(lock) {
            val cur = _snapshot.value
            if (!cur.running) return
            val now = System.currentTimeMillis()
            val atMs = (now - cur.startedAt).coerceAtLeast(0L)
            val prev = cur.steps.lastOrNull()?.atMs ?: 0L
            val duration = (atMs - prev).coerceAtLeast(0L)
            val s = Step(key = key, durationMs = duration, atMs = atMs)
            // 完成后清掉 currentStep：同一时刻只应有一个「进行中」阶段。
            _snapshot.value = cur.copy(steps = cur.steps + s, currentStep = null)
            buffer.append("  ")
                .append(key.padEnd(22))
                .append(duration.toString().padStart(6))
                .append(" ms   (cumulative ")
                .append(atMs)
                .append(" ms)\n")
        }
    }

    /**
     * 标记「某阶段即将开始」——必须在耗时操作**之前**调用。
     *
     * 与 [step] 配对：`mark(k)` … 操作 … `step(k)`。界面上因此能显示正在跑的那一步，
     * 而不是慢一拍地显示上一个已完成的阶段。多次 mark 以最后一次为准。
     */
    fun mark(key: String) {
        synchronized(lock) {
            val cur = _snapshot.value
            if (!cur.running) return
            if (cur.currentStep == key) return
            _snapshot.value = cur.copy(currentStep = key)
        }
    }

    /** 解压进度。调用方负责节流（本方法只做一次赋值与一次状态比较）。 */
    fun install(install: Install) {
        synchronized(lock) {
            val cur = _snapshot.value
            if (!cur.running) return
            _snapshot.value = cur.copy(install = install)
        }
    }

    /** 标记本次启动复用了已安装运行时（跳过解压），供界面说明「无需解压」 */
    fun markRuntimeReused() {
        synchronized(lock) {
            val cur = _snapshot.value
            if (!cur.running) return
            _snapshot.value = cur.copy(runtimeReused = true)
        }
    }

    /**
     * 启动成功收尾。
     *
     * @param ctx 用于落盘 `engine/boot.log`；为 null 时只更新内存状态
     * @param webViewLoadedMs WebView 首屏完成耗时（相对启动开始）；调用方稍后才知道，
     *                        用 [noteWebViewLoaded] 补记
     */
    fun ready(ctx: Context?) {
        synchronized(lock) {
            val cur = _snapshot.value
            if (!cur.running) return
            val now = System.currentTimeMillis()
            val total = (now - cur.startedAt).coerceAtLeast(0L)
            _snapshot.value = cur.copy(
                stage = Stage.READY,
                finishedAt = now,
                totalMs = total,
                // 收尾后不应再有「进行中」阶段，否则重绘时会显示一条假的最后阶段。
                currentStep = null,
            )
            buffer.append("  ")
                .append("TOTAL".padEnd(22))
                .append(total.toString().padStart(6))
                .append(" ms   (engine ready)\n")
            flush(ctx)
        }
    }

    /** 启动失败收尾；失败原因会同时写进 boot.log 与 engine.log。 */
    fun fail(ctx: Context?, reason: String) {
        synchronized(lock) {
            val cur = _snapshot.value
            if (cur.startedAt <= 0) return
            if (cur.finishedAt != null) return   // 已收尾，别覆盖成功记录
            val now = System.currentTimeMillis()
            val total = (now - cur.startedAt).coerceAtLeast(0L)
            _snapshot.value = cur.copy(
                stage = Stage.FAILED,
                finishedAt = now,
                totalMs = total,
                failure = reason,
                // 同上：失败收尾也清掉，避免残留「正在…」的误导文案。
                currentStep = null,
            )
            buffer.append("  ")
                .append("FAILED".padEnd(22))
                .append(total.toString().padStart(6))
                .append(" ms   ")
                .append(reason.replace('\n', ' ').take(300))
                .append('\n')
            flush(ctx)
        }
    }

    /**
     * 补记 WebView 首屏耗时。
     *
     * 引擎 healthy 与「页面第一帧」之间还有一段可观测的空档（加载 WebUI、执行客户端
     * 插件）。这段时间用户看到的就是遮罩，所以它是启动体感的一部分，值得记下来。
     */
    fun noteWebViewLoaded(ctx: Context?) {
        synchronized(lock) {
            val cur = _snapshot.value
            if (cur.startedAt <= 0) return
            val atMs = (System.currentTimeMillis() - cur.startedAt).coerceAtLeast(0L)
            buffer.append("  ")
                .append("webview-first-paint".padEnd(22))
                .append("".padStart(6))
                .append("      (cumulative ")
                .append(atMs)
                .append(" ms)\n")
            flush(ctx)
        }
    }

    // ==================== 落盘 ====================

    /** boot.log 上限：20 次启动左右，足够覆盖"昨天还好、今天变慢"的对比 */
    private const val MAX_LOG_BYTES = 128 * 1024

    fun logFile(ctx: Context): File = File(EngineConfig.engineRoot(ctx), "boot.log")

    /**
     * 把本次记录追加进 boot.log（调用方必须持有 [lock]）。
     *
     * 环形策略与 [EngineLog] 一致：超过上限只保留尾部一半，避免无限增长。
     */
    private fun flush(ctx: Context?) {
        if (buffer.isEmpty()) return
        val text = buffer.toString()
        buffer.setLength(0)
        if (ctx == null) return
        runCatching {
            val f = logFile(ctx)
            if (f.length() + text.length > MAX_LOG_BYTES) {
                val keepFrom = (f.length() / 2).toInt()
                val tail = runCatching {
                    f.readBytes().let { bytes ->
                        String(bytes, keepFrom, bytes.size - keepFrom, Charsets.UTF_8)
                    }
                }.getOrElse { "" }
                f.writeText("...[earlier boots trimmed]...\n" + tail)
            }
            f.appendText(text)
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** 读取 boot.log 尾部（设置页「启动详情」用），最多 [maxBytes] 字节 */
    fun readLogTail(ctx: Context, maxBytes: Int = 16 * 1024): String = runCatching {
        val f = logFile(ctx)
        if (!f.isFile) return@runCatching ""
        val len = f.length()
        val from = maxOf(0L, len - maxBytes)
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray((len - from).toInt())
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    }.getOrDefault("")
}
