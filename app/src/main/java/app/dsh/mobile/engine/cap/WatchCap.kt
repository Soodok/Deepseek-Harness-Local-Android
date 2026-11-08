package app.dsh.mobile.engine.cap

import android.content.Context
import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.SensitiveAccess
import org.json.JSONArray
import org.json.JSONObject

/**
 * 事件流采集（v1.6.x）：输入变化 / 物理按键 / 窗口切换。
 *
 * ## 为什么这些数据不是「查询」而是「事件」
 *
 * 现有的 /dev/status、/dev/notifications 都是**问一次答一次**的快照。但有三种
 * 信息本质上是**流**：用户敲了什么、按了哪个键、切到了哪个 App。它们没有
 * 「当前值」可读 —— 只有「刚刚发生过什么」。所以要一个环形缓冲 + 增量拉取。
 *
 * ## 线程模型
 *
 * 写入发生在无障碍服务的回调线程（主线程），读取发生在 AgentBridge 的请求线程。
 * 三个缓冲各自 `synchronized`，只做「追加 + 淘汰最旧」和「按 since 切片」，
 * 不做任何网络/磁盘/主线程阻塞操作。
 *
 * ## 隐私边界（重要）
 *
 * · `input` 只记 `length` 与文本，**不记** 输入框密码状态为真的内容
 *   （`AccessibilityEvent.isPassword` 为真时整条丢弃，连长度都不记）。
 * · 三个缓冲都只在内存里，**不落盘**：进程一死就没了，这是有意的 ——
 *   落盘会让「AI 能读输入内容」变成「随时能翻历史输入」。
 * · 每次 `collect` 都经 [SensitiveAccess] 留痕（input 档）。
 */
object WatchCap {

    /** 单个缓冲的容量上限。够覆盖一次「用户操作几步给我看」的窗口，又不至于失控。 */
    private const val MAX_INPUT = 200
    private const val MAX_KEY = 200
    private const val MAX_WINDOW = 120

    private val inputBuf = ArrayDeque<JSONObject>()
    private val keyBuf = ArrayDeque<JSONObject>()
    private val windowBuf = ArrayDeque<JSONObject>()

    private fun append(buf: ArrayDeque<JSONObject>, obj: JSONObject, max: Int) {
        synchronized(buf) {
            buf.addLast(obj)
            while (buf.size > max) buf.removeFirst()
        }
    }

    /**
     * 记录一次输入框内容变化。
     *
     * @param text 事件里的文本。调用方已确认 `isPassword == false`。
     */
    fun recordInput(pkg: String, cls: String, text: String) {
        append(
            inputBuf,
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("pkg", pkg)
                .put("cls", cls)
                .put("len", text.length)
                .put("text", text),
            MAX_INPUT,
        )
    }

    /** 记录一次物理按键。`action` 为 0=DOWN / 1=UP，与 KeyEvent 一致。 */
    fun recordKey(keyCode: Int, action: Int, pkg: String) {
        append(
            keyBuf,
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("key", keyCode)
                .put("name", keyName(keyCode))
                .put("action", action)
                .put("pkg", pkg),
            MAX_KEY,
        )
    }

    /** 记录一次窗口切换（前台 Activity 变化）。 */
    fun recordWindow(pkg: String, cls: String) {
        // 同一个 Activity 会因为内容变化反复上报，连续重复只留一条，
        // 否则缓冲会被同一个界面瞬间灌满。
        synchronized(windowBuf) {
            val last = windowBuf.lastOrNull()
            if (last != null && last.optString("pkg") == pkg && last.optString("cls") == cls) {
                last.put("at", System.currentTimeMillis())
                last.put("repeat", last.optInt("repeat", 1) + 1)
                return
            }
        }
        append(
            windowBuf,
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("pkg", pkg)
                .put("cls", cls),
            MAX_WINDOW,
        )
    }

    private fun keyName(code: Int): String = when (code) {
        android.view.KeyEvent.KEYCODE_VOLUME_UP -> "volume_up"
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "volume_down"
        android.view.KeyEvent.KEYCODE_POWER -> "power"
        android.view.KeyEvent.KEYCODE_CAMERA -> "camera"
        android.view.KeyEvent.KEYCODE_BACK -> "back"
        android.view.KeyEvent.KEYCODE_HOME -> "home"
        android.view.KeyEvent.KEYCODE_MENU -> "menu"
        android.view.KeyEvent.KEYCODE_HEADSETHOOK -> "headset_hook"
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "media_play_pause"
        android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> "media_next"
        android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "media_previous"
        else -> "key_$code"
    }

