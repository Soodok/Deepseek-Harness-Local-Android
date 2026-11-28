package app.dsh.mobile.engine

/**
 * 「运行模式」的**权威切换**：写偏好不算成功，回读一致才算。
 *
 * ## 为什么需要独立一层
 *
 * 引擎到底跑在原生还是容器里，是 [EngineModeSelector.resolve] 在
 * [EngineSupervisor.spawnEngine] 里算出来的 —— 它同时看两件事：
 * 用户偏好（SharedPreferences 的布尔键）与 [LinuxEngineLauncher.readiness]
 * 的真实探测结果。于是「我把偏好写成 true 了」和「引擎真的会用 Linux 起」
 * 是两件事：rootfs 没部署、node 没装、SELinux 上下文漂移时，偏好为 true
 * 但引擎仍走原生（NATIVE_FALLBACK）。
 *
 * 面板上点一下必须给出**一句真话**。所以这里的顺序固定为：
 *
 *   1. 目标 == 当前判定值 → 幂等成功，不写偏好、不重算（无副作用）；
 *   2. 目标是 Linux → 先跑 readiness；不就绪就直接失败，**偏好一个字节都不动**
 *      （失败必须保留用户原来的选择，不能留下「偏好改了但引擎没切」的半截状态）；
 *   3. 就绪 → 落偏好 → 调 resolve() 拿到权威判定 → 与目标比对；
 *   4. 不一致（理论上只在探测期间环境变坏时出现）→ 回滚偏好并重算，报失败原因。
 *
 * ## 会不会「隐式提权」
 *
 * 不会**额外**提权。这条路径不主动请求 su/Shizuku，也不启动或停止引擎进程 ——
 * 重启是用户的显式动作，回执里的 [Receipt.restartRequired] 只做说明，不执行。
 *
 * 唯一可能的特权动作用在 readiness() 里，且与本机每次冷启完全同一条路径：
 * 探测到 SELinux 标签漂移时，它会走 Shizuku/su 去 `chcon` 自愈（没有通道就
 * 如实报 Unavailable，不静默放行）。也就是说，点一下切换**不会**带来一个新的
 * 权限面 —— 该自愈在引擎启动时本来就会跑，且幂等。
 *
 * ## 为什么用 [Port] 而不是直接吃 Context
 *
 * 上面每一步都是纯判断，只有「读偏好/跑探测/算模式」需要 Android。把这三件
 * 拆到接口后面，切换逻辑就能在 JVM 单测里用假端口把每条分支（含回滚）都走到，
 * 不必真机装 250MB 的 rootfs 才能验一次。
 */
object RuntimeModeAuthority {

    /** 面板能选的两个目标。SSH 不属于「本机运行模式」，不在此列。 */
    enum class Target(val wire: String) {
        NATIVE("native"),
        LINUX("linux"),
        ;

        companion object {
            fun fromWire(value: String?): Target? = when (value?.trim()?.lowercase()) {
                "native", "native_fallback" -> NATIVE
                "linux" -> LINUX
                else -> null
            }
        }
    }

    /**
     * 切换需要宿主提供的三件能力。
     *
     * 三个方法的语义必须与真实实现一致：
     * [resolve] 要**副作用等价于**一次引擎启动期的判定（会更新 lastMode/lastReason）。
     */
    interface Port {
        /** 用户偏好当前值 */
        fun isLinuxEnabled(): Boolean

        /** 落偏好（只写布尔键，不重启引擎） */
        fun setLinuxEnabled(on: Boolean)

        /** 走一遍与启动期相同的判定，返回 wire 形式的模式 */
        fun resolve(): String

        /** 最近一次判定的原因（失败时给用户看的那句话） */
        fun lastReason(): String

        /**
         * 目标是 Linux 时的可用性探测。
         *
         * @return null = 就绪；非 null = 不可用的具体原因（必须可操作）
         */
        fun linuxUnavailableReason(): String?
    }

