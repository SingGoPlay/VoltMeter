package io.moyi.voltmeter

import android.content.Context

/** 电芯数量模式 */
enum class CellMode { Auto, One, Two }

/** 电量算法 */
enum class Curve { Linear, Piecewise }

/** 小部件刷新方式 */
enum class RefreshMode { M15, M1, Realtime, Manual }

/** 电流单位模式（部分机型把 mA 当 µA 透传，需要纠正） */
enum class CurUnit { Auto, Micro, Milli }

data class VoltSettings(
    val cells: CellMode = CellMode.Auto,
    /** 单芯关机电压(mV) */
    val cutOffMv: Int = 3300,
    /** 单芯满电电压(mV) */
    val fullMv: Int = 4200,
    val curve: Curve = Curve.Piecewise,
    val refresh: RefreshMode = RefreshMode.M15,
    /** 电流补偿开关 */
    val compEnabled: Boolean = false,
    /** 单芯内阻(mΩ)，用于按 I×R 把带载压降补回去 */
    val resistanceMilliohm: Int = 50,
    /** 电流单位解读方式 */
    val curUnit: CurUnit = CurUnit.Auto,
)

object Prefs {
    private const val NAME = "volt_settings"

    fun get(context: Context): VoltSettings {
        val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return VoltSettings(
            cells = runCatching { CellMode.valueOf(sp.getString("cells", null) ?: "") }
                .getOrDefault(CellMode.Auto),
            cutOffMv = sp.getInt("cutoff", 3300),
            fullMv = sp.getInt("full", 4200),
            curve = runCatching { Curve.valueOf(sp.getString("curve", null) ?: "") }
                .getOrDefault(Curve.Piecewise),
            refresh = runCatching { RefreshMode.valueOf(sp.getString("refresh", null) ?: "") }
                .getOrDefault(RefreshMode.M15),
            compEnabled = sp.getBoolean("comp", false),
            resistanceMilliohm = sp.getInt("resistance", 50),
            curUnit = runCatching { CurUnit.valueOf(sp.getString("curUnit", null) ?: "") }
                .getOrDefault(CurUnit.Auto),
        )
    }

    fun set(context: Context, s: VoltSettings) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("cells", s.cells.name)
            .putInt("cutoff", s.cutOffMv)
            .putInt("full", s.fullMv)
            .putString("curve", s.curve.name)
            .putString("refresh", s.refresh.name)
            .putBoolean("comp", s.compEnabled)
            .putInt("resistance", s.resistanceMilliohm)
            .putString("curUnit", s.curUnit.name)
            .apply()
    }
}
