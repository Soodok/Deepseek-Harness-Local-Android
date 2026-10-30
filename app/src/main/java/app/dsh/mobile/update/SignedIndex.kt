package app.dsh.mobile.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import app.dsh.mobile.R
import app.dsh.mobile.engine.RuntimeDownloadConfig
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.X509EncodedKeySpec

/**
 * 签名索引抓取与验签（`update-index.json` / `runtime-index.json` 共用）。
 *
 * ## 为什么抓取规则必须单独写，不能复用 `RuntimeDownloadConfig.orderUrls()`
 * `orderUrls()` 是「**代理优先**、直连兜底」——对 250MB 的 APK 是对的（省 20s 超时），
 * 但对**哈希清单**是错的：清单决定了"什么才算正版包"，它要是被代理替换，
 * 后续的 sha pin 就全部失效。所以这里的方向是反的：
 *
 * ```
 * ① 首请求严格直连，绝不套任何代理前缀（探针 _test_update_index_direct.py 锁死）
 * ② 直连失败（超时/重置/DNS/非 2xx）才允许经代理重取 —— 且必须验签通过
 * ③ 验签不过 → 直接失败，绝不降级为"无签名也用"
 * ```
 *
 * ②这一步不是安全妥协：索引是 release key 签名的，代理改内容 → 验签失败 → 拒绝。
 * 真正的防线是签名，不是"直连"（直连也挡不住 DNS 劫持）。留这一步只是为了
 * 国内可用性 —— 否则自动更新在直连 GitHub 不通的网络里等于不存在。
 */
object SignedIndex {

    private const val TAG = "SignedIndex"
    private const val MAX_BYTES = 512 * 1024
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 20_000

    /** 索引拿不到（含验签失败）。调用方据此显示「无法获取更新信息」。 */
    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    sealed interface Result {
        data class Ok(
            val bytes: ByteArray,
            val signature: ByteArray,
            val url: String,
            val viaProxy: Boolean,
            val etag: String?,
        ) : Result

        /** 直连带 `If-None-Match` 命中 304：用本地缓存即可（缓存当初已验签）。 */
        data object NotModified : Result
    }

