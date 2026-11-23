package app.dsh.mobile.ui

import app.dsh.mobile.engine.LinuxRootfsCatalog
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import app.dsh.mobile.R
import app.dsh.mobile.engine.BootTrace
import app.dsh.mobile.engine.EngineModeSelector
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.util.Locale

/**
 * 启动遮罩的渲染与生命周期（v1.6.19）。
 *
 * ## 从 MainActivity 抽出来的原因
 *
 * 旧实现在 Activity 里堆了 15 个 splash 字段、三套渲染路径（ticker / 订阅 /
 * 展开步骤各写一遍）与两处写同一条进度条的地方。抽成独立类之后：
 *
 * · **状态只有一个来源**：全部读 [BootTrace.snapshot]，不再有「安装总进度」
 *   与「当前阶段百分比」互相覆盖的问题；
 * · Activity 只剩 `setupSplash` / `renderSplashTrace` / `dismissSplash` 三个入口；
 * · 退出、看门狗、动画停止都在这里统一 dispose，不留悬挂回调。
 *
 * ## 进度语义（这里最容易出错，单独说明）
 *
 * 环上显示的百分比**只表示「当前阶段」**，不是整个启动过程的百分比：
 * 下载阶段是下载进度，解压阶段是解压进度。阶段切换时 [BootTrace.stage] 会把
 * `install` 清零，所以不会出现「正在解压 + 100%」。
 *
 * 拿不到总量时（`install.totalBytes == 0` 且 `totalFiles == 0`）宁可不显示
 * 百分比 —— 环转不确定态短弧。**不估算**：估算出来的百分比会在真实数据到达时
 * 跳变，比不显示更让人困惑。
 */
