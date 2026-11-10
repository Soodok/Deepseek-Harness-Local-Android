package app.dsh.mobile

/**
 * 无障碍服务在 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 里的那一串条目（v1.7.0）。
 *
 * 这一串是 `pkg/cls` 或 `pkg/.Short` 用 `:` 连接的列表。我们只允许改**自己那一条**，
 * 别人的（TalkBack、其他助手）一个字都不能动 —— 所以这里是纯字符串运算，
 * 且每条判定都按 Android 的两条合法写法（全名 / 点号缩写）比对。
 *
 * 纯 Kotlin，无 Android 依赖，可在 JVM 上断言。
 */
object A11yEntry {

    /** 我们这个服务在列表里的标准写法：pkg/完整类名。 */
    fun entryOf(pkg: String, cls: String): String = pkg + "/" + cls

    /**
     * 这一条是不是我们。
     *
     * 两种写法都认：pkg/app.dsh.mobile.DshAccessibilityService 与
     * pkg/.DshAccessibilityService。系统自己写回去的是哪一种不保证，
     * 只认一种就会出现「明明开着却判成没开」，然后我们再去 ADD 一条重复项。
     */
    fun isOurs(item: String, pkg: String, cls: String): Boolean {
        val e = item.trim()
        val slash = e.indexOf('/')
        if (slash <= 0) return false
        if (e.substring(0, slash) != pkg) return false
        val c = e.substring(slash + 1)
        if (c == cls) return true
        return c.startsWith(".") && pkg + c == cls
    }

    /** 列表里有没有我们。 */
    fun listed(list: String?, pkg: String, cls: String): Boolean =
        !list.isNullOrEmpty() && list.split(':').any { isOurs(it, pkg, cls) }

    /** 加上我们（已经有了就原样返回，不制造重复项）。 */
    fun withMe(list: String?, pkg: String, cls: String): String {
        if (listed(list, pkg, cls)) return list.orEmpty()
        val base = list.orEmpty().trim().trimEnd(':')
        val me = entryOf(pkg, cls)
        return if (base.isEmpty()) me else base + ":" + me
    }

    /** 去掉我们（只去我们自己那几条，其余保持原顺序）。 */
    fun withoutMe(list: String?, pkg: String, cls: String): String =
        list.orEmpty().split(':').filterNot { isOurs(it, pkg, cls) }.joinToString(":")
}