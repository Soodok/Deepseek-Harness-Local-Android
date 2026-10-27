package app.dsh.mobile.pet

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.dsh.mobile.R
import kotlin.math.max
import kotlin.math.min

/**
 * 长按动作菜单：贴着桌宠弹出的小卡片（参考 dsh-pet 的 Web 菜单），
 * 二级菜单按需展开，不再用「半屏底部面板」。
 *
 * 结构：
 *  - 一个全屏透明根窗口负责「点外面收起」；
 *  - 卡片浮在根窗口里，位置按桌宠当前的屏幕矩形自动选：优先贴上方，放不下就贴下方，
 *    左右夹在屏幕内；
 *  - 首页 = 待机 / 点击回应 / 动作（分类→具体动作）+ 回位、翻转、设置、收起；
 *  - 子页标题行可返回上一级。
 */
class PetActionMenu(
    private val ctx: Context,
    private val wm: WindowManager,
    private val pack: PetPackConfig?,
    /** 桌宠窗口在屏幕上的矩形（TOP|START 坐标系），没有就退回右上角。 */
    private val anchor: Rect?,
    private val onAction: (String) -> Unit,
    private val onReset: () -> Unit,
    private val onFlip: () -> Unit,
    private val onSettings: () -> Unit,
    private val onStop: () -> Unit,
    private val onDismiss: () -> Unit = {},
) {
    private enum class Page { MAIN, CATEGORIES, ACTIONS }

    private var root: FrameLayout? = null
    private var card: LinearLayout? = null
    private var page = Page.MAIN
    private var category: PetAnimCategory? = null
    private var listTitle = ""

    private val d = ctx.resources.displayMetrics.density
    private val dm = ctx.resources.displayMetrics

    val isShowing: Boolean get() = root != null

    fun show() {
        if (root != null) return
        val host = FrameLayout(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { dismiss() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(0xF0FFFFFF.toInt())
                setStroke(dp(1), 0x26000000)
            }
            isClickable = true
        }
        host.addView(panel, FrameLayout.LayoutParams(cardWidthPx(), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
            topMargin = dp(8)
        })

        runCatching {
            wm.addView(host, lp)
            root = host
            card = panel
        }.onFailure {
            Log.w(TAG, "menu addView failed: ${it.message}")
            root = null
            card = null
            return
        }

        page = Page.MAIN
        category = null
        render()
    }

    fun dismiss() {
        val v = root ?: return
        root = null
        card = null
        runCatching { wm.removeView(v) }
        onDismiss()
    }

    // ------------------------------------------------------------ 页面

    private fun render() {
        val panel = card ?: return
        panel.removeAllViews()
        when (page) {
            Page.MAIN -> renderMain(panel)
            Page.CATEGORIES -> renderCategories(panel)
            Page.ACTIONS -> renderActions(panel)
        }
        positionCard()
    }

    private fun renderMain(panel: LinearLayout) {
        var any = false
        val cfg = pack
        if (cfg == null) {
            panel.addView(hint(ctx.getString(R.string.pet_menu_no_pack)))
        } else {
            if (cfg.idle.any { cfg.isAvailable(it) }) {
                panel.addView(row(ctx.getString(R.string.pet_menu_idle), submenu = true) {
                    listTitle = ctx.getString(R.string.pet_menu_idle)
                    page = Page.ACTIONS
                    render()
                })
                any = true
            }
            if (cfg.clicks.any { cfg.isAvailable(it) }) {
                panel.addView(row(ctx.getString(R.string.pet_menu_clicks), submenu = true) {
                    listTitle = ctx.getString(R.string.pet_menu_clicks)
                    page = Page.ACTIONS
                    render()
                })
                any = true
            }
            if (cfg.categories.any { c -> c.actions.any { cfg.isAvailable(it) } }) {
                panel.addView(row(ctx.getString(R.string.pet_menu_actions), submenu = true) {
                    page = Page.CATEGORIES
                    render()
                })
                any = true
            }
            if (!any) panel.addView(hint(ctx.getString(R.string.pet_menu_no_pack)))
        }

        panel.addView(divider())
        panel.addView(row(ctx.getString(R.string.pet_menu_reset)) { dismiss(); onReset() })
        panel.addView(row(ctx.getString(R.string.pet_menu_flip)) { dismiss(); onFlip() })
        panel.addView(row(ctx.getString(R.string.pet_menu_settings)) { dismiss(); onSettings() })
        panel.addView(row(ctx.getString(R.string.pet_menu_stop)) { dismiss(); onStop() })
    }

    private fun renderCategories(panel: LinearLayout) {
        panel.addView(
            row(
                "${ctx.getString(R.string.pet_menu_actions)}  ‹",
                submenu = false,
                accent = true,
            ) {
                page = Page.MAIN
                render()
            },
        )
        panel.addView(divider())
        val cfg = pack ?: return
        val cats = cfg.categories.filter { c -> c.actions.any { cfg.isAvailable(it) } }
        if (cats.isEmpty()) {
            panel.addView(hint(ctx.getString(R.string.pet_menu_no_pack)))
            return
        }
        for (cat in cats) {
            val count = cat.actions.count { cfg.isAvailable(it) }
            panel.addView(row("${cat.id}  ($count)", submenu = true) {
                category = cat
                listTitle = cat.id
                page = Page.ACTIONS
                render()
            })
        }
    }

    private fun renderActions(panel: LinearLayout) {
        val cfg = pack ?: return
        panel.addView(
            row("‹  ${listTitle}", submenu = false, accent = true) {
                page = if (category != null) Page.CATEGORIES else Page.MAIN
                render()
            },
        )
        panel.addView(divider())
        val slugs = when (page) {
            Page.ACTIONS -> when {
                category != null -> category!!.actions.filter { cfg.isAvailable(it) }
                listTitle == ctx.getString(R.string.pet_menu_idle) -> cfg.idle.filter { cfg.isAvailable(it) }
                listTitle == ctx.getString(R.string.pet_menu_clicks) -> cfg.clicks.filter { cfg.isAvailable(it) }
                else -> emptyList()
            }
            else -> emptyList()
        }
        if (slugs.isEmpty()) {
            panel.addView(hint(ctx.getString(R.string.pet_menu_no_pack)))
            return
        }
        val scroll = ScrollView(ctx)
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        for (slug in slugs) {
            list.addView(row(cfg.nameOf(slug)) {
                dismiss()
                onAction(slug)
            })
        }
    }

    // ------------------------------------------------------------ 定位

    private fun positionCard() {
        val host = root ?: return
        val panel = card ?: return
        val width = cardWidthPx()

        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxPanelHeightPx(), View.MeasureSpec.AT_MOST),
        )
        val height = panel.measuredHeight

        val pet = anchor ?: Rect(dm.widthPixels - dp(150), dp(100), dm.widthPixels, dp(100) + dp(84))
        val gap = dp(10)
        val margin = dp(8)

        val left = (pet.centerX() - width / 2)
            .coerceIn(margin, max(margin, dm.widthPixels - width - margin))
        val above = pet.top - height - gap
        val below = pet.bottom + gap
        val top = if (above >= dp(24)) above
        else min(below, max(margin, dm.heightPixels - height - margin))
            .coerceIn(dp(24), max(dp(24), dm.heightPixels - height - margin))

        val lp = panel.layoutParams as FrameLayout.LayoutParams
        lp.width = width
        lp.leftMargin = left
        lp.topMargin = top
        panel.layoutParams = lp
    }

    private fun cardWidthPx(): Int = dp(238).coerceAtMost(dm.widthPixels - dp(16))

    private fun maxPanelHeightPx(): Int = (dm.heightPixels * 0.62f).toInt()

    // ------------------------------------------------------------ 行与分隔

    private fun row(
        label: String,
        submenu: Boolean = false,
        accent: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val tv = TextView(ctx).apply {
            text = if (submenu) "$label  ›" else label
            textSize = 13.5f
            setTextColor(if (accent) 0xFF2F6FED.toInt() else 0xFF2A3140.toInt())
            setPadding(dp(14), dp(11), dp(12), dp(11))
            isClickable = true
            setOnClickListener { onClick() }
            if (submenu) setTypeface(typeface, Typeface.NORMAL)
        }
        return tv
    }

    private fun hint(text: String): View = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF7A8494.toInt())
        setPadding(dp(14), dp(10), dp(12), dp(10))
    }

    private fun divider(): View = View(ctx).apply {
        setBackgroundColor(0x14000000)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) }
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    companion object {
        private const val TAG = "DshPetMenu"
    }
}
