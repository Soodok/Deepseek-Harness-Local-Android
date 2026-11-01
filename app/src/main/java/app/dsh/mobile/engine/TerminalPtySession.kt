package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * 真 PTY 终端会话（对齐 DSHA 的 `PtySession`）。
 *
 * DSHA 用 Termux 拆出的 terminal-emulator / terminal-view（Apache 2.0）把容器
 * 里的 bash 挂在伪终端上，让 vim / htop / tmux 能跑；这里把同一套库接在本项目
 * 自己的运行时上：shell 取 [EngineConfig.shellPathFor]（自带 bionic bash，缺失
 * 时退回 /system/bin/sh），环境变量复用 [EngineConfig.buildEnv]（与引擎内 AI 的
 * bash 工具看到同一份 PATH / LD_LIBRARY_PATH / SHELL / HOME）。
 *
 * 本类只负责会话与回调转发，不碰任何 View。Termux 的 PTY fork 由随包分发、且已
 * 重编为 16 KB 页对齐的 `libtermux.so` 完成（见 scripts/termux-jni/）。
 */
class TerminalPtySession private constructor() : TerminalSessionClient {

    /** UI 侧只关心这几件事。 */
    interface Listener {
        /** 屏幕内容变了 → 该重绘。 */
        fun onOutput()

        /** 标题变了（PS1 与 tmux 都会改），可以显示在标题栏。 */
        fun onTitle(title: String)

        /** 会话结束（exit / 进程被杀）。 */
        fun onExit(status: Int)

        /** 终端要求把选中内容放进剪贴板。 */
        fun onCopy(text: String)

        /** 终端要求粘贴（比如 bracketed paste）。 */
        fun onPasteRequest()

        /** 响铃：震一下比响一声合适。 */
        fun onBell()
    }

    companion object {
        private const val TAG = "DSH-term"

        /** 回滚缓冲行数：够往上翻几屏日志，又不至于把内存吃掉。 */
        private const val TRANSCRIPT_ROWS = 2000

        /**
         * 起一个会话。
         *
         * @param port 引擎当前端口（用于 [EngineConfig.buildEnv]；未就绪时用默认 3080）
         * @param cols 列数，@param rows 行数 —— 必须是按控件实测字宽算出来的。
         */
        fun start(ctx: Context, port: Int, cols: Int, rows: Int, listener: Listener?): TerminalPtySession {
            val ps = TerminalPtySession()
            ps.listener = listener
            val root = EngineConfig.engineRoot(ctx)
            val shell = EngineConfig.shellPathFor(root)
            val cwd = EngineConfig.dshHome(ctx).absolutePath
            // args 含 argv[0]（与 ProcessBuilder 行为一致，execvp 原样使用）。
            //
            // ⚠️ v1.5.0-beta.7：**先 stty sane 再 exec 登录 shell**（对齐 DSHA
            // `runtime/TerminalRuntime.java` 的 prepare()）。原因：PTY 刚建出来时
            // 行的 termios 可能带着上层 App 继承来的脏标志（echo 关掉 / icanon 关掉 /
            // 残留 ONLCR 差异），表现就是「程序起得来、但按键没回显」或「回车双份
            // 换行」。直接 exec shell 改不了它 —— 必须先在同一个 PTY 上把 termios
            // 复位。`|| true` 保证 stty 不可用时不影响进入 shell。
            // `-l` 仅 bash 支持；回退到 /system/bin/sh 时不带 -l。
            val bash = File(root, "bin/bash")
            val argv = if (bash.canExecute()) {
                arrayOf(shell, "-c", "stty sane 2>/dev/null || stty echo icanon 2>/dev/null || true; exec \"$shell\" -l")
            } else {
                arrayOf(shell, "-c", "stty sane 2>/dev/null || stty echo icanon 2>/dev/null || true; exec \"$shell\"")
            }
            val env = EngineConfig.buildEnv(ctx, port).toMutableList()
            // Termux 库默认注入 TERM，但显式写死更稳：bionic bash 的 readline / TUI 都靠它。
            env += "TERM=xterm-256color"
            env += "LANG=C.UTF-8"
            env += "PWD=$cwd"
            val session = TerminalSession(shell, cwd, argv, env.toTypedArray(), TRANSCRIPT_ROWS, ps)
            ps.session = session
            // initializeEmulator 才真正 fork 出子进程，所以尺寸要在这之前定好
            session.initializeEmulator(maxOf(4, cols), maxOf(2, rows))
            return ps
        }
    }

    @Volatile private var session: TerminalSession? = null
    @Volatile private var listener: Listener? = null

    fun attachListener(l: Listener?) {
        listener = l
    }

    fun detachListener(l: Listener?) {
        if (listener === l) listener = null
    }

    private fun currentListener(): Listener? = listener

    fun session(): TerminalSession? = session

    fun write(text: String) {
        val t = session ?: return
        if (text.isNotEmpty()) t.write(text)
    }

    fun resize(cols: Int, rows: Int) {
        session?.updateSize(maxOf(4, cols), maxOf(2, rows))
    }

    fun isRunning(): Boolean = session?.isRunning == true

    fun pid(): Int = session?.pid ?: -1

    fun exitStatus(): Int = session?.exitStatus ?: -1

    /** 结束会话（切页面/退出时调，避免留一个孤儿 bash）。 */
    fun finish() {
        session?.finishIfRunning()
    }

    // ==================== TerminalSessionClient ====================

    override fun onTextChanged(changedSession: TerminalSession) {
        currentListener()?.onOutput()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        currentListener()?.onTitle(changedSession.title ?: "")
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        currentListener()?.onExit(finishedSession.exitStatus)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        currentListener()?.onCopy(text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        currentListener()?.onPasteRequest()
    }

    override fun onBell(session: TerminalSession) {
        currentListener()?.onBell()
    }

    override fun onColorsChanged(session: TerminalSession) {
        currentListener()?.onOutput()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // 光标闪烁状态变化：重绘由 TerminalView 自己安排，这里不用管
    }

    override fun getTerminalCursorStyle(): Int? = null // null = 用库的默认（块状光标）

    // ---- 日志：库里打得很细，统一收口到 logcat ----

    override fun logError(tag: String, message: String) {
        Log.e(TAG, "$tag: $message")
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(TAG, "$tag: $message")
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(TAG, "$tag: $message")
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(TAG, "$tag: $message")
    }

    override fun logVerbose(tag: String, message: String) {
        // Verbose 里是每个字节的读写，开着会把 logcat 冲垮 —— 故意丢掉
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.w(TAG, "$tag: $message $e")
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.w(TAG, "$tag: $e")
    }
}
