package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * 状态栏设置（v1.5.x）：展开态 / 收起态两个入口。
 *
 * 两个形态差异很大 —— 展开态是长条胶囊（可以换背景图、贴贴纸），
 * 收起态是正圆或自定义图片（形状/尺寸可调）—— 所以各给一个子页。
 */
class StatusBarActivity : ThemedActivity() {

    /** 属于设置体系：启用「设置页背景图」。 */
    override val usesSettingsBackground: Boolean get() = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_status_bar)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<LinearLayout>(R.id.rowExpanded).setOnClickListener {
            startActivity(Intent(this, ExpandedStatusBarActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.rowCollapsed).setOnClickListener {
            startActivity(Intent(this, IslandStatusActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 探针契约：设置类页面每次回到前台都要重新对齐朝向（从外观页返回时可能已改）
        OrientationPrefs.applyToSettingsPage(this)
    }
}
