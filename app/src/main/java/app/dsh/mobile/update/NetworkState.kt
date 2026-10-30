package app.dsh.mobile.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

/**
 * 网络状态判定（"仅 WiFi 下静默下载"的依据）。
 *
 * 全仓库原先**没有任何** ConnectivityManager 用法（`docs/AUTO_UPDATE_PLAN.md` §2.2 D5），
 * 所以这块是新建的。
 *
 * ## 为什么用"不计费"而不是"是不是 WiFi"
 * 用户的真实诉求是"别偷偷花我流量"，而不是"必须走 WiFi 这个名字"。以太网、
 * USB 网络共享、部分运营商免费热点在技术上都属"不计费"，把它们排除掉只会让
 * 用户困惑"我明明连着网为什么不下载"。所以判据是 `NET_CAPABILITY_NOT_METERED`，
 * 而不是 `TRANSPORT_WIFI`。
 */
object NetworkState {

    private fun cm(ctx: Context): ConnectivityManager? =
        ctx.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun isOnline(ctx: Context): Boolean {
        val active = cm(ctx)?.activeNetwork ?: return false
        val caps = cm(ctx)?.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 当前网络是否不计费（WiFi / 以太网 / USB 共享）。无网络时返回 false。 */
    fun isUnmetered(ctx: Context): Boolean {
        val active = cm(ctx)?.activeNetwork ?: return false
        val caps = cm(ctx)?.getNetworkCapabilities(active) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
        // 极少数 ROM 不报 NOT_METERED，但对 WiFi/以太网仍报 transport，补一层兜底。
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** 当前是不是蜂窝网络（用于文案"当前非 WiFi"）。 */
    fun isCellular(ctx: Context): Boolean {
        val active = cm(ctx)?.activeNetwork ?: return false
        val caps = cm(ctx)?.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * 网络可用/转为不计费时回调（用于"移动网络下没下，回 WiFi 自动开始"）。
     *
     * 返回的 callback 需要调用方在生命周期结束时 `unregisterNetworkCallback` —— 
     * 但本 App 的调度器绑进程生命周期，进程退出时系统自动清理，故只在应用内
     * 常驻单例里注册一次，不额外注销。
     */
    fun watchUnmetered(ctx: Context, onChange: (Boolean) -> Unit): ConnectivityManager.NetworkCallback? {
        val manager = cm(ctx) ?: return null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { onChange(isUnmetered(ctx)) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                runCatching { onChange(isUnmetered(ctx)) }
            }

            override fun onLost(network: Network) {
                runCatching { onChange(isUnmetered(ctx)) }
            }
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(callback)
            } else {
                @Suppress("DEPRECATION")
                manager.registerNetworkCallback(android.net.NetworkRequest.Builder().build(), callback)
            }
            callback
        }.getOrNull()
    }
}
