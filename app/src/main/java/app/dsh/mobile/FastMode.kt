package app.dsh.mobile

import java.io.File

/**
 * `fast_mode` 标记文件的读写（v1.7.0）。
 *
 * 背景：`fast_mode` 一直是**模型自己决定**要不要开的工具（assets/android-plugins/
 * fast-mode.mjs）。实测它不会主动调 —— 每一轮无障碍操作都在付思考成本。
 * 现在改成由「行动边界」驱动：第一个动作被放行时打开，任务收尾/叫停/空闲时关闭。
 *
 * 契约与插件保持一致，否则两边会打架：
 *  - 文件名 `fast-mode`，内容任意（插件写 Date.now()，这里写同样的时间戳）；
 *  - 插件用 **mtime** 算 30 分钟 TTL，所以这里的写入天然刷新 TTL；
 *  - 插件在读的时候发现过期会删掉它，所以这里不能假设「文件在 = 开着」。
 *
 * 这个类是纯 Kotlin（不碰 Android API），可以直接在 JVM 上断言。
 */
object FastMode {
    /** 与 fast-mode.mjs 的 MARKER_NAME 一致。 */
    const val MARKER_NAME = "fast-mode"

    /** 与 fast-mode.mjs 的 TTL_MS 一致：防「开了忘了关」。 */
    const val TTL_MS = 30L * 60L * 1000L

    /**
     * 标记文件。`home` 是引擎的 $DSH_HOME（EngineConfig 里叫 dsh-home）。
     * 传 null 或空路径时返回 null —— 调用方据此判断「没有可写的家目录」。
     */
    fun marker(home: File?): File? {
        if (home == null) return null
        if (home.path.isBlank()) return null
        return File(home, MARKER_NAME)
    }

    /**
     * 现在是否真的开着。
     *
     * 只看 mtime 是否在 TTL 内 —— **不删过期文件**：删除是插件的职责，
     * 这里每步都会被调一次，多做一次写操作没有收益。
     */
    fun isOn(home: File?, now: Long = System.currentTimeMillis()): Boolean {
        val f = marker(home) ?: return false
        val t = runCatching { f.lastModified() }.getOrDefault(0L)
        if (t <= 0L) return false
        return now - t <= TTL_MS
    }

    /**
     * 打开。已经开着就**不重写**（每步都碰磁盘没意义），
     * 只有真写成功才返回 true —— 调用方据此记住「是我开的」，
     * 收尾时才敢去关，否则会关掉用户或模型自己开的 fast_mode。
     */
    fun on(home: File?, now: Long = System.currentTimeMillis()): Boolean {
        val f = marker(home) ?: return false
        if (isOn(home, now)) return true
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(now.toString())
            true
        }.getOrDefault(false)
    }

    /** 关闭。文件本来就不在也算成功（幂等）。 */
    fun off(home: File?): Boolean {
        val f = marker(home) ?: return false
        return runCatching { !f.exists() || f.delete() }.getOrDefault(false)
    }
}