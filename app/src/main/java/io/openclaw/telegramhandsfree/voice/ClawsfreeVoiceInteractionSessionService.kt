package io.openclaw.telegramhandsfree.voice

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import androidx.core.content.ContextCompat

class ClawsfreeVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return ClawsfreeVoiceInteractionSession(this)
    }
}

private class ClawsfreeVoiceInteractionSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Tell the foreground service to start recording (no-op if already recording).
        ContextCompat.startForegroundService(
            context,
            ClawsfreeForegroundService.createIntent(context, ClawsfreeForegroundService.ACTION_START_RECORDING)
        )
        // Dismiss the session immediately so no UI is shown.
        // Do NOT stop recording in onHide — recording lifecycle is controlled
        // by explicit media-button presses only.
        hide()
    }

    override fun onHide() {
        super.onHide()
        // Intentionally empty — recording must NOT be stopped when the
        // voice-interaction session is dismissed.  The user will stop
        // recording with a short media-button press.
    }
}
