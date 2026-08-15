package tw.scooter.rules

/** 騎士當下的狀態，警示判定的唯一輸入。 */
data class RiderState(
    val location: LatLon,
    /** 行進方位角，度。無有效值時為 null（靜止或低速時 GPS 不提供）。 */
    val bearing: Double?,
    val speedKmh: Double,
    val epochMillis: Long,
    /** 週一為 1，週日為 7。 */
    val dayOfWeek: Int,
    val minuteOfDay: Int,
)

data class AlertCandidate(
    val rule: IntersectionRule,
    val distanceMeters: Double,
    val bearingDelta: Double,
) {
    /** 依當前速度估算的剩餘秒數。警示排序依此值，不依規則類型 —— 見第五輪 Q33。 */
    fun secondsAway(speedKmh: Double): Double {
        val mps = (speedKmh / 3.6).coerceAtLeast(0.1)
        return distanceMeters / mps
    }
}

object AlertThresholds {
    const val MAX_DISTANCE_METERS = 300.0

    /**
     * 放寬至 30 度而非 PRD 原訂的 20 度：彎道接近時，300 公尺外的行進方向
     * 與最終進入方向可以差很多。寧可多播一次，不要漏播。
     */
    const val MAX_BEARING_DELTA_DEGREES = 30.0

    const val MIN_SPEED_KMH = 15.0

    /** 回報按鈕的解鎖上限。高於此速度即鎖定，避免騎乘中操作。 */
    const val REPORT_UNLOCK_MAX_SPEED_KMH = 10.0

    /** 同一規則的重播冷卻。以時間計而非以行程計 —— 我們沒有「行程」的概念。 */
    const val COOLDOWN_MILLIS = 5 * 60 * 1000L
}

/**
 * 從候選規則中挑出唯一該播報的一則。
 *
 * 刻意不做佇列：路口過了才播的警示比沒播更危險，落選的候選直接丟棄。
 */
class RuleMatcher(private val cooldownMillis: Long = AlertThresholds.COOLDOWN_MILLIS) {

    private val lastAlertedAt = mutableMapOf<Long, Long>()

    fun select(state: RiderState, nearby: List<IntersectionRule>): AlertCandidate? {
        val bearing = state.bearing ?: return null
        if (state.speedKmh <= AlertThresholds.MIN_SPEED_KMH) return null

        return nearby
            .asSequence()
            .filter { it.effectivePeriod?.covers(state.dayOfWeek, state.minuteOfDay) ?: true }
            .filter { it.status != RuleStatus.DISPUTED }
            .filter { !isCoolingDown(it.id, state.epochMillis) }
            .map {
                AlertCandidate(
                    rule = it,
                    distanceMeters = haversineMeters(state.location, it.location),
                    bearingDelta = bearingDelta(bearing, it.approachBearing),
                )
            }
            .filter { it.distanceMeters <= AlertThresholds.MAX_DISTANCE_METERS }
            .filter { it.bearingDelta <= AlertThresholds.MAX_BEARING_DELTA_DEGREES }
            .minWithOrNull(
                compareBy({ it.secondsAway(state.speedKmh) }, { it.bearingDelta }),
            )
    }

    fun markAlerted(ruleId: Long, epochMillis: Long) {
        lastAlertedAt[ruleId] = epochMillis
    }

    private fun isCoolingDown(ruleId: Long, now: Long): Boolean {
        val last = lastAlertedAt[ruleId] ?: return false
        return now - last < cooldownMillis
    }
}
