package app.dsh.mobile.engine

/**
 * 轨迹保留策略（TTL 7 天 + 最多 200 个文件）—— **纯函数，可 JVM 单测**。
 *
 * ## 为什么把决策从 TaskRecorder 里抽出来
 * [TaskRecorder] 依赖 Android 的 Context / Log 与 org.json，宿主机 JVM 根本编不过；
 * 而「该删哪些文件」恰恰是最容易写错、也最值得钉死边界的那部分：
 * 差一天就删掉用户还在用的轨迹，差一个文件就淘汰掉最新的记录。
 * 抽成不碰 IO 的纯函数后，淘汰规则能在 `scripts/jvm/BehaviourTest.kt` 里逐条断言，
 * 真正落盘的那层只剩「拿到名单 → delete()」，没有分支就没有 bug。
 *
 * ## 规则（契约 docs/A11Y_TRACE_CONTRACT.md §1）
 * 1. **TTL**：`startedAt < now - 7d` 才删。恰好 7 天整**不删** —— 删早了没有任何收益，
 *    删晚了只是多留一条，边界必须站在「不误删」那一侧。
 * 2. **容量**：先扣掉已过期的，剩下的仍超过 [TraceContract.MAX_FILES] 时，
 *    按 `startedAt` 升序淘汰最旧的，**保留最近的 200 个**（不是「删最新的」）。
 * 3. **确定性**：同一毫秒开始的多个文件用文件名做次级排序，同样的输入永远得到同样的名单 ——
 *    否则断言会随文件系统枚举顺序飘，门禁就成了掷骰子。
 *
 * 这个对象只做**决策**，不删任何东西：删文件是调用方（[TaskRecorder.pruneOld]）的事，
 * 这样策略可以单独跑、也可以被 /trace 端点复用来「先看一眼会删哪些」。
 */
object TraceRetention {

    /**
     * 单个文件是否已过期。
     *
     * 用严格小于：`startedAt == now - TTL` 表示「刚好活满 7 天」，仍算有效 ——
     * 边界上的文件留一天没有任何代价，误删一次就少了一条可重剪的原始轨迹。
     */
    fun isExpired(startedAt: Long, now: Long): Boolean =
        startedAt < now - TraceContract.TTL_MS

    /**
     * 给出该删的文件名。
     *
     * @param files 文件名 → startedAt（epoch ms）。调用方只应传 `.jsonl` 轨迹文件；
     *              读不出头行的文件请用 `lastModified()` 兜底，免得一个坏文件永久留存。
     * @param now 当前时刻（epoch ms）—— 显式传入而不是内部取时间，测试才能钉边界。
     * @return 该删的文件名。先按 TTL（最旧在前），再按容量（最旧在前）；同一个名字只出现一次。
     */
    fun toDelete(files: List<Pair<String, Long>>, now: Long): List<String> {
        if (files.isEmpty()) return emptyList()

        // 先去重：调用方可能把头行扫描与其它来源各塞了一次同一个文件。
        // 同名的多个 startedAt 取**最大**（最保守）的那个，宁可判它没过期也不误删。
        val byName = LinkedHashMap<String, Long>(files.size)
        for ((name, startedAt) in files) {
            val seen = byName[name]
            if (seen == null || startedAt > seen) byName[name] = startedAt
        }

        // 一次排序同时服务两条规则：升序 = 最旧在前。
        val sorted = byName.entries.sortedWith(compareBy({ it.value }, { it.key }))
        val expired = sorted.filter { isExpired(it.value, now) }
        val alive = sorted.filterNot { isExpired(it.value, now) }

        // 容量只对「还活着」的那批生效：已经因 TTL 被删的不该再占名额，
        // 否则过期文件会把配额也吃掉，导致同一次里多删一批本来该留的。
        val overflow = if (alive.size > TraceContract.MAX_FILES) {
            alive.subList(0, alive.size - TraceContract.MAX_FILES)
        } else {
            emptyList()
        }

        return (expired + overflow).map { it.key }
    }
}
