package app.dsh.mobile.engine

import java.io.File

/**
 * engine.log 的唯一写入入口。
 *
 * ## 为什么需要它
 * engine.log 有**两个生产者**，原本各自直接 `appendText`、彼此没有同步：
 *  · [EngineProcess] 的日志泵线程（引擎 stdout 泵入）
 *  · `MainActivity.logWebView()` —— WebView 的 `onConsoleMessage` 回调，
 *    跑在 **WebView/UI 线程**上，一次 `appendText` 就是一次同步磁盘写。
 *
 * 由此带来三个真实问题：
 *  1. **并发写无锁**：两条线程同时对同一文件 append，落盘内容可能交错。
 *  2. **环形容量的判断是 TOCTOU**：`if (length() < max) appendText(...)` 在两次
 *     系统调用之间文件可能已被另一线程写大，2MB 上限不严格。
 *  3. **主线程同步 I/O**：UI 线程上的磁盘写会在磁盘繁忙/内存紧张时直接掉帧
 *     （本项目目标设备常见 4-8GB 内存、后台挂着多个大型 App）。
 *  4. **环形截断的代价随日志增长**：旧实现超限时 `readText()` 一次性读入 2MB
 *     再整体 `writeText`，在低内存设备上是一个明显卡顿点。
 *
 * ## 做法
 *  · 所有写入串行化（单一锁），从根本上消除交错与 TOCTOU；
 *  · 环形截断改为「按行追加、超限时只保留尾部一半」，且用随机存取定位，
 *    不再把全文读进堆内存；
 *  · UI 线程的写入走 [appendAsync]（交给后台单线程执行器），不阻塞渲染；
 *  · 任何失败都静默（日志本身不该影响主流程），与旧行为一致。
 */
object EngineLog {

    /** 2MB 上限（与既有约定一致） */
    const val MAX_BYTES: Long = 2L shl 20

    /** 所有写操作共用的锁：跨实例、跨线程串行化 */
    private val lock = Any()

    /** UI 线程使用的后台写入执行器（单线程即可保证顺序） */
    private val asyncExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-engine-log").apply { isDaemon = true }
    }

    /** 同步追加（日志泵线程用：它本就是后台线程，直接写即可保持顺序） */
    fun append(file: File, text: String) {
        synchronized(lock) { writeLocked(file, text) }
    }

    /** 异步追加（UI 线程用：绝不阻塞渲染） */
    fun appendAsync(file: File, text: String) {
        runCatching { asyncExec.execute { synchronized(lock) { writeLocked(file, text) } } }
    }

    /**
     * 调用方必须已持有 [lock]。
     *
     * 环形策略：若追加后超过 [MAX_BYTES]，只保留尾部一半再追加新内容。
     * 相比旧实现（`readText()` 把整个 2MB 读进堆再 `writeText`），这里只做一次
     * 文件内的区段读取，避免在大日志时产生整块堆分配与复制。
     */
    private fun writeLocked(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            val add = text.toByteArray(Charsets.UTF_8)
            val cur = if (file.exists()) file.length() else 0L
            if (cur + add.size <= MAX_BYTES) {
                file.appendBytes(add)
                return
            }
            // 截断：保留尾部 keep 字节
            val keep = MAX_BYTES / 2
            val start = (cur - keep).coerceAtLeast(0L)
            var tail = java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.seek(start)
                val buf = ByteArray((cur - start).toInt())
                raf.readFully(buf)
                buf
            }
            // 截断点可能落在多字节 UTF-8 字符中间：跳过续字节（10xxxxxx），
            // 否则日志开头会出现替换符。只在尾部判断一次，代价可以忽略。
            var i = 0
            while (i < tail.size && (tail[i].toInt() and 0xC0) == 0x80) i++
            if (i > 0) tail = tail.copyOfRange(i, tail.size)
            file.writeBytes(tail)
            file.appendBytes(add)
        } catch (_: Throwable) {
            // 日志失败不影响主流程（与旧行为一致）
        }
    }
}
