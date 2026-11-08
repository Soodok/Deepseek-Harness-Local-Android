package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 动作结果的诚实判定（v1.6.19）。
 *
 * 以前动作端点直接回 ok:true —— 它只说明「手势发出去了」，不说明「界面变了」。
 * 模型于是把「我点了」当成「点成功了」，在没变的界面上继续往下走，越走越偏。
 *
 * 这里把「执行」和「结果」拆开：动作提交后重新读一次屏，比对元素指纹，
 * 给出**能被证伪的结论**；证据不足时明确说「无法验证」，而不是假装成功。
 *
 * 核心原则：缺少观测依据不等于界面没变。
 */
object ScreenVerdict {

    const val CHANGED = "界面有变化。"
    const val UNCHANGED = "界面没有明显变化。"
    const val SCROLL_END = "内容没有变化，可能已经到头了。"
    const val UNVERIFIABLE =
        "（动作已提交，但无法验证结果：缺少足够的可读内容，可能受敏感视图策略限制。）"
    const val KEYBOARD_SHOWN = "键盘已弹出。"
    const val KEYBOARD_HIDDEN = "键盘已收起。"

    /** 键盘是否在屏上：有输入法窗口即算。 */
    fun keyboardVisible(svc: AccessibilityService): Boolean = runCatching {
        svc.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    /**
     * 比对动作前后的两屏，返回一句能落地的判定。
     *
     * @param before 动作前那份文档（编号的基准）
     * @param after  动作后重建的文档；null = 读不到
     * @param scroll 动作是否是滚动（滚动到头的说法不一样）
     * @param keysBefore 动作前键盘状态
     * @param keysAfter  动作后键盘状态
     */
    fun of(
        before: ScreenDoc.Doc,
        after: ScreenDoc.Doc?,
        scroll: Boolean,
        keysBefore: Boolean,
        keysAfter: Boolean,
    ): String {
        if (after == null) return UNVERIFIABLE
        if (keysBefore != keysAfter) return if (keysAfter) KEYBOARD_SHOWN else KEYBOARD_HIDDEN
        // 可读内容太少时无法判断：说「没变」是撒谎，说「变了」也没有依据
        if (before.poorTree || after.poorTree) {
            return if (before.signature() == after.signature()) UNVERIFIABLE else CHANGED
        }
        if (after.packageName != before.packageName) return CHANGED
        if (after.signature() == before.signature()) return if (scroll) SCROLL_END else UNCHANGED
        return CHANGED
    }

    /** 变化了几项（新增 + 消失），供调用方拼「界面有变化（N 项）」。 */
    fun diffCount(before: ScreenDoc.Doc, after: ScreenDoc.Doc): Int {
        val b = before.signature().toSet()
        val a = after.signature().toSet()
        return (a - b).size + (b - a).size
    }

    /** 目标在读完到点击之间变了 —— /tap-ref 的拒绝文案。 */
    fun changedReason(id: Int, was: String, now: String?): String {
        val nowTxt = now ?: "(它已经不在这一项了)"
        return "[" + id + "]「" + was + "」在读完到点击之间变了，没有执行；现在是「" + nowTxt + "」，请看新屏幕再选"
    }

    /** 保护页拒绝点击的理由。 */
    const val PROTECTED_REASON =
        "这是系统权限 / 安全页面。可以读，但不能自动点击；请把手机交给用户完成。"

    /** 三级降级全部失败的理由。 */
    const val TAP_FAILED_REASON =
        "节点不接受 ACTION_CLICK，祖先也不接受，坐标注入也没有生效。"
}