class SplashController(
    private val activity: Activity,
    private val root: View,
    private val minimumMs: Long,
    private val isBusy: () -> Boolean,
    private val snapshotForDisplay: (BootTrace.Snapshot) -> BootTrace.Snapshot,
    private val onWatchdog: () -> Unit,
    private val onHidden: () -> Unit,
) {

    private fun <T : View> view(id: Int): T = root.findViewById(id)

    private val body = view<View>(R.id.splashBody)
    private val ring = view<CircularProgressIndicator>(R.id.splashRing)
    private val status = view<TextView>(R.id.splashStatus)
    private var lastFailed: Boolean? = null
    private val elapsed = view<TextView>(R.id.splashElapsed)
    private val percent = view<TextView>(R.id.splashPercent)
    private val detail = view<TextView>(R.id.splashDetail)
    private val toggle = view<TextView>(R.id.splashToggle)
    private val stepsPanel = view<View>(R.id.splashStepsPanel)
    private val steps = view<TextView>(R.id.splashSteps)

    private val modeDot = view<View>(R.id.splashModeDot)
    private val modeTitle = view<TextView>(R.id.splashModeTitle)
    private val modeSub = view<TextView>(R.id.splashModeSub)

    private val shownAt = SystemClock.uptimeMillis()

    private var disposed = false
    private var closing = false
    private var expanded = false
    private var watchdogExtensions = 0
    private var lastModeSignature: String? = null
    private var lastStepsText: String? = null
    private var lastElapsedText: String? = null

    /** 每 250ms 拉一次快照。比旧实现的 200ms 稍慢 —— 250ms 内人眼看不出差别，
     *  但少 20% 的 invalidate 次数，在低端机上滚动步骤列表更顺。 */
    private val ticker = object : Runnable {
        override fun run() {
            if (disposed || closing) return
            render(BootTrace.snapshot.value)
            root.postDelayed(this, 250L)
        }
    }

    /**
     * 兜底看门狗：任何加载路径异常都不能让遮罩永久吞掉触摸（issue #17）。
     *
     * 45 秒起算；期间只要引擎仍处于 Installing/Starting 就每 15 秒续期，
     * 最多 20 次（约 5 分钟）。超过就强制收起 —— 用户宁愿看到一块空白
     * WebView，也不愿看到一个永远转圈的遮罩点不动。
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (disposed || closing) return
            if (isBusy() && watchdogExtensions < 20) {
                watchdogExtensions++
                root.postDelayed(this, 15_000L)
            } else {
                onWatchdog()
            }
        }
    }

    private val exit = Runnable {
        if (!disposed) {
            body.animate().cancel()
            ring.visibility = View.INVISIBLE
            if (ValueAnimator.areAnimatorsEnabled()) {
                root.animate()
                    .alpha(0f)
                    .setDuration(280L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { finishHide() }
                    .start()
            } else {
                finishHide()
            }
        }
    }

    /**
     * 宽屏（平板 / 横屏 / 分屏）下把正文宽度收在 420dp 内。
     *
     * 不限宽时正文会被拉到整屏宽：一行文字横跨 1200dp，用户眼睛要来回扫，
     * 而且阶段大字看起来像 slogan 而不是进度提示。420dp 是桌面排版里常用的
     * 舒适阅读宽度。
     */
    private val widthListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        constrainWidth()
    }

    init {
        root.visibility = View.VISIBLE
        root.alpha = 1f
        stepsPanel.visibility = View.GONE
        toggle.setText(R.string.splash_show_steps)

        toggle.setOnClickListener {
            if (disposed || closing) return@setOnClickListener
            expanded = !expanded
            stepsPanel.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.setText(
                if (expanded) R.string.splash_hide_steps else R.string.splash_show_steps,
            )
            if (expanded) renderSteps(snapshotForDisplay(BootTrace.snapshot.value))
        }

        render(BootTrace.snapshot.value)

        // 入场只做透明度：缩放入场在低端机上会掉帧，而且「内容从 0.9 放大到 1」
        // 会让文字在动画期间处于亚像素位置，看起来发虚。
        if (ValueAnimator.areAnimatorsEnabled()) {
            body.alpha = 0f
            body.animate().alpha(1f).setDuration(320L)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            body.alpha = 1f
        }

        root.addOnLayoutChangeListener(widthListener)
        root.post { constrainWidth() }

        root.postDelayed(ticker, 250L)
        root.postDelayed(watchdog, 45_000L)
    }

    /** 见 [widthListener] 的注释。 */
    private fun constrainWidth() {
        if (disposed || root.width <= 0) return
        val params = body.layoutParams as? FrameLayout.LayoutParams ?: return
        val available = (root.width - params.leftMargin - params.rightMargin).coerceAtLeast(1)
        val target = minOf(available, dp(420))
        if (params.width != target) {
            params.width = target
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            body.layoutParams = params
        }
    }

    /** 渲染一帧。UI 线程调用。 */
    fun render(rawSnapshot: BootTrace.Snapshot) {
        if (disposed || closing) return
        val snapshot = snapshotForDisplay(rawSnapshot)
        renderMode()

        val stage = snapshot.stage
        val install = snapshot.install
        val failed = stage == BootTrace.Stage.FAILED

        // 阶段文案
        status.setText(
            when (stage) {
                BootTrace.Stage.IDLE, BootTrace.Stage.PREPARING -> R.string.splash_preparing
                BootTrace.Stage.DOWNLOADING -> R.string.splash_downloading
                BootTrace.Stage.EXTRACTING -> R.string.splash_extracting
                BootTrace.Stage.STARTING -> R.string.splash_starting
                BootTrace.Stage.LOADING, BootTrace.Stage.READY -> R.string.splash_loading
                BootTrace.Stage.FAILED -> R.string.splash_failed
            },
        )
        status.setTextColor(MaterialColors.getColor(
            status,
            if (failed) androidx.appcompat.R.attr.colorError
            else com.google.android.material.R.attr.colorOnSurface,
        ))

        // 耗时行：只在文本真的变化时 setText，避免每帧重排
        val elapsedText = activity.getString(
            R.string.splash_ui_elapsed,
            formatSeconds(snapshot.elapsedMs()),
        )
        if (elapsedText != lastElapsedText) {
            lastElapsedText = elapsedText
            elapsed.text = elapsedText
        }

        // 进度环：只在传输类阶段用确定进度
        val transfer = stage == BootTrace.Stage.DOWNLOADING || stage == BootTrace.Stage.EXTRACTING
        val fraction = if (transfer) install.fraction?.takeIf { it.isFinite() } else null
        // A static arc remains visible when system animations are disabled or startup fails.
        val indeterminate = fraction == null && !failed && ValueAnimator.areAnimatorsEnabled()
        if (ring.isIndeterminate != indeterminate) {
            // ProgressBar forbids switching modes while visible; hide first.
            ring.visibility = View.INVISIBLE
            ring.isIndeterminate = indeterminate
            ring.visibility = View.VISIBLE
        }
        ring.contentDescription = status.text
        if (lastFailed != failed) {
            lastFailed = failed
            ring.setIndicatorColor(MaterialColors.getColor(
                ring,
                if (failed) androidx.appcompat.R.attr.colorError
                else androidx.appcompat.R.attr.colorPrimary,
            ))
        }
        if (!indeterminate) {
            val progress = fraction ?: 0.25f
            ring.setProgressCompat((progress * 10000).toInt(), false)
        }
        percent.text = if (fraction != null) {
            activity.getString(
                R.string.splash_ui_phase_percent,
                (fraction * 100f).toInt().coerceIn(0, 100),
            )
        } else {
            ""
        }

        // 明细行：失败原因 > 文件/字节数 > 当前阶段 > 复用提示
        val detailText = when {
            snapshot.failure != null -> snapshot.failure.take(300)
            transfer && install.totalFiles > 0 -> buildString {
                append(activity.getString(R.string.splash_files, install.doneFiles, install.totalFiles))
                if (install.doneBytes > 0) {
                    append(" · ")
                    append(formatBytes(install.doneBytes))
                }
            }
            transfer && install.totalBytes > 0 ->
                activity.getString(
                    R.string.splash_ui_bytes,
                    formatBytes(install.doneBytes),
                    formatBytes(install.totalBytes),
                )
            transfer && install.doneBytes > 0 -> formatBytes(install.doneBytes)
            snapshot.currentStep != null -> stageLabel(snapshot.currentStep)
            snapshot.runtimeReused -> activity.getString(R.string.splash_runtime_reused)
            else -> ""
        }
        detail.text = detailText

        if (expanded) renderSteps(snapshot)
    }

    /** 遮罩已建但还没收到 BootTrace 时，允许外部直接改阶段文案。 */
    fun setFallbackStatus(resId: Int) {
        if (!disposed && !closing) status.setText(resId)
    }

    /** 请求收起遮罩（幂等由调用方保证）。 */
    fun dismiss() {
        if (disposed || closing) return
        closing = true
        root.removeCallbacks(ticker)
        root.removeCallbacks(watchdog)
        val remaining = (minimumMs - (SystemClock.uptimeMillis() - shownAt)).coerceAtLeast(0L)
        // +180ms 是给「整页重栅格化」留的窗口（见 MainActivity.onPageFinished 的
        // LAYER_TYPE_SOFTWARE 切换），不能省，否则用户会看到缩放未生效的一帧。
        root.postDelayed(exit, remaining + 180L)
    }

    /** 彻底释放：停掉所有回调与动画。Activity 销毁时必须调。 */
    fun dispose() {
        if (disposed) return
        disposed = true
        root.removeCallbacks(ticker)
        root.removeCallbacks(watchdog)
        root.removeCallbacks(exit)
        root.removeOnLayoutChangeListener(widthListener)
        root.animate().cancel()
        body.animate().cancel()
        ring.visibility = View.INVISIBLE
        toggle.setOnClickListener(null)
    }

    private fun finishHide() {
        if (disposed) return
        root.visibility = View.GONE
        dispose()
        onHidden()
    }

    /**
     * 运行模式徽章。
     *
     * 判定完成前必须显示「检测运行环境」—— `lastMode` 的默认值是 NATIVE，
     * 直接读会把 Linux 模式在判定完成前的一小段时间显示成原生，等于撒谎。
     */
    private fun renderMode() {
        val resolved = EngineModeSelector.resolved
        val mode = EngineModeSelector.lastMode
        val signature = "$resolved|$mode"
        if (signature == lastModeSignature) return
        lastModeSignature = signature

        val linux = resolved && mode == EngineModeSelector.Mode.LINUX
        val fallback = resolved && mode == EngineModeSelector.Mode.NATIVE_FALLBACK

        val titleRes = when {
            !resolved -> R.string.splash_mode_detecting
            linux -> R.string.splash_mode_linux
            fallback -> R.string.splash_mode_fallback
            else -> R.string.splash_mode_native
        }
        val subRes = when {
            !resolved -> R.string.splash_ui_mode_waiting
            linux -> R.string.splash_mode_linux_sub
            else -> R.string.splash_mode_native_sub
        }
        // 容器模式的副标题要按**实际发行版**说 libc：写死 musl 的话，用 Ubuntu
        // 启动时也在说 musl（它是 glibc），而这恰好是用户最容易看出来的地方。
        val subText = if (linux) {
            activity.getString(
                R.string.splash_mode_linux_sub_libc,
                LinuxRootfsCatalog.active(activity).family.libc,
            )
        } else {
            activity.getString(subRes)
        }
        val tint = MaterialColors.getColor(
            modeTitle, com.google.android.material.R.attr.colorOnSecondaryContainer,
        )

        modeTitle.setText(titleRes)
        modeTitle.setTextColor(tint)
        modeSub.setText(subText)
        modeSub.visibility = View.VISIBLE

        modeDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(tint)
        }
    }

    private fun renderSteps(snapshot: BootTrace.Snapshot) {
        val lines = snapshot.steps.map { step ->
            activity.getString(
                R.string.splash_step_line,
                formatDuration(step.durationMs),
                formatSeconds(step.atMs),
                stageLabel(step.key),
            )
        }.toMutableList()
        snapshot.currentStep?.let { key ->
            lines += activity.getString(R.string.splash_ui_step_running, stageLabel(key))
        }
        val text = if (lines.isEmpty()) {
            activity.getString(R.string.splash_steps_empty)
        } else {
            lines.joinToString("\n")
        }
        // 内容没变就不重设：全量 setText 会让滚动位置回到顶部，用户翻看历史时被拽回
        if (text != lastStepsText) {
            lastStepsText = text
            steps.text = text
        }
    }

    /** 阶段 key → 本地化文案；未知 key 原样显示（新阶段不会因缺翻译而消失）。 */
    private fun stageLabel(key: String): String {
        val resId = when (key) {
            "guardian-restore" -> R.string.boot_step_guardian
            "agent-seed" -> R.string.boot_step_seed
            "parallel-preflight" -> R.string.boot_step_parallel_preflight
            "orphan-reap" -> R.string.boot_step_orphans
            "bridge" -> R.string.boot_step_bridge
            "log-sweep" -> R.string.boot_step_log_sweep
            "home-owner-check" -> R.string.boot_step_owner
            "hardssh-install" -> R.string.boot_step_hardssh
            "runtime-install" -> R.string.boot_step_runtime
            "profile-plugins" -> R.string.boot_step_profile_plugins
            "user-data" -> R.string.boot_step_userdata
            "spawn" -> R.string.boot_step_spawn
            "health-poll" -> R.string.boot_step_health
            else -> return key
        }
        return activity.getString(resId)
    }

    private fun formatSeconds(ms: Long): String =
        if (ms < 60_000L) {
            String.format(Locale.US, "%.1fs", ms / 1000.0)
        } else {
            String.format(Locale.US, "%d:%02d", ms / 60_000L, (ms % 60_000L) / 1000L)
        }

    private fun formatDuration(ms: Long): String =
        if (ms < 1000L) "${ms}ms" else formatSeconds(ms)

    /** 用 MiB/KiB 而不是 MB/KB：这是**字节**口径，1024 进制写 MB 会更不准。 */
    private fun formatBytes(bytes: Long): String {
        val v = bytes.coerceAtLeast(0L)
        return when {
            v >= (1L shl 30) -> String.format(Locale.US, "%.1f GiB", v / 1073741824.0)
            v >= (1L shl 20) -> String.format(Locale.US, "%.1f MiB", v / 1048576.0)
            else -> String.format(Locale.US, "%.0f KiB", v / 1024.0)
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
