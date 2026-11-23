package app.dsh.mobile

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class DshDockGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    var onCollapse: (() -> Unit)? = null

    /** Receives coordinates relative to this layout; true means the point is blank. */
    var isBlankPoint: ((Float, Float) -> Boolean)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var blankDown = false
    private var moved = false
    private var swiping = false
    private var multiTouch = false

    init {
        isClickable = true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                blankDown = isBlankPoint?.invoke(downX, downY) == true
                moved = false
                swiping = false
                multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true
                blankDown = false
                multiTouch = true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0 || abs(event.getX(index) - downX) > touchSlop ||
                    abs(event.getY(index) - downY) > touchSlop
                ) {
                    moved = true
                }
            }
        }

        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val index = event.findPointerIndex(activePointerId)
            val blankTap = !swiping && !moved && blankDown && index >= 0 &&
                isBlankPoint?.invoke(event.getX(index), event.getY(index)) == true
            if (blankTap) performClick()
            activePointerId = MotionEvent.INVALID_POINTER_ID
            return handled || blankTap
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            activePointerId = MotionEvent.INVALID_POINTER_ID
            swiping = false
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !swiping && !multiTouch) {
            val index = event.findPointerIndex(activePointerId)
            if (index >= 0) {
                val dx = event.getX(index) - downX
                val dy = event.getY(index) - downY
                if (dy > touchSlop && dy > abs(dx)) {
                    swiping = true
                    return true
                }
            }
        }
        return swiping || super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (swiping) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                if (!multiTouch) onCollapse?.invoke()
                swiping = false
            }
            return true
        }
        // Blank taps are handled after dispatch, including those consumed by a child.
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onCollapse?.invoke()
        return true
    }
}
