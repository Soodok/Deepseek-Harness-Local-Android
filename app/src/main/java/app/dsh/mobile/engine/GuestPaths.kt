package app.dsh.mobile.engine

import java.io.File

/**
 * 容器视角（guest）路径 → 宿主路径。
 *
 * ## 为什么需要
 *
 * Linux 模式下引擎的 `DSH_HOME=/dsh`（见 [LinuxEngineLauncher.buildBinds] 的
 * `Bind("/dsh", dshHome)`），它把默认工作区记成 **`/dsh/workspaces`** 写进
 * `storages/workspace.json`。切回原生模式后这个 guest 拼写留在注册表里：
 *
 *   · 宿主上 `/dsh` 这一级**不可能存在**（根目录不可写，mkdir 直接 EACCES/ENOENT）；
 *   · 引擎启动时 `ensure project directory "/dsh/workspaces"` 失败
 *     → `initial Session restoration failed: SessionCreateError`
 *     → 用户看到的是「一打开就是空白/报错」。
 *
 * ## 为什么不能只靠挂载
 *
 * [LinuxEngineLauncher.buildBinds] 已经反向挂载了宿主根（/storage、/data/user），
 * 让宿主路径在容器里结构上成立 —— 那解决了「宿主 → guest」，解决不了
 * 「guest → 宿主」：容器里的 `/dsh` 是 bind 出来的，宿主上并没有这个目录，
 * 也无法创建。所以 guest 拼写必须翻译回宿主拼写（宿主任一模式都成立）。
 *
 * ## 映射表与 bind 表同源
 *
 * | guest | 宿主 |
 * |---|---|
 * | `/dsh` | `$DSH_HOME`（`files/dsh-home`） |
 * | `/ws` | 工作区根（`EngineConfig.workspaces`） |
 * | `/eng` | 引擎根（`EngineConfig.engineRoot`） |
 *
 * 纯函数（无 Android 依赖），便于在宿主 JVM 上直接断言 —— 见
 * `scripts/jvm/BehaviourTest.kt`。
 */
object GuestPaths {

    /** guest 前缀，与 [LinuxEngineLauncher.buildBinds] 一一对应。 */
    private val PREFIXES = listOf("/dsh", "/ws", "/eng")

    /**
     * 把 guest 路径翻译成宿主绝对路径；不是 guest 路径时返回 **null**。
     *
     * 注意「不是 guest 路径」与「翻译失败」是两件事：前者返回 null 由调用方
     * 走原有逻辑；后者（前缀对得上但映射缺失）在本函数里不会发生 —— 三个
     * 前缀都有确定目标。
     *
     * 只做前缀替换，不解析 `..`：注册表里的路径由引擎写入，不含相对段；
     * 真要规范化交给调用方的 `File(...).canonicalFile`。
     */
    fun toHost(path: String, dshHome: File, workspaces: File, engineRoot: File): String? {
        if (!path.startsWith("/")) return null
        for (guest in PREFIXES) {
            if (path == guest) {
                return target(guest, dshHome, workspaces, engineRoot)?.absolutePath
            }
            if (path.startsWith("$guest/")) {
                val base = target(guest, dshHome, workspaces, engineRoot) ?: return null
                val rest = path.removePrefix("$guest/")
                return if (rest.isEmpty()) base.absolutePath else File(base, rest).absolutePath
            }
        }
        return null
    }

    private fun target(guest: String, dshHome: File, workspaces: File, engineRoot: File): File? =
        when (guest) {
            "/dsh" -> dshHome
            "/ws" -> workspaces
            "/eng" -> engineRoot
            else -> null
        }

    /**
     * 该路径是不是 guest 拼写（只判前缀，不做存在性检查）。
     *
     * 供 UI/日志判断「这条记录是不是容器视角写下来的」，不参与路径翻译。
     */
    fun isGuest(path: String): Boolean =
        PREFIXES.any { path == it || path.startsWith("$it/") }
}
