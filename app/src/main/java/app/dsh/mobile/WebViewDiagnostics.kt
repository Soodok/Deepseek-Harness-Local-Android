package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.webkit.WebView

/**
 * WebView 内核诊断（v1.6.8）。
 *
 * ## 为什么要独立一个 object
 * 版本判定原先散落在 `MainActivity.currentWebViewVersion()` 里（且是 private），
 * 设置页、关于页想显示同一份信息就得复制一遍。更要紧的是**判定本身有缺陷**：
 *
 *  ① 门槛写的是 Chrome 85，但前端 bundle 的真实语法门槛是 **Chrome 94**
 *     （依据见 [MIN_CHROME] 注释）。85–93 的设备会**通过门禁然后白屏** ——
 *     连一句提示都没有，比「提示版本过低」体验更糟。
 *  ② 版本读不到时返回 0，而 `0 < 85` 成立 → 误判成「版本过低」并弹框。
 *     `WebView.getCurrentWebViewPackage()` 在 `onResume` 时序上本就会返回 null
 *     （provider 尚未敲定），这是**正常设备**，不该被当成故障。现在拆出
 *     [Cause.UNKNOWN]：读不到 ≠ 太旧，走「放行 + 记日志」，不打扰用户。
 *
 * ## 「内置一个 WebView 内核」为什么不行（核实于 2026-11-05）
 * 本对象的引导逻辑围绕这个结论设计，不要试图改成「给 App 自带内核」：
 *  · Chromium 官方文档 `android_webview/docs/webview-providers.md` 明确三条硬约束：
 *    包名白名单（`com.google.android.webview` / `com.android.webview` 等，
 *    用户构建上仅前者）、**release 签名校验**（"only permitting apps signed with
 *    the expected release keys"）、`targetSdkVersion >= Build.VERSION.SDK_INT`。
 *    三者由系统 WebViewUpdateService 强制，第三方 App 无法成为 provider。
 *  · 于是「下载一个 WebView 包装上」只在 root 后改系统镜像才成立，普通用户不可达。
 *  · 唯一能打进 APK 的独立内核是 GeckoView，但它不是 WebView 的替代品：
 *    实测 `org.mozilla.geckoview:geckoview-arm64-v8a` 的 AAR 为 **91,498,586 字节**
 *    （约 87 MB / 单 abi），且渲染 API 完全不同（GeckoSession/GeckoView），
 *    需要改写全部 WebView 调用点。代价与收益不匹配，故不采用。
 *
 * 因此本模块只做**平台允许范围内能做的三件事**：准确诊断、清楚告知、给出
 * 真正能出界面的出路（升级内核 / 用系统浏览器打开 / 用户坚持）。
 */
object WebViewDiagnostics {

    private const val TAG = "WebViewDiag"

    /**
     * 真正的最低内核版本（Chrome 大版本）。
     *
     * ## 为什么是 94 而不是 85
     * 85 是「API 层门槛」——`String#replaceAll` 需要 Chrome 85，polyfill 能补。
     * 但 bundle 里还有 **语法层** 特性，polyfill 补不了（解析期就报 SyntaxError）：
     *  · `class static {}` 静态初始化块 → **Chrome 94**（ES2022）
     *  · `#privateField` / `#privateMethod` 私有成员 → Chrome 84
     *  · `??=` / `||=` / `&&=` 逻辑赋值 → Chrome 85
     *  · `?.` / `??` → Chrome 80
     * 只有在 **Chrome 94** 才全部可用，故取最大者。
     *
     * 实测依据（2026-11-05，acorn 逐级降版解析 dist/DSH-Mobile-1.4.17 内
     * `assets/runtime.zip` 抽出的 61 个前端 chunk）：
     *  · 61/61 chunk 在 es2022 全部解析通过，0/61 能在 es2021 通过
     *  · 入口 chunk `assets/index-*.js` 含 2 处 `static {}`（字节偏移 15932、29510）
     *  · `assets/vendor-*.js` 含 5 个私有字段 + 1 个私有方法
     * 这两个 chunk 由 `index.html` 直接 `<script type="module">` / `modulepreload`
     * 加载，**必然执行**，所以门槛不可回避。
     */
    const val MIN_CHROME = 94

