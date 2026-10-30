package app.dsh.mobile.update

import android.content.Context
import app.dsh.mobile.R

/**
 * 失败分类 → 用户可见文案。
 *
 * 约定：`UpdateState.message` 里是异常原文（含 URL、HTTP 码），**只进日志**；
 * UI 一律走这里。这样换文案、换索引地址都不会让界面出现半截技术细节。
 */
fun UpdateFailure.textRes(): Int = when (this) {
    UpdateFailure.None, UpdateFailure.Unknown -> R.string.update_fail_unknown
    UpdateFailure.NoIndex -> R.string.update_fail_no_index
    UpdateFailure.Network -> R.string.update_fail_network
    UpdateFailure.Signature -> R.string.update_fail_signature
    UpdateFailure.Channel -> R.string.update_fail_channel
    UpdateFailure.TooOld -> R.string.update_fail_too_old
    UpdateFailure.NoAsset -> R.string.update_fail_no_asset
}

/** 失败态的人话描述；非失败态返回空串（调用方按状态自行处理）。 */
fun UpdateState.failureText(ctx: Context): String =
    if (failure == UpdateFailure.None) "" else ctx.getString(failure.textRes())
