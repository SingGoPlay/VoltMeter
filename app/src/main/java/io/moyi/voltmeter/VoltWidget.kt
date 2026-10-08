package io.moyi.voltmeter

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews

const val ACTION_TAP = "io.moyi.voltmeter.action.TAP"
const val ACTION_TICK = "io.moyi.voltmeter.action.TICK"

/** 算一次数、拼一份 RemoteViews */
object WidgetPainter {

    fun build(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_volt)
        val settings = Prefs.get(context)
        val snap = BatteryReader.read(context)

        if (snap == null) {
            views.setTextViewText(R.id.widget_pct, "--")
            views.setTextViewText(R.id.widget_unit, "%")
            views.setTextViewText(R.id.widget_volt, "无数据")
            return views
        }

        val pct = Calc.percent(snap, settings)
        views.setTextViewText(R.id.widget_pct, pct.toString())
        views.setTextViewText(R.id.widget_unit, "%")
        views.setTextViewText(
            R.id.widget_volt,
            if (snap.charging) {
                "${formatMv(snap.voltageMv)} · 充电中"
            } else {
                formatMv(snap.voltageMv)
            }
        )
        views.setTextColor(
            R.id.widget_pct,
            context.getColor(
                if (snap.charging) R.color.widget_charging else R.color.widget_text
            )
        )
        views.setOnClickPendingIntent(R.id.widget_root, tapIntent(context))
        return views
    }

    fun formatMv(mv: Int): String = String.format("%.3f V", mv / 1000f)
}

/** 刷新所有小部件实例 */
fun updateAllWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context) ?: return
    val ids = manager.getAppWidgetIds(ComponentName(context, VoltWidgetProvider::class.java))
    if (ids == null || ids.isEmpty()) return
    val views = WidgetPainter.build(context)
    ids.forEach { manager.updateAppWidget(it, views) }
}

private fun tapIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    1,
    Intent(context, VoltWidgetProvider::class.java).setAction(ACTION_TAP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    2,
    Intent(context, VoltWidgetProvider::class.java).setAction(ACTION_TICK),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

/** 定时刷新方式的调度 */
object Ticker {
    const val MIN_1 = 60_000L
    const val MIN_15 = 15 * 60_000L

    fun apply(context: Context, mode: RefreshMode) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = tickIntent(context)
        am.cancel(pi)

        when (mode) {
            RefreshMode.M15 -> am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + MIN_1,
                MIN_15,
                pi
            )

            RefreshMode.M1 -> am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + MIN_1,
                MIN_1,
                pi
            )

            RefreshMode.Realtime -> Unit // 由前台服务驱动
            RefreshMode.Manual -> Unit   // 只靠点击
        }
    }
}

class VoltWidgetProvider : AppWidgetProvider() {

    /** 第一个小部件被添加时，顺手把定时刷新排上（不用等用户去点保存） */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Ticker.apply(context, Prefs.get(context).refresh)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = WidgetPainter.build(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
        Ticker.apply(context, Prefs.get(context).refresh)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TAP, ACTION_TICK -> updateAllWidgets(context)

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val settings = Prefs.get(context)
                Ticker.apply(context, settings.refresh)
                if (settings.refresh == RefreshMode.Realtime) {
                    VoltService.start(context)
                }
                updateAllWidgets(context)
            }
        }
    }
}
