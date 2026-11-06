package me.ik.argon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * App side of the engine's audio-focus policy. Android audio focus is a Java API, so
 * this holds the AudioManager request while the engine reports players that want to
 * make sound, and forwards focus changes and "becoming noisy" (headphones unplugged)
 * back through three native entry points.
 *
 * Engines without the entry points (the stock engine, or one built without the media
 * backend) throw UnsatisfiedLinkError on first call; the bridge then disables itself
 * and does nothing.
 */
object MediaFocus {
    private const val TAG = "ArgonMediaFocus"
    private const val POLL_MS = 400L

    // Mirrors focus.rs.
    private const val GRANTED = 0
    private const val LOST_TRANSIENT = 1
    private const val LOST = 2
    private const val DUCK = 3

    @JvmStatic private external fun nativeActiveAudioPlayers(): Int
    @JvmStatic private external fun nativeSetFocus(state: Int)
    @JvmStatic private external fun nativeBecomingNoisy()

    private val handler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var request: AudioFocusRequest? = null
    private var holding = false
    private var enabled = true
    private var started = false
    private var appContext: Context? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) guarded { nativeBecomingNoisy() }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!enabled) return
            guarded {
                val want = nativeActiveAudioPlayers() > 0
                if (want && !holding) acquire() else if (!want && holding) release()
            }
            if (enabled) handler.postDelayed(this, POLL_MS)
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            Log.i(TAG, "engine has no media focus entry points; bridge disabled")
            enabled = false
            teardown()
        }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        audioManager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        appContext?.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        handler.postDelayed(poll, POLL_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(poll)
        teardown()
    }

    private fun teardown() {
        if (holding) release()
        try {
            appContext?.unregisterReceiver(noisyReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun acquire() {
        val am = audioManager ?: return
        val req = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                guarded {
                    nativeSetFocus(
                        when (change) {
                            AudioManager.AUDIOFOCUS_GAIN -> GRANTED
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> LOST_TRANSIENT
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> DUCK
                            AudioManager.AUDIOFOCUS_LOSS -> LOST
                            else -> return@guarded
                        },
                    )
                }
            }
            .build()
            .also { request = it }
        when (am.requestAudioFocus(req)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                holding = true
                guarded { nativeSetFocus(GRANTED) }
            }
            // Denied (e.g. during a call): hold playback until focus is offered.
            else -> {
                holding = true
                guarded { nativeSetFocus(LOST_TRANSIENT) }
            }
        }
    }

    private fun release() {
        val am = audioManager
        val req = request
        if (am != null && req != null) am.abandonAudioFocusRequest(req)
        holding = false
        guarded { nativeSetFocus(GRANTED) }
    }
}