    /** 已知的 WebView provider 包名。系统对该查询有 visibility 豁免，无需 QUERY_ALL_PACKAGES。 */
    private val KNOWN_PROVIDERS = listOf(
        "com.google.android.webview",
        "com.android.webview",
        "com.google.android.trichromelibrary",
        "com.miui.webview",
        "com.huawei.webview",
        "com.hihonor.webview",
        "com.oplus.webview",
        "com.heytap.webview",
    )

    /** 版本探测的三种归宿。注意 [UNKNOWN] 与 [TOO_OLD] 语义完全不同。 */
    enum class Cause {
        /** 读到了版本且达标 */
        OK,

        /** 读到了版本，但低于 [MIN_CHROME] */
        TOO_OLD,

        /** 一条探测路径都没成功（`getCurrentWebViewPackage()` 时序上返回 null 等） */
        UNKNOWN,
    }

    /**
     * 一次诊断的完整结论。
     *
     * @param chrome          解析出的 Chrome 大版本；0 表示没读到
     * @param provider        provider 包名；null 表示没读到
     * @param providerVersion provider 的 versionName（如 `141.0.7390.122`）
     */
    data class Report(
        val chrome: Int,
        val provider: String?,
        val providerVersion: String?,
        val cause: Cause,
    ) {
        /** 是否允许启动引擎。UNKNOWN 放行（读不到不等于太旧，误伤正常设备更糟）。 */
        val allowed: Boolean get() = cause != Cause.TOO_OLD
    }

