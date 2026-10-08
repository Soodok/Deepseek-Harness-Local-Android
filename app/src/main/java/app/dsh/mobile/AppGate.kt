package app.dsh.mobile

import android.content.Context
import android.util.Log

/**
 * 全局启动门禁（v1.2.100）。
 *
 * ## 为什么需要单独一个 object
 * WebView 版本判定最初只写在 `MainActivity.onResume` 里，但**引擎还有别的启动路径**：
 *  · 无障碍服务的保活看门狗（每 60s 检查，掉了就拉起）
 *  · 系统重启后的前台服务自动恢复
 * 这些路径不经过 Activity，于是低版本 WebView 的设备上引擎照常被拉起 ——
 * 「引擎白跑（前端根本渲染不了）+ 用户看到白屏」，承诺的「不足则提示而非强开」落空。
 * 独立审查指出这个缺口后，把判定抽到这里，**所有启动路径统一查同一个状态**。
 *
 * ## 状态语义
 *  · [State.UNKNOWN]  —— 尚未检测（首次）
 *  · [State.OK]       —— 版本达标
 *  · [State.BLOCKED]  —— 版本不足且用户尚未表态（**不启动引擎**）
 *  · [State.OVERRIDDEN] —— 版本不足，但用户已明确选择「仍然继续」（放行）
 *
 * 仅存内存（不落盘）：版本可用性随系统更新变化，每次启动重判最准确；
 * 且「本次会话用户已知情」的语义本就该在进程生命周期内。
 */
object AppGate {

    private const val TAG = "AppGate"

    /** 最低 WebView 内核版本（Chrome 大版本）；引擎前端用了 `??=` 等解析期语法 */
    const val MIN_WEBVIEW_CHROME = 85

    enum class State { UNKNOWN, OK, BLOCKED, OVERRIDDEN }

    @Volatile
    var webViewState: State = State.UNKNOWN
        private set

    /** 版本不足时用户主动选择继续（由 MainActivity 的对话框回调设置） */
    fun overrideWebViewGate() {
        webViewState = State.OVERRIDDEN
        Log.i(TAG, "webview gate overridden by user")
    }

    /** 记录检测结果（达标时调用） */
    fun markWebViewOk() {
        webViewState = State.OK
    }

    /** 记录拦截（版本不足、用户未表态） */
    fun markWebViewBlocked() {
        webViewState = State.BLOCKED
    }

    /**
     * 引擎是否允许自动启动（供 [app.dsh.mobile.service.EngineService.start] 调用）。
     *
     * UNKNOWN 状态视为放行：**首次启动时还没人检测过**，此时放行让 MainActivity
     * 有机会做检测并弹框；若在这里拦掉，就永远没人触发展示说明了。
     * 检测一旦完成（OK / BLOCKED / OVERRIDDEN），后续调用即以该结果为准。
     */
    fun webViewAllowed(@Suppress("UNUSED_PARAMETER") context: Context): Boolean =
        when (webViewState) {
            State.BLOCKED -> false
            else -> true
        }
}
