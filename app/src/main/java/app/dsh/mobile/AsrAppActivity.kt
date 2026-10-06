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
 * 语音识别服务页（v1.2.93）—— 单一推荐 + 配置教程。
 *
 * ## 为什么只剩我们自己这一个
 * 主人要求：「在 dsh mobile 里面的语音服务建议的应用就直接贴我们那个 ASR 的 GitHub
 * release 地址，然后其他应用都取消掉」。
 * 第三方（Sayboard / whisperIME / F-Droid）全部移除 —— 我们自己的服务是系统级
 * RecognitionService，质量与集成度都更好，没必要再让用户去装别家的。
 *
 * ## 空出来的地方做成教程
 * 主人要求：「空掉地方则做一个教程，教该如何正确下载，下载完如何在里面配置模型，
 * 配置完之后如何在 dsh mobile 里激活」。
 * 于是本页 = 一张主卡片（下载/已装则打开）+ 四步教程 + 排错提示 + 项目主页链接。
 * 四步与 README / release notes 保持一致：
 *   ① 下载 APK ② 打开下载模型 ③ 设为系统识别服务 ④ 在 DSH Mobile 里启用
 */
class AsrAppActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr_app)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // ---- 主卡片：我们的识别服务（已装 → 打开；未装 → 去 Releases 下载）----
        val installed = isPluginInstalled()
        findViewById<TextView>(R.id.oursTitle).setText(R.string.asrapp_ours)
        findViewById<TextView>(R.id.oursDesc).setText(R.string.asrapp_ours_desc)
        findViewById<TextView>(R.id.oursAction).setText(
            if (installed) R.string.asrapp_installed_open else R.string.asrapp_download
        )
        findViewById<View>(R.id.oursCard).setOnClickListener {
            if (installed) openPlugin() else openUrl(RELEASE_PAGE)
        }

        // ---- 四步教程 ----
        bindStep(R.id.step1Title, R.id.step1Body, R.string.asrapp_step1_title, R.string.asrapp_step1_body)
        bindStep(R.id.step2Title, R.id.step2Body, R.string.asrapp_step2_title, R.string.asrapp_step2_body)
        bindStep(R.id.step3Title, R.id.step3Body, R.string.asrapp_step3_title, R.string.asrapp_step3_body)
        bindStep(R.id.step4Title, R.id.step4Body, R.string.asrapp_step4_title, R.string.asrapp_step4_body)

        // ---- 排错 ----
        findViewById<TextView>(R.id.troubleTitle).setText(R.string.asrapp_trouble_title)
        findViewById<TextView>(R.id.troubleBody).setText(R.string.asrapp_trouble_body)

        // ---- 项目主页 ----
        findViewById<TextView>(R.id.repoTitle).setText(R.string.asrapp_repo_title)
        findViewById<TextView>(R.id.repoDesc).setText(R.string.asrapp_repo_desc)
        findViewById<View>(R.id.repoCard).setOnClickListener { openUrl(REPO_PAGE) }
    }

    private fun bindStep(titleId: Int, bodyId: Int, titleRes: Int, bodyRes: Int) {
        findViewById<TextView>(titleId).setText(titleRes)
        findViewById<TextView>(bodyId).setText(bodyRes)
    }

    /** DSH 离线识别服务的包名（同源项目 dsh-asr-service） */
    private fun isPluginInstalled(): Boolean = runCatching {
        packageManager.getPackageInfo(PLUGIN_PKG, 0)
        true
    }.getOrDefault(false)

    /** 打开识别服务应用（它没有桌面图标，只能靠自定义 action 拉起） */
    private fun openPlugin() {
        runCatching {
            startActivity(
                Intent("app.dsh.asr.OPEN").setPackage(PLUGIN_PKG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            // 兜底：用 launch intent（万一 action 被改过）
            runCatching {
                packageManager.getLaunchIntentForPackage(PLUGIN_PKG)?.let { startActivity(it) }
            }.onFailure {
                Toast.makeText(this, getString(R.string.asrapp_open_failed, it.message.orEmpty()),
                    Toast.LENGTH_LONG).show()
            }
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

    companion object {
        /** 我们自己的识别服务（dsh-asr-service） */
        private const val PLUGIN_PKG = "app.dsh.asr"

        /**
         * Release 页面 —— 直接指向最新 release，用户点进去就能看到 APK 附件。
         * 用 /releases/latest 而不是具体 tag，这样以后发新版不用改代码。
         */
        private const val RELEASE_PAGE = "https://github.com/Soodok/dsh-asr-service/releases/latest"
        private const val REPO_PAGE = "https://github.com/Soodok/dsh-asr-service"
    }
}
