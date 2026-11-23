package app.dsh.mobile.engine

/**
 * 把 pnpm / 引擎的失败原文压成**按钮上那一小段**（纯 Kotlin，无 Android 依赖）。
 *
 * 抽成独立对象而不是留在 Activity 里，是因为这段逻辑决定「用户截图能不能
 * 提供线索」——它必须能被 JVM 单测直接验证（见 InstallErrorSummaryTest）。
 * 留在 Activity 私有方法里只能靠 py 探针看字符串，验证不到真实输出。
 *
 * ## 历史（每一步都是真机反馈逼出来的，不是设计出来的）
 *
 * v1.4.68：msg.take(28) 把 Error: ERR_PNPM_PACKAGE_MANA... 砍成碎片。
 * v1.5.0-beta.6：改成「错误码优先」，因为 pnpm 输出是
 *     Error: ERR_PNPM_XXX
 *     （空行）
 *       × adding a new package
 *       ╰─▶ <真正原因>
 * 前 28 字符恰好停在 Error: 与空行之间，所有失败长得一模一样。
 * v1.7.31：用户 2026-11-14 的截图里按钮上**只有一串 file:// 路径**——
 * 因为 Node 对未被捕获的 ESM 异常，输出第一行是模块 URL：
 *     file:///data/.../engine/lib/node_modules/corepack/dist/pnpm.js:123
 *     throw new Error(...)
 *     ^
 *     Error: <真正原因>
 * 旧的第 ④ 步兜底取「首行有效文本」，正好取中这串 URL，真因整行不可见。
 * 现在先剔掉这类纯位置行，让后面的错误行有机会被选中。
 */
object InstallErrorSummary {

    /** 按钮上的兜底词：没有任何可判别文本时用它（不是错误码，不参与 ① 的匹配）。 */
    const val UNKNOWN = "unknown"

    /** 按钮上那一小段；空输入返回 [UNKNOWN]。 */
    fun summarize(msg: String): String {
        val text = msg.replace("\r\n", "\n").trim()
        if (text.isEmpty()) return UNKNOWN
        val body = stripLocationLines(text)
        // ① 错误码：pnpm 的 ERR_PNPM_*、Node 的 ENOENT/ENOTFOUND 等
        Regex("[A-Z][A-Z0-9_]{6,}").find(body)?.let { return it.value.take(40) }
        // ② 上游给的诊断/拒绝行
        body.lineSequence().firstOrNull { it.trimStart().startsWith("dsh:") }?.let {
            return it.trim().take(60)
        }
        // ③ 原因链最后一节（「╰─▶」之后才是真正的根因）
        val cause = body.lineSequence()
            .map { it.substringAfter("▶", "").trim() }
            .lastOrNull { it.isNotEmpty() && !it.startsWith("×") && !isLocationOnly(it) }
        if (!cause.isNullOrEmpty()) return cause.take(60)
        // ④ 首行有效文本（跳过 Error: / 空行 / × 开头这类无信息行）
        body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != "Error:" && !it.startsWith("×") && !isLocationOnly(it) }
            ?.let { return it.take(60) }
        // ⑤ 原文里只剩位置行（v1.7.32）—— 摘要永远不返回裸路径。
        //
        // 到这一步说明 msg 整份就是一个或几个 file:// 路径：典型形态是 Node 的
        // ESM/CJS 栈只剩一行模块 URL，真正的错误行已被上游裁掉。旧实现这里走
        // body.take(60)，而 stripLocationLines 在「全都被剔光」时 ifBlank 回退原文，
        // 于是 take(60) 正好截出一段路径：
        //     file:///data/user/0/app.dsh.mobile/files/engine/lib/node_mod
        // 用户截图里那行就是这个（恰 60 字符、末尾断在 node_mod）。
        //
        // 退路分两种，都不是那段路径：
        //  · 路径带 `:行号`（`.../pnpm.js:4127`）→ **只留文件名:行号**。
        //    它对用户仍然不是「原因」，但对排查有决定作用（哪个文件崩的），
        //    而且不会把整串 /data/... 塞进按钮；
        //  · 路径被上游截断到没有行号（`.../node_mod`）→ 认输返回 unknown，
        //    完整原文仍可从长按弹窗看到。宁可承认不知道，也不摆一串无法行动的路径。
        val loc = text.lineSequence().map { it.trim() }.firstOrNull { isLocationOnly(it) }
        val locTail = loc?.substringAfterLast('/')?.trim().orEmpty()
        val hasLineNumber = locTail.count { it == ':' } >= 1 &&
            locTail.substringAfterLast(':').isNotEmpty() &&
            locTail.substringAfterLast(':').all { it.isDigit() }
        return if (hasLineNumber) locTail.take(60) else UNKNOWN
    }

    /**
     * 剔掉「纯位置行」：整行就是一个 file:// URL（可带 :行:列 后缀），
     * 且行内没有 Error / ERR_ 这类错误词。
     *
     * 为什么限定这么死：Node 的栈头、ESM 加载失败、pnpm 的 lifecycle 报错
     * 都会先打一行模块 URL；而**真正的错误行**同样可能提到 file://（例如
     * Cannot find module 'file:///...'），那种行必须留下。判据宽一点就会
     * 把线索一起删掉——那正是这次要修的那个问题的反面。
     */
    internal fun stripLocationLines(text: String): String = text.lineSequence()
        .filterNot { isLocationOnly(it.trim()) }
        .joinToString("\n")
        .ifBlank { text }

    /**
     * 这一行是不是**只有位置**、没有任何可读原因。
     *
     * 两种形态都算：
     *  · `file:///abs/path/x.js:12:3` —— Node 的 ESM 栈头；
     *  · `/abs/path/x.js:12`       —— Node 的 CJS 栈头（无 file:// 前缀）。
     *
     * 三个必须同时成立的限定（宽一条就会把线索删掉）：
     *  ① 整行就是一条路径（不含空格，路径里没有空格才成立 —— 真遇到带空格的
     *     路径，宁可留着让它显示，也不能误删）；
     *  ② 行内没有 `rror` / `ERR_`（真正的错误行常写成
     *     `Cannot find module 'file:///...'`，那种必须留下）；
     *  ③ 不是 `dsh:` 诊断行（那是上游给的可读原因）。
     */
    internal fun isLocationOnly(line: String): Boolean {
        if (line.isEmpty()) return false
        if (line.contains(' ')) return false
        if (line.contains("rror") || line.contains("ERR_")) return false
        if (line.startsWith("dsh:")) return false
        if (line.startsWith("file://")) return true
        // 绝对路径：必须以 / 开头且至少再有一个字符，避免把单独的 "/" 当位置
        return line.length > 1 && line.startsWith("/")
    }
}
