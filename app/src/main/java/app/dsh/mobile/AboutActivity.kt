package app.dsh.mobile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * 关于页（MIUI 风格）：应用标识卡 + 介绍/特性 + 开源地址（点击复制）。
 * 固定竖屏，与设置页一致。
 */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_about)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.txtVersion).text =
            getString(R.string.about_version, versionName())

        // 开源地址：点击复制到剪贴板（WebView 只放行回环，外链交系统浏览器亦可，复制更轻）
        findViewById<android.view.View>(R.id.rowRepo).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dsh-android", getString(R.string.about_repo)))
            Toast.makeText(this, "已复制仓库地址", Toast.LENGTH_SHORT).show()
        }
    }

    /** 从包管理器读取 versionName（与设置页同源） */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"
}
