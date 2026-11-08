package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.slider.Slider
import android.widget.TextView
import android.widget.Toast

/**
 * 收起态状态栏设置（v1.5.x）：状态栏收起成小圆点时显示什么。
 *
 * 三项：
 *  - 图片：自定义图标；不设则用默认的「深色圆 + 中心小亮核」；
 *  - 形状：圆形裁切 / 保留方形原图；
 *  - 尺寸：圆点边长 26~72dp。
 *
 * 改完立刻写偏好并刷新预览；回到主界面时 [MainActivity] 的
 * `applyIslandDotImage` 会按新值重绘，不需要重启。
 */
class IslandStatusActivity : ThemedActivity() {

    /** 属于设置体系：启用「设置页背景图」。 */
    override val usesSettingsBackground: Boolean get() = true

    private var shape = CustomVisualPref.DotShape.CIRCLE
    private lateinit var previewDot: android.view.ViewGroup
    private lateinit var txtHint: TextView
    private lateinit var txtSize: TextView
    private lateinit var seekSize: Slider

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_island_status)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        previewDot = findViewById(R.id.previewDot)
        txtHint = findViewById(R.id.txtDotHint)
        txtSize = findViewById(R.id.valDotSize)
        seekSize = findViewById(R.id.seekDotSize)

        shape = CustomVisualPref.islandDotShape(this)

        findViewById<LinearLayout>(R.id.rowShapeCircle).setOnClickListener {
            setShape(CustomVisualPref.DotShape.CIRCLE)
        }
        findViewById<LinearLayout>(R.id.rowShapeSquare).setOnClickListener {
            setShape(CustomVisualPref.DotShape.SQUARE)
        }
        findViewById<View>(R.id.btnPickDot).setOnClickListener { pickImage() }
        findViewById<View>(R.id.btnRemoveDot).setOnClickListener {
            CustomVisualPref.clearIslandDot(this)
            refreshPreview()
        }

        // MD3 Slider 以 dp 偏移值表示圆点尺寸，避免把绝对 dp 误当进度。
        seekSize.valueFrom = 0f
        seekSize.valueTo = (CustomVisualPref.DOT_SIZE_MAX_DP - CustomVisualPref.DOT_SIZE_MIN_DP).toFloat()
        seekSize.stepSize = 1f
        seekSize.value = (CustomVisualPref.islandDotSizeDp(this) - CustomVisualPref.DOT_SIZE_MIN_DP).toFloat()
        seekSize.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val size = CustomVisualPref.DOT_SIZE_MIN_DP + value.toInt()
            CustomVisualPref.setIslandDotSizeDp(this@IslandStatusActivity, size)
            refreshPreview()
        }
    }

    override fun onResume() {
        super.onResume()
        // 探针契约：设置类页面每次回到前台都要重新对齐朝向（从外观页返回时可能已改）
        OrientationPrefs.applyToSettingsPage(this)
        // 可能刚在外观页改过形状/尺寸，回来重新对齐
        shape = CustomVisualPref.islandDotShape(this)
        refreshPreview()
    }

    private fun setShape(next: CustomVisualPref.DotShape) {
        shape = next
        CustomVisualPref.setIslandDotShape(this, next)
        refreshPreview()
    }

    /**
     * 刷新预览。
     *
     * 直接调 [IslandDotRenderer.apply] —— 与主界面渲染圆点用的是**同一份代码**，
     * 所以"预览显示什么、收起后就是什么"：默认外观的中心小亮核、自定义图的
     * 圆形/方形、以及尺寸，全都一致。
     *
     * 尺寸上限压到 80dp：预览框只有 96dp，按实际 dp 渲染时超大会溢出。
     */
    private fun refreshPreview() {
        val sizeDp = CustomVisualPref.islandDotSizeDp(this)
        txtSize.text = getString(R.string.setting_dot_size_value, sizeDp)
        seekSize.value = (sizeDp - CustomVisualPref.DOT_SIZE_MIN_DP).toFloat()

        // 单选标记：当前形状那行显示勾，另一行隐藏（用 INVISIBLE 保持行高不跳）
        findViewById<ImageView>(R.id.chkShapeCircle).visibility =
            if (shape == CustomVisualPref.DotShape.CIRCLE) View.VISIBLE else View.INVISIBLE
        findViewById<ImageView>(R.id.chkShapeSquare).visibility =
            if (shape == CustomVisualPref.DotShape.SQUARE) View.VISIBLE else View.INVISIBLE

        IslandDotRenderer.apply(this, previewDot, sizeDp.coerceAtMost(80), centerInParent = true)

        txtHint.setText(
            if (CustomVisualPref.hasIslandDot(this)) R.string.setting_image_set
            else R.string.setting_image_none,
        )
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching { startActivityForResult(intent, REQ_PICK) }.onFailure {
            Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        val uri = data?.data ?: return
        if (!CustomVisualPref.importIslandDot(this, uri)) {
            Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
            return
        }
        refreshPreview()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_PICK = 4311
    }
}
