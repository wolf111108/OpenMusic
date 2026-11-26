package io.github.openmusic.practice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Foreground practice pauses on another app's audio, calls, or transient focus loss. */
class PracticeAudioFocus(context: Context, onLost: () -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var held = false
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) onLost()
        }, Handler(Looper.getMainLooper()))
        .build()

    fun acquire(): Boolean {
        if (held) return true
        held = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    fun release() {
        if (held) manager.abandonAudioFocusRequest(request)
        held = false
    }
}
