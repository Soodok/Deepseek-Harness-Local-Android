package app.dsh.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.RepoConfig
import app.dsh.mobile.update.AppUpdatePrefs
import app.dsh.mobile.update.AppUpdateScheduler
import app.dsh.mobile.update.UpdateDialogs

/**
 * 关于页（MIUI 风格）：应用标识卡 + 介绍/特性 + 开源地址（点击复制）。
 *
 * v1.4.58 起更新两行（rowUpdate/rowRuntime）迁出到 设置→系统→升级 子页，
 * 本页回归纯"身份信息"：版本 / 仓库 / 社群 / 隐私说明。
 * 更新状态机抽到了 [app.dsh.mobile.update.UpdateStatusCard]，升级子页持有。
 */
class AboutActivity : ThemedActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.forcePortrait(this)
        setContentView(R.layout.activity_about)

        // 归一化过时的"已下载/待装"状态（版本不比当前已安装的新 → 清掉），
        // onResume 里 UpdateDialogs.maybeShow 的就绪弹窗依赖干净状态（v1.4.56 修）。
        AppUpdatePrefs.clearStaleInstallState(this)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.txtVersion).text =
            getString(R.string.about_version, versionName())

        // fork 构建要显式声明身份：仓库地址按构建期 RepoConfig 显示，
        // 避免把 XiaoPb 的包看成上游正式包（两者签名与发版节奏都不同）。
        findViewById<TextView>(R.id.txtRepo).text = "github.com/" + RepoConfig.REPO
        findViewById<TextView>(R.id.txtForkBadge).visibility =
            if (RepoConfig.IS_FORK) android.view.View.VISIBLE else android.view.View.GONE

        // 开源地址：点击复制到剪贴板（WebView 只放行回环，外链交系统浏览器亦可，复制更轻）
        findViewById<android.view.View>(R.id.rowRepo).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(
                ClipData.newPlainText("dsh-android", "https://github.com/" + RepoConfig.REPO),
            )
            Toast.makeText(this, getString(R.string.about_copied), Toast.LENGTH_SHORT).show()
        }

        // QQ 交流群：点击复制群号 —— 与开源地址同样的轻交互。
        // 不尝试 deep link 拉起 QQ：加群链接格式各版本不一，未安装时会失败。
        findViewById<android.view.View>(R.id.rowQqGroup).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(
                ClipData.newPlainText("qq-group", getString(R.string.about_qq_group_id)),
            )
            Toast.makeText(this, getString(R.string.about_qq_group_copied), Toast.LENGTH_SHORT).show()
        }

        // Telegram 交流群（v1.4.38 上游）：与 QQ 不同，Telegram 有统一的 t.me 邀请链接，
        // 可以直接 ACTION_VIEW 打开；没装 Telegram 时系统会用浏览器兜底。
        // 链接里带邀请 token，所以放在 strings.xml 而不是写死在代码里。
        findViewById<android.view.View>(R.id.rowTelegram).setOnClickListener {
            val url = getString(R.string.about_telegram_url)
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url),
            )
            runCatching { startActivity(intent) }.onFailure {
                // 没有浏览器也没有 Telegram：复制链接，至少让用户能自己粘出去
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("telegram", url))
                Toast.makeText(this, getString(R.string.about_telegram_copied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台：距上次检查超过 24h 则补一次（§6）；就绪/可用弹窗照常在本页触发
        AppUpdateScheduler.onForeground(this)
        UpdateDialogs.maybeShow(this)
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"
}
