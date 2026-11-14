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

    /** 按钮上那一小段；空输入返回 "unknown"。 */
    fun summarize(msg: String): String {
        val text = msg.replace("\r\n", "\n").trim()
        if (text.isEmpty()) return "unknown"
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
            .lastOrNull { it.isNotEmpty() && !it.startsWith("×") }
        if (!cause.isNullOrEmpty()) return cause.take(60)
        // ④ 首行有效文本（跳过 Error: / 空行 / × 开头这类无信息行）
        return body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != "Error:" && !it.startsWith("×") }
            ?.take(60)
            ?: body.take(60).ifBlank { text.take(60) }
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
        .filterNot { line ->
            val s = line.trim()
            Regex("^file://\\S+$").matches(s) &&
                !s.contains("rror") &&
                !s.contains("ERR_")
        }
        .joinToString("\n")
        .ifBlank { text }
}
