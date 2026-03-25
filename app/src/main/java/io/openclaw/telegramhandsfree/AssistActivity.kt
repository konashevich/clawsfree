package io.openclaw.telegramhandsfree

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import io.openclaw.telegramhandsfree.voice.ClawsfreeForegroundService

/**
 * Invisible activity that handles ASSIST / VOICE_COMMAND intents.
 * When the user long-presses the Bluetooth media button (or selects this app
 * as their assistant), Android launches this activity which immediately
 * starts recording via the foreground service and finishes itself.
 */
class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "AssistActivity launched with action=${intent?.action}")

        // Fire-and-forget: tell the foreground service to start recording.
        // The VoiceInteractionSession.onShow() does the same thing so this is
        // a safe no-op if recording already started through that path.
        ContextCompat.startForegroundService(
            this,
            ClawsfreeForegroundService.createIntent(this, ClawsfreeForegroundService.ACTION_START_RECORDING)
        )

        // Finish immediately — Theme.NoDisplay requires finish() inside onCreate.
        finish()
    }

    companion object {
        private const val TAG = "AssistActivity"
    }
}
