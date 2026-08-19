package tw.scooter.ride

import tw.scooter.data.ScooterDatabase
import tw.scooter.rules.AlertCandidate
import tw.scooter.rules.AlertThresholds
import tw.scooter.rules.EnforcementCandidate
import tw.scooter.rules.EnforcementMatcher
import tw.scooter.rules.EnforcementThresholds
import tw.scooter.rules.ProhibitedCandidate
import tw.scooter.rules.ProhibitedMatcher
import tw.scooter.rules.ProhibitedThresholds
import tw.scooter.rules.RiderState
import tw.scooter.rules.RuleMatcher
import tw.scooter.rules.SelfReports

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
     * 騎士正走在一段全面禁行機車的路上。
     *
     * 不與 [turn] 合併：它說的不是「前方路口怎麼轉」，而是「你現在在的這條路
     * 不能走」—— 對騎士的處置完全不同（下一個路口就要離開），
     * 而且它是**狀態**不是接近事件。
     */
    val prohibited: ProhibitedCandidate?,
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
    private val prohibitedMatcher: ProhibitedMatcher = ProhibitedMatcher(),
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
        // 沒有行進方位角就什麼都判不了：規則掛在來向上，速限也要方向才選得出來。
        if (state.bearing == null) return RideAlerts(null, null, null, null)

        val riding = state.speedKmh > AlertThresholds.MIN_SPEED_KMH

        // 路口規則只在騎乘中查。靜止時不存在「接近路口」這件事，
        // 省下的是每秒一次的網格查詢。
        val turn = if (!riding) null else SelfReports
            .merge(
                published = database.rulesNear(
                    lat = state.location.lat,
                    lon = state.location.lon,
                    radiusMeters = AlertThresholds.MAX_DISTANCE_METERS,
                ),
                // 自己按過的回報立刻生效，不等發布閘門 —— 那道閘門管的是
                // 「回報 → 別人的裝置」，不是「回報 → 自己的裝置」。理由的
                // 完整版在 SelfReports，那裡也有測試。
                own = database.selfReportsNear(
                    lat = state.location.lat,
                    lon = state.location.lon,
                    radiusMeters = AlertThresholds.MAX_DISTANCE_METERS,
                ),
            )
            .takeIf { it.isNotEmpty() }
            ?.let { matcher.select(state, it) }
            ?.also { matcher.markAlerted(it.rule.id, state.epochMillis) }

        // 執法點**無論速度多少都要查**。警示本身有速度門檻（在 matcher 裡），
        // 但速限是一個持續存在的狀態：塞車時時速掉到 15 以下，速限並沒有跟著消失。
        // 舊版把整個 evaluate 綁在同一個速度門檻上，結果是圓圈在慢行時無故變灰，
        // 而那正是騎士最可能低頭看它的時候。
        val points = database.enforcementNear(
            lat = state.location.lat,
            lon = state.location.lon,
            radiusMeters = EnforcementThresholds.MAX_DISTANCE_METERS,
        )
        val enforcement = points
            .takeIf { it.isNotEmpty() }
            ?.let { enforcementMatcher.select(state, it) }
            ?.also { enforcementMatcher.markAlerted(it.point.id, state.epochMillis) }

        // 禁行路段用**自己的**速度門檻（8 km/h，比警示的 15 低）：騎士在禁行
        // 路段上塞車也還是在禁行路段上。查詢半徑只要涵蓋側向容許量再加上
        // 一格網格的粗篩誤差，不需要跟警示一樣遠 —— 這是「我現在在哪」，
        // 不是「前方有什麼」。
        val prohibited = if (state.speedKmh < ProhibitedThresholds.MIN_SPEED_KMH) null else {
            database
                .prohibitedNear(
                    lat = state.location.lat,
                    lon = state.location.lon,
                    radiusMeters = ProhibitedThresholds.MAX_LATERAL_METERS,
                )
                .takeIf { it.isNotEmpty() }
                ?.let { prohibitedMatcher.select(state, it) }
                ?.also { prohibitedMatcher.markAlerted(it.segment.id, state.epochMillis) }
        }

        return RideAlerts(
            turn = turn,
            enforcement = enforcement,
            prohibited = prohibited,
            // 速限與警示分開算：警示播過就冷卻，速限必須在播完之後到通過鏡頭
            // 之間那段路上繼續有效 —— 那正是圓圈的顏色要幫上忙的時候。
            speedLimitKmh = enforcementMatcher.contextLimit(state, points),
        )
    }
}
