package app.dsh.mobile.engine.cap

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 媒体控制：读当前在播媒体、播放/暂停/切歌/拖进度/调音量。
 *
 * ## 为什么是这个 API
 * `MediaSessionManager.getActiveSessions(ComponentName)` 是**公开** API（API 21 起），
 * 参数要求传入「本应用自己拥有的通知监听组件」——系统据此判断你有资格看会话列表。
 * 这正好绕开了 `MEDIA_CONTENT_CONTROL`（signature|privileged，普通 App 永远拿不到）。
 *
 * 曾经想过用 `NotificationListenerService.getActiveSessions()`，但那个方法是
 * **hidden API**，公开 SDK 里根本不存在（javap 核对过：NLS 只有 getActiveNotifications
 * 等，没有 getActiveSessions）——写了编译不过。
 *
 * ## 为什么要挑「活跃」会话
 * 一次可能同时存在多个会话（音乐 App 后台 + 视频 App 暂停）。直接操作第一个会打错目标，
 * 所以优先选 `PlaybackState.STATE_PLAYING` 的那个，没有在播的才退回列表首个 ——
 * 此时用户多半是想「继续上次那个」。
 */
object MediaCap {

    private fun listenerComponent(ctx: Context): ComponentName =
        ComponentName(ctx.packageName, DeviceNotificationListener::class.java.name)

