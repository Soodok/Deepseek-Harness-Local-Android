package app.dsh.mobile.engine

/**
 * 屏上动作的全局互斥（v1.7.13）。
 *
 * ## 为什么必须互斥
 * [/loop/run] 与 [/memory/run] 都会在**同一块物理屏**上连续点击、输入，而读屏执行器
 * 有 2 条线程（那是为了让快速读屏不被长任务堵死）。副作用是：两个长任务会真的
 * **同时**在屏上动作 —— 一个在点设置页、另一个在点别的 App，两边都返回 ok，
 * 但界面早就不是各自以为的那个。这类错乱事后极难归因：每一步都成功，只有整体
 * 结果不对。
 *
 * ## 为什么是「拿不到就报错」而不是排队
 * 排队会让调用方以为卡住，而且预算从排队那一刻就开始算了（调用方的 deadline 已经
 * 在过去）。立刻回 409 + 明确文案，调用方知道该等而不是该重试。
 *
 * ## 覆盖范围
 * 只护两个长任务入口。单步读屏、单次点击不抢锁 —— 否则快路径会被长任务拖成串行，
 * 那正是 v1.2.152 加第二条线程要解决的问题。
 */
object OnScreenTask {

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 当前占据锁的任务名（排查用；null = 空闲）。 */
    @Volatile
    var holder: String? = null
        private set

    /**
     * 拿锁执行 [block]；已被占用时返回 null（调用方据此回 409）。
     *
     * @param name 任务名，进 [holder]，超时归因时能看出是谁占着。
     */
    fun <T> runOrNull(name: String, block: () -> T): T? {
        if (!busy.compareAndSet(false, true)) return null
        holder = name
        try {
            return block()
        } finally {
            holder = null
            busy.set(false)
        }
    }
}
