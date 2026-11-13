package app.dsh.mobile

/**
 * **JVM 测试用的编译期占位**（不是实现，不会被编进 APK）。
 *
 * ## 它解决什么问题
 * engine/MemoryReplay.kt 的 run() 要真的碰无障碍服务，而 DshAccessibilityService 是
 * 一个两三千行、拖着一堆 android.* 依赖的类 —— 宿主机 JVM 编不动它。
 * 但 MemoryReplay 的**纯函数**（checkAnchor / precheck / checkAfter）必须真跑断言，
 * 不能因为在同一个文件里就整份跳过。做法：JVM 这条编译路给 app.dsh.mobile 提供一个
 * 只有**签名**的同名类，于是 MemoryReplay.kt 能编过、纯函数能跑，run() 只要不被调用
 * 就不会碰到任何空实现。
 *
 * ## 为什么不会污染 App 构建
 * 本文件在 scripts/jvm/ 下，只被 scripts/run-a11y-jvm-test.sh 拷进临时目录编译；
 * app/src 里没有它，Gradle 侧永远看不到，不可能出现重复定义。
 *
 * ## 漂移风险（已用断言兜住）
 * 这里的签名必须与真实的 DshAccessibilityService 一致：方法少了 → MemoryReplay 编不过（会立刻发现）；
 * 方法多写一个或名字写错 → 也会编不过；**参数/返回类型悄悄变了**则可能编过而语义错。
 * 因此 BehaviourTest 里有一条 assertStubMatchesService()：读真实源码文件，
 * 逐个确认这里用到的每个方法名在 DshAccessibilityService.kt 里都以 "fun 名字(" 的形式存在。
 * 改了真实服务的方法名，那条断言会红——比跑到真机上才发现"点了没反应"便宜得多。
 */
@Suppress("unused")
class DshAccessibilityService {

    fun dumpScreenCompact(filter: String = "all", ocrFallback: Boolean = true): String = ""

    fun foregroundPackage(): String? = null

    /** 保护页判据只看标题区（与桥的 protectedPageGuard 同源）。 */
    fun titleBlob(): String = ""

    fun screenSize(): Pair<Int, Int> = 1080 to 1920

    fun awaitSettle(quietMs: Long = 220L, timeoutMs: Long = 900L, pollMs: Long = 60L): Boolean = false

    fun tapText(text: String): Boolean = false

    fun tapById(rid: String): Triple<Int, Int, String>? = null

    fun dispatchTapRect(rect: android.graphics.Rect): Boolean = false

    fun dispatchSwipeHuman(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300L,
    ): Boolean = false

    /** 真实类里是 internal（同模块可见），v1 的 swipe 动作在 human=false 时走它。 */
    internal fun dispatchSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300L,
    ): Boolean = false

    fun scrollToFind(query: String, maxSwipes: Int = 8, forward: Boolean = true): android.graphics.Rect? = null

    fun performGlobalActionByName(name: String): Boolean = false

    fun pressEnter(): Boolean = false

    fun inputText(
        text: String,
        append: Boolean = false,
        targetText: String? = null,
        displayId: Int? = null,
    ): Boolean = false

    // ---- /dev/node 语义动作（v1.7.x 起重放侧也会调它们）----
    //
    // stub 必须与真实服务同步：缺方法时 JVM 测试编译不过（这次就是这么发现的），
    // 而如果只补个空实现又不写进 assertStubMatchesService 的清单，真实服务改名后
    // 就没人发现了。所以两侧一起加。
    fun tapTextDeep(text: String, byDesc: Boolean = false, displayId: Int? = null): String? = null

    fun longClickText(text: String, byDesc: Boolean = false, displayId: Int? = null): Boolean = false

    fun expandText(
        text: String,
        expand: Boolean = true,
        byDesc: Boolean = false,
        displayId: Int? = null,
    ): Boolean = false
}