    /** 拿所有可见会话（无权限时返回空列表，而不是抛 SecurityException）。 */
    private fun sessions(ctx: Context): List<MediaController> {
        if (!CapabilityGate.can(ctx, CapabilityGate.MEDIA)) return emptyList()
        return runCatching {
            val mgr = ctx.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
            mgr.getActiveSessions(listenerComponent(ctx)).orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 选「正在播」的；都不在播就取第一个（可能是暂停中、用户想续播）。 */
    private fun active(ctx: Context): MediaController? {
        val all = sessions(ctx)
        if (all.isEmpty()) return null
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.first()
    }

    private fun stateName(state: Int): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_NONE -> "none"
        PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
        PlaybackState.STATE_REWINDING -> "rewinding"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "skipping_next"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping_previous"
        PlaybackState.STATE_ERROR -> "error"
        else -> "unknown($state)"
    }

    /** 会话音量：走 PlaybackInfo（不是 AudioManager，那个是全局音频流）。 */
    private fun volumeOf(c: MediaController): JSONObject {
        val info = runCatching { c.playbackInfo }.getOrNull() ?: return JSONObject()
        val max = info.maxVolume
        val cur = info.currentVolume
        return JSONObject()
            .put("volume", if (max > 0) cur.toFloat() / max else 0f)
            .put("current", cur)
            .put("max", max)
            .put("fixed", info.volumeControl == 0)
    }

    /** GET /dev/media —— 列出所有会话的元数据与播放状态。 */
    fun list(ctx: Context): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.MEDIA)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled")
                .put("capability", CapabilityGate.MEDIA)
                .toString()
        }
        CapabilityGate.audit(ctx, "dev.media.list")
        val current = active(ctx)?.packageName
        val arr = JSONArray()
        sessions(ctx).forEach { c ->
            val md = c.metadata
            val ps = c.playbackState
            arr.put(
                JSONObject()
                    .put("package", c.packageName)
                    .put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
                    .put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
                    .put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
                    .put("duration_ms", md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
                    .put("state", stateName(ps?.state ?: PlaybackState.STATE_NONE))
                    .put("position_ms", ps?.position ?: 0L)
                    .put("speed", ps?.playbackSpeed ?: 1f)
                    .put("volume_info", volumeOf(c))
                    .put("active", c.packageName == current),
            )
        }
        return 200 to JSONObject().put("ok", true).put("items", arr).toString()
    }

    /**
     * POST /dev/media —— 执行一个媒体动作。
     *
     * body: `{"op":"play|pause|toggle|next|previous|stop|seek|volume","position_ms":0,"volume":0.5}`
     *
     * `op=toggle` 是最常用的 —— 用户说「放歌/暂停一下」时不必先知道当前状态。
     */
    fun control(ctx: Context, body: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.MEDIA)) {
            return 403 to JSONObject().put("ok", false)
                .put("error", "capability disabled")
                .put("capability", CapabilityGate.MEDIA)
                .toString()
        }
        val req = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json").toString()
        val op = req.optString("op", "toggle").lowercase()

        val c = active(ctx)
            ?: return 404 to JSONObject().put("ok", false)
                .put("error", "no_active_session")
                .put("hint", "nothing is playing, or notification access is off")
                .toString()

        return runCatching {
            // transportControls 的 Kotlin 映射是非空类型（底层可能为 null，但 API 声明不是），
            // 故这里直接调用；真拿不到时 getOrElse 会接住 NPE 并返回 control_failed。
            val t = c.transportControls
            when (op) {
                "play" -> t.play()
                "pause" -> t.pause()
                "toggle" -> {
                    val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                    if (playing) t.pause() else t.play()
                }
                "next" -> t.skipToNext()
                "previous" -> t.skipToPrevious()
                "stop" -> t.stop()
                "seek" -> {
                    val pos = req.optLong("position_ms", 0L).coerceAtLeast(0L)
                    t.seekTo(pos)
                }
                "volume" -> {
                    // 会话音量用 setVolumeTo；只给比例时按该会话的 max 换算，
                    // 而不是套用全局音频流的档位（两者经常不一致）。
                    val max = c.playbackInfo.maxVolume
                    if (max <= 0) {
                        return 409 to JSONObject().put("ok", false)
                            .put("error", "volume_not_controllable")
                            .put("hint", "this session reports a fixed volume; use op=stream-volume instead")
                            .toString()
                    }
                    val raw = if (req.has("volume")) req.getDouble("volume") else -1.0
                    if (raw < 0) {
                        return 400 to JSONObject().put("ok", false).put("error", "volume required").toString()
                    }
                    val target = (if (raw <= 1.0) Math.round(raw * max).toInt() else raw.toInt()).coerceIn(0, max)
                    c.setVolumeTo(target, 0)
                }
                "stream-volume" -> {
                    // 全局音频流：没有活动会话时也该能调，所以允许走到这里再用 AudioManager。
                    val am = ctx.getSystemService(AudioManager::class.java)
                        ?: return 500 to JSONObject().put("ok", false).put("error", "no audio manager").toString()
                    val streamId = when (req.optString("stream", "music").lowercase()) {
                        "ring" -> AudioManager.STREAM_RING
                        "alarm" -> AudioManager.STREAM_ALARM
                        "notification" -> AudioManager.STREAM_NOTIFICATION
                        "call" -> AudioManager.STREAM_VOICE_CALL
                        else -> AudioManager.STREAM_MUSIC
                    }
                    val max = am.getStreamMaxVolume(streamId)
                    val target = if (req.has("volume")) {
                        val raw = req.getDouble("volume")
                        (if (raw <= 1.0) Math.round(raw * max).toInt() else raw.toInt()).coerceIn(0, max)
                    } else {
                        am.getStreamVolume(streamId)
                    }
                    if (req.has("volume")) am.setStreamVolume(streamId, target, 0)
                    return 200 to JSONObject().put("ok", true).put("op", op)
                        .put("volume", target).put("max", max).toString()
                }
                else -> return 400 to JSONObject().put("ok", false)
                    .put("error", "unknown_op").put("op", op)
                    .put("allowed", JSONArray(listOf("play", "pause", "toggle", "next", "previous", "stop", "seek", "volume", "stream-volume")))
                    .toString()
            }
            CapabilityGate.audit(ctx, "dev.media.$op")
            200 to JSONObject().put("ok", true)
                .put("op", op)
                .put("package", c.packageName)
                .put("title", c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
                .toString()
        }.getOrElse { e ->
            500 to JSONObject().put("ok", false).put("error", "control_failed")
                .put("detail", e.message.orEmpty().take(160)).toString()
        }
    }
}
