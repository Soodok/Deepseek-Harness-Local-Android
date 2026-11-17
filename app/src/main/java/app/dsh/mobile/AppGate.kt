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

    /**
     * 最低 WebView 内核版本（Chrome 大版本）。
     *
     * ## 沿革（别只看这条注释就改值）
     * · 原值 85：只按「API 层门槛」定（`String#replaceAll` = 85，polyfill 可补）。
     * · v1.6.8（`50d74042`）临时抬到 **94**：上游**未转译**产物含 `class static {}`
     *   （Chrome 94），语法层 polyfill 补不了，85–93 会「通过门禁 → 解析期
     *   SyntaxError → 白屏」。
     * · v1.6.9（`699ceedf`）回落 **85**：构建期改用
     *   `scripts/patch-webview-syntax-floor.py` 以 esbuild `--target=chrome85`
     *   重新转译入口 chunk，把 `static {}` 降级掉 —— 语法门槛回到 85，与 API 门槛
     *   对齐，85–93 的设备是真的能用，不再需要拦。
     *
     * ⚠️ 所以「85」成立的前提是**那个转译步骤真的跑了**。它被
     * `scripts/probes/_test_webview_syntax_floor.mjs` 守在产品上（对 runtime 里的
     * 入口 chunk 做 AST 扫描，实测门槛 > 常量就红）。若哪天关掉该步骤，实际门槛会
     * 弹回 94 而常量还是 85，低版本设备又变成「通过门禁然后白屏」。
     *
     * ⚠️ 另有一个**尚未纳入门禁**的维度：CSS 特性门槛（见 README 边界一节）。
     * JS 侧能到 85，但上游 CSS 用到 `:has()`(105) / `color-mix()`(111) /
     * `anchor()`+`position-try`(125)，在 85–124 上是**静默降级**（不报错、不白屏，
     * 但样式与弹层定位失效）。抬 MIN_CHROME 会砍掉大量现有设备，属产品决策，
     * 目前用客户端插件兜底（`chat-anchor-fallback`），未改本常量。
     *
     * 保留这个常量名是为了不动调用方（引擎服务等），值统一指向诊断模块的单一真源。
     */
    const val MIN_WEBVIEW_CHROME = WebViewDiagnostics.MIN_CHROME

    enum class State { UNKNOWN, OK, BLOCKED, OVERRIDDEN }

    @Volatile
    var webViewState: State = State.UNKNOWN
        private set

    /**
     * 最近一次诊断结论（v1.6.8）。供版本门禁弹窗、关于页等处复用，
     * 避免每处都重新探测一遍（探测要扫包管理器，不该反复做）。
     */
    @Volatile
    var lastReport: WebViewDiagnostics.Report? = null
        private set

    /** 记录一次诊断结论，并按结果推进状态机。由 MainActivity 的门禁检查调用。 */
    fun applyReport(report: WebViewDiagnostics.Report) {
        lastReport = report
        webViewState = when (report.cause) {
            WebViewDiagnostics.Cause.OK -> State.OK
            // 读不到版本 ≠ 太旧：放行但记 OK，避免把正常设备误判成故障。
            WebViewDiagnostics.Cause.UNKNOWN -> State.OK
            WebViewDiagnostics.Cause.TOO_OLD -> State.BLOCKED
        }
        Log.i(TAG, "webview gate: ${WebViewDiagnostics.summary(report)} → $webViewState")
    }

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
