package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 无障碍服务（m1.30 读屏升级 v1.1.0）：
 *  - 手势注入：tap(x,y) / swipe —— 模拟点击与滑动
 *  - 读屏：dumpScreenJson() 遍历可见节点树，输出文本+坐标+可点击性（"非盲"能力）
 *  - 按文本点击：tapText("确定") —— 在节点树里找含该文本的可点击节点并点它
 *
 * 权限边界（privacy-first）：读屏能力由 canRetrieveWindowContent 开关（xml）授权；
 * 服务必须由用户在系统设置手动开启（Android 安全模型），关闭即所有能力失效。
 *
 * Agent 调用入口：AgentBridge (127.0.0.1:3083) 的 GET /screen、POST /tap，
 * 引擎内经 `scr` 包装器使用。
 */
class DshAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 常规无障碍事件流：本服务不监听特定事件，保留空实现以符合契约
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ==================== 读屏 ====================

    /**
     * 遍历活跃窗口可见节点，输出 JSON：
     * {"ok":true,"nodes":[{"text":"...","desc":"...","cls":"...","x":..,"y":..,"w":..,"h":..,"clickable":true}]}
     * 只保留「有文本/描述」或「可点击」的节点，上限 200 个防超大界面。
     */
    fun dumpScreenJson(): String {
        val arr = JSONArray()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= MAX_NODES) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val visible = rect.width() > 0 && rect.height() > 0 &&
                rect.top < rootHeight && rect.bottom > 0
            if (visible) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
                    arr.put(JSONObject().apply {
                        put("text", text)
                        put("desc", desc)
                        put("cls", node.className?.toString() ?: "")
                        put("x", rect.centerX())
                        put("y", rect.centerY())
                        put("w", rect.width())
                        put("h", rect.height())
                        put("clickable", node.isClickable)
                    })
                    count++
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        return JSONObject().put("ok", true).put("nodes", arr).toString()
    }

    private val rootHeight: Int
        get() = resources.displayMetrics.heightPixels

    // ==================== 点击 ====================

    /** 按文本查找可点击节点并点击（contains 匹配，优先完全/前缀命中）。找不到回退坐标点击其中心。 */
    fun tapText(text: String): Boolean {
        val target = findNodeByText(text) ?: return false
        val rect = Rect().also { target.getBoundsInScreen(it) }
        return tap(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        // 1) 精确/视图文本命中（Android 原生快速路径）
        root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isClickable }?.let { return it }
        // 2) contains 慢路径
        var hit: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (hit != null || node == null) return
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            if ((t.contains(text) || d.contains(text)) && (node.isClickable || node.parent != null)) {
                // 可点击节点，或其可点击祖先
                var cur: AccessibilityNodeInfo? = node
                while (cur != null) {
                    if (cur.isClickable) { hit = cur; return }
                    cur = cur.parent
                }
                if (hit == null) hit = node
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return hit
    }

    // ==================== companion ====================

    companion object {
        @Volatile
        internal var instance: DshAccessibilityService? = null

        /** 服务是否已启用（用户在系统设置开启后为 true） */
        fun isEnabled(): Boolean = instance != null

        /** 是否支持手势注入（API 24+ 且服务已连接） */
        fun canTap(): Boolean = instance != null && Build.VERSION.SDK_INT >= 24

        /** dumpScreen 的最大节点数（防超大界面卡顿） */
        private const val MAX_NODES = 200

        /**
         * 模拟点击屏幕 (x, y)（物理像素坐标）。
         * @return true 表示已成功派发合成点击
         */
        fun tap(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < 24) return false
            return svc.dispatchTap(x, y)
        }
    }

    /** 实例内手势派发（companion.tap 与 tapText 共用） */
    internal fun dispatchTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
