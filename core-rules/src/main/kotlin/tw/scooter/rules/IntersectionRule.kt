package tw.scooter.rules

/**
 * 某路口某進入方向的左轉規定。
 *
 * 身分由 [location] 與 [approachBearing] 決定，路名僅供顯示 —— 見 ADR-0001。
 * [exitBearing] 可為 null：官方資料與無路線回報只記錄進入方向。
 */
data class IntersectionRule(
    val id: Long,
    val location: LatLon,
    val approachBearing: Double,
    val exitBearing: Double?,
    val rule: TurnRule,
    val status: RuleStatus,
    val confidence: Int,
    val entryRoadName: String?,
    val exitRoadName: String?,
    val effectivePeriod: EffectivePeriod?,
)

/**
 * 規則的生效時段。null 代表全時段適用。
 *
 * 僅支援「週間/週末 + 時段」，刻意不處理國定假日補班補假 ——
 * 那需要每年更新的行事曆，無法判定時退回中性播報比猜測安全。見 ADR-0004。
 */
data class EffectivePeriod(
    val days: DaySet,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
) {
    fun covers(dayOfWeekMonday1: Int, minuteOfDay: Int): Boolean {
        val dayMatches = when (days) {
            DaySet.ALL -> true
            DaySet.WEEKDAY -> dayOfWeekMonday1 in 1..5
            DaySet.WEEKEND -> dayOfWeekMonday1 in 6..7
        }
        if (!dayMatches) return false
        return if (startMinuteOfDay <= endMinuteOfDay) {
            minuteOfDay in startMinuteOfDay..endMinuteOfDay
        } else {
            // 跨午夜，例如 22:00–06:00
            minuteOfDay >= startMinuteOfDay || minuteOfDay <= endMinuteOfDay
        }
    }

    companion object {
        /** 無法解析的時段字串，呼叫端應據此退回中性播報。 */
        val UNPARSEABLE: EffectivePeriod? = null
    }
}

enum class DaySet { ALL, WEEKDAY, WEEKEND }
