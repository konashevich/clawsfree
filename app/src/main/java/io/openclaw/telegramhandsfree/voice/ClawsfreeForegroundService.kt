package io.openclaw.telegramhandsfree.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.AudioTrack
import android.os.SystemClock
import android.media.AudioManager
import android.util.Log
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import io.openclaw.telegramhandsfree.R
import io.openclaw.telegramhandsfree.audio.AudioPlaybackManager
import io.openclaw.telegramhandsfree.audio.AudioRecorder
import io.openclaw.telegramhandsfree.config.ClawsfreeConfig
import io.openclaw.telegramhandsfree.telegram.TdLibClient
import io.openclaw.telegramhandsfree.telegram.TelegramStatus
import io.openclaw.telegramhandsfree.telegram.TelegramRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class ClawsfreeForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var recorder: AudioRecorder
    private lateinit var playbackManager: AudioPlaybackManager
    private lateinit var repository: TelegramRepository
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var bluetoothScoActive = false
    private var coreReady = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var mediaButtonReceiverRegistered = false
    private var mediaKeyKeepAliveTrack: AudioTrack? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastHandledMediaKeyAtMs: Long = 0L
    private var mediaSessionDownAtMs: Long = 0L
    private var mediaSessionLongPressTriggered: Boolean = false
    private var recordingStartPending: Boolean = false
    private var awaitingReplyPlayback: Boolean = false
    private var pendingReplyFile: File? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        recorder = AudioRecorder(this) {
            stopRecordingAndSend()
        }
        playbackManager = AudioPlaybackManager(this)
        repository = TelegramRepository(TdLibClient(applicationContext))
        coreReady = true

        // startForeground MUST be called immediately or Android kills the service.
        // Use mediaPlayback while idle; microphone type is requested only while recording.
        try {
            startInForeground(isRecording = false)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            stopSelf()
            return
        }

        // Keep CPU awake while service runs (screen off during driving)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openclaw:foreground")
            .apply { acquire() }

        repository.startMonitoring { incomingVoice ->
            if (incomingVoice.chatId == ClawsfreeConfig.TELEGRAM_GROUP_ID) {
                if (!awaitingReplyPlayback) {
                    Log.i(TAG, "Ignoring incoming media because no reply is currently awaited")
                    return@startMonitoring
                }

                if (recorder.isRecording) {
                    pendingReplyFile = incomingVoice.file
                    Log.i(TAG, "Queued incoming media for playback after recording stops")
                    return@startMonitoring
                }

                // Dispatch to main thread — playbackManager handles the thread switch
                // internally but we also clear state on the main thread for safety.
                mainHandler.post {
                    if (!recorder.isRecording) {
                        activateMediaButtonSession()
                        playbackManager.playFile(incomingVoice.file) {
                            releaseMediaButtonSession(abandonFocus = false)
                        }
                        awaitingReplyPlayback = false
                        pendingReplyFile = null
                        Log.i(TAG, "Played incoming reply media and cleared awaited-reply state")
                    }
                }
            }
        }

        serviceScope.launch {
            repository.status.collectLatest { status ->
                val msg = when (status) {
                    TelegramStatus.Idle -> "idle"
                    TelegramStatus.Initializing -> "connecting"
                    TelegramStatus.WaitingCode -> "waiting_code"
                    TelegramStatus.WaitingPassword -> "waiting_password"
                    TelegramStatus.Ready -> "connected"
                    is TelegramStatus.NeedsConfiguration -> "needs_config:${status.reason}"
                    is TelegramStatus.NativeUnavailable -> "error:${status.reason}"
                    is TelegramStatus.Error -> "error:${status.reason}"
                }
                Log.i(TAG, "Telegram status: $msg")
                broadcastStatus(msg)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!coreReady) {
            Log.w(TAG, "Ignoring action ${intent?.action} because service core is not ready")
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_IF_RECORDING -> stopRecordingAndSend()
            ACTION_CANCEL_RECORDING -> cancelRecording()
            ACTION_TOGGLE_RECORDING -> {
                if (recorder.isRecording) stopRecordingAndSend() else startRecording()
            }
            ACTION_BEGIN_AUTH -> {
                repository.submitAuthCode()
            }
            ACTION_SUBMIT_AUTH -> {
                repository.submitAuthCode()
            }
            ACTION_SUBMIT_PASSWORD -> {
                repository.submitPassword()
            }
            ACTION_REFRESH_CHAT_BINDING -> {
                repository.refreshTargetChatBinding()
            }
            ACTION_ENSURE_RUNNING, null -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterMediaButtonReceiver()
        stopMediaKeyKeepAlivePlayback()
        abandonAudioFocus()
        stopBluetoothSco()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        mediaSession?.release()
        mediaSession = null
        if (::recorder.isInitialized) recorder.release()
        if (::playbackManager.isInitialized) playbackManager.stop()
        if (::repository.isInitialized) repository.shutdown()
        broadcastStatus("idle")
        broadcastActivity("idle")
        serviceScope.cancel()
        coreReady = false
        super.onDestroy()
    }

    private fun startRecording() {
        if (!coreReady) return
        if (isRecordingOrPending()) return
        if (!ClawsfreeConfig.canStartRecording(this)) {
            Log.i(TAG, "Ignoring startRecording because setup is not completed")
            broadcastActivity("idle")
            return
        }

        try {
            startInForeground(isRecording = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote service to microphone foreground mode: ${e.message}")
            updatePlaybackState(isRecording = false)
            updateNotification(isRecording = false)
            broadcastActivity("idle")
            return
        }

        val useBt = ClawsfreeConfig.USE_BLUETOOTH_MIC
        recorder.useBluetoothSource = useBt
        requestAudioFocus()
        activateMediaButtonSession()
        playbackManager.stop()

        beepStartRecording {
            if (useBt) startBluetoothSco()
        }
        if (!useBt) {
            recorder.start()
        } else {
            // Bluetooth starts after the media-routed confirmation beep so the
            // sound stays on car speakers before SCO takes over the microphone.
            recordingStartPending = true
            mainHandler.postDelayed({
                if (recordingStartPending && !recorder.isRecording) {
                    recordingStartPending = false
                    recorder.start()
                }
            }, START_RECORDING_AFTER_BEEP_MS)
        }
        updatePlaybackState(isRecording = true)
        updateNotification(isRecording = true)
        broadcastActivity("recording")
    }

    private fun stopRecordingAndSend() {
        if (!coreReady) return
        if (recordingStartPending) {
            cancelPendingRecordingStart()
            return
        }
        val recordingFile = recorder.stop() ?: return
        beepStopRecording()
        stopBluetoothSco()
        releaseMediaButtonSession(abandonFocus = true)
        try {
            startInForeground(isRecording = false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to switch service back to idle foreground mode: ${e.message}")
        }
        updatePlaybackState(isRecording = false)
        updateNotification(isRecording = false)
        broadcastActivity("sending")
        awaitingReplyPlayback = true
        pendingReplyFile = null
        Log.i(TAG, "Send cycle started; awaiting next incoming reply playback")

        serviceScope.launch {
            repository.sendVoiceMessage(recordingFile)

            pendingReplyFile?.let { queuedFile ->
                if (!recorder.isRecording) {
                    mainHandler.post {
                        activateMediaButtonSession()
                        playbackManager.playFile(queuedFile) {
                            releaseMediaButtonSession(abandonFocus = false)
                        }
                        awaitingReplyPlayback = false
                        pendingReplyFile = null
                        Log.i(TAG, "Played queued incoming media after send completion")
                    }
                }
            }

            broadcastActivity("sent")
            // After a pause, go back to idle
            mainHandler.postDelayed({ broadcastActivity("idle") }, 2000)
        }
    }

    private fun cancelRecording() {
        if (!coreReady) return
        if (recordingStartPending) {
            cancelPendingRecordingStart()
            broadcastActivity("cancelled")
            mainHandler.postDelayed({ broadcastActivity("idle") }, 1200)
            return
        }
        val recordingFile = recorder.stop() ?: return
        stopBluetoothSco()
        releaseMediaButtonSession(abandonFocus = true)
        recordingFile.delete()
        awaitingReplyPlayback = false
        pendingReplyFile = null
        try {
            startInForeground(isRecording = false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to switch service back to idle foreground mode after cancel: ${e.message}")
        }
        updatePlaybackState(isRecording = false)
        updateNotification(isRecording = false)
        broadcastActivity("cancelled")
        mainHandler.postDelayed({ broadcastActivity("idle") }, 1200)
        Log.i(TAG, "Recording cancelled and discarded")
    }

    private fun startBluetoothSco() {
        val am = audioManager ?: return
        if (!bluetoothScoActive) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            bluetoothScoActive = true
            Log.i(TAG, "Bluetooth SCO started")
        }
    }

    private fun stopBluetoothSco() {
        val am = audioManager ?: return
        if (bluetoothScoActive) {
            am.isBluetoothScoOn = false
            am.stopBluetoothSco()
            am.mode = AudioManager.MODE_NORMAL
            bluetoothScoActive = false
            Log.i(TAG, "Bluetooth SCO stopped")
        }
    }

    /** App-generated double beep — "recording started" */
    private fun beepStartRecording(onFirstGap: (() -> Unit)? = null) {
        playBeepPattern(
            listOf(
                BeepTone(frequencyHz = 880.0, durationMs = 95, volume = 0.70f),
                BeepTone(frequencyHz = 0.0, durationMs = 55, volume = 0f),
                BeepTone(frequencyHz = 1320.0, durationMs = 115, volume = 0.70f)
            ),
            onMarker = onFirstGap,
            markerDelayMs = 120L
        )
    }

    /** App-generated falling beep — "recording stopped, sending" */
    private fun beepStopRecording() {
        playBeepPattern(
            listOf(
                BeepTone(frequencyHz = 1040.0, durationMs = 90, volume = 0.65f),
                BeepTone(frequencyHz = 0.0, durationMs = 45, volume = 0f),
                BeepTone(frequencyHz = 660.0, durationMs = 115, volume = 0.65f)
            )
        )
    }

    /**
     * MediaSession is the only reliable way to receive Bluetooth media button
     * events on Android 8.0+. The static BroadcastReceiver approach stopped
     * working because the system delivers MEDIA_BUTTON to the active MediaSession.
     *
     * Long press usually routes through the assistant slot. If a device sends
     * raw repeated media-key events instead, this session starts only after the
     * same long-press threshold. Short press while idle is intentionally ignored.
     */
    private fun setupMediaSession() {
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setClass(this@ClawsfreeForegroundService, MediaButtonReceiver::class.java)
            setPackage(packageName)
        }
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession(this, "ClawsfreeHandsfree").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setMediaButtonReceiver(mediaButtonPendingIntent)

            setCallback(object : MediaSession.Callback() {

                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        ?: return super.onMediaButtonEvent(mediaButtonIntent)

                    Log.i(TAG, "MediaButton: keyCode=${event.keyCode} action=${event.action} repeat=${event.repeatCount}")

                    if (!isMediaButtonKey(event)) return super.onMediaButtonEvent(mediaButtonIntent)

                    return when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            handleMediaSessionKeyDown(event)
                            true
                        }
                        KeyEvent.ACTION_UP -> {
                            handleMediaSessionKeyUp(event)
                            true
                        }
                        else -> true
                    }
                }

                // Some BT devices send transport callbacks without press duration.
                // While idle, ignore them so connect/resume PLAY cannot start recording.
                override fun onPlay() {
                    if (isRecordingOrPending()) {
                        Log.i(TAG, "MediaSession.onPlay while recording -> stop+send")
                        stopRecordingAndSend()
                    } else {
                        Log.i(TAG, "MediaSession.onPlay while idle ignored")
                    }
                }

                override fun onPause() {
                    Log.i(TAG, "MediaSession.onPause -> stop if recording")
                    if (isRecordingOrPending()) stopRecordingAndSend()
                }

                override fun onStop() {
                    Log.i(TAG, "MediaSession.onStop -> stop if recording")
                    if (isRecordingOrPending()) stopRecordingAndSend()
                }

                private fun handleMediaSessionKeyDown(event: KeyEvent) {
                    val eventAtMs = eventTimeOrNow(event)
                    if (event.repeatCount == 0) {
                        mediaSessionDownAtMs = eventAtMs
                        mediaSessionLongPressTriggered = false
                        return
                    }

                    val isLongPress = eventAtMs - mediaSessionDownAtMs >= MEDIA_LONG_PRESS_MS
                    if (!recorder.isRecording && !mediaSessionLongPressTriggered && isLongPress) {
                        mediaSessionLongPressTriggered = true
                        Log.i(TAG, "MediaButton long press while idle -> start recording")
                        startRecording()
                    }
                }

                private fun handleMediaSessionKeyUp(event: KeyEvent) {
                    val eventAtMs = eventTimeOrNow(event)
                    val pressDuration = if (mediaSessionDownAtMs > 0L) eventAtMs - mediaSessionDownAtMs else 0L
                    val longPressAlreadyHandled = mediaSessionLongPressTriggered
                    val isLongPress = longPressAlreadyHandled || pressDuration >= MEDIA_LONG_PRESS_MS

                    mediaSessionDownAtMs = 0L
                    mediaSessionLongPressTriggered = false

                    if (longPressAlreadyHandled) {
                        Log.i(TAG, "MediaButton UP after long-press start ignored")
                        return
                    }

                    if (isMediaKeyDebounced()) return

                    if (isRecordingOrPending()) {
                        Log.i(TAG, "MediaButton UP while recording -> stop+send")
                        stopRecordingAndSend()
                        return
                    }

                    if (isLongPress) {
                        Log.i(TAG, "MediaButton long press release while idle -> start recording")
                        startRecording()
                    } else {
                        Log.i(TAG, "MediaButton short press while idle ignored")
                    }
                }

                private fun isMediaKeyDebounced(): Boolean {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastHandledMediaKeyAtMs < MEDIA_KEY_DEBOUNCE_MS) {
                        return true
                    }
                    lastHandledMediaKeyAtMs = now
                    return false
                }

                private fun isMediaButtonKey(event: KeyEvent): Boolean {
                    return event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                        event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                        event.keyCode == KeyEvent.KEYCODE_MEDIA_STOP ||
                        event.keyCode == KeyEvent.KEYCODE_MEDIA_RECORD ||
                        event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
                        event.keyCode == KeyEvent.KEYCODE_ASSIST
                }

                private fun eventTimeOrNow(event: KeyEvent): Long {
                    return if (event.eventTime > 0L) event.eventTime else SystemClock.elapsedRealtime()
                }
            })

            // Activated only while Clawsfree is recording or playing a reply so
            // idle short presses remain available to the current media app.
            setPlaybackState(buildPlaybackState(isRecording = false))
            isActive = false
        }
    }

    private fun registerMediaButtonReceiver() {
        val am = audioManager ?: return
        if (mediaButtonReceiverRegistered) return
        @Suppress("DEPRECATION")
        am.registerMediaButtonEventReceiver(ComponentName(this, MediaButtonReceiver::class.java))
        mediaButtonReceiverRegistered = true
        Log.i(TAG, "Media button receiver registered")
    }

    private fun unregisterMediaButtonReceiver() {
        val am = audioManager ?: return
        if (!mediaButtonReceiverRegistered) return
        @Suppress("DEPRECATION")
        am.unregisterMediaButtonEventReceiver(ComponentName(this, MediaButtonReceiver::class.java))
        mediaButtonReceiverRegistered = false
        Log.i(TAG, "Media button receiver unregistered")
    }

    private fun updatePlaybackState(isRecording: Boolean) {
        mediaSession?.setPlaybackState(buildPlaybackState(isRecording))
    }

    private fun buildPlaybackState(isRecording: Boolean): PlaybackState {
        val stateValue = if (isRecording) PlaybackState.STATE_PLAYING else PlaybackState.STATE_NONE
        val actions = if (isRecording) {
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        } else {
            0L
        }
        return PlaybackState.Builder()
            .setActions(actions)
            .setState(stateValue, 0, 1f, SystemClock.elapsedRealtime())
            .build()
    }

    private fun activateMediaButtonSession() {
        if (mediaSession == null) {
            setupMediaSession()
        }
        registerMediaButtonReceiver()
        mediaSession?.isActive = true
        startMediaKeyKeepAlivePlayback()
        updatePlaybackState(isRecording = true)
    }

    private fun isRecordingOrPending(): Boolean {
        return recorder.isRecording || recordingStartPending
    }

    private fun releaseMediaButtonSession(abandonFocus: Boolean) {
        stopMediaKeyKeepAlivePlayback()
        updatePlaybackState(isRecording = false)
        mediaSession?.isActive = false
        unregisterMediaButtonReceiver()
        mediaSession?.release()
        mediaSession = null
        if (abandonFocus) abandonAudioFocus()
    }

    private fun cancelPendingRecordingStart() {
        recordingStartPending = false
        stopBluetoothSco()
        releaseMediaButtonSession(abandonFocus = true)
        try {
            startInForeground(isRecording = false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to switch service back to idle after pending recording cancel: ${e.message}")
        }
        updateNotification(isRecording = false)
        broadcastActivity("idle")
        Log.i(TAG, "Pending recording start cancelled")
    }

    private fun buildNotification(isRecording: Boolean): Notification {
        val title = if (isRecording) R.string.notif_title_recording else R.string.notif_title_idle
        val text = if (isRecording) R.string.notif_text_recording else R.string.notif_text_idle

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clawsfree_status)
            .setContentTitle(getString(title))
            .setContentText(getString(text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(isRecording: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isRecording))
    }

    private fun startInForeground(isRecording: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val serviceType = if (isRecording) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            startForeground(NOTIFICATION_ID, buildNotification(isRecording), serviceType)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(isRecording))
        }
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (hasAudioFocus) return

        val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }

        hasAudioFocus = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "Audio focus granted=$hasAudioFocus")
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (!hasAudioFocus) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        hasAudioFocus = false
    }

    private fun startMediaKeyKeepAlivePlayback() {
        if (mediaKeyKeepAliveTrack != null) return

        val sampleRate = 8_000
        val frameCount = sampleRate // 1 second mono PCM16
        val bufferSizeBytes = frameCount * 2

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bufferSizeBytes)
            .build()

        val silence = ByteArray(bufferSizeBytes)
        track.write(silence, 0, silence.size)
        track.setLoopPoints(0, frameCount, -1)
        track.setVolume(0f)
        track.play()

        mediaKeyKeepAliveTrack = track
        Log.i(TAG, "Started silent keep-alive playback for media key routing")
    }

    private fun stopMediaKeyKeepAlivePlayback() {
        val track = mediaKeyKeepAliveTrack ?: return
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
        mediaKeyKeepAliveTrack = null
        Log.i(TAG, "Stopped silent keep-alive playback")
    }

    private fun playBeepPattern(
        tones: List<BeepTone>,
        onMarker: (() -> Unit)? = null,
        markerDelayMs: Long = 0L
    ) {
        val sampleRate = 44_100
        val pcm = generatePcm16(tones, sampleRate)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()

        track.write(pcm, 0, pcm.size)
        track.play()

        if (onMarker != null) {
            mainHandler.postDelayed(onMarker, markerDelayMs)
        }

        val releaseDelayMs = tones.sumOf { it.durationMs.toLong() } + 100L
        mainHandler.postDelayed({
            runCatching { track.stop() }
            runCatching { track.release() }
        }, releaseDelayMs)
    }

    private fun generatePcm16(tones: List<BeepTone>, sampleRate: Int): ByteArray {
        val totalSamples = tones.sumOf { tone ->
            ((sampleRate * tone.durationMs) / 1000.0).toInt()
        }
        val pcm = ByteArray(totalSamples * 2)
        var offset = 0

        tones.forEach { tone ->
            val samples = ((sampleRate * tone.durationMs) / 1000.0).toInt()
            for (i in 0 until samples) {
                val value = if (tone.frequencyHz <= 0.0 || tone.volume <= 0f) {
                    0
                } else {
                    val envelope = fadeEnvelope(i, samples)
                    (sin(2.0 * PI * tone.frequencyHz * i / sampleRate) *
                        Short.MAX_VALUE *
                        tone.volume *
                        envelope).toInt()
                }.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

                pcm[offset++] = (value and 0xff).toByte()
                pcm[offset++] = ((value shr 8) and 0xff).toByte()
            }
        }
        return pcm
    }

    private fun fadeEnvelope(index: Int, sampleCount: Int): Double {
        val fadeSamples = minOf(sampleCount / 4, 320)
        if (fadeSamples <= 0) return 1.0
        return when {
            index < fadeSamples -> index.toDouble() / fadeSamples
            index >= sampleCount - fadeSamples -> (sampleCount - index).toDouble() / fadeSamples
            else -> 1.0
        }.coerceIn(0.0, 1.0)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun broadcastStatus(status: String) {
        // Persist so activity can read it on resume (broadcasts are fire-and-forget)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_STATUS, status).apply()

        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
        }
        sendBroadcast(intent)
    }

    private fun broadcastActivity(activityState: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_ACTIVITY, activityState).apply()

        val intent = Intent(ACTION_ACTIVITY_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_ACTIVITY, activityState)
        }
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "ClawsfreeForegroundService"
        private const val MEDIA_LONG_PRESS_MS = 650L
        private const val MEDIA_KEY_DEBOUNCE_MS = 200L
        private const val START_RECORDING_AFTER_BEEP_MS = 290L

        const val ACTION_START_RECORDING = "io.openclaw.telegramhandsfree.action.START_RECORDING"
        const val ACTION_STOP_IF_RECORDING = "io.openclaw.telegramhandsfree.action.STOP_IF_RECORDING"
        const val ACTION_CANCEL_RECORDING = "io.openclaw.telegramhandsfree.action.CANCEL_RECORDING"
        const val ACTION_TOGGLE_RECORDING = "io.openclaw.telegramhandsfree.action.TOGGLE_RECORDING"
        const val ACTION_ENSURE_RUNNING = "io.openclaw.telegramhandsfree.action.ENSURE_RUNNING"
        const val ACTION_BEGIN_AUTH = "io.openclaw.telegramhandsfree.action.BEGIN_AUTH"
        const val ACTION_SUBMIT_AUTH = "io.openclaw.telegramhandsfree.action.SUBMIT_AUTH"
        const val ACTION_SUBMIT_PASSWORD = "io.openclaw.telegramhandsfree.action.SUBMIT_PASSWORD"
        const val ACTION_REFRESH_CHAT_BINDING = "io.openclaw.telegramhandsfree.action.REFRESH_CHAT_BINDING"
        const val ACTION_STATUS_UPDATE = "io.openclaw.telegramhandsfree.action.STATUS_UPDATE"
        const val ACTION_ACTIVITY_UPDATE = "io.openclaw.telegramhandsfree.action.ACTIVITY_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ACTIVITY = "activity"

        private const val CHANNEL_ID = "clawsfree_handsfree"
        private const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "clawsfree_service_status"
        const val KEY_LAST_STATUS = "last_status"
        const val KEY_LAST_ACTIVITY = "last_activity"

        fun createIntent(context: Context, action: String): Intent {
            return Intent(context, ClawsfreeForegroundService::class.java).apply {
                this.action = action
            }
        }
    }

    private data class BeepTone(
        val frequencyHz: Double,
        val durationMs: Int,
        val volume: Float
    )
}
