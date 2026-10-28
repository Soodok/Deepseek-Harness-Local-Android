package app.dsh.mobile.update

import android.util.Log

/** 选包结果。 */
sealed interface PickResult {
    /**
     * 唯一确定的包。
     * @param runtimePrepare 装完首启是否需要重解压运行时（会提示"首次启动 1–3 分钟"）
     */
    data class Single(
        val asset: IndexAsset,
        val mode: ModeNote,
        val runtimePrepare: Boolean,
    ) : PickResult

    /**
     * 运行时有变更，两种形态都存在 → 交给用户选（F2）。
     * @param appOnly 只更新应用（noruntime，体积小、秒起）
     * @param withRuntime 连运行时一起更新（full，体积大、首启重解压）
     */
    data class AskUser(
        val appOnly: IndexAsset,
        val withRuntime: IndexAsset,
    ) : PickResult

    /** 索引里没有匹配当前环境的资产（如 debug 构建只看到 release 包）。 */
    data object NoAsset : PickResult
}

/**
 * 资产选择（纯函数，不依赖 Android 框架 → 可被 `scripts/probes/_test_update_asset_picker.py`
 * 用同一套规则复刻断言）。
 *
 * 匹配维度：`(variant, abi)` + 形态决策。**绝不按文件名猜** —— 这是旧
 * `UpdateChecker` 两个事故的根因。
 */
object UpdateAssetPicker {

    private const val TAG = "UpdatePicker"

    /**
     * @param strategy [AppUpdatePrefs.STRATEGY_AUTO] / [AppUpdatePrefs.STRATEGY_KEEP]
     */
    fun pick(index: UpdateIndex, env: RuntimeEnv, strategy: String): PickResult {
        val sameVariant = index.assets.filter { it.variant == env.variant }
        val sameAbi = sameVariant.filter { it.abi == env.abi }
        if (sameAbi.isEmpty()) {
            Log.i(TAG, "no asset for variant=${env.variant} abi=${env.abi}; have=${index.assets.map { it.name }}")
            return PickResult.NoAsset
        }
        val full = sameAbi.firstOrNull { it.isFull }
        val noRuntime = sameAbi.firstOrNull { it.isNoRuntime }

        return when (strategy) {
            AppUpdatePrefs.STRATEGY_KEEP -> pickKeepMode(env, full, noRuntime)
            else -> pickAuto(env, full, noRuntime)
        }
    }

    /** 保守策略：尽量与当前形态一致（不确定的地方就不改）。 */
    private fun pickKeepMode(
        env: RuntimeEnv,
        full: IndexAsset?,
        noRuntime: IndexAsset?,
    ): PickResult {
        if (env.packageMode == IndexAsset.NORUNTIME) {
            noRuntime?.let { return PickResult.Single(it, ModeNote.KEPT_RUNTIME, false) }
            // 索引里没有 noruntime（发布侧缺失）→ 退回 full，但要提示会重解压
            full?.let {
                val changed = runtimeChanged(it, env)
                return PickResult.Single(it, ModeNote.ADDED_RUNTIME, changed)
            }
            return PickResult.NoAsset
        }
        // 当前是 full
        full?.let {
            val changed = runtimeChanged(it, env)
            return PickResult.Single(it, ModeNote.KEPT_RUNTIME, changed)
        }
        noRuntime?.let { return PickResult.Single(it, ModeNote.FULL_TO_NORUNTIME, false) }
        return PickResult.NoAsset
    }

    /** 智能策略（F2）：运行时没变更就省 250MB；有变更让用户决定要不要现在换。 */
    private fun pickAuto(
        env: RuntimeEnv,
        full: IndexAsset?,
        noRuntime: IndexAsset?,
    ): PickResult {
        if (env.packageMode == IndexAsset.NORUNTIME) {
            // 本来就是精简包：优先继续精简；只有索引缺 noruntime 时才退 full。
            noRuntime?.let { return PickResult.Single(it, ModeNote.KEPT_RUNTIME, false) }
            full?.let {
                return PickResult.Single(it, ModeNote.ADDED_RUNTIME, runtimeChanged(it, env))
            }
            return PickResult.NoAsset
        }

        // 当前是 full
        if (full == null) {
            // 发布侧没给 full 包 → 只能拿 noruntime（运行时保持本机现状，不重解压）
            noRuntime?.let { return PickResult.Single(it, ModeNote.FULL_TO_NORUNTIME, false) }
            return PickResult.NoAsset
        }
        val changed = runtimeChanged(full, env)
        if (!changed) {
            // 运行时没变 → 优先 noruntime：省 ~250MB，且装完不用重解压
            noRuntime?.let { return PickResult.Single(it, ModeNote.FULL_TO_NORUNTIME, false) }
            return PickResult.Single(full, ModeNote.KEPT_RUNTIME, false)
        }
        // 运行时变了：两种都给用户看，让他自己权衡流量 vs 首启耗时
        noRuntime?.let { return PickResult.AskUser(appOnly = it, withRuntime = full) }
        return PickResult.Single(full, ModeNote.ADDED_RUNTIME, true)
    }

    /**
     * 远端的 full 包是否携带**比本机更新**的运行时。
     *
     * 判据优先用 `runtimeBuiltAt`（与 `RuntimeInstaller.ensureInstalled()` 内部
     * `installed.builtAt > manifest.runtime.builtAt` 同源），缺失时退回版本字符串比较；
     * 两者都没有时按"没变"处理 —— 宁可少下一个大包，也不要无谓地让用户重解压 580MB。
     */
    fun runtimeChanged(full: IndexAsset, env: RuntimeEnv): Boolean {
        val remoteBuiltAt = full.runtimeBuiltAt
        val localBuiltAt = env.installedRuntimeBuiltAt
        if (remoteBuiltAt > 0 && localBuiltAt > 0) return remoteBuiltAt > localBuiltAt
        val remoteVersion = full.runtimeVersion
        if (remoteVersion.isNullOrBlank()) return false
        val localVersion = env.installedRuntimeVersion
        // 本机还没装运行时（noruntime 首启中断等）→ 装上就是"变更"
        if (localVersion.isNullOrBlank()) return true
        return remoteVersion != localVersion
    }
}
