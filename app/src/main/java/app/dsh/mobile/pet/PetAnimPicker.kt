package app.dsh.mobile.pet

import kotlin.random.Random

/**
 * 动画选取：桌面版 dsh-pet `src/shared/pickers.ts` 的 Kotlin 移植。
 *
 * 全部是无副作用的纯函数，便于单独验证。两条设计原则与上游一致：
 *  1) **排除当前动画**：new 尽量不重复正在播的那一段；
 *  2) **宁可重复也不要无解**：排除后池为空就退回原池，绝不返回 null（上游原话
 *     "宁可重复，也不要返回 undefined"）。
 */
object PetAnimPicker {

    /** 顶层掷骰的结果。 */
    enum class Kind { IDLE, TURN, MOVE, ACTION }

    /**
     * 从池里等概率抽一个，[exclude] 排除某个值。
     * 排除后池空（典型：单元素池恰好就是当前动画）→ 退回原池。
     */
    fun <T> pick(pool: List<T>, exclude: T? = null): T? {
        if (pool.isEmpty()) return null
        val entries = if (exclude != null) pool.filter { it != exclude } else pool
        val src = entries.ifEmpty { pool }
        return src[Random.nextInt(src.size)]
    }

    /** 槽位内的抽取（同样避开当前）。 */
    fun pickSlot(slot: List<String>, exclude: String? = null): String? = pick(slot, exclude)

    /**
     * 顶层掷骰：idle / turn / move 之外的概率全部归入 action（随即二级选取）。
     * [fixed]（宠物钉住）时 turn/move 权重按 0 算，份额自然落到 idle 与动作。
     */
    fun rollKind(roll: Double, w: PetAnimWeights, fixed: Boolean = false): Kind {
        val turn = if (fixed) 0 else w.turn
        val move = if (fixed) 0 else w.move
        if (roll < w.idle / 100.0) return Kind.IDLE
        if (roll < (w.idle + turn) / 100.0) return Kind.TURN
        if (roll < (w.idle + turn + move) / 100.0) return Kind.MOVE
        return Kind.ACTION
    }

    /**
     * 按分类权重挑一个分类；[facingRight] 时过滤掉 noMirror 分类（文字会镜像）。
     * 若过滤后为空则退回未过滤集合 —— 与上游一致，此时仍会选中它。
     */
    fun pickWeightedCategory(
        categories: List<PetAnimCategory>,
        /** 渲染时是否已水平镜像（素材本身就朝另一侧）。镜像时不选 noMirror 分类。 */
        mirrored: Boolean,
    ): PetAnimCategory? {
        val cats = categories.filter { it.actions.isNotEmpty() }
        if (cats.isEmpty()) return null
        val filtered = cats.filter { !(it.noMirror && mirrored) }
        val eligible = filtered.ifEmpty { cats }
        val totalW = eligible.sumOf { it.weight }.coerceAtLeast(1)
        var t = Random.nextDouble() * totalW
        for (c in eligible) {
            t -= c.weight
            if (t <= 0) return c
        }
        return eligible.last()
    }

    /** 二级选取结果：分类 id + 具体动作名。分类内动作**等概率**。 */
    data class CategoryAction(val categoryId: String, val slug: String)

    /**
     * 随机动作：先挑分类（加权）再挑分类内动作（等概率），避开 [current]。
     * 没有可用分类时回落到 idle 池。
     */
    fun pickCategoryAction(
        categories: List<PetAnimCategory>,
        idlePool: List<String>,
        mirrored: Boolean,
        current: String? = null,
    ): CategoryAction? {
        val cat = pickWeightedCategory(categories, mirrored)
        if (cat == null) {
            val fallback = pick(idlePool, current) ?: return null
            return CategoryAction("idle", fallback)
        }
        val slug = pick(cat.actions, current) ?: return null
        return CategoryAction(cat.id, slug)
    }

    /**
     * 工作状态多候选轮换：当前动画属于某个多候选槽位时，返回该槽位内的另一个候选。
     * 返回 null 表示"不需要轮换"（单候选槽位，或当前不在任何槽位里）。
     */
    fun nextWorkStatusAnim(pool: List<List<String>>, current: String): String? {
        val slot = pool.firstOrNull { current in it } ?: return null
        if (slot.size <= 1) return null
        return pickSlot(slot, current)
    }

    /** 当前动画是否在某个工作状态槽位里。 */
    fun inWorkStatus(pool: List<List<String>>, slug: String): Boolean =
        pool.any { slug in it }

    /**
     * 工作状态恢复用：取指定槽位的一个候选（避开 [current]）。
     * 多候选槽位返回 true 表示"应播一遍后轮换"（由调用方据此决定是否 once）。
     */
    fun resumeWorkStatus(pool: List<List<String>>, index: Int, current: String?): String? {
        val slot = pool.getOrNull(index) ?: return null
        return pickSlot(slot, current)
    }
}
