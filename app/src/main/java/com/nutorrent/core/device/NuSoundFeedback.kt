package com.nutorrent.core.device

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.nutorrent.NuTorrentApplication
import java.util.concurrent.Executors

object NuSoundFeedback {

    private val executor = Executors.newSingleThreadExecutor()
    private var toneGenerator: ToneGenerator? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
        } catch (_: Exception) {}
    }

    private fun isSoundEnabled(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                    return false
                }
            }
            val app = context.applicationContext as? NuTorrentApplication
            app?.settingsRepository?.settings?.value?.enableSoundEffects ?: true
        } catch (_: Exception) {
            true
        }
    }

    fun playCompletionChime(context: Context) {
        if (!isSoundEnabled(context)) return
        executor.execute {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            } catch (_: Exception) {}
        }
    }

    fun playStartBeep(context: Context) {
        if (!isSoundEnabled(context)) return
        executor.execute {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 70)
            } catch (_: Exception) {}
        }
    }

    fun playCautionBeep(context: Context) {
        if (!isSoundEnabled(context)) return
        executor.execute {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 120)
            } catch (_: Exception) {}
        }
    }
}

