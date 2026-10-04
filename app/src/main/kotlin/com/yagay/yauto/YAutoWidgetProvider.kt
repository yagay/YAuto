package com.yagay.yauto

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class YAutoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_CONFIGURE -> {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_LABEL, intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "YAuto" })
                    .putString(KEY_COMMAND, intent.getStringExtra(EXTRA_COMMAND).orEmpty())
                    .apply()
                updateAll(context)
            }
            ACTION_REFRESH -> updateAll(context)
        }
    }

    private fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, YAutoWidgetProvider::class.java)
        manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val label = prefs.getString(KEY_LABEL, "YAuto").orEmpty().ifBlank { "YAuto" }
        val command = prefs.getString(KEY_COMMAND, "").orEmpty()

        val click = Intent(context, ShortcutDispatchActivity::class.java)
            .putExtra("shortcutId", "widget:" + appWidgetId)
            .putExtra("command", command)
        val pending = PendingIntent.getActivity(
            context,
            appWidgetId,
            click,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val views = RemoteViews(context.packageName, R.layout.yauto_widget).apply {
            setTextViewText(R.id.yauto_widget_label, label)
            setOnClickPendingIntent(R.id.yauto_widget_root, pending)
        }
        manager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        const val ACTION_CONFIGURE = "com.yagay.yauto.WIDGET_CONFIGURE"
        const val ACTION_REFRESH = "com.yagay.yauto.WIDGET_REFRESH"
        const val EXTRA_LABEL = "label"
        const val EXTRA_COMMAND = "command"
        private const val PREFS = "yauto_widget"
        private const val KEY_LABEL = "label"
        private const val KEY_COMMAND = "command"
    }
}
