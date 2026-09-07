package com.englus.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.englus.app.R
import com.englus.app.data.EnglusDatabase
import com.englus.app.exposure.ExposureSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One missed sentence, on the home screen.
 *
 * A phone is unlocked dozens of times a day for a couple of seconds. No single
 * glance is long enough to study anything, but taken together they are more
 * contact with English than any deliberate session will ever be, and they cost
 * nothing - the learner was going to look at the screen anyway.
 *
 * The material is their own failures rather than a word of the day: a sentence
 * that already defeated them once is worth meeting again, and a generic quote is
 * wallpaper within a week.
 *
 * Everything here is gated on [ExposureSettings]. A widget that keeps showing
 * English during a meeting, or at three in the morning, gets removed - and the
 * learner loses the exposure entirely rather than pausing it.
 */
class SentenceWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { render(context, manager, it) }
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val settings = ExposureSettings(context)
        val views = RemoteViews(context.packageName, R.layout.widget_sentence)

        views.setOnClickPendingIntent(R.id.widget_english, openApp(context))
        views.setOnClickPendingIntent(R.id.widget_label, openApp(context))

        if (!settings.enabled || !settings.widgetSentences) {
            // A blank face rather than a hidden widget: the learner put it there,
            // and it should be obvious that it is off rather than broken.
            views.setTextViewText(R.id.widget_label, "englus")
            views.setTextViewText(R.id.widget_english, "문장 표시가 꺼져 있습니다")
            views.setTextViewText(R.id.widget_korean, "설정에서 다시 켤 수 있습니다")
            manager.updateAppWidget(id, views)
            return
        }
        if (settings.snoozed || settings.inQuietHours()) {
            views.setTextViewText(R.id.widget_label, "englus")
            views.setTextViewText(R.id.widget_english, "쉬는 중")
            views.setTextViewText(R.id.widget_korean, settings.status())
            manager.updateAppWidget(id, views)
            return
        }

        // The database read cannot happen on the broadcast thread; the widget is
        // filled in as soon as it comes back.
        scope.launch {
            val saved = runCatching {
                EnglusDatabase.get(context).dictation().observeSaved().first()
            }.getOrDefault(emptyList())

            if (saved.isEmpty()) {
                views.setTextViewText(R.id.widget_label, "englus")
                views.setTextViewText(R.id.widget_english, "아직 저장된 문장이 없습니다")
                views.setTextViewText(R.id.widget_korean, "받아쓰기에서 놓친 문장이 여기 올라옵니다")
            } else {
                // Rotates on its own without storing a cursor: the widget updates
                // on a half-hour cadence, so the half-hour is the seed.
                val index = ((System.currentTimeMillis() / 1_800_000L) % saved.size).toInt()
                val item = saved[index]
                views.setTextViewText(R.id.widget_label, item.episodeTitle)
                views.setTextViewText(R.id.widget_english, item.sentence.text)
                views.setTextViewText(
                    R.id.widget_korean,
                    item.sentence.translation.orEmpty(),
                )
            }
            manager.updateAppWidget(id, views)
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent().setClassName(context, "com.englus.app.ui.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Pushes a fresh sentence out immediately.
         *
         * Called when the exposure settings change so that turning it off takes
         * effect now rather than at the next half-hour tick - the whole value of
         * an off switch is that it is immediate.
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, SentenceWidget::class.java)
            )
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, SentenceWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
            )
        }
    }
}
