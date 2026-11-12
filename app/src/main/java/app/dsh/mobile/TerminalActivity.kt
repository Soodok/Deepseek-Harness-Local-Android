package app.dsh.mobile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Vibrator
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.TerminalPtySession
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 真 PTY 终端页（对齐 DSHA 的 `PtyTerminalFragment`）。
 *
 * 复用 Termux 拆出的 `TerminalView`（Apache 2.0，见 scripts/termux-jni/），接在
 * [TerminalPtySession] 上：vim / htop / tmux / top 都能跑。软键盘没有 Esc、Ctrl
 * 和方向键，所以底部放一排扩展键；字体大小、会话标签与 DSHA 同款。
 *
 * 会话在 Activity 存活期内由本页持有：切出去再回来历史与正在跑的程序都还在，
 * 真正结束只发生在关闭标签或退出本页。
 */
class TerminalActivity : Activity(), TerminalViewClient, TerminalPtySession.Listener {

    companion object {
        /** Intent extra：true = 在 Linux 容器内开会话（否则用宿主 bionic bash）。 */
        const val EXTRA_IN_LINUX = "app.dsh.mobile.extra.TERMINAL_IN_LINUX"

        private const val TAG = "DSH-term-ui"

        private const val KEY_FONT_PX = "term_font_px"
        private const val FONT_MIN_SP = 8
        private const val FONT_MAX_SP = 24
        private const val FONT_DEF_SP = 13

        /**
         * 扩展键：`{显示, 要发的序列}`，序列为 null 表示这是个状态键（Ctrl / Alt）。
         * 手机软键盘没有 Esc、Ctrl 和方向键，缺了这排 TUI 基本没法用。
         */
        private val KEYS = arrayOf<Array<String?>>(
            arrayOf("ESC", "\u001b"),
            arrayOf("TAB", "\t"),
            arrayOf("CTRL", null),
            arrayOf("ALT", null),
            arrayOf("↑", "\u001b[A"),
            arrayOf("↓", "\u001b[B"),
            arrayOf("←", "\u001b[D"),
            arrayOf("→", "\u001b[C"),
            arrayOf("^C", "\u0003"),
            arrayOf("^D", "\u0004"),
            arrayOf("^Z", "\u001a"),
            arrayOf("|", "|"),
            arrayOf("~", "~"),
            arrayOf("/", "/"),
            arrayOf("-", "-"),
        )
    }

    private class Tab(val id: Long, val number: Int, val session: TerminalPtySession) {
        var closing = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val redrawPending = AtomicBoolean(false)

    private val tabs = ArrayList<Tab>()
    private var nextId = 1L
    private var selectedId = 0L

    private var terminalView: TerminalView? = null
    private var titleView: TextView? = null
    private var emptyView: TextView? = null
    private var tabsRow: LinearLayout? = null
    private var tabsScroll: HorizontalScrollView? = null
    private var newBtn: TextView? = null

    private var attachedSession: TerminalPtySession? = null
    private var sessionListener: TerminalPtySession.Listener? = null
    /**
     * 本 Activity 是否运行在 Linux 容器模式（由 Intent extra 决定）。
     *
     * 一个 Activity 同时服务两种 shell：宿主 bionic bash（原行为）与
     * Alpine 容器内 `/bin/sh -l`。不拆两个 Activity 是因为 Tab 管理、字体缩放、
     * 输入法/快捷键处理、会话恢复这些逻辑完全一样，拆开就是两份要同步维护的副本。
     */
    private var inLinux = false

    private var ctrlDown = false
    private var altDown = false
    private var ctrlBtn: TextView? = null
    private var altBtn: TextView? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyTo(this)
        setContentView(R.layout.activity_terminal)

        terminalView = findViewById(R.id.pty_view)
        titleView = findViewById(R.id.pty_title)
        emptyView = findViewById(R.id.pty_empty)
        tabsRow = findViewById(R.id.terminal_tabs)
        tabsScroll = findViewById(R.id.terminal_tabs_scroll)
        newBtn = findViewById(R.id.terminal_new)

        inLinux = intent.getBooleanExtra(EXTRA_IN_LINUX, false)

        terminalView?.setTerminalViewClient(this)
        applyFontSize(fontSp())
        buildExtraKeys(findViewById(R.id.pty_keys))

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.pty_font_dec).setOnClickListener { bumpFont(-1) }
        findViewById<TextView>(R.id.pty_font_inc).setOnClickListener { bumpFont(+1) }
        newBtn?.setOnClickListener { startTerminal() }

