package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * 装包进度窗（v1.5.4）：给 Linux 模式下的 `apk add` / `apt-get` 用。
 *
 * ## 为什么需要它
 *
 * 旧实现是 `ProgressDialog.setMessage(固定文案)` + `setCancelable(false)`，
 * 而 [app.dsh.mobile.engine.LinuxContainerRunner] 只在**命令结束时**返回输出。
 * 于是装 node 这种要跑几分钟的操作，界面上就是一个不知道动没动的圈 ——
 * 用户既判断不了还在不在干活，也看不出卡在哪一步。更糟的是失败时把
 * `rawOutput` 塞进 `setMessage()`，`AlertDialog` 的 message 是普通 TextView，
 * 长按能选但无法全选复制，用户想反馈报错只能拍照。
 *
 * ## 这一版给了什么
 *
 * - **实时滚动输出**：执行器每读到一块就回调进来，日志跟着往下走
 *   （只保留末尾若干行，与 `adb logcat` 的手感一致）。
 * - **阶段标题**：`apt-get update` / `apt-get -d install` / `dpkg -i` 这类
 *   多步流程可以在中途改标题，用户知道现在到哪一步了。
 * - **可复制**：整窗日志一次性进剪贴板；文本框本身也可选中复制。
 * - **可转后台**：不再锁死不可取消。点「后台运行」关掉窗口但**任务继续**，
 *   装完再弹结果 —— 装 node 要几分钟，强制用户盯着屏幕不合理。
 * - **失败原文全留**：不截断、不改写，直接给用户能贴给开发者的东西。
 */
object PkgProgressDialog {

    /** 日志最多保留的行数。超出丢弃最老的，避免长时间任务把内存吃满。 */
    private const val MAX_LINES = 400

    /**
     * 打开进度窗并执行 [work]。
     *
     * [work] 在后台线程执行，收到 `onLine` 就把一行输出喂进窗口。
     * 返回的 `OpResult` 由调用方的 `onDone` 处理（在主线程）。
     *
     * @param title      标题，如「正在安装 Node.js」
     * @param subtitle   标题下的一行说明，如包名列表
     * @param failedTitle 失败后的标题，如「安装失败：Node.js」
     * @param onDone     任务结束回调（主线程）
     */
    fun <T> run(
        activity: Activity,
        title: CharSequence,
        subtitle: CharSequence,
        /** 失败时的标题。装/卸必须分开 —— 卸载失败却写「安装失败」会误导用户。 */
        failedTitle: CharSequence,
        work: (onLine: (String) -> Unit) -> T,
        failed: (T) -> Boolean,
        resultText: (T) -> String,
        onDone: (T) -> Unit,
    ) {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        val logView = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(activity.dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
            typeface = android.graphics.Typeface.MONOSPACE
            // 等宽字体 + 可选中：用户想复制某一段报错时顺手就能选。
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundResource(R.drawable.bg_md3_input)
        }
        val scroll = ScrollView(activity).apply {
            addView(
                logView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(180),
            ).apply { topMargin = dp(8) }
        }
        val spinner = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3),
            )
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
            addView(
                TextView(activity).apply {
                    text = subtitle
                    textSize = 12.5f
                    setTextColor(activity.dshColor(R.attr.dshTextSecondary, R.color.text_secondary))
                },
            )
            addView(spinner, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3),
            ).apply { topMargin = dp(12) })
            addView(scroll)
        }

        // 行缓冲：执行器按 8KB 块回调，一行可能被切成几段，攒到换行再显示。
        //
        // ⚠️ lines 会被两个线程碰：工作线程 append（onLine 回调）、UI 线程读
        //    （render / 复制按钮）。**必须共用同一把锁**。早先工作线程在
        //    synchronized(pending) 里改 lines，而 render 裸读，于是
        //    ArrayDeque.toArray 先读 size 再按 size 拷贝，中途 size 增长就崩：
        //        java.lang.ArrayIndexOutOfBoundsException:
        //          src.length=823 srcPos=0 dst.length=800 length=823
        //          at java.util.ArrayDeque.toArray
        //          at app.dsh.mobile.PkgProgressDialog.run$render(PkgProgressDialog.kt)
        //    pending 与 lines 是两个不同的对象，锁 pending 保护不了 lines。
        val lines = ArrayDeque<String>()
        val linesLock = Any()
        val pending = StringBuilder()
        var closed = false

        fun render() {
            if (closed) return
            // 快照必须在锁内取：toList() 内部按 size 分配数组再逐元素拷贝，
            // 与工作线程的 addLast 并发会越界（见 lines 声明处的说明）。
            val tail = synchronized(linesLock) {
                if (lines.size > MAX_LINES) lines.toList().takeLast(MAX_LINES) else lines.toList()
            }
            logView.text = tail.joinToString("\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(body)
            .setPositiveButton(activity.getString(R.string.pkg_progress_background), null)
            .setNeutralButton(activity.getString(R.string.pkg_progress_copy), null)
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            // 「后台运行」：只关窗，线程继续跑，装完再弹结果。
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                closed = true
                dialog.dismiss()
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.pkg_progress_background_toast),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            // 「复制全部」：一次性把整窗日志进剪贴板。
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val cm = activity.getSystemService(Activity.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                val all = synchronized(linesLock) {
                    (lines.toList() + pending.toString().trim())
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                }
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("dsh-pkg-log", all),
                )
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.file_editor_path_copied),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        dialog.show()

        Thread({
            val r = runCatching {
                work { chunk ->
                    // 同一把锁同时保护 pending 与 lines —— 两者在 render/复制
                    // 里是**一起**读的，分行加锁会读到「pending 里还有残行、
                    // lines 已被另一次回调推进」的中间态。
                    synchronized(linesLock) {
                        pending.append(chunk)
                        var idx = pending.indexOf("\n")
                        while (idx >= 0) {
                            val line = pending.substring(0, idx).trimEnd('\r')
                            pending.delete(0, idx + 1)
                            if (line.isNotBlank()) lines.addLast(line)
                            while (lines.size > MAX_LINES * 2) lines.removeFirst()
                            idx = pending.indexOf("\n")
                        }
                    }
                    activity.runOnUiThread { render() }
                }
            }
            activity.runOnUiThread {
                val failedNow = r.isFailure || (r.isSuccess && failed(r.getOrThrow()))
                // 失败就**不许**自动关窗：用户要能读、能复制、能拍照留证。
                // 只有成功才自动收起，失败时把按钮换成「关闭」由用户自己关。
                if (!failedNow && !closed) {
                    closed = true
                    dialog.dismiss()
                } else if (failedNow) {
                    spinner.isIndeterminate = false
                    spinner.progress = 0
                    spinner.visibility = android.view.View.GONE
                    dialog.setTitle(failedTitle)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).text =
                        activity.getString(R.string.pkg_progress_close)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        closed = true
                        dialog.dismiss()
                    }
                    val extra = when {
                        r.isFailure -> r.exceptionOrNull()?.message ?: ""
                        else -> resultText(r.getOrThrow())
                    }
                    if (extra.isNotBlank()) {
                        synchronized(pending) {
                            extra.lineSequence().forEach { if (it.isNotBlank()) lines.addLast(it) }
                        }
                        render()
                    }
                }
                r.getOrNull()?.let { onDone(it) }
            }
        }, "pkg-progress").start()
    }
}
