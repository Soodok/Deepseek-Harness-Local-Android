package app.dsh.mobile

/**
 * 悬浮窗的可见状态（v1.6.27）。
 *
 * ## 为什么要有它
 *
 * 此前悬浮窗只表达两种情形：「空闲」和「执行中」。用户于是在三种真实
 * 场景里无从判断发生了什么：
 *
 * 1. 动作失败了 —— 状态点还是强调色，看起来一切正常；
 * 2. AI 在等用户回答 —— 只多了一个问题框，状态行没变；
 * 3. 刚做完一件事 —— 和「空闲」长得一样，看不出是刚结束还是从没开始。
 *
 * 所以状态拆成五档，每档有固定配色与一句解释。**颜色是语义，不是装饰**：
 * 用户扫一眼状态点就该知道要不要去看。
 *
 * ## 边界
 *
 * 这个对象只管「哪一档」和「什么颜色」，不管文案 —— 文案是资源 id，
 * 由 UI 层映射。这样它不依赖 R，能在 JVM 上直接跑断言。
 */
object OverlayState {

    enum class Kind {
        /** 什么都不在做。中性灰。 */
        IDLE,
        /** AI 正在操作手机。强调蓝（跟随用户配色）。 */
        WORKING,
        /** 上一步失败了。危险红。 */
        ERROR,
        /** 刚做完一件事。成功绿。 */
        DONE,
        /** 有提问等着回答。警告黄。 */
        ASK,
    }

    // 语义色固定，不让用户配色改动 —— 把「错误」改成蓝色等于把报警关掉。
    const val COLOR_IDLE = 0xFF9AA0A6.toInt()
    const val COLOR_ERROR = 0xFFE5534B.toInt()
    const val COLOR_DONE = 0xFF81C995.toInt()
    const val COLOR_ASK = 0xFFF5A623.toInt()

    /** 强调蓝兜底值；实际应传入用户配置的 accent。 */
    const val COLOR_WORKING_FALLBACK = 0xFF7DD3FC.toInt()

    /**
     * 某一档的颜色。
     *
     * [accent] 只作用于 WORKING：其余四档是语义色，不能被用户配色改掉。
     */
    fun colorOf(kind: Kind, accent: Int = COLOR_WORKING_FALLBACK): Int = when (kind) {
        Kind.IDLE -> COLOR_IDLE
        Kind.WORKING -> accent
        Kind.ERROR -> COLOR_ERROR
        Kind.DONE -> COLOR_DONE
        Kind.ASK -> COLOR_ASK
    }

    /** 这一档是否需要用户关注（决定状态行是否显示、是否自动展开）。 */
    fun matters(kind: Kind): Boolean = kind != Kind.IDLE

    /**
     * 这一档是否**必须打断用户**（决定面板要不要自己弹出来）。
     *
     * 只有「等你回答」和「出错了」值得抢屏幕：这两种情况用户不动手就卡住了。
     * 「执行中」和「完成了」只是告知 —— 每点一下就把面板弹开，
     * 等于 AI 干活时一直在你眼前晃，反而挡事。
     */
    fun needsAttention(kind: Kind): Boolean =
        kind == Kind.ERROR || kind == Kind.ASK

    /**
     * 从四路信号推断该显示哪一档。
     *
     * 优先级：提问 > 错误 > 行动中 > 完成 > 空闲。
     *
     * 提问排最前，因为它是唯一**需要用户动手**的状态；其余三档都只是告知。
     * 错误排在行动中之前，是因为「正在重试另一个动作」不该把刚发生的失败盖掉 ——
     * 用户需要先知道出事了。
     */
    fun resolve(
        asking: Boolean,
        lastFailed: Boolean,
        working: Boolean,
        justFinished: Boolean,
    ): Kind = when {
        asking -> Kind.ASK
        lastFailed -> Kind.ERROR
        working -> Kind.WORKING
        justFinished -> Kind.DONE
        else -> Kind.IDLE
    }
}