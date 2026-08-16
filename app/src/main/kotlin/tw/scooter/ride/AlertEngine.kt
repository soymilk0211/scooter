package tw.scooter.ride

import tw.scooter.data.ScooterDatabase
import tw.scooter.rules.AlertCandidate
import tw.scooter.rules.AlertThresholds
import tw.scooter.rules.EnforcementCandidate
import tw.scooter.rules.EnforcementMatcher
import tw.scooter.rules.EnforcementThresholds
import tw.scooter.rules.RiderState
import tw.scooter.rules.RuleMatcher

/**
 * 一次定位更新的判定結果。
 *
 * 兩種警示分開回傳，不合併成一個「最高優先度警示」—— 它們搶的是不同的東西：
 * 語音通道只有一條，但畫面可以同時顯示路口指示與速限。硬要合併，畫面就會少掉
 * 一半資訊。
 */
data class RideAlerts(
    val turn: AlertCandidate?,
    val enforcement: EnforcementCandidate?,
    /**
     * 時速圓圈該拿來對照的速限。null 代表這一帶沒有速限資料，圓圈就只顯示速度
     * 不評價 —— 猜一個速限出來上色比不上色危險得多。
     */
    val speedLimitKmh: Int?,
)

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
    private val enforcementMatcher: EnforcementMatcher = EnforcementMatcher(),
) {

    /**
     * 對一次定位更新做判定。命中的規則會立刻記入冷卻，所以同一則警示不會在後續的
     * 定位更新中重複觸發。
     *
     * 兩種警示各自判定、各自冷卻。它們的時窗本來就不重疊（測速 500–320 公尺，
     * 路口 300 公尺以內），所以這裡不需要仲裁 —— 讓路是由窗的位置決定的，
     * 不是由一個排序函式裡的隱含行為決定的。
     */
    fun evaluate(state: RiderState): RideAlerts {
        if (state.bearing == null || state.speedKmh <= AlertThresholds.MIN_SPEED_KMH) {
            return RideAlerts(null, null, null)
        }

        val turn = database
            .rulesNear(
                lat = state.location.lat,
                lon = state.location.lon,
                radiusMeters = AlertThresholds.MAX_DISTANCE_METERS,
            )
            .takeIf { it.isNotEmpty() }
            ?.let { matcher.select(state, it) }
            ?.also { matcher.markAlerted(it.rule.id, state.epochMillis) }

        val points = database.enforcementNear(
            lat = state.location.lat,
            lon = state.location.lon,
            radiusMeters = EnforcementThresholds.MAX_DISTANCE_METERS,
        )
        val enforcement = points
            .takeIf { it.isNotEmpty() }
            ?.let { enforcementMatcher.select(state, it) }
            ?.also { enforcementMatcher.markAlerted(it.point.id, state.epochMillis) }

        return RideAlerts(
            turn = turn,
            enforcement = enforcement,
            // 速限與警示分開算：警示播過就冷卻，速限必須在播完之後到通過鏡頭
            // 之間那段路上繼續有效 —— 那正是圓圈的顏色要幫上忙的時候。
            speedLimitKmh = enforcementMatcher.contextLimit(state, points),
        )
    }
}
