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

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

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
            Toast.makeText(this, getString(R.string.about_copied), Toast.LENGTH_SHORT).show()
        }

        // 检查更新（v1.2.77）：查 GitHub Releases 是否有更高版本
        // 网络请求必须离开主线程（否则 ANR）；结果回主线程更新文案
        findViewById<android.view.View>(R.id.rowUpdate).setOnClickListener {
            // 已发现新版本 → 本次点击是"前往下载"
            val target = pendingReleaseUrl
            if (target != null) {
                openUrl(target)
                return@setOnClickListener
            }
            checkForUpdate()
        }
    }

    /** 待跳转的 Release 地址（检测到新版本后由点击触发跳转） */
    private var pendingReleaseUrl: String? = null

    /** 检测更新：后台请求 → 主线程回显 */
    private fun checkForUpdate() {
        val tv = findViewById<TextView>(R.id.txtUpdate)
        tv.text = getString(R.string.about_update_checking)
        val current = versionName()
        Thread({
            val r = app.dsh.mobile.engine.UpdateChecker.check(this, current)
            runOnUiThread {
                when {
                    r.hasUpdate -> {
                        pendingReleaseUrl = r.releaseUrl
                        tv.text = getString(R.string.about_update_available, r.latestTag)
                        tv.setTextColor(0xFF7DD3FC.toInt())
                    }
                    r.error.isNotEmpty() -> {
                        tv.text = getString(R.string.about_update_failed, r.error)
                        tv.setTextColor(0xFF8A94A3.toInt())
                    }
                    else -> {
                        tv.text = getString(R.string.about_update_latest)
                        tv.setTextColor(0xFF81C995.toInt())
                    }
                }
            }
        }, "update-check").apply { isDaemon = true; start() }
    }

    /** 用系统浏览器打开外链（本 App 的 WebView 只放行回环地址，外链必须交系统） */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(this, getString(R.string.about_update_failed, it.message.orEmpty()), Toast.LENGTH_LONG).show()
        }
    }

    /** 从包管理器读取 versionName（与设置页同源） */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"
}