    private fun snapshot(buf: ArrayDeque<JSONObject>, since: Long): JSONArray {
        val out = JSONArray()
        synchronized(buf) {
            for (o in buf) if (o.optLong("at") > since) out.put(JSONObject(o.toString()))
        }
        return out
    }

    private fun clearBuf(buf: ArrayDeque<JSONObject>): Int = synchronized(buf) {
        val n = buf.size
        buf.clear()
        n
    }

    /**
     * `GET/POST /dev/watch`
     *
     * · `op=list`（默认）· `kind=input|key|window|all` · `since=<epoch ms，增量拉取>`
     *   · `clear=1`（清空后再列，用于「从现在开始记录」的语义）
     *
     * 权限：`input` 要 `cap.inputwatch`；`key` 要 `cap.keywatch`；`window` 归入
     * 基础感知（`cap.status`），因为它等价于「前台应用历史」，不含用户输入。
     */
    fun handle(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
        val op = obj.optString("op", "list").lowercase()
        val kind = obj.optString("kind", "all").lowercase()
        val since = obj.optLong("since", 0L)

        if (op == "clear") {
            val n = JSONObject()
                .put("input", clearBuf(inputBuf))
                .put("key", clearBuf(keyBuf))
                .put("window", clearBuf(windowBuf))
            CapabilityGate.audit(ctx, "dev.watch.clear")
            return 200 to JSONObject().put("ok", true).put("cleared", n).toString()
        }
        if (op != "list") return 400 to err("op must be list|clear")

        val wantsInput = kind == "all" || kind == "input"
        val wantsKey = kind == "all" || kind == "key"
        val wantsWindow = kind == "all" || kind == "window"
        if (!wantsInput && !wantsKey && !wantsWindow) {
            return 400 to err("kind must be input|key|window|all")
        }

        // 逐个 kind 判权：`kind=all` 时只要有任意一项没授权，就把那一项标成
        // denied 而不是整体 403 —— 否则「我只想看窗口切换」会被输入开关挡住。
        if (wantsInput && !CapabilityGate.can(ctx, CapabilityGate.INPUTWATCH)) {
            return 403 to err("capability disabled", CapabilityGate.INPUTWATCH,
                "enable Input monitoring in Permission Center; it is a separate switch from accessibility")
        }
        if (wantsKey && !CapabilityGate.can(ctx, CapabilityGate.KEYWATCH)) {
            return 403 to err("capability disabled", CapabilityGate.KEYWATCH,
                "enable Key event stream in Permission Center")
        }
        if (wantsWindow && !CapabilityGate.can(ctx, CapabilityGate.STATUS)) {
            return 403 to err("capability disabled", CapabilityGate.STATUS)
        }

        if (obj.optBoolean("clear", false)) {
            clearBuf(inputBuf); clearBuf(keyBuf); clearBuf(windowBuf)
        }

        val out = JSONObject().put("ok", true).put("kind", kind).put("since", since)
        if (wantsInput) {
            val arr = snapshot(inputBuf, since)
            out.put("input", arr)
            // 每次读到真实输入内容就留痕：这是本表里最需要可见性的一项。
            if (arr.length() > 0) {
                SensitiveAccess.record(ctx, SensitiveAccess.Kind.INPUT_TEXT, arr.length())
            }
        }
        if (wantsKey) out.put("key", snapshot(keyBuf, since))
        if (wantsWindow) out.put("window", snapshot(windowBuf, since))
        out.put(
            "hint",
            "buffers are memory-only and capped (input/key 200, window 120); they vanish when the process dies. " +
                "Use since=<last at> for incremental polling.",
        )
        CapabilityGate.audit(ctx, "dev.watch.list:$kind")
        return 200 to out.toString()
    }

    private fun err(msg: String, cap: String = "", hint: String = ""): String {
        val o = JSONObject().put("ok", false).put("error", msg)
        if (cap.isNotEmpty()) o.put("capability", cap)
        if (hint.isNotEmpty()) o.put("hint", hint)
        return o.toString()
    }
}
