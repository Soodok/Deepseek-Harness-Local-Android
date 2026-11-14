package app.dsh.mobile

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dockedtoolbar.DockedToolbarLayout
import kotlin.math.abs

/**
 * A bottom-aligned, standalone toolbar. Inflate with a themed context and add [root] to a
 * bottom-aligned container. The host handles window insets and persists [dragRatio].
 * Ratio 0 is the physical left edge and 1 is the physical right edge, independent of RTL.
 * Call [setExpanded] and [setShown] to restore or control state without emitting callbacks.
 */
class DshBottomToolbarController(
    inflater: LayoutInflater,
    parent: ViewGroup,
    private val callbacks: Callbacks,
    initialExpanded: Boolean = true,
    initialDragRatio: Float = 0.5f,
    initiallyShown: Boolean = true,
) {
    interface Callbacks {
        fun onHome()
        fun onMenu()
        fun onSettings()
        fun onExpand()
        fun onCollapse()
        fun onDragRatio(ratio: Float)
        fun onMoveDock()
    }

    val root: FrameLayout = inflater.inflate(R.layout.view_dsh_bottom_toolbar, parent, false) as FrameLayout
    val expandedView: DockedToolbarLayout = root.findViewById(R.id.dsh_bottom_toolbar_expanded)
    val collapsedView: MaterialButton = root.findViewById(R.id.dsh_bottom_toolbar_collapsed)
    private val collapseButton: MaterialButton = root.findViewById(R.id.dsh_bottom_toolbar_collapse)
    private val touchSlop = ViewConfiguration.get(root.context).scaledTouchSlop
    var isExpanded: Boolean = initialExpanded
        private set
    var isShown: Boolean = initiallyShown
        private set
    var dragRatio: Float = sanitizeRatio(initialDragRatio)
        private set

    init {
        root.findViewById<View>(R.id.dsh_bottom_toolbar_home).setOnClickListener { callbacks.onHome() }
        root.findViewById<View>(R.id.dsh_bottom_toolbar_menu).setOnClickListener { callbacks.onMenu() }
        root.findViewById<View>(R.id.dsh_bottom_toolbar_settings).setOnClickListener { callbacks.onSettings() }
        collapseButton.setOnClickListener { collapseFromUser() }
        collapsedView.setOnClickListener { expandFromUser() }
        collapsedView.setOnLongClickListener { callbacks.onMoveDock(); true }
        installCollapseGesture()
        installDragGesture()
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionHandle() }
        collapsedView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionHandle() }
        updateVisibility()
    }

    fun setShown(shown: Boolean) {
        if (isShown == shown) return
        isShown = shown
        updateVisibility()
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        updateVisibility()
    }

    fun setHorizontalInsets(left: Int, right: Int) {
        val safeLeft = left.coerceAtLeast(0)
        val safeRight = right.coerceAtLeast(0)
        if (root.paddingLeft == safeLeft && root.paddingRight == safeRight) return
        root.setPadding(safeLeft, root.paddingTop, safeRight, root.paddingBottom)
        positionHandle()
    }

    /** Restores or changes the physical horizontal position without invoking the callback. */
    fun setDragRatio(ratio: Float) {
        dragRatio = sanitizeRatio(ratio)
        positionHandle()
    }

    private fun updateVisibility() {
        root.visibility = if (isShown) View.VISIBLE else View.GONE
        expandedView.visibility = if (isExpanded) View.VISIBLE else View.GONE
        collapsedView.visibility = if (isExpanded) View.GONE else View.VISIBLE
    }

    private fun positionHandle() {
        val leftPadding = root.paddingLeft
        val rightPadding = root.paddingRight
        val available = (root.width - leftPadding - rightPadding - collapsedView.width).coerceAtLeast(0)
        collapsedView.translationX = (leftPadding - collapsedView.left) + available * dragRatio
    }

    private fun collapseFromUser() {
        if (!isExpanded) return
        setExpanded(false)
        callbacks.onCollapse()
    }

    private fun expandFromUser() {
        if (isExpanded) return
        setExpanded(true)
        callbacks.onExpand()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installCollapseGesture() {
        var downX = 0f
        var downY = 0f
        var moved = false
        collapseButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dy > touchSlop && dy > abs(dx)) collapseFromUser()
                    else if (!moved && abs(dx) <= touchSlop && abs(dy) <= touchSlop) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDragGesture() {
        var downX = 0f
        var downY = 0f
        var startRatio = 0f
        var dragging = false
        var moved = false
        var longPressed = false
        val longPress = Runnable {
            if (!moved) {
                longPressed = true
                collapsedView.performLongClick()
            }
        }
        collapsedView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startRatio = dragRatio
                    dragging = false
                    moved = false
                    longPressed = false
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        view.removeCallbacks(longPress)
                    }
                    if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) dragging = true
                    if (dragging) {
                        val available = root.width - root.paddingLeft - root.paddingRight - view.width
                        if (available > 0) setDragRatio(startRatio + dx / available)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    view.removeCallbacks(longPress)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (longPressed) return@setOnTouchListener true
                    if (dragging) {
                        val available = root.width - root.paddingLeft - root.paddingRight - view.width
                        if (available > 0) setDragRatio(startRatio + dx / available)
                        callbacks.onDragRatio(dragRatio)
                    } else if (!moved && abs(dx) <= touchSlop && abs(dy) <= touchSlop) {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    if (dragging) setDragRatio(startRatio)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
    }

    companion object {
        private fun sanitizeRatio(ratio: Float): Float =
            if (ratio.isNaN()) 0.5f else ratio.coerceIn(0f, 1f)
    }
}
