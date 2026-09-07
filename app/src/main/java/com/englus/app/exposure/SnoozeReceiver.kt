package com.englus.app.exposure

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.englus.app.widget.SentenceWidget

/**
 * "Not for the rest of today", from the notification itself.
 *
 * The alternative the learner has otherwise is to turn the notification channel
 * off in system settings, which is permanent and which nobody ever undoes. An
 * action that silences the app until tomorrow keeps the feature alive.
 */
class SnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ExposureSettings(context).snoozeForToday()
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }
        SentenceWidget.refresh(context)
    }
}