    /**
     * 诊断当前 WebView 内核。
     *
     * 三级探测，前一级失败才走下一级：
     *  ① [WebView.getCurrentWebViewPackage]（API 26+，官方 API）
     *  ② 扫 [KNOWN_PROVIDERS] 的 versionName
     *  ③ 传入的 [userAgent] 里的 `Chrome/NN`（调用方有实例时才有值）
     *
     * @param userAgent 当前 WebView 实例的 UA；不传则跳过第 ③ 级。
     *                  刻意不在本函数里自建 WebView 取 UA —— 首次构造会触发
     *                  Chromium 初始化（实测约 700ms）且必须在 UI 线程。
     */
    fun inspect(context: Context, userAgent: String? = null): Report {
        // ① 官方 API
        runCatching {
            val pkg = WebView.getCurrentWebViewPackage()
            if (pkg != null) {
                val v = parseChromeVersion(pkg.versionName)
                if (v > 0) {
                    return Report(v, pkg.packageName, pkg.versionName, classify(v))
                }
                // 读到了包但解析不出大版本：记下包名继续往下试
                Log.w(TAG, "provider ${pkg.packageName} version=${pkg.versionName} unparsable")
            }
        }.onFailure { Log.w(TAG, "getCurrentWebViewPackage failed: ${it.message}") }

        // ② 扫已知 provider
        for (pkgName in KNOWN_PROVIDERS) {
            val info = runCatching { context.packageManager.getPackageInfo(pkgName, 0) }
                .getOrNull() ?: continue
            val v = parseChromeVersion(info.versionName)
            if (v > 0) {
                return Report(v, pkgName, info.versionName, classify(v))
            }
        }

        // ③ 调用方传入的 UA（当前实例的 `settings.userAgentString`）。
        //    UA 里的 `Chrome/NN` 永远反映真正在干活的内核，与 provider 包名无关 ——
        //    前两级都可能因 ROM 改包名而失效，这条兜底。
        //
        //    ⚠️ 不在这里自己 new 一个 WebView 取 UA：WebView 首次构造会触发
        //    Chromium 初始化（实测约 700ms），为读一个版本号付这个代价不值得，
        //    而且必须在 UI 线程。调用方本来就有实例，传进来即可。
        val uaVersion = userAgent
            ?.let { Regex("Chrome/(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (uaVersion != null && uaVersion > 0) {
            return Report(
                uaVersion,
                runCatching { WebView.getCurrentWebViewPackage()?.packageName }.getOrNull(),
                null,
                classify(uaVersion),
            )
        }

        // ④ 都失败 —— 读不到，不是太旧
        Log.w(TAG, "webview version undetectable on this device; treating as unknown")
        return Report(0, null, null, Cause.UNKNOWN)
    }

    /**
     * 版本号 → Chrome 大版本。
     *
     * provider 的 versionName 形如 `141.0.7390.122`，首段即 Chrome 大版本。
     * 少数 ROM 会写成 `Chrome/141.0.7390.122` 或含额外前缀，故先试 UA 形态再退回首段。
     */
    private fun parseChromeVersion(versionName: String?): Int {
        if (versionName.isNullOrBlank()) return 0
        Regex("Chrome/(\\d+)").find(versionName)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { if (it > 0) return it }
        return versionName.substringBefore('.').trim().toIntOrNull() ?: 0
    }

    private fun classify(chrome: Int): Cause =
        if (chrome >= MIN_CHROME) Cause.OK else Cause.TOO_OLD

    /**
     * 升级引导的目标，按「成功率」从高到低排。
     *
     * 顺序有讲究：Play 商店能一条 Intent 直达 WebView 的应用详情页（用户点一下就更新）；
     * 国产 ROM 大多没有 Play，但有厂商商店；两者都没有时才退到系统「应用详情」——
     * 那里只能看不能更新，但至少告诉用户内核是哪个包。
     *
     * @return 可尝试的 Intent 列表，调用方按序 startActivity 直到成功（含 `resolveActivity` 校验）
     */
    fun updateIntents(context: Context, report: Report): List<Intent> {
        val pkg = report.provider ?: "com.google.android.webview"
        val out = mutableListOf<Intent>()

        // ① Play 商店（仅当设备真的装了 Play，否则 market:// 会抛 ActivityNotFound）
        if (isPackageInstalled(context, "com.android.vending")) {
            out += Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        }
        // ② 厂商应用商店的详情页（华为/小米/OPPO/vivo 的 market:// 协议同名不同宿主）
        val vendorStores = listOf(
            "com.huawei.appmarket",
            "com.xiaomi.market",
            "com.oppo.market",
            "com.heytap.market",
            "com.bbk.appstore",
            "com.sec.android.app.samsungapps",
        )
        if (vendorStores.any { isPackageInstalled(context, it) }) {
            out += Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        }
        // ③ 兜底：系统应用详情页（任何设备都有）
        out += Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$pkg"),
        )
        // ④ 终极兜底：纯文本告知包名，让用户自己搜
        out += Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))

        return out.filter { it.resolveActivity(context.packageManager) != null }
    }

    /**
     * 挑一个能真正把 WebUI 显示出来的外部浏览器。
     *
     * ## 为什么这条兜底可行
     * 引擎只监听 `127.0.0.1`，而**回环地址在设备自身的任何浏览器里都能访问** ——
     * 外部浏览器与 App 处于同一网络命名空间。且 `http://127.0.0.1` 属于
     * "potentially trustworthy origin"，是 secure context，`crypto.randomUUID`
     * 等只有安全上下文才有的 API 正常可用（对照：局域网 IP 不是 secure context，
     * 所以 LAN WebUI 才需要那个注入 randomUUID 的反向代理）。
     *
     * 于是「WebView 太旧无法渲染」时，把同一个带 token 的 URL 交给系统浏览器，
     * 界面照样能用——功能全在引擎侧，WebView 只是个显示器。
     *
     * @return 可用于打开 [url] 的 Intent；没有浏览器时返回 null
     */
    fun browserFallbackIntent(context: Context, url: String): Intent? {
        if (url.isEmpty()) return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /**
     * 给日志用的一行摘要（用户报障时贴出来即可定位）。
     *
     * 注意此串只进日志、不进 UI，故用英文保持与既有 `DshWebView` 日志风格一致；
     * 面向用户的文案放在五个 strings.xml 里（键名以 `webview_gate_` 开头）。
     */
    fun summary(report: Report): String =
        "chrome=${if (report.chrome > 0) report.chrome else "?"} " +
            "min=$MIN_CHROME provider=${report.provider ?: "?"} " +
            "version=${report.providerVersion ?: "?"} cause=${report.cause}"
}
