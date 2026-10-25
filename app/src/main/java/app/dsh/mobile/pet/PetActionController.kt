package app.dsh.mobile.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.dsh.mobile.LocaleHelper
import app.dsh.mobile.R

/**
 * 把 dsh-pet-bridge 的事件翻译成「表情 + 气泡 + 工作状态档位」。
 *
 * 动画播放本身不再由这里负责 —— 交给 [PetAnimChain]，避免两套定时器互相打架：
 * 这里只声明"现在是什么工作状态"，链决定播哪一段、播完后怎么轮换/恢复。
 *
 * 时序规则（实测 dsh-pet-bridge 的行为）：
 *  - user/pre 连续到达时，以最新为准；
 *  - post 只代表「一步完成」，没有工具名，不能拼成「已读 xxx」；
 *  - stop 可能来自多个 agent，因此延迟 1.5s 再庆祝，给同回合后续事件留出窗口；
 *  - 引擎被杀时不会再有 stop，由 Service 每 30s 调 [onTick] 兜底回待机。
 */
class PetActionController(
    context: Context,
    private val packProvider: () -> PetPackConfig?,
    private val onWork: (PetAnimChain.WorkSlot?) -> Unit,
    private val onFace: (PetView.Face) -> Unit,
    private val onBubble: (String?) -> Unit,
) {
    private val appCtx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var localeCode = LocaleHelper.get(appCtx)
    private var localeCtx: Context = LocaleHelper.wrap(appCtx)

    private var turnActive = false
    private var current: PetView.Face = PetView.Face.IDLE
    private var lastEventAt = 0L
    private var pendingStop: Runnable? = null
    private var pendingAck: Runnable? = null

    fun start() {
        idle()
    }

    fun onEvent(event: PetEvent) {
        cancelPendingStop()
        cancelPendingAck()
        lastEventAt = System.currentTimeMillis()
        when (event.kind) {
            "user" -> {
                turnActive = true
                thinking()
            }
            "pre" -> {
                turnActive = true
                working(event)
            }
            "post" -> ack()
            "stop" -> {
                turnActive = false
                scheduleStop()
            }
            "error" -> {
                turnActive = false
                error()
            }
            "waiting" -> {
                face(PetView.Face.EXPLORING)
                work(PetAnimChain.WorkSlot.WAITING)
                bubble(str(R.string.pet_bubble_waiting))
            }
        }
    }

    /** 每 30s 由 Service 调用；引擎不在时把卡住的工作态收回待机。 */
    fun onTick(engineRunning: Boolean) {
        if (current == PetView.Face.IDLE) return
        val stale = System.currentTimeMillis() - lastEventAt > 15 * 60_000L
        if (!engineRunning || stale) {
            turnActive = false
            idle()
        }
    }

    /** 引擎回合是否进行中（漫游/碎碎念据此让路）。 */
    fun isTurnActive(): Boolean = turnActive

    fun reset() {
        cancelPendingStop()
        cancelPendingAck()
        turnActive = false
        idle()
    }

    // ------------------------------------------------------------ 状态

    private fun idle() {
        face(PetView.Face.IDLE)
        work(null)
        bubble(null)
    }

    private fun thinking() {
        face(PetView.Face.THINKING)
        work(PetAnimChain.WorkSlot.THINKING)
        bubble(str(R.string.pet_bubble_thinking))
    }

    private fun working(event: PetEvent) {
        val tool = event.toolName.orEmpty()
        if (isExplore(tool)) {
            face(PetView.Face.EXPLORING)
            work(PetAnimChain.WorkSlot.WAITING)
            bubble(toolBubble(tool, event.toolInputValue, R.string.pet_bubble_searching))
        } else {
            face(PetView.Face.WORKING)
            work(PetAnimChain.WorkSlot.WORKING)
            val res = when {
                isEdit(tool) -> R.string.pet_bubble_editing
                isCommand(tool) -> R.string.pet_bubble_running
                else -> R.string.pet_bubble_working
            }
            bubble(toolBubble(tool, event.toolInputValue, res))
        }
    }

    /** 「一步完成」：短暂切到整理的档位，之后自行回到工作 / 空闲。 */
    private fun ack() {
        face(PetView.Face.ACK)
        work(PetAnimChain.WorkSlot.RESULT)
        bubble(str(R.string.pet_bubble_step_done))
        cancelPendingAck()
        val r = Runnable {
            pendingAck = null
            if (turnActive) {
                face(PetView.Face.WORKING)
                work(PetAnimChain.WorkSlot.WORKING)
                bubble(str(R.string.pet_bubble_working))
            } else {
                idle()
            }
        }
        pendingAck = r
        handler.postDelayed(r, ACK_HOLD_MS)
    }

    private fun scheduleStop() {
        cancelPendingStop()
        val r = Runnable {
            pendingStop = null
            celebrate()
        }
        pendingStop = r
        handler.postDelayed(r, STOP_GRACE_MS)
    }

    private fun celebrate() {
        face(PetView.Face.CELEBRATE)
        // 终态：链会播一遍庆祝动画后自动收回随机链
        work(PetAnimChain.WorkSlot.SUCCESS)
        bubble(str(R.string.pet_bubble_done))
        cancelPendingStop()
        val r = Runnable {
            pendingStop = null
            idle()
        }
        pendingStop = r
        handler.postDelayed(r, CELEBRATE_HOLD_MS)
    }

    private fun error() {
        face(PetView.Face.ERROR)
        work(PetAnimChain.WorkSlot.ERROR)
        bubble(str(R.string.pet_bubble_error))
        cancelPendingStop()
        val r = Runnable {
            pendingStop = null
            idle()
        }
        pendingStop = r
        handler.postDelayed(r, ERROR_HOLD_MS)
    }

    // ------------------------------------------------------------ 输出

    private fun work(slot: PetAnimChain.WorkSlot?) {
        onWork(slot)
    }

    private fun face(face: PetView.Face) {
        current = face
        onFace(face)
    }

    private fun bubble(text: String?) {
        onBubble(text)
    }

    private fun str(id: Int): String = refreshLocale().getString(id)

    private fun str(id: Int, arg: String): String = refreshLocale().getString(id, arg)

    private fun toolBubble(tool: String, value: String?, resId: Int): String {
        if (value.isNullOrBlank()) return str(R.string.pet_bubble_working)
        val prefix = when {
            tool.startsWith("read") || tool.startsWith("list") || tool.startsWith("glob") ->
                R.string.pet_bubble_reading
            tool.startsWith("grep") || tool.startsWith("search") -> R.string.pet_bubble_searching
            isEdit(tool) -> R.string.pet_bubble_editing
            isCommand(tool) -> R.string.pet_bubble_running
            else -> resId
        }
        return str(prefix, value)
    }

    private fun refreshLocale(): Context {
        val code = LocaleHelper.get(appCtx)
        if (code != localeCode) {
            localeCode = code
            localeCtx = LocaleHelper.wrap(appCtx)
        }
        return localeCtx
    }

    private fun isExplore(tool: String): Boolean =
        tool.startsWith("read") || tool.startsWith("grep") || tool.startsWith("search") ||
            tool.startsWith("glob") || tool.startsWith("list")

    private fun isEdit(tool: String): Boolean =
        tool.startsWith("write") || tool.startsWith("edit") || tool.startsWith("insert") ||
            tool.startsWith("apply_patch")

    private fun isCommand(tool: String): Boolean =
        tool.startsWith("bash") || tool.startsWith("shell") || tool.startsWith("exec")

    private fun cancelPendingStop() {
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null
    }

    private fun cancelPendingAck() {
        pendingAck?.let { handler.removeCallbacks(it) }
        pendingAck = null
    }

    companion object {
        private const val ACK_HOLD_MS = 1_200L
        private const val STOP_GRACE_MS = 1_500L
        private const val CELEBRATE_HOLD_MS = 2_000L
        private const val ERROR_HOLD_MS = 2_500L
    }
}
