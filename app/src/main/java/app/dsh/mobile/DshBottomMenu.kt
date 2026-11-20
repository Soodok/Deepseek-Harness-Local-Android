package app.dsh.mobile

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Bottom-sheet host for the island's existing actions and status.
 * All methods and callbacks must be used on the main thread. The activity owns this
 * instance and should call [dismiss] when it is destroyed.
 *
 * Set the callbacks, call [update] with the current engine status, then call [show].
 * Forward subsequent status changes to [update], including while the sheet is open.
 */
class DshBottomMenu(private val activity: Activity) {
    var onRunMode: (() -> Unit)? = null
    var onRestart: (() -> Unit)? = null
    var onStatus: (() -> Unit)? = null

    private var dialog: BottomSheetDialog? = null
    private var panel: IslandSidePanel? = null
    private var state = ""
    private var detail = ""
    private var dotColor = IslandPalette.themed(activity).dot
    private var statusClickable = false

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        if (isShowing || activity.isFinishing || activity.isDestroyed) return

        val sheet = BottomSheetDialog(activity)
        val sidePanel = IslandSidePanel(activity, IslandDock.BOTTOM).apply {
            onCollapse = { sheet.dismiss() }
            onRunMode = {
                sheet.dismiss()
                this@DshBottomMenu.onRunMode?.invoke()
            }
            onRestart = {
                sheet.dismiss()
                this@DshBottomMenu.onRestart?.invoke()
            }
            onStatus = {
                sheet.dismiss()
                this@DshBottomMenu.onStatus?.invoke()
            }
        }
        val content = sidePanel.build()
        // The Material sheet supplies the surface and top corners, not the side panel.
        sidePanel.root.background = null
        sidePanel.update(state, detail, dotColor, statusClickable)
        applyRipples(sidePanel.root, sidePanel.root, IslandPalette.themed(activity).title)
        sheet.setContentView(content)
        sheet.setOnDismissListener {
            if (dialog === sheet) {
                dialog = null
                panel = null
            }
        }
        dialog = sheet
        panel = sidePanel
        sheet.show()
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    /** Mirrors [IslandSidePanel.update]; set statusClickable only when diagnosis is available. */
    fun update(state: String, detail: String, dotColor: Int, statusClickable: Boolean = false) {
        this.state = state
        this.detail = detail
        this.dotColor = dotColor
        this.statusClickable = statusClickable
        panel?.update(state, detail, dotColor, statusClickable)
    }

    private fun applyRipples(view: View, root: ViewGroup, color: Int) {
        // The status header becomes clickable only after update(), so include the first row.
        if (view !== root && (view.isClickable || view === root.getChildAt(0))) {
            view.foreground = RippleDrawable(
                ColorStateList.valueOf((color and 0x00ffffff) or 0x24000000),
                null,
                ColorDrawable(Color.WHITE),
            )
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyRipples(view.getChildAt(i), root, color)
        }
    }
}
