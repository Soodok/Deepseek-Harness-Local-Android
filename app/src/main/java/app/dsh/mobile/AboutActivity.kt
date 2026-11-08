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
    /** 属于设置体系：启用「设置页背景图」（见 ThemedActivity.usesSettingsBackground）。 */
    override val usesSettingsBackground: Boolean get() = true


    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
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

        // 开源地址：直接交给系统浏览器打开，不再要求用户复制粘贴。
        findViewById<android.view.View>(R.id.rowRepo).setOnClickListener {
            val url = "https://github.com/" + RepoConfig.REPO
            runCatching {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            }.onFailure {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dsh-android", url))
                Toast.makeText(this, getString(R.string.about_copied), Toast.LENGTH_SHORT).show()
            }
        }

        // QQ 交流群：优先直接拉起 QQ 群名片；未安装 QQ 时回退到复制群号。
        findViewById<android.view.View>(R.id.rowQqGroup).setOnClickListener {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&card_type=group&uin=1128769016"),
            )
            runCatching { startActivity(intent) }.onFailure {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("qq-group", getString(R.string.about_qq_group_id)))
                Toast.makeText(this, getString(R.string.about_qq_group_copied), Toast.LENGTH_SHORT).show()
            }
        }

        // 求 star（v1.6.1）：直接跳仓库主页，不做「复制链接让用户自己粘」——
        // 复制那套对「支持一下」这种低意愿动作门槛太高，点了就该到地儿。
        // 落点是仓库根而不是 /stargazers：后者要登录后才有意义，未登录会跳登录页，
        // 登录完还要自己再找一次 star 按钮；仓库主页的 Star 按钮是首屏可见的。
        findViewById<android.view.View>(R.id.rowStar).setOnClickListener {
            val url = "https://github.com/" + RepoConfig.REPO
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url),
            )
            runCatching { startActivity(intent) }.onFailure {
                // 没有浏览器：退回复制，至少留一条路
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dsh-star", url))
                Toast.makeText(this, getString(R.string.about_copied), Toast.LENGTH_SHORT).show()
            }
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

        applyStarPromoAccent()
    }

    /**
     * 求 star 卡片的强调色跟随「配色方案」偏好。
     *
     * 布局里的 accent 是静态资源值，换配色时不会变（`AccentApply` 的注释也说了
     * 硬编码颜色在运行时无法改变，需要代码覆盖）。这里把卡片底与行动标签的文字
     * 都按当前配色重涂一遍，避免用户选了紫色配色、关于页还是默认天蓝的割裂感。
     */
    private fun applyStarPromoAccent() {
        val a = Accent.current(this)
        // 卡片底：重建 layer-list（accent 实心 + 白高光），圆角与静态定义保持一致
        findViewById<android.view.View>(R.id.rowStar)?.background =
            android.graphics.drawable.LayerDrawable(
                arrayOf(
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 18f * resources.displayMetrics.density
                        setColor(a.argb)
                    },
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 18f * resources.displayMetrics.density
                        colors = intArrayOf(0x33FFFFFF, 0x00FFFFFF)
                        orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                    },
                ).apply {
                    // 妙用：dimCards 只压 GradientDrawable 背景，LayerDrawable 不在里面，
                    // 所以有背景图时这张卡不会被降透明度（它是「广告」，本就该最亮）。
                },
            )
        findViewById<TextView>(R.id.txtStarAction)?.setTextColor(a.argb)
    }

    override fun onResume() {
        super.onResume()
        // 从外观页返回时偏好可能已变（开关/朝向），重新对齐一次；值未变时是空操作
        OrientationPrefs.applyToSettingsPage(this)
        // 回到前台：距上次检查超过 24h 则补一次（§6）；就绪/可用弹窗照常在本页触发
        AppUpdateScheduler.onForeground(this)
        UpdateDialogs.maybeShow(this)
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"
}
