package io.moyi.voltmeter

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** 一次电池采样 */
data class Snapshot(
    /** 系统上报的原始电压 mV（可能是单芯，也可能是整包） */
    val voltageMv: Int,
    /** 系统自带电量百分比，拿不到为 -1 */
    val systemLevel: Int,
    val temperatureC: Float,
    val charging: Boolean,
    /** 实时电流 µA（充电为正、放电为负；不支持时为 Int.MIN_VALUE） */
    val currentUa: Int = Int.MIN_VALUE,
) {
    companion object {
        /** 从 ACTION_BATTERY_CHANGED 的 Intent 解析 */
        fun from(intent: Intent, currentUa: Int = Int.MIN_VALUE): Snapshot? {
            val mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (mv <= 0) return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            val status = intent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN
            )
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val charging = plugged ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            return Snapshot(mv, pct, temp, charging, currentUa)
        }
    }
}

/**
 * 零权限读取电池状态：
 * - ACTION_BATTERY_CHANGED 是 sticky 广播，传 null 接收器即可立刻拿到当前值；
 * - BATTERY_PROPERTY_CURRENT_NOW 读实时电流，同样不需要权限。
 */
object BatteryReader {
    private val FILTER = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    /** 实时电流 µA，不支持/异常时返回 Int.MIN_VALUE */
    fun currentUa(context: Context): Int = runCatching {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: Int.MIN_VALUE
    }.getOrDefault(Int.MIN_VALUE)

    fun read(context: Context): Snapshot? {
        val intent = context.registerReceiver(null, FILTER) ?: return null
        return Snapshot.from(intent, currentUa(context))
    }
}
