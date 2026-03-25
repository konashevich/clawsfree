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
import android.media.ToneGenerator
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

        setupMediaSession()

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
                        playbackManager.playFile(incomingVoice.file)
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
        serviceScope.cancel()
        coreReady = false
        super.onDestroy()
    }

    private fun startRecording() {
        if (!coreReady) return
        if (recorder.isRecording) return

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
        if (useBt) startBluetoothSco()
        requestAudioFocus()
        startMediaKeyKeepAlivePlayback()
        playbackManager.stop()

        beepStartRecording()
        recorder.start()
        updatePlaybackState(isRecording = true)
        updateNotification(isRecording = true)
        broadcastActivity("recording")
    }

    private fun stopRecordingAndSend() {
        if (!coreReady) return
        val recordingFile = recorder.stop() ?: return
        stopMediaKeyKeepAlivePlayback()
        abandonAudioFocus()
        beepStopRecording()
        stopBluetoothSco()
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
                        playbackManager.playFile(queuedFile)
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

    /** Single rising beep — "recording started" */
    private fun beepStartRecording() {
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
        mainHandler.postDelayed({ tg.release() }, 300)
    }

    /** Double falling beep — "recording stopped, sending" */
    private fun beepStopRecording() {
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        tg.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        mainHandler.postDelayed({ tg.release() }, 400)
    }

    /**
     * MediaSession is the only reliable way to receive Bluetooth media button
     * events on Android 8.0+. The static BroadcastReceiver approach stopped
     * working because the system delivers MEDIA_BUTTON to the active MediaSession.
     *
     * Short press routes to onPlay/onPause/onStop on many BT headsets.
     * Long press is intercepted by the OS and triggers the assistant slot.
     */
    private fun setupMediaSession() {
        registerMediaButtonReceiver()

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

                    if (event.action != KeyEvent.ACTION_UP) return true

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastHandledMediaKeyAtMs < 200L) {
                        return true
                    }
                    lastHandledMediaKeyAtMs = now

                    // Any media button press while recording → stop and send
                    if (recorder.isRecording) {
                        Log.i(TAG, "MediaButton UP while recording → stop+send")
                        stopRecordingAndSend()
                        return true
                    }
                    // Any media button press while idle → start recording
                    if (!recorder.isRecording) {
                        Log.i(TAG, "MediaButton UP while idle → start recording")
                        startRecording()
                        return true
                    }
                    return true
                }

                // Many BT headsets route short press through these callbacks
                // instead of onMediaButtonEvent
                override fun onPlay() {
                    Log.i(TAG, "MediaSession.onPlay → toggle")
                    if (recorder.isRecording) stopRecordingAndSend() else startRecording()
                }

                override fun onPause() {
                    Log.i(TAG, "MediaSession.onPause → stop if recording")
                    if (recorder.isRecording) stopRecordingAndSend()
                }

                override fun onStop() {
                    Log.i(TAG, "MediaSession.onStop → stop if recording")
                    if (recorder.isRecording) stopRecordingAndSend()
                }

                private fun isMediaButtonKey(event: KeyEvent): Boolean {
                    return event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                        event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                        event.keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                }
            })

            // Must be active + have a playback state to receive media button events
            updatePlaybackState(isRecording = false)
            isActive = true
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
        val stateValue = if (isRecording) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
            )
            .setState(stateValue, 0, 1f, SystemClock.elapsedRealtime())
            .build()
        mediaSession?.setPlaybackState(state)
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

        const val ACTION_START_RECORDING = "io.openclaw.telegramhandsfree.action.START_RECORDING"
        const val ACTION_STOP_IF_RECORDING = "io.openclaw.telegramhandsfree.action.STOP_IF_RECORDING"
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
}
