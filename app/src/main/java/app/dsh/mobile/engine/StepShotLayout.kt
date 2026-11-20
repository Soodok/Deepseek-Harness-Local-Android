package app.dsh.mobile.engine

/**
 * 逐步截图（记忆的「当时长什么样」证据）—— **纯逻辑层**。
 *
 * ## 为什么单独一层
 *
 * 这一层只做三件事，全部不碰 Bitmap / Canvas / 文件：
 *
 * 1. **侧车编解码**：录制时截到的缩略图记在 `<traceId>.shots` 里（一行一张）。
 * 2. **剪枝映射**：轨迹被剪掉若干步后，哪些图该跟着留下。
 * 3. **拼接布局**：N 张缩略图在一张画布上怎么摆。
 *
 * 读方（`StepShots`）负责真正的像素活。分开的理由与 `TraceModel` / `TraceCodec`
 * 同一套：宿主 JVM 上没有 `android.graphics`，混在一起就只能「源码 grep 式断言」，
 * 而这类断言恰恰测不出「剪枝后图片错位」这种真缺陷。
 *
 * ## 与轨迹契约的关系
 *
 * 图片不进轨迹 JSONL 本身，而是**并列的侧车文件**。原因有两条：
 *
 * - **写入时机不同**：轨迹行在动作完成后立即写（进程被杀也不丢），而截图要走
 *   `takeScreenshot`，有 100–400ms 延迟，还常常撞系统节流。等图再写行，等于把
 *   录制开销挂到动作路径上（当前 +40ms/动作 已经是待优化的头号负担）。
 * - **不改写已落盘的行**：追加写的 JSONL 一旦回头改某行，崩溃时就可能留下半行。
 *   侧车另开一个文件，主文件的语义完全不变，老读者（含探针与 fixture）零影响。
 *
 * 侧车格式（TSV，容忍缺列，读方遇坏行直接跳过）：
 *
 * ```
 * -1	320	691	S	3	start.webp
 * 0	320	691	0	3	0.webp
 * ```
 *
 * 列依次为 `stepIndex / w / h / label / bytes / fileName`。`stepIndex = -1` 是任务
 * 起手那一屏（第一步的「之前」），label 用 `S`；其余用数字。label 只用于画在拼图
 * 每格的角标上，所以刻意全 ASCII —— Kotlin 侧不许出现中文字面量（i18n D 段门禁）。
 */
object StepShotLayout {

    /** 侧车文件后缀。轨迹是 `<traceId>.jsonl`，侧车是 `<traceId>.shots`。 */
    const val SIDECAR_EXT = ".shots"

    /** 任务起手那一屏的索引。负数不会与真实步骤索引（从 0 开始）冲突。 */
    const val START_INDEX = -1

    /** 起手那一屏的角标文字。 */
    const val START_LABEL = "S"

    /** 一张缩略图的侧车记录。 */
    data class Thumb(
        val stepIndex: Int,
        val w: Int,
        val h: Int,
        val fileName: String,
        val bytes: Long = 0,
    ) {
        fun label(): String =
            if (stepIndex == START_INDEX) START_LABEL else stepIndex.toString()

        /** 宽高比；脏数据（0 或负）返回 0，调用方据此退化成方图而不是算出 NaN。 */
        fun aspect(): Float =
            if (w > 0 && h > 0) h.toFloat() / w.toFloat() else 0f

        fun isValid(): Boolean = fileName.isNotBlank() && w > 0 && h > 0
    }

    /** 编码一行。字段里不可能出现制表符（文件名由我们自己生成），所以不做转义。 */
    fun encodeLine(t: Thumb): String =
        listOf(
            t.stepIndex.toString(),
            t.w.toString(),
            t.h.toString(),
            t.label(),
            t.bytes.toString(),
            t.fileName,
        ).joinToString("\t")

    /**
     * 解一行。任何异常都返回 null。
     *
     * 容忍度是刻意的：侧车是旁路证据，坏一行不该让整条记忆读不出来。
     * 旧版本侧车可能只有 `<index>\t<file>` 两列 —— 那种行按「尺寸未知」接受，
     * 拼接时退化成方图（总比丢掉整条拼图好）。
     */
    fun decodeLine(line: String): Thumb? {
        val raw = line.trim()
        if (raw.isEmpty()) return null
        val parts = raw.split('\t')
        if (parts.size < 2) return null
        val idx = parts[0].toIntOrNull() ?: return null
        // 两列形态：index \t file
        if (parts.size == 2) {
            val name = parts[1].trim()
            if (name.isEmpty()) return null
            return Thumb(idx, 0, 0, name)
        }
        val w = parts[1].toIntOrNull() ?: 0
        val h = parts[2].toIntOrNull() ?: 0
        // 六列形态：第 5 列是 bytes、第 6 列是文件名
        val bytes = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        val name = parts.getOrNull(5)?.trim().orEmpty()
        if (name.isEmpty()) return null
        return Thumb(idx, w, h, name, bytes)
    }

