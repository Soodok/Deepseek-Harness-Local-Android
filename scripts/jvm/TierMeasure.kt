package jvmtest

import app.dsh.mobile.engine.MemoryEntry
import app.dsh.mobile.engine.MemoryIndex
import app.dsh.mobile.engine.MemoryStep

/**
 * 分档打分实测（**不是断言**，是把数字打出来给人看）。
 *
 * 为什么单独一个程序：`tier=high` 决定「记忆能不能自动重放」，而它同时受意图相似度、
 * 屏幕指纹、锚点三路影响。没有真实数字之前，任何「阈值该不该调」的讨论都是猜。
 * 这个程序把三路拆开，跑 4 类意图 × 3 种屏幕指纹，打印分数与分档。
 *
 * 跑法：`bash scripts/measure-tier.sh`（输出贴进 docs/reports/TIER_HASH.md）
 *
 * 用 hash 引擎（不依赖 llama-server）：这正是**没装 llama.cpp 扩展的用户**所处的状态。
 */
private const val PKG = "com.android.settings"

private val SCREEN = listOf(
    "Search settings", "Network & internet", "Connected devices", "Apps & notifications",
    "Battery", "Display", "Sound", "Storage", "Privacy", "pkg:" + PKG,
)

private fun entry(id: String, intent: String, keyword: String): MemoryEntry = MemoryEntry(
    id = id,
    pkg = PKG,
    intent = intent,
    keywords = listOf(keyword),
    title = intent,
    steps = listOf(
        MemoryStep(
            sourceIndex = 0, action = "tap", targetText = keyword,
            targetRole = "", targetViewId = "", targetBounds = emptyList(),
            args = emptyMap(), expectAfter = emptyList(), preFp = emptyList(),
        ),
    ),
    state = "active",
    createdAt = 1L,
    pending = false,
    sceneFp = SCREEN,
)

private fun pct(v: Double): String = String.format("%.2f", v)

private fun measure() {
    val entries = listOf(
        entry("m_display", "打开设置里的显示页", "Display"),
        entry("m_sound", "打开设置里的声音页", "Sound"),
        entry("m_battery", "打开设置里的电池页", "Battery"),
        entry("m_storage", "打开设置里的存储页", "Storage"),
    )

    val queries = listOf(
        "exact" to "打开设置里的显示页",
        "word-order" to "帮我把设置里的显示页打开",
        "synonym" to "调一下屏幕亮度",
        "unrelated" to "在微信里给妈妈发个红包",
    )

    val fpCases = listOf(
        "fp=1.0 (same screen)" to SCREEN.toSet(),
        "fp=0.5 (half the rows changed)" to (SCREEN.take(5) + listOf("pkg:" + PKG)).toSet(),
        "fp=0.0 (empty)" to emptySet<String>(),
    )

    println("== MemoryIndex 分档实测（hash 引擎，无 bge）==")
    println("权重 W_COS=0.6 W_FP=0.3 W_ANCHOR=0.1 · 阈值 high>=0.85 mid>=0.65")
    println()
    for ((fpName, fp) in fpCases) {
        println("-- " + fpName + " --")
        println(String.format("%-12s %-24s %6s %6s %6s %6s %5s", "case", "top-1 intent", "cos", "fp", "anchor", "score", "tier"))
        for ((name, q) in queries) {
            val ranked = MemoryIndex.rank(entries, q, fp, setOf("Display"), 1)
            val top = ranked.firstOrNull()
            if (top == null) {
                println(String.format("%-12s %-24s %6s %6s %6s %6s %5s", name, "(none)", "-", "-", "-", "-", "-"))
                continue
            }
            println(
                String.format(
                    "%-12s %-24s %6s %6s %6s %6s %5s",
                    name, top.entry.intent, pct(top.cos), pct(top.fp),
                    if (top.anchor) "1" else "0", pct(top.score), top.tier,
                ),
            )
        }
        println()
    }

    println("== 解读 ==")
    println("· 同句 + 同屏：score≈1.00 → high（自动重放）")
    println("· 词面改写 + 同屏：cos 由哈希 bigram 决定，0.6*cos+0.3*1.0+0.1 ≈ 0.65-0.75 → 最多 mid")
    println("· 语义改写（无共同字词）：hash cos≈0 → 即便屏幕完全一致也只有 0.40 → low")
    println("· 结论：没有 bge 时，high 基本只有「意图一字不差」才拿得到")
}

/**
 * 入口必须是 object 里的 main：顶层 fun main() 生成的是 TierMeasureKt 类，
 * 而运行脚本按 jvmtest.<JVM_MAIN> 找类，会报「找不到或无法加载主类」。
 */
object TierMeasure {
    @JvmStatic
    fun main(args: Array<String>) {
        measure()
    }
}
