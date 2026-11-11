package app.dsh.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.dsh.mobile.engine.EngineModeSelector
import app.dsh.mobile.engine.LinuxEnvironmentManager
import app.dsh.mobile.engine.LinuxRootfsCatalog

/**
 * 「运行模式」悬浮窗（v1.5.8）。
 *
 * 用户明确要求这是**悬浮窗**、不是对话框弹窗：
 * 「运行模式选择窗口我是想要悬浮窗，不是弹窗」。所以这里只负责造一张卡片
 * （`build()` 返回 View），由宿主（MainActivity）把它挂在根容器的浮层里，
 * 配一层全屏透明接收层点空白收起 —— 与灵动岛侧边面板同一套摆放方式。
 *
 * 从灵动岛侧边面板进入：用户要求「展开之后把语音和插件换成运行模式，点击之后
 * 出现一个新的悬浮窗口，可以选择以什么模式运行（两个分组，原生和容器）」。
 *
 * ## 两个分组
 * - **原生**：只有一个选项，且是默认 —— bionic（Android 自带 libc，不套容器）。
 * - **容器**：列出**用户已经下载**的 Linux 系统包（`LinuxRootfsCatalog.usableCatalog`
 *   里 `isDeployed` 为真的那些）。一个都没下时不给假选项，直接提示 + 给下载入口。
 *
 * ## 为什么选完还要问一次重启
 * 运行模式是引擎启动期读的（`EngineModeSelector`），改完不重启不生效。直接替用户
 * 重启会掐掉正在跑的会话，所以改成「立刻重启 / 稍后」二选一 —— 后者只落偏好，
 * 下次启动生效。
 */
class RunModeOverlay(private val act: Activity) {

    private val density get() = act.resources.displayMetrics.density
    private fun px(v: Int) = (v * density).toInt()

    /** 用户最终选择的动作；null 表示取消。 */
    sealed class Pick {
        data object Native : Pick()
        data class Container(val option: LinuxRootfsCatalog.Option) : Pick()
    }

    /** 造出悬浮卡片；由宿主决定挂在哪、多宽。 */
    fun build(onPicked: (Pick) -> Unit): View {
        val mgr = LinuxEnvironmentManager.get(act)
        val linuxOn = EngineModeSelector.isLinuxEnabled(act)
        val active = runCatching { LinuxRootfsCatalog.active(act) }.getOrNull()
        // ⚠️ 用 flavorPackagesReady 而不是 isDeployed：
        // isDeployed 只看 family（三个 flavor 共享同一 rootfs 目录），于是
        // 「只下了 alpine 基础包」会把 Alpine GUI / Alpine Dev 一起列出来 ——
        // 用户选它进不去，因为 Xvfb / 编译链根本没装。
        // flavorPackagesReady 还要求该 flavor 自己的 bootstrap 标记存在。
        val deployed = LinuxRootfsCatalog.usableCatalog.filter {
            runCatching { mgr.flavorPackagesReady(it) }.getOrDefault(false)
        }

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(22).toFloat()
                setColor(0xFF101416.toInt())
                setStroke(px(1).coerceAtLeast(1), 0x33FFFFFF)
            }
        }

        box.addView(TextView(act).apply {
            text = act.getString(R.string.island_run_mode)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFF2F5F9.toInt())
            setPadding(px(4), 0, px(4), px(2))
        })

        box.addView(sectionLabel(act.getString(R.string.run_mode_native)))
        box.addView(
            optionRow(
                title = act.getString(R.string.run_mode_native_name),
                sub = act.getString(R.string.run_mode_native_sub),
                selected = !linuxOn,
            ) { onPicked(Pick.Native) },
        )

        box.addView(sectionLabel(act.getString(R.string.run_mode_container)))
        if (deployed.isEmpty()) {
            box.addView(
                TextView(act).apply {
                    text = act.getString(R.string.run_mode_container_empty)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(0xFFB45309.toInt())
                    setPadding(px(4), px(6), px(4), px(4))
                },
            )
            box.addView(actionText(act.getString(R.string.run_mode_download)) {
                act.startActivity(Intent(act, LinuxActivity::class.java))
            })
        } else {
            deployed.forEach { opt ->
                val isActive = linuxOn && active != null &&
                    active.family == opt.family &&
                    active.versionLabel == opt.versionLabel &&
                    active.flavor == opt.flavor
                // 三个 flavor 共享同一份 rootfs，只显示 displayName 会出现三行
                // 一模一样的「Alpine」（实测）→ 必须拼 flavor.label。
                val flavorLabel = opt.flavor.label.ifBlank { act.getString(R.string.run_mode_flavor_base) }
                box.addView(
                    optionRow(
                        title = "${opt.displayName} · $flavorLabel",
                        sub = "${opt.versionLabel} · ${opt.libc}",
                        selected = isActive,
                    ) { onPicked(Pick.Container(opt)) },
                )
            }
        }
        return box
    }

    private fun sectionLabel(text: String) = TextView(act).apply {
        this.text = text.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(0xFF6B7684.toInt())
        setPadding(px(4), px(12), px(4), px(4))
    }

    private fun optionRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(10), px(12), px(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(12).toFloat()
                setColor(if (selected) 0x3322A06B else 0x00000000)
                if (selected) setStroke(px(1).coerceAtLeast(1), 0xFF22A06B.toInt())
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(act).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFF2F5F9.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(act).apply {
            text = sub
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF9CA3AF.toInt())
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) {
            row.addView(TextView(act).apply {
                text = "✓"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0xFF22A06B.toInt())
            })
        }
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = px(6) }
        return row
    }

    private fun actionText(text: String, onClick: () -> Unit) = TextView(act).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(0xFF7DD3FC.toInt())
        setPadding(px(4), px(6), px(4), px(8))
        isClickable = true
        setOnClickListener { onClick() }
    }

    companion object {
        /**
         * 应用选择 + 二次确认重启。
         * 复用给侧边面板与设置页，避免两处各写一遍「改完要不要重启」的逻辑。
         */
        fun apply(act: Activity, pick: Pick) {
            when (pick) {
                is Pick.Native -> EngineModeSelector.setLinuxEnabled(act, false)
                is Pick.Container -> {
                    LinuxRootfsCatalog.setActiveExact(act, pick.option.family, pick.option.flavor)
                    EngineModeSelector.setLinuxEnabled(act, true)
                }
            }
            android.app.AlertDialog.Builder(act)
                .setTitle(R.string.run_mode_restart_q)
                .setMessage(R.string.run_mode_restart_msg)
                .setPositiveButton(R.string.run_mode_restart_now) { _, _ ->
                    (act.application as DshApp).supervisor.restart()
                }
                .setNegativeButton(R.string.run_mode_restart_later, null)
                .show()
        }
    }
}
