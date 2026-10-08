package io.moyi.voltmeter

/**
 * 电压 → 电量百分比，以及可选的电流补偿。
 *
 * 锂电池的电压-电量关系是非线性的（两端陡、中间平），所以除了线性映射，
 * 还提供一条按 OCV-SOC 经验点做的分段折线曲线。
 *
 * 另外手机上报的电压是「带载电压」，大电流放电时会被内阻拉低，导致电量被低估。
 * 打开电流补偿后按 V_静置 ≈ V_实测 + (-I × R) 把压降补回去。
 */
object Calc {

    /** 基准曲线：单芯标准 4.20V 体系的静置电压 → 剩余电量 */
    private val BASE = listOf(
        4200f to 100f,
        4150f to 95f,
        4100f to 90f,
        4050f to 84f,
        4000f to 78f,
        3950f to 70f,
        3900f to 63f,
        3850f to 55f,
        3800f to 47f,
        3750f to 40f,
        3700f to 33f,
        3650f to 27f,
        3600f to 21f,
        3550f to 16f,
        3500f to 11f,
        3450f to 7f,
        3400f to 4f,
        3350f to 2f,
        3300f to 0f,
    )
    private const val BASE_MIN = 3300f
    private const val BASE_MAX = 4200f

    /** 补偿量上限，防止电流读数异常时报出离谱的值 */
    private const val MAX_COMP_MV = 400f

    /** 自动判断电芯数：整包电压超过 4.6V 就认为是双芯串联 */
    fun cellsOf(rawMv: Int, mode: CellMode): Int = when (mode) {
        CellMode.One -> 1
        CellMode.Two -> 2
        CellMode.Auto -> if (rawMv > 4600) 2 else 1
    }

    /** 折算到单芯电压 mV */
    fun perCellMv(rawMv: Int, mode: CellMode): Float = rawMv / cellsOf(rawMv, mode).toFloat()

    /**
     * 电流符号纠正 + 单位纠正，统一成「µA，充电为正、放电为负」。
     * - 符号用充电状态判定，比信任厂商定义可靠；
     * - 单位：不少 OH/OPPO 系机型的 HAL 把 mA 当 µA 透传（读数只有几十~几千），
     *   自动模式下读数绝对值 < 100000 就按 mA 解读。
     */
    fun currentUa(snap: Snapshot, s: VoltSettings): Long {
        val raw = snap.currentUa
        if (raw == Int.MIN_VALUE || raw == 0) return 0L
        val ua = when (s.curUnit) {
            CurUnit.Micro -> raw.toLong()
            CurUnit.Milli -> raw.toLong() * 1000L
            CurUnit.Auto -> if (raw in -100_000..100_000) raw.toLong() * 1000L else raw.toLong()
        }
        val signed = when {
            snap.charging && ua < 0 -> -ua
            !snap.charging && ua > 0 -> -ua
            else -> ua
        }
        return signed.coerceIn(-20_000_000L, 20_000_000L)
    }

    /** 要加到实测电压上的补偿量(mV，单芯)；放电时为正（把被拉低的电压抬回去） */
    fun compensationMv(snap: Snapshot, s: VoltSettings): Int {
        if (!s.compEnabled) return 0
        val ua = currentUa(snap, s)
        if (ua == 0L) return 0
        // µA / 1e6 = A；A × mΩ = mV
        val mv = -(ua / 1_000_000f) * s.resistanceMilliohm
        return mv.coerceIn(-MAX_COMP_MV, MAX_COMP_MV).toInt()
    }

    /** 补偿后的单芯电压 mV */
    fun ocvPerCellMv(snap: Snapshot, s: VoltSettings): Float =
        perCellMv(snap.voltageMv, s.cells) + compensationMv(snap, s)

    fun percent(snap: Snapshot, s: VoltSettings): Int = percentOf(ocvPerCellMv(snap, s), s)

    /** 不做电流补偿时的估算（保留给预览用） */
    fun percent(rawMv: Int, s: VoltSettings): Int = percentOf(perCellMv(rawMv, s.cells), s)

    private fun percentOf(perCellV: Float, s: VoltSettings): Int {
        val lo = s.cutOffMv.toFloat()
        val hi = s.fullMv.toFloat()
        if (hi <= lo) return 0

        val raw = when (s.curve) {
            Curve.Linear -> (perCellV - lo) / (hi - lo) * 100f
            Curve.Piecewise -> {
                // 把用户的 [关机电压, 满电电压] 等比映射到基准曲线的 [3300, 4200] 后插值
                val t = (perCellV - lo) / (hi - lo)
                interp(BASE_MIN + t * (BASE_MAX - BASE_MIN))
            }
        }
        return raw.coerceIn(0f, 100f).let { if (it >= 99.5f) 100 else it.toInt() }
    }

    private fun interp(x: Float): Float {
        if (x >= BASE_MAX) return 100f
        if (x <= BASE_MIN) return 0f
        for (i in 0 until BASE.size - 1) {
            val (v1, p1) = BASE[i]
            val (v2, p2) = BASE[i + 1]
            if (x <= v1 && x >= v2) {
                val t = (v1 - x) / (v1 - v2)
                return p1 + (p2 - p1) * t
            }
        }
        return 0f
    }
}
