package app.dsh.mobile

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * 当前前台 Activity 追踪（v1.6.2）。
 *
 * ## 为什么需要
 * `GET/POST /dev/settings` 让 AI 能改 App 自己的设置。用户选了「直接生效，
 * 不询问」，所以改完必须让界面立刻反映出来 —— 否则 AI 说「已把主题改成深色」
 * 而屏幕毫无变化，用户会以为没生效。
 *
 * 这里的职责很窄：记住**当前处于 resumed 状态的 Activity**，供设置端点调用
 * `recreate()`（主题/配色/布局变了都要重建才生效，这是既有 UI 的约定，
 * 见 SettingsActivity.onResume 里比对 appliedTheme/appliedAccent/appliedLayout 后 recreate）。
 *
 * ## 为什么用弱引用
 * 这是个 Application 级的静态持有者，强引用 Activity 会漏一整个视图树。
 * 弱引用 + 在 `onActivityDestroyed` 里清空，双保险。
 *
 * ## 为什么不直接广播
 * 项目里没有任何 `registerOnSharedPreferenceChangeListener` / 刷新广播的既有机制
 * （17 个 `usesSettingsBackground` 页面全靠 `onResume` 自己重新对齐）。
 * 加一套广播要改 17 个页面，而这里只需要「让当前这个页面重建」，
 * 直接持有弱引用最省事，也不会改变既有页面的行为。
 */
object CurrentActivity {

    private var ref: WeakReference<Activity>? = null

    /** 当前 resumed 的 Activity；没有则为 null。 */
    fun get(): Activity? = ref?.get()?.takeIf { !it.isFinishing }

    /**
     * 让当前页面重建，使设置改动立刻可见。
     *
     * 必须在主线程调用 —— `Activity.recreate()` 只能在主线程跑。
     * 桥的请求线程不能直接调，所以这里自己 post 到主线程。
     *
     * @return true 表示找到了前台页面并已排队重建；false 表示当前没有前台页面
     *   （App 在后台），此时改动会在用户下次打开对应页面时自然生效。
     */
    fun recreateIfAny(): Boolean {
        val a = get() ?: return false
        a.runOnUiThread {
            // 二次确认：post 到主线程之间用户可能已经退出这个页面
            if (!a.isFinishing && !a.isDestroyed) a.recreate()
        }
        return true
    }

    /** 注册生命周期回调。由 [DshApp.onCreate] 调一次。 */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                ref = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                // 只在「暂停的正是当前记录的这个」时清空：
                // A 页面 paused → B 页面 resumed 时，resumed 会覆盖，不必在这里抢
                if (ref?.get() === activity) ref = null
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (ref?.get() === activity) ref = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }
}
