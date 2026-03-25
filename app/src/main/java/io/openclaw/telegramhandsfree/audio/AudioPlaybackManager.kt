package io.openclaw.telegramhandsfree.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File

class AudioPlaybackManager(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(playbackAttributes)
        .setOnAudioFocusChangeListener { }
        .build()

    /**
     * Play the given audio file.  Safe to call from any thread — the actual
     * MediaPlayer work is always dispatched to the main looper.
     */
    fun playFile(file: File) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { playFile(file) }
            return
        }
        stop()

        val granted = audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "playFile path=${file.absolutePath} exists=${file.exists()} size=${file.length()} focusGranted=$granted")

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(playbackAttributes)
                // Hold a partial wake-lock for the duration of playback so the
                // CPU doesn't sleep mid-playback when the screen is off.
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra path=${file.absolutePath}")
                    stop()
                    true
                }
                setOnCompletionListener {
                    Log.i(TAG, "Playback complete path=${file.absolutePath}")
                    stop()
                }
                prepare()
                start()
                Log.i(TAG, "Playback started path=${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
            reset()
            release()
        }
        mediaPlayer = null
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    companion object {
        private const val TAG = "AudioPlaybackManager"
    }
}