    /**
     * 解析整份侧车内容。同一 stepIndex 出现多次时**保留最后一条** ——
     * 任务中途重试可能对同一步截到两张，后截到的那张才是最终界面。
     */
    fun parseAll(text: String): List<Thumb> {
        val byIndex = LinkedHashMap<Int, Thumb>()
        for (line in text.split('\n')) {
            val t = decodeLine(line) ?: continue
            byIndex[t.stepIndex] = t
        }
        return byIndex.values.toList()
    }

    /**
     * 剪枝后该保留哪些图，按展示顺序返回。
     *
     * [keptIndices] 是剪枝后各步的 `sourceIndex`（原始顺序），顺序即重放顺序。
     * 起手那一屏（[START_INDEX]）若存在**永远排第一** —— 它是「这条记忆从哪个界面
     * 开始」的唯一视觉证据，剪枝删掉的都是动作，不是起点。
     *
     * 去重：同一 index 只留一次（`mapIndexed` 后可能出现重复，防御性处理）。
     */
    fun pickForKept(shots: List<Thumb>, keptIndices: List<Int>): List<Thumb> {
        if (shots.isEmpty()) return emptyList()
        val byIndex = HashMap<Int, Thumb>(shots.size * 2)
        for (t in shots) byIndex[t.stepIndex] = t
        val out = ArrayList<Thumb>(keptIndices.size + 1)
        byIndex[START_INDEX]?.let { out.add(it) }
        val seen = HashSet<Int>()
        for (i in keptIndices) {
            if (i == START_INDEX) continue
            if (!seen.add(i)) continue
            byIndex[i]?.let { out.add(it) }
        }
        return out
    }

    /** 拼接画布上的一格（像素坐标，已含角标区）。 */
    data class Cell(
        /** 图片区的左上角（角标在它上方）。 */
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        /** 角标基线 y（文字从这条基线往上画）。 */
        val labelY: Int,
    )

    /**
     * 一张拼接图的布局。
     *
     * [cells] 与输入顺序一一对应；`canvasW/H` 是画布尺寸。
     */
    data class Plan(
        val cols: Int,
        val rows: Int,
        val canvasW: Int,
        val canvasH: Int,
        val cells: List<Cell>,
        val cellW: Int,
        val cellH: Int,
        val labelH: Int,
        val gap: Int,
    )

    /**
     * 算布局。
     *
     * 列数取舍：1–3 张排一行（少留空白），4 张起固定 4 列。
     * 4 列 × 200px ≈ 820px 宽，在手机上与一屏信息量相当；再多列，
     * 单格会窄到认不出界面（那这张拼图就失去意义了）。
     *
     * @param n 张数；<=0 返回空布局（画布 0×0，调用方不要建位图）
     * @param aspect 单张的高宽比（h/w）；<=0 时按 2.0 估（现代手机大致如此）
     */
    fun plan(
        n: Int,
        cellW: Int = 200,
        aspect: Float = 0f,
        gap: Int = 8,
        labelH: Int = 22,
        maxCols: Int = 4,
    ): Plan {
        if (n <= 0 || cellW <= 0) {
            return Plan(0, 0, 0, 0, emptyList(), cellW, 0, labelH, gap)
        }
        val a = if (aspect > 0f) aspect else 2.0f
        val cellH = (cellW * a).toInt().coerceAtLeast(1)
        val cols = if (n <= 3) n else minOf(n, maxCols)
        val rows = (n + cols - 1) / cols
        val canvasW = cols * cellW + (cols + 1) * gap
        val canvasH = rows * (cellH + labelH) + (rows + 1) * gap
        val cells = ArrayList<Cell>(n)
        for (i in 0 until n) {
            val c = i % cols
            val r = i / cols
            val x = gap + c * (cellW + gap)
            val bandTop = gap + r * (cellH + labelH + gap)
            val imgTop = bandTop + labelH
            cells.add(Cell(x, imgTop, cellW, cellH, imgTop - 5))
        }
        return Plan(cols, rows, canvasW, canvasH, cells, cellW, cellH, labelH, gap)
    }

    /**
     * 一张图在格子里按等比缩放后的目标尺寸（居中留黑边）。
     *
     * 为什么要这一步：横竖屏混录、或某次截图尺寸不同（分屏、折叠屏展开）时，
     * 直接拉伸会把界面拉变形，AI 和人都会认错控件位置。宁可留边。
     */
    fun fitInto(srcW: Int, srcH: Int, boxW: Int, boxH: Int): IntArray {
        if (srcW <= 0 || srcH <= 0 || boxW <= 0 || boxH <= 0) return intArrayOf(boxW, boxH, 0, 0)
        val scale = minOf(boxW.toFloat() / srcW, boxH.toFloat() / srcH)
        val w = (srcW * scale).toInt().coerceAtLeast(1)
        val h = (srcH * scale).toInt().coerceAtLeast(1)
        return intArrayOf(w, h, (boxW - w) / 2, (boxH - h) / 2)
    }

    /**
     * 估算这份侧车值不值得留（给预算淘汰用）。
     *
     * 不精确也没关系：真正的淘汰按目录实际字节数做，这里只在**入库前**判断
     * 「这条记忆的图会不会大得离谱」（比如某人录了 24 步）。
     */
    fun estimateBytes(shots: List<Thumb>): Long = shots.sumOf { if (it.bytes > 0) it.bytes else 12_000L }
}
