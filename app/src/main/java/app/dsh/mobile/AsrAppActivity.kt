package app.dsh.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 本地语音识别应用（v1.2.84）—— 一键跳转下载。
 *
 * ## 为什么做成独立页面
 * 主人要求「一键安装，直接给下载链接，一键跳转下载」，且**跳浏览器**
 * （而不是 App 内下载安装 —— 那要额外申请「安装未知应用」权限）。
 *
 * ## 为什么链接指向 GitHub Releases 而不是 F-Droid
 * 实测本机与服务器**都连不上 f-droid.org**（国内访问受限，curl 返回 000），
 * 而 GitHub 三个仓库均 200 可达；Sayboard 的 release 里还有 **arm64 直链 APK**
 * （11MB，浏览器点一下即下）。所以优先给直链，GitHub 仓库页作为备选。
 *
 * ## 装完怎么用
 * 系统设置 → 语言和输入法 → 语音输入 → 选中它。
 * （本应用已声明 `<queries>` 可见性，装完即可被识别到。）
 */
class AsrAppActivity : Activity() {

    /** 一个推荐项：标题 / 说明 / 下载地址 */
    private data class Entry(val titleRes: Int, val descRes: Int, val url: String)

    private val entries = listOf(
        // Sayboard：有 arm64 直链 APK（实测 GitHub API 确认），推荐
        Entry(R.string.asrapp_sayboard, R.string.asrapp_sayboard_desc,
            "https://github.com/ElishaAz/Sayboard/releases/download/v4.2.1/Sayboard_arm64-v8a.apk"),
        Entry(R.string.asrapp_sayboard_repo, R.string.asrapp_repo_desc,
            "https://github.com/ElishaAz/Sayboard"),
        Entry(R.string.asrapp_whisperime, R.string.asrapp_whisperime_desc,
            "https://github.com/woheller69/whisperIME/releases"),
        Entry(R.string.asrapp_whisperimeplus, R.string.asrapp_whisperimeplus_desc,
            "https://github.com/woheller69/whisperIMEplus/releases"),
        Entry(R.string.asrapp_fdroid, R.string.asrapp_fdroid_desc,
            "https://f-droid.org/packages/com.elishaazaria.sayboard/"),
    )

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr_app)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val host = findViewById<LinearLayout>(R.id.asrAppList)
        entries.forEach { e ->
            val row = layoutInflater.inflate(R.layout.item_asr_app, host, false)
            row.findViewById<TextView>(R.id.appTitle).setText(e.titleRes)
            row.findViewById<TextView>(R.id.appDesc).setText(e.descRes)
            row.findViewById<TextView>(R.id.appUrl).text = e.url
            row.setOnClickListener { openUrl(e.url) }
            host.addView(row)
        }
    }

    /** 跳系统浏览器（不申请安装权限，下载由浏览器负责） */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(this, getString(R.string.asrapp_open_failed, it.message.orEmpty()),
                Toast.LENGTH_LONG).show()
        }
    }
}
