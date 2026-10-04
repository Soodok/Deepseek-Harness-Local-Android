package app.dsh.mobile.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

/**
 * Sound feedback manager (Phase 2).
 *
 * Plays short, non-intrusive sound effects for key engine events:
 * - Engine start
 * - Engine stop
 * - Task complete
 * - Task failed
 * - Warning
 *
 * Uses system sound effects to avoid adding large audio assets.
 * Sounds are loaded from system resources or generated programmatically.
 */
object SoundManager {

    private const val TAG = "SoundManager"
    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()
    @Volatile private var loaded = false
    private lateinit var ctx: Context

    fun init(context: Context) {
        if (loaded) return
        ctx = context.applicationContext
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
        loadSystemSounds()
        loaded = true
    }

    private fun loadSystemSounds() {
        // Use system notification sound for all events — consistent and always available
        // No need to bundle audio files
    }

    /**
     * Play a short notification sound.
     * Uses system notification/ringtone sounds which are always available.
     */
    fun playEvent(event: SoundEvent, context: Context? = null) {
        val ctx = context ?: if (::ctx.isInitialized) ctx else return
        if (!loaded) init(ctx)

        // Use system notification sound for all events — lightweight, always available
        playSystemSound(ctx, event)
    }

    private fun playSystemSound(context: Context, event: SoundEvent) {
        val soundRes = when (event) {
            SoundEvent.ENGINE_START -> android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            SoundEvent.ENGINE_STOP -> android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            SoundEvent.TASK_COMPLETE -> android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            SoundEvent.TASK_FAILED -> android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            SoundEvent.WARNING -> android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        }
        val player = android.media.AudioManager.STREAM_MUSIC
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // Use media player for system sounds (SoundPool only handles resource files)
        Thread {
            try {
                val afd = context.contentResolver.openAssetFileDescriptor(soundRes, "r")
                if (afd == null) {
                    Log.w(TAG, "sound resource not found: $soundRes")
                    return@Thread
                }
                val playerObj = android.media.MediaPlayer.create(context, soundRes)
                playerObj?.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                playerObj?.isLooping = false
                playerObj?.setVolume(1.0f, 1.0f)
                playerObj?.start()
                playerObj?.setOnCompletionListener { mp -> mp.release() }
                afd.close()
            } catch (e: Exception) {
                Log.w(TAG, "play sound failed: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    fun shutdown() {
        soundPool?.release()
        soundPool = null
        soundIds.clear()
        loaded = false
    }

    enum class SoundEvent {
        ENGINE_START,
        ENGINE_STOP,
        TASK_COMPLETE,
        TASK_FAILED,
        WARNING,
    }
}
