package app.dsh.mobile.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import kotlin.random.Random

/**
 * 碎碎念触发器：按情境从台词库挑一句，经 20s 全局冷却后交给气泡显示。
 *
 * 情境（对应 PetLines 的分类）：
 *  - 开机 [onBoot]：按当前时段优先 morning / evening / late_night，没有就 boot；
 *  - 被连点 [onTap]：4s 内第 1 次点击说 tap，累计 3 次以上说 tap_many；
 *  - 被甩 [onFling]：松手速度超过甩抛阈值；
 *  - 贴边 [onEdge]：拖到/停到屏幕左右边缘；
 *  - 早晚深夜 [onTick]：时段切换时说一句；
 *  - 久置 [onTick]：10 分钟没有任何交互说一句 idle_long；
 *  - random [onTick]：闲置超过 5 分钟时 30% 概率乱弹一句。
 *
 * 冷却：所有台词共用 20s；冷却期内触发一律静默丢弃（不排队，避免刷屏）。
 * 台词来源全部走 [PetLines]，用户改过的分类优先。
 */
class PetChatter(
    context: Context,
    private val say: (String) -> Unit,
) {
    private val appCtx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var lastLineAt = 0L
    private var lastBand: Band? = null
    private var lastInteractionAt = System.currentTimeMillis()
    private var idleLineSaid = false
    private var randomLineAt = 0L
    private var tapStreak = 0
    private var tapStreakAt = 0L

    private enum class Band(val id: String) {
        MORNING("morning"), EVENING("evening"), LATE_NIGHT("late_night"), DAY("day");

        companion object {
            fun of(hour: Int): Band = when (hour) {
                in 5..10 -> MORNING
                in 17..22 -> EVENING
                else -> if (hour >= 23 || hour <= 4) LATE_NIGHT else DAY
            }
        }
    }

    /** 服务启动 / 桌宠重新挂载：开机问候（时段优先）。 */
    fun onBoot() {
        lastInteractionAt = System.currentTimeMillis()
        val band = Band.of(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        lastBand = band
        sayFrom(when (band) {
            Band.MORNING -> "morning"
            Band.EVENING -> "evening"
            Band.LATE_NIGHT -> "late_night"
            Band.DAY -> "boot"
        })
    }

    /** 单次点击：4s 窗口内累计，1 次说 tap，3 次以上说 tap_many。 */
    fun onTap() {
        lastInteractionAt = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        tapStreak = if (now - tapStreakAt > TAP_STREAK_MS) 1 else tapStreak + 1
        tapStreakAt = now
        if (tapStreak >= 3) {
            sayFrom("tap_many")
        } else if (tapStreak == 1) {
            sayFrom("tap")
        }
    }

    /** 被甩出去。 */
    fun onFling() {
        lastInteractionAt = System.currentTimeMillis()
        sayFrom("fling")
    }

    /** 贴到屏幕边缘。 */
    fun onEdge() {
        lastInteractionAt = System.currentTimeMillis()
        sayFrom("edge")
    }

    /** 贴边缩进后被人碰出来。 */
    fun onPeeked() {
        lastInteractionAt = System.currentTimeMillis()
        sayFrom("edge")
    }

    /** 引擎进入工作回合时偶尔插一句（由 Service 在事件回调里触发）。 */
    fun onWorking() {
        lastInteractionAt = System.currentTimeMillis()
        if (Random.nextInt(100) < 25) sayFrom("working")
    }

    /**
     * 每 30s 一次的巡检：时段切换、久置、乱弹。
     * 都受 20s 冷却约束，不会连续刷。
     */
    fun onTick() {
        val now = System.currentTimeMillis()
        val band = Band.of(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        if (band != lastBand) {
            val changed = lastBand != null
            lastBand = band
            if (changed) {
                when (band) {
                    Band.MORNING -> sayFrom("morning")
                    Band.EVENING -> sayFrom("evening")
                    Band.LATE_NIGHT -> sayFrom("late_night")
                    Band.DAY -> {}
                }
                return
            }
        }
        if (now - lastInteractionAt > IDLE_LONG_MS && !idleLineSaid) {
            idleLineSaid = true
            sayFrom("idle_long")
            return
        }
        if (now - lastInteractionAt > RANDOM_IDLE_MS &&
            now - randomLineAt > RANDOM_GAP_MS &&
            Random.nextInt(100) < 30
        ) {
            randomLineAt = now
            sayFrom("random")
        }
    }

    /** 用户有了新交互（点击/拖拽/菜单），重置久置标记。 */
    fun onInteracted() {
        lastInteractionAt = System.currentTimeMillis()
        idleLineSaid = false
    }

    private fun sayFrom(id: String) {
        val now = System.currentTimeMillis()
        if (now - lastLineAt < COOLDOWN_MS) return
        val pool = PetLines.lines(appCtx, id)
        if (pool.isEmpty()) return
        lastLineAt = now
        val line = pool[Random.nextInt(pool.size)]
        handler.post { say(line) }
    }

    companion object {
        private const val COOLDOWN_MS = 20_000L
        private const val TAP_STREAK_MS = 4_000L
        private const val IDLE_LONG_MS = 10 * 60_000L
        private const val RANDOM_IDLE_MS = 5 * 60_000L
        private const val RANDOM_GAP_MS = 8 * 60_000L
    }
}