    /**
     * 一次切换的回执。
     *
     * [applied] 是**回读**结果，不是请求值 —— 两者不同时 [ok] 必为 false。
     */
    data class Receipt(
        val ok: Boolean,
        val target: String,
        val applied: String,
        val previous: String,
        val restartRequired: Boolean,
        val reason: String,
    ) {
        fun toWire(): Map<String, Any> = mapOf(
            "ok" to ok,
            "target" to target,
            "applied" to applied,
            "previous" to previous,
            "restartRequired" to restartRequired,
            "reason" to reason,
        )
    }

    /**
     * 执行一次切换。
     *
     * @param previous 本次切换**之前**引擎的判定值（wire）。由调用方传入而不是
     *   自己 resolve 一遍：多算一次会改写 lastReason，把「切换失败的原因」
     *   冲成上一次的残留值。
     */
    fun select(target: Target, port: Port, previous: String): Receipt {
        val previousEnabled = port.isLinuxEnabled()
        val desiredEnabled = target == Target.LINUX

        // 1) 偏好没变、判定也对得上 → 真正什么都不做。
        if (desiredEnabled == previousEnabled && previous == target.wire) {
            return Receipt(
                ok = true,
                target = target.wire,
                applied = previous,
                previous = previous,
                restartRequired = false,
                reason = "",
            )
        }

        // 2) 切到 Linux 前先验可用性 —— 不可用就**不改偏好**。
        if (desiredEnabled) {
            val unavailable = port.linuxUnavailableReason()
            if (unavailable != null) {
                // 偏好本来就没动，这里再判一次只是为了让回执带上真实的当前模式。
                val cur = port.resolve()
                return Receipt(
                    ok = false,
                    target = target.wire,
                    applied = cur,
                    previous = previous,
                    restartRequired = false,
                    reason = unavailable,
                )
            }
        }

        // 3) 偏好没变但判定对不上（如启动前还没判定、或上一步留了回退态）：
        //    只重算，不写偏好 —— 写一个和现在相同的值不算改动。
        //
        //    重启提示只在「判定从非 Linux 变成 Linux」时给：偏好早已为真、
        //    只是环境当时没就绪而回退，重算发现环境好了 —— 这时引擎进程确实
        //    还在原生下跑，必须重启才真的进容器。反过来，native_fallback →
        //    native 只是同一个进程内标签变化，重启不改变任何东西。
        if (desiredEnabled == previousEnabled) {
            val resolved = port.resolve()
            return Receipt(
                ok = resolved == target.wire,
                target = target.wire,
                applied = resolved,
                previous = previous,
                restartRequired = target == Target.LINUX && previous != resolved,
                reason = if (resolved == target.wire) {
                    ""
                } else {
                    port.lastReason().ifBlank {
                        "engine resolved to " + resolved + " instead of " + target.wire
                    }
                },
            )
        }

        // 4) 真的改偏好 + 权威判定。
        port.setLinuxEnabled(desiredEnabled)
        val resolved = port.resolve()

        // 5) 回读一致才算成功；否则回滚偏好，把用户的旧选择原样还回去。
        if (resolved != target.wire) {
            // ★原因必须在回滚**之前**取：回滚那一次 resolve() 会覆盖 lastReason
            //   （变成「linux mode disabled」这种和失败毫不相干的话），
            //   用户看到的就是一句解释不了失败的提示。
            val why = port.lastReason().ifBlank {
                "engine resolved to " + resolved + " instead of " + target.wire
            }
            port.setLinuxEnabled(previousEnabled)
            val reverted = port.resolve()
            return Receipt(
                ok = false,
                target = target.wire,
                applied = reverted,
                previous = previous,
                restartRequired = false,
                reason = why,
            )
        }

        return Receipt(
            ok = true,
            target = target.wire,
            applied = resolved,
            previous = previous,
            // 引擎在 spawnEngine 时读偏好，已经在跑的进程不会热切换。
            restartRequired = true,
            reason = "",
        )
    }
}
