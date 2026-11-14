package app.dsh.mobile

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.dockedtoolbar.DockedToolbarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.max

/** The host attaches [root] at the bottom and [floatingView] directly to the same FrameLayout. */
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
        fun onPosition(xRatio: Float, yRatio: Float) {}
    }

    val root: DshDockGestureLayout = inflater.inflate(R.layout.view_dsh_bottom_toolbar, parent, false) as DshDockGestureLayout
    val expandedView: DockedToolbarLayout = root.findViewById(R.id.dsh_bottom_toolbar_expanded)
    val collapsedView: MaterialButton = root.findViewById(R.id.dsh_bottom_toolbar_collapsed)
    val floatingView: MaterialButton = collapsedView
    private val collapseButton: MaterialButton = root.findViewById(R.id.dsh_bottom_toolbar_collapse)
    private val navigation: BottomNavigationView = root.findViewById(R.id.dsh_bottom_navigation)
    private val touchSlop = ViewConfiguration.get(root.context).scaledTouchSlop
    private var yRatio = 1f
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0
    private var transitionGeneration = 0
    var isExpanded: Boolean = initialExpanded
        private set
    var isShown: Boolean = initiallyShown
        private set
    var dragRatio: Float = sanitizeRatio(initialDragRatio)
        private set

    init {
        // It must be a direct child of the activity's FrameLayout so its hit area follows it.
        root.removeView(collapsedView)
        ViewCompat.setOnApplyWindowInsetsListener(navigation) { _, insets -> insets }
        navigation.setOnItemSelectedListener { item ->
            if (!isExpanded || !isShown) return@setOnItemSelectedListener false
            when (item.itemId) {
                R.id.dsh_bottom_toolbar_home -> { callbacks.onHome(); true }
                R.id.dsh_bottom_toolbar_menu -> { callbacks.onMenu(); false }
                R.id.dsh_bottom_toolbar_settings -> { callbacks.onSettings(); false }
                else -> false
            }
        }
        navigation.setOnItemReselectedListener { item ->
            if (isExpanded && isShown && item.itemId == R.id.dsh_bottom_toolbar_home) callbacks.onHome()
        }
        root.onCollapse = { collapseFromUser() }
        root.isBlankPoint = { x, y -> isBlankPoint(x, y) }
        collapseButton.setOnClickListener { collapseFromUser() }
        collapsedView.setOnClickListener { expandFromUser() }
        collapsedView.setOnLongClickListener { callbacks.onMoveDock(); true }
        installDragGesture()
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionHandle() }
        collapsedView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionHandle() }
        updateVisibility(animate = false)
    }

    fun setShown(shown: Boolean) {
        if (isShown == shown) return
        isShown = shown
        updateVisibility(animate = false)
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        updateVisibility(animate = isShown && ValueAnimator.areAnimatorsEnabled())
    }

    fun setHorizontalInsets(left: Int, right: Int) {
        val safeLeft = left.coerceAtLeast(0)
        val safeRight = right.coerceAtLeast(0)
        if (root.paddingLeft != safeLeft || root.paddingRight != safeRight) {
            root.setPadding(safeLeft, root.paddingTop, safeRight, root.paddingBottom)
        }
        setFloatingInsets(safeLeft, insetTop, safeRight, insetBottom)
    }

    /** Insets are measured from the edges of the FrameLayout containing [floatingView]. */
    fun setFloatingInsets(left: Int, top: Int, right: Int, bottom: Int) {
        insetLeft = left.coerceAtLeast(0)
        insetTop = top.coerceAtLeast(0)
        insetRight = right.coerceAtLeast(0)
        insetBottom = bottom.coerceAtLeast(0)
        positionHandle()
    }

    fun setDragRatio(ratio: Float) {
        dragRatio = sanitizeRatio(ratio)
        positionHandle()
    }

    fun setPosition(xRatio: Float, yRatio: Float) {
        dragRatio = sanitizeRatio(xRatio)
        this.yRatio = sanitizeRatio(yRatio)
        positionHandle()
    }

    private fun updateVisibility(animate: Boolean) {
        val generation = ++transitionGeneration
        root.animate().cancel()
        collapsedView.animate().cancel()
        root.alpha = 1f
        root.translationY = 0f
        collapsedView.alpha = 1f
        collapsedView.scaleX = 1f
        collapsedView.scaleY = 1f
        expandedView.importantForAccessibility = if (isExpanded) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        collapseButton.isEnabled = isExpanded
        navigation.isEnabled = isExpanded
        collapsedView.isEnabled = !isExpanded
        collapsedView.importantForAccessibility = if (isExpanded) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (!isShown) {
            root.visibility = View.GONE
            collapsedView.visibility = View.GONE
            return
        }
        if (!animate) {
            root.visibility = if (isExpanded) View.VISIBLE else View.GONE
            expandedView.visibility = View.VISIBLE
            collapsedView.visibility = if (isExpanded) View.GONE else View.VISIBLE
            positionHandle()
            return
        }
        positionHandle()
        if (isExpanded) {
            root.visibility = View.VISIBLE
            expandedView.visibility = View.VISIBLE
            root.alpha = 0f
            root.translationY = root.resources.displayMetrics.density * 16f
            root.animate().alpha(1f).translationY(0f).setDuration(180).start()
            collapsedView.animate().alpha(0f).scaleX(0.75f).scaleY(0.75f).setDuration(150)
                .withEndAction {
                    if (generation == transitionGeneration) collapsedView.visibility = View.GONE
                }.start()
        } else {
            collapsedView.visibility = View.VISIBLE
            collapsedView.alpha = 0f
            collapsedView.scaleX = 0.75f
            collapsedView.scaleY = 0.75f
            collapsedView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
            root.animate().alpha(0f).translationY(root.resources.displayMetrics.density * 16f).setDuration(150)
                .withEndAction {
                    if (generation == transitionGeneration) root.visibility = View.GONE
                }.start()
        }
    }

    private fun positionHandle() {
        val container = collapsedView.parent as? ViewGroup ?: return
        val width = collapsedView.width.takeIf { it > 0 } ?: collapsedView.layoutParams?.width ?: return
        val height = collapsedView.height.takeIf { it > 0 } ?: collapsedView.layoutParams?.height ?: return
        if (width <= 0 || height <= 0 || container.width <= 0 || container.height <= 0) return
        val availableX = (container.width - insetLeft - insetRight - width).coerceAtLeast(0)
        val availableY = (container.height - insetTop - insetBottom - height).coerceAtLeast(0)
        collapsedView.translationX = insetLeft + availableX * dragRatio - collapsedView.left
        collapsedView.translationY = insetTop + availableY * yRatio - collapsedView.top
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

    private fun isBlankPoint(x: Float, y: Float): Boolean {
        val origin = IntArray(2)
        root.getLocationOnScreen(origin)
        val pointX = origin[0] + x
        val pointY = origin[1] + y
        val rect = android.graphics.Rect()
        val targets = listOf(collapseButton) + listOf(
            R.id.dsh_bottom_toolbar_home, R.id.dsh_bottom_toolbar_menu, R.id.dsh_bottom_toolbar_settings,
        ).mapNotNull { navigation.findViewById<View>(it) }
        return targets.none { it.isShown && it.getGlobalVisibleRect(rect) && rect.contains(pointX.toInt(), pointY.toInt()) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDragGesture() {
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        var longPressed = false
        val longPress = Runnable {
            if (!dragging) {
                longPressed = true
                collapsedView.isPressed = false
                collapsedView.performLongClick()
            }
        }
        collapsedView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = dragRatio
                    startY = yRatio
                    dragging = false
                    longPressed = false
                    view.isPressed = true
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && max(abs(dx), abs(dy)) > touchSlop) {
                        dragging = true
                        view.isPressed = false
                        view.removeCallbacks(longPress)
                    }
                    if (dragging && !longPressed) {
                        val container = view.parent as? ViewGroup
                        if (container != null) {
                            val availableX = container.width - insetLeft - insetRight - view.width
                            val availableY = container.height - insetTop - insetBottom - view.height
                            setPosition(
                                if (availableX > 0) startX + dx / availableX else startX,
                                if (availableY > 0) startY + dy / availableY else startY,
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    view.isPressed = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!longPressed && dragging) {
                        callbacks.onDragRatio(dragRatio)
                        callbacks.onPosition(dragRatio, yRatio)
                    } else if (!longPressed) {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    view.isPressed = false
                    if (dragging && !longPressed) setPosition(startX, startY)
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