    /**
     * 取索引 + 签名。
     *
     * @param etag 上次成功响应的 ETag（仅对**直连**这次请求生效；经代理时不能带，
     *             否则代理可能返回自己缓存的 304 而内容与本地缓存不一致）
     */
    fun fetch(
        ctx: Context,
        url: String,
        allowProxyFallback: Boolean,
        proxy: String,
        etag: String? = null,
    ): Result {
        val sources = ArrayList<Pair<String, Boolean>>(2)
        sources.add(url to false)
        if (allowProxyFallback && proxy.isNotBlank()) {
            // 只要代理变体，直连已经在上面试过了 —— 不用 orderUrls() 的原始顺序，
            // 因为它的语义是"代理优先"，这里必须"直连优先"（见类注释）。
            RuntimeDownloadConfig.orderUrls(listOf(url), proxy)
                .filter { it != url && RuntimeDownloadConfig.isProxied(it, proxy) }
                .forEach { sources.add(it to true) }
        }

        var lastError: Exception? = null
        for ((source, viaProxy) in sources) {
            try {
                if (!isAllowedScheme(source, viaProxy, proxy)) {
                    throw SecurityException("refusing non-https index source: $source")
                }
                val (body, newEtag) = fetchBytes(source, if (viaProxy) null else etag)
                if (body == null) {
                    Log.i(TAG, "index not modified (direct, etag hit)")
                    return Result.NotModified
                }
                val sigBody = fetchBytes(source + ".sig", null).first
                    ?: throw IllegalStateException("HTTP 304 for signature")
                // 必须先验签，再交给任何解析代码（探针断言这一点）
                if (!verify(ctx, body, sigBody)) {
                    throw SecurityException("update-index signature mismatch ($source)")
                }
                Log.i(TAG, "index fetched: ${body.size}B via ${if (viaProxy) "proxy" else "direct"} ($source)")
                return Result.Ok(body, sigBody, source, viaProxy, newEtag)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "index fetch failed: $source: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw Unavailable(
            lastError?.message ?: "cannot fetch signed update-index",
            lastError,
        )
    }

    /**
     * https 强制。唯一例外：用户**自己配置的代理前缀本身就是 http**
     * （内容仍受签名约束，强行剔除等于把功能关掉）。
     */
    private fun isAllowedScheme(url: String, viaProxy: Boolean, proxy: String): Boolean {
        if (url.startsWith("https://", ignoreCase = true)) return true
        if (!url.startsWith("http://", ignoreCase = true)) return false
        if (!viaProxy) return false
        val base = proxy.trim().trimEnd('/')
        return base.startsWith("http://", ignoreCase = true) &&
            url.startsWith("$base/", ignoreCase = true)
    }

    /** @return (内容, ETag)；内容为 null 表示 304。 */
    private fun fetchBytes(url: String, etag: String?): Pair<ByteArray?, String?> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "dsh-mobile-update")
            setRequestProperty("Cache-Control", "no-cache")
            if (!etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return null to etag
            if (code !in 200..299) throw IllegalStateException("HTTP $code ($url)")
            val data = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(1 shl 14)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) {
                        throw IllegalStateException("index too large (> $MAX_BYTES bytes)")
                    }
                }
                out.toByteArray()
            }
            return data to conn.getHeaderField("ETag")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 签名校验。信任锚按优先级：
     *  1. 构建期从正式 keystore 导出的 `res/raw/runtime_index_pub.pem`
     *     （debug/preview 也能验证 release 签名的索引；Fork 构建自动换成自己的 key）；
     *  2. APK 自身的签名证书公钥（release 包无需内置 PEM，轮换 key 时自动跟随）。
     *
     * 两者任一通过即可；都不可用时**拒绝**（不降级为"无签名也用"）。
     */
    fun verify(ctx: Context, data: ByteArray, signature: ByteArray): Boolean {
        val sigBytes = runCatching {
            Base64.decode(String(signature, Charsets.US_ASCII).trim(), Base64.DEFAULT)
        }.getOrNull() ?: return false

        val keys = mutableListOf<PublicKey>()
        runCatching {
            val pem = ctx.resources.openRawResource(R.raw.runtime_index_pub)
                .bufferedReader().use { it.readText() }
            if (pem.isNotBlank()) publicKeyFromPem(pem)?.let { keys.add(it) }
        }.onFailure { Log.i(TAG, "bundled index pubkey unavailable: ${it.message}") }
        keys.addAll(appSigningKeys(ctx))

        if (keys.isEmpty()) {
            Log.w(TAG, "no index verification key (bundled PEM missing and signer unreadable)")
            return false
        }
        return keys.any { key ->
            runCatching {
                val algorithm = if (key.algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA"
                val sig = Signature.getInstance(algorithm)
                sig.initVerify(key)
                sig.update(data)
                sig.verify(sigBytes)
            }.getOrDefault(false)
        }
    }

    private fun publicKeyFromPem(pem: String): PublicKey? = runCatching {
        val der = Base64.decode(
            pem.replace(Regex("-----[^-]+-----"), "").replace(Regex("\\s"), ""),
            Base64.DEFAULT,
        )
        val spec = X509EncodedKeySpec(der)
        runCatching { KeyFactory.getInstance("EC").generatePublic(spec) }
            .getOrElse { KeyFactory.getInstance("RSA").generatePublic(spec) }
    }.getOrNull()

    /** APK 自身的签名证书公钥（API 28+ 用 signingInfo，26/27 回退 GET_SIGNATURES）。 */
    @Suppress("DEPRECATION")
    private fun appSigningKeys(ctx: Context): List<PublicKey> = runCatching {
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
                ?.mapNotNull { signatureToPublicKey(it) }
                ?: emptyList()
        } else {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            info.signatures?.mapNotNull { signatureToPublicKey(it) } ?: emptyList()
        }
    }.getOrDefault(emptyList())

    /** android.content.pm.Signature → X.509 证书 → 公钥（API 36 起 getPublicKey 已移除）。 */
    private fun signatureToPublicKey(sig: android.content.pm.Signature): PublicKey? = runCatching {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(sig.toByteArray()))
            .publicKey
    }.getOrNull()
}
