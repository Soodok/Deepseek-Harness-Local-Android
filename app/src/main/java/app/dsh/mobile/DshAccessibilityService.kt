package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务（m1.30）：为 AI 提供「模拟屏幕点击」能力。
 *
 * 通过 AccessibilityService.dispatchGesture 注入合成手势（点击/滑动），
 * 需用户在系统设置里手动开启本服务（Android 无障碍安全模型不允许应用
 * 自行开启，只能跳转引导）。允许模拟屏幕点击已是 Android 无障碍的标准场景。
 *
 * 由于无障碍服务只能由用户开启，本类自身处理 OnAccessibilityEvent 的常规流，
 * 对外通过 companion 暴露一个「服务是否已启用」静态标志 + 模拟点击入口。
 */
class DshAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 常规无障碍事件流：本服务暂不监听特定事件，保留空实现以符合契约
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: DshAccessibilityService? = null

        /** 服务是否已启用（用户在系统设置开启后为 true） */
        fun isEnabled(): Boolean = instance != null

        /** 是否支持手势注入（API 24+ 且服务已连接） */
        fun canTap(): Boolean = instance != null && Build.VERSION.SDK_INT >= 24

        /**
         * 模拟点击屏幕 (x, y)（物理像素坐标）。
         * @return true 表示已成功派发合成点击
         */
        fun tap(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < 24) return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            return svc.dispatchGesture(gesture, null, null)
        }
    }
}
