package tw.scooter.ride

import tw.scooter.data.ScooterDatabase
import tw.scooter.rules.AlertCandidate
import tw.scooter.rules.AlertThresholds
import tw.scooter.rules.RiderState
import tw.scooter.rules.RuleMatcher

/**
 * 把定位、規則資料庫與判定邏輯接在一起。
 *
 * 三者先前各自存在卻沒有連線：RuleMatcher 有單元測試但沒人餵它真實座標，
 * ScooterDatabase 查得到規則但沒人查，RideService 收得到定位但只更新 UI。
 * 這個類別是那條缺掉的線。
 *
 * 空間粗篩交給資料庫的網格索引，精確的距離與方位角比對留在 core-rules ——
 * 那裡沒有 Android 依賴，可以完整單元測試。
 */
class AlertEngine(
    private val database: ScooterDatabase,
    private val matcher: RuleMatcher = RuleMatcher(),
) {

    /**
     * 對一次定位更新做判定，回傳應該播報的警示；沒有則為 null。
     *
     * 命中的規則會立刻記入冷卻，所以同一則警示不會在後續的定位更新中重複觸發。
     */
    fun evaluate(state: RiderState): AlertCandidate? {
        if (state.bearing == null || state.speedKmh <= AlertThresholds.MIN_SPEED_KMH) return null

        val nearby = database.rulesNear(
            lat = state.location.lat,
            lon = state.location.lon,
            radiusMeters = AlertThresholds.MAX_DISTANCE_METERS,
        )
        if (nearby.isEmpty()) return null

        return matcher.select(state, nearby)?.also {
            matcher.markAlerted(it.rule.id, state.epochMillis)
        }
    }
}