        startTerminal()
    }

    /** 已有会话就接回去（切页面回来不丢历史），没有就起一个。 */
    private fun startTerminal() {
        try {
            // 环境变量与引擎内 AI 的 bash 工具保持一致；引擎未就绪时用默认端口，
            // 此时仅影响 notify/scr 等闸门脚本里的端口，bash 本身照常工作。
            val port = (application as DshApp).supervisor.healthyPort
                .takeIf { it > 0 } ?: EngineConfig.DEFAULT_PORT
            // 初始 80x24 只是占位：attachSession 之后 TerminalView 会按控件实测的
            // 字宽重新算行列并通知 PTY（否则 TUI 的边框会错位）。
            //
            // inLinux=true 时改为在 Alpine 容器内开 shell（见 TerminalPtySession.startLinux）。
            // 容器环境未就绪时 startLinux 返回 null —— 这时不能静默回退到宿主 bash，
            // 否则用户以为自己在 Linux 里，`apk add` 却报 command not found。
            val ns = if (inLinux) {
                TerminalPtySession.startLinux(this, 80, 24, null) ?: run {
                    titleView?.text = getString(R.string.terminal_linux_not_ready)
                    return
                }
            } else {
                TerminalPtySession.start(this, port, 80, 24, null)
            }
            val tab = Tab(nextId++, nextTabNumber(), ns)
            tabs.add(tab)
            selectedId = tab.id
            attachSelected()
        } catch (t: Throwable) {
            val safe = t.message ?: t.javaClass.simpleName
            titleView?.text = getString(R.string.terminal_start_failed, safe)
            Log.w(TAG, "terminal start failed: $safe", t)
        }
    }

    private fun currentTab(): Tab? = tabs.firstOrNull { it.id == selectedId }

    private fun nextTabNumber(): Int {
        val occupied = tabs.map { it.number }.toHashSet()
        var number = 1
        while (occupied.contains(number)) number++
        return number
    }

    private fun attachSelected() {
        val tv = terminalView ?: return
        val title = titleView ?: return
        val empty = emptyView ?: return

        attachedSession?.detachListener(sessionListener)
        attachedSession = null
        sessionListener = null
        ctrlDown = false
        altDown = false
        paintModifiers()

        val tab = currentTab()
        empty.visibility = if (tab == null) View.VISIBLE else View.GONE
        tv.visibility = if (tab == null) View.INVISIBLE else View.VISIBLE
        tv.isEnabled = tab != null && !tab.closing

        if (tab == null) {
            title.text = getString(if (inLinux) R.string.terminal_title_linux else R.string.terminal_title)
        } else {
            val selected = tab.session
            attachedSession = selected
            // 每次绑定捕获会话身份；旧会话排队中的回调不能改写新标签。
            sessionListener = object : TerminalPtySession.Listener {
                private fun active() = attachedSession === selected

                override fun onOutput() {
                    if (active()) this@TerminalActivity.onOutput()
                }

                override fun onTitle(value: String) {
                    if (active()) this@TerminalActivity.onTitle(value)
                }

                override fun onExit(status: Int) {
                    if (active()) this@TerminalActivity.onExit(status)
                }

                override fun onCopy(value: String) {
                    if (active()) this@TerminalActivity.onCopy(value)
                }

                override fun onPasteRequest() {
                    if (active()) this@TerminalActivity.onPasteRequest()
                }

                override fun onBell() {
                    if (active()) this@TerminalActivity.onBell()
                }
            }
            selected.attachListener(sessionListener)
            tv.attachSession(selected.session())
            tv.onScreenUpdated()
            title.text =
                if (selected.isRunning()) displayTitle(selected)
                else getString(R.string.terminal_session_ended, selected.exitStatus().toString())
        }
        renderTabs()
    }

    /**
     * 标题栏文字。
     *
     * 空标题时的兜底不能一律写 "DSH · PTY"：容器里的 shell 与宿主 bionic 的 shell
     * 标题栏一模一样，用户就分不清点进来的是哪一个 —— 「跳到原生终端」这个误会
     * 有它一份。inLinux 时改说容器。
     */
    private fun displayTitle(s: TerminalPtySession): String {
        val t = s.session()?.title
        if (!t.isNullOrBlank()) return t.trim()
        return getString(if (inLinux) R.string.terminal_title_linux else R.string.terminal_title_pty)
    }

    private fun renderTabs() {
        val row = tabsRow ?: return
        val scroll = tabsScroll
        row.removeAllViews()
        val current = currentTab()
        for (tab in tabs) {
            val selected = current != null && current.id == tab.id
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            }
            val name = TextView(this).apply {
                text =
                    if (tab.closing) getString(R.string.terminal_closing, tab.number)
                    else if (inLinux) getString(R.string.terminal_number_linux, tab.number)
                    else getString(R.string.terminal_number, tab.number)
                setTextColor(if (selected) Accent.current(this@TerminalActivity).argb else 0xFF9AA5B4.toInt())
                textSize = 13f
                includeFontPadding = false
                gravity = Gravity.CENTER
                isSingleLine = true
                minWidth = dp(72)
                minHeight = dp(48)
                setPadding(dp(12), 0, dp(8), 0)
                contentDescription = getString(R.string.terminal_switch_to, tab.number)
                setOnClickListener { selectTab(tab.id) }
            }
            chip.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val close = TextView(this).apply {
                text = "×"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(0xFF9AA5B4.toInt())
                minHeight = dp(48)
                minWidth = dp(48)
                isEnabled = !tab.closing
                alpha = if (tab.closing) 0.4f else 1f
                contentDescription = getString(R.string.terminal_close, tab.number)
                setOnClickListener { closeTerminal(tab.id) }
            }
            chip.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))

            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            row.addView(chip, lp)
            if (selected) {
                chip.post {
                    if (chip.isAttachedToWindow) scroll?.smoothScrollTo(chip.left, 0)
                }
            }
        }
    }

    private fun selectTab(id: Long) {
        if (tabs.none { it.id == id }) return
        selectedId = id
        attachSelected()
    }

    private fun closeTerminal(id: Long) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        if (tab.closing) return
        tab.closing = true
        attachSelected()
        renderTabs()
        val app = applicationContext
        Thread({
            var failure: String? = null
            try {
                tab.session.finish()
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (tab.session.isRunning() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                if (tab.session.isRunning()) failure = "timeout"
            } catch (e: Exception) {
                failure = e.message
            }
            val problem = failure
            mainHandler.post {
                if (problem != null) {
                    tab.closing = false
                    Toast.makeText(app, getString(R.string.terminal_close_failed, problem), Toast.LENGTH_LONG).show()
                } else {
                    val index = tabs.indexOf(tab)
                    tabs.remove(tab)
                    if (selectedId == id) {
                        selectedId = if (tabs.isEmpty()) 0L else tabs[minOf(index, tabs.size - 1)].id
                    }
                }
                if (terminalView != null) attachSelected()
            }
        }, "dsh-close-terminal").start()
    }

    // ==================== 扩展键 ====================

    private fun buildExtraKeys(box: LinearLayout) {
        box.removeAllViews()
        for (k in KEYS) {
            val label = k[0]!!
            val seq = k[1]
            val b = TextView(this).apply {
                text = label
                minHeight = dp(44)
                minWidth = dp(44)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(0xFF9AA5B4.toInt())
                typeface = Typeface.MONOSPACE
                textSize = 13f
                setPadding(dp(11), dp(7), dp(11), dp(7))
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            b.layoutParams = lp
            if (seq == null) {
                if ("CTRL" == label) ctrlBtn = b else altBtn = b
                b.setOnClickListener { toggleModifier(label) }
            } else {
                b.setOnClickListener { send(seq) }
            }
            box.addView(b)
        }
        paintModifiers()
    }

    /** Ctrl / Alt 是状态键：按一下亮起来，下一个字符带上这个修饰键（TerminalView 会来问）。 */
    private fun toggleModifier(which: String) {
        if ("CTRL" == which) ctrlDown = !ctrlDown else altDown = !altDown
        paintModifiers()
        showKeyboard()
    }

    private fun paintModifiers() {
        val accent = Accent.current(this).argb
        ctrlBtn?.setTextColor(if (ctrlDown) accent else 0xFF9AA5B4.toInt())
        altBtn?.setTextColor(if (altDown) accent else 0xFF9AA5B4.toInt())
    }

    private fun send(seq: String) {
        if (!inputAllowed()) return
        val s = attachedSession
        if (s == null || !s.isRunning()) {
            Toast.makeText(this, R.string.terminal_session_ended_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val tab = currentTab()
        if (tab == null || tab.closing) return
        s.write(seq)
        // 修饰键是一次性的：发完就灭，跟物理键盘的手感一致
        if (ctrlDown || altDown) {
            ctrlDown = false
            altDown = false
            paintModifiers()
        }
    }

    // ==================== 字号 ====================

    private fun prefs() = getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)

    private fun fontSp(): Int = prefs().getInt(KEY_FONT_PX, FONT_DEF_SP).coerceIn(FONT_MIN_SP, FONT_MAX_SP)

    private fun bumpFont(delta: Int) {
        val next = (fontSp() + delta).coerceIn(FONT_MIN_SP, FONT_MAX_SP)
        prefs().edit().putInt(KEY_FONT_PX, next).apply()
        applyFontSize(next)
    }

    private fun applyFontSize(sp: Int) {
        val tv = terminalView ?: return
        // TerminalView.setTextSize 收的是 px；行列数由它自己按字宽重算并同步给 PTY
        tv.setTextSize(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), resources.displayMetrics).toInt(),
        )
    }

    private fun showKeyboard() {
        val tv = terminalView ?: return
        if (!inputAllowed()) return
        tv.requestFocus()
        val im = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        im.showSoftInput(tv, 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ==================== TerminalPtySession.Listener ====================

    /** PTY 每来一小段输出就回调一次（几十字节一次很常见），无节流地 post 会把主线程
     *  队列灌满 —— 用一个 pending 标志把连续多次输出合并成一次重绘。 */
    override fun onOutput() {
        if (!redrawPending.compareAndSet(false, true)) return
        val target = terminalView
        if (target == null) {
            redrawPending.set(false)
            return
        }
        target.postOnAnimation {
            redrawPending.set(false)
            if (isDestroyed || terminalView !== target || target.windowToken == null) return@postOnAnimation
            target.onScreenUpdated()
        }
    }

    override fun onTitle(t: String) {
        val target = titleView
        val source = attachedSession
        mainHandler.post {
            if (isDestroyed || target == null || titleView !== target || attachedSession !== source) return@post
            if (t.isNotBlank()) target.text = t.trim()
        }
    }

    override fun onExit(status: Int) {
        val target = titleView
        val exited = attachedSession
        mainHandler.post {
            if (isDestroyed || target == null || titleView !== target || attachedSession !== exited) return@post
            target.text = getString(R.string.terminal_session_ended, status.toString())
        }
    }

    override fun onCopy(text: String) {
        val target = terminalView
        val source = attachedSession
        mainHandler.post {
            runCatching {
                if (isDestroyed || target == null || terminalView !== target || attachedSession !== source) return@runCatching
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching
                cm.setPrimaryClip(ClipData.newPlainText("term", text))
                Toast.makeText(this, R.string.terminal_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPasteRequest() {
        val target = terminalView
        val source = attachedSession
        mainHandler.post {
            runCatching {
                if (isDestroyed || target == null || terminalView !== target || attachedSession !== source) return@runCatching
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching
                val clip = cm.primaryClip ?: return@runCatching
                if (clip.itemCount == 0) return@runCatching
                val cs = clip.getItemAt(0).coerceToText(this)
                if (cs != null && cs.isNotEmpty()) send(cs.toString())
            }
        }
    }

    override fun onBell() {
        val target = terminalView
        mainHandler.post {
            runCatching {
                if (isDestroyed || target == null || terminalView !== target) return@runCatching
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@runCatching
                v.vibrate(30) // 响铃改成震一下：手机上「响」多半是骚扰
            }
        }
    }

    // ==================== TerminalViewClient ====================

    override fun onScale(scale: Float): Float {
        // 双指缩放调字号：返回值是库要的「当前字号」，它拿去继续算下一次缩放
        if (scale < 0.9f || scale > 1.1f) {
            bumpFont(if (scale > 1f) +1 else -1)
        }
        return fontSp().toFloat()
    }

    override fun onSingleTapUp(e: MotionEvent) {
        showKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false // 返回键就该是返回键

    override fun shouldEnforceCharBasedInput(): Boolean = true // 对中文输入法友好

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = inputAllowed()

    private fun inputAllowed(): Boolean {
        val tab = currentTab() ?: return false
        return terminalView != null && !tab.closing
    }

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = !inputAllowed()

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(e: MotionEvent): Boolean = false // 返回 false 才会走库自带的文本选择

    override fun readControlKey(): Boolean = ctrlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDownFromKeyboard: Boolean, session: TerminalSession): Boolean = !inputAllowed()

    override fun onEmulatorSet() {
        onOutput()
    }

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

    override fun logVerbose(tag: String, message: String) {}

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.w(TAG, "$tag: $message $e")
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.w(TAG, "$tag: $e")
    }

    override fun onDestroy() {
        attachedSession?.detachListener(sessionListener)
        tabs.forEach { runCatching { it.session.finish() } }
        tabs.clear()
        attachedSession = null
        sessionListener = null
        terminalView = null
        titleView = null
        emptyView = null
        tabsRow = null
        tabsScroll = null
        newBtn = null
        ctrlBtn = null
        altBtn = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
