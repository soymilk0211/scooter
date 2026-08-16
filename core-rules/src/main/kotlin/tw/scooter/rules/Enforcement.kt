package tw.scooter.rules

/** 執法設備的種類。序數值即資料庫儲存值，變更會破壞既有資料。 */
enum class EnforcementKind(val id: Int) {
    FIXED_SPEED_CAMERA(1),
    SMART_ENFORCEMENT(2),
    UNKNOWN(0);

    companion object {
        fun fromId(id: Int): EnforcementKind = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/**
 * 一個固定執法點。
 *
 * [bearing] 為 null 代表**不限方向** —— 可能是雙向取締，也可能是原始資料的方向
 * 欄位寫得無法確定。兩者都走同一條保守路徑：對所有來向發出警示。對著反方向多播
 * 一次只是吵，該播沒播是騎士收到罰單。
 *
 * [speedLimitKmh] 為 null 代表這個點沒有速限資料（科技執法多半如此），
 * 播報時就不能講速限，也判不出超速。
 */
data class EnforcementPoint(
    val id: Long,
    val location: LatLon,
    val bearing: Double?,
    val kind: EnforcementKind,
    val speedLimitKmh: Int?,
    val description: String?,
)

data class EnforcementCandidate(
    val point: EnforcementPoint,
    val distanceMeters: Double,
    /** 行進方向與取締方向的夾角。點位不限方向時為 null。 */
    val bearingDelta: Double?,
    /** 播報當下是否已經超速。無速限資料時恆為 false。 */
    val overSpeed: Boolean,
)

object EnforcementThresholds {
    /**
     * 測速警示的**彈性時窗**（見 CONTEXT.md）。
     *
     * 轉向指示是剛性的：太早講騎士會忘，太晚講來不及轉，所以固定 300 公尺。
     * 測速不是 —— 五百公尺前講與兩百公尺前講同樣有效，因為減速隨時做得到。
     * 窗開得比轉向指示遠，兩者就會自然錯開：測速在 500–320 公尺之間講完，
     * 轉向指示的 300 公尺窗打開時語音通道已經空了。
     *
     * 這就是「彈性的往前讓，剛性的不動」的實作 —— 不用延後低優先度的那則，
     * 因為被延後的若是剛性警示，等於直接讓騎士錯過路口。
     */
    const val MAX_DISTANCE_METERS = 500.0

    /** 低於這個距離就不再開口。這時候該講的是路口，不是幾百公尺前就能做完的減速。 */
    const val MIN_DISTANCE_METERS = 320.0

    /**
     * 方位角閘門比路口規則寬。測速照相多半設在直路上，而騎士在 500 公尺外的
     * 行進方向與通過鏡頭時的方向差得比路口更多。
     */
    const val MAX_BEARING_DELTA_DEGREES = 45.0

    /** 靜止或牽車時不播。與路口警示同一個門檻。 */
    const val MIN_SPEED_KMH = AlertThresholds.MIN_SPEED_KMH

    /**
     * 判定「已超速」時容許的誤差。
     *
     * **這不是通融，是 GPS 的雜訊。** 定速 50 的實測速度會在 48–53 之間跳，
     * 零容許值會讓警示在完全守法的騎士耳邊反覆說他超速 —— 那會讓他學會忽略它。
     * 畫面上的時速圓圈用的是精確值，顏色照實變；只有**開口說話**這件事有容許值。
     */
    const val OVER_SPEED_MARGIN_KMH = 3.0

    /** 同一個點的重播冷卻。與路口警示相同。 */
    const val COOLDOWN_MILLIS = AlertThresholds.COOLDOWN_MILLIS
}

/**
 * 從附近的執法點中挑出該播報的一則。
 *
 * 與 [RuleMatcher] 分開，因為兩者的時窗性質不同（剛性 vs 彈性），混在一起會讓
 * 「誰該讓誰」變成一個排序函式裡的隱含行為。分開之後，讓路是由**窗的位置**
 * 決定的，看得見也測得到。
 */
class EnforcementMatcher(
    private val cooldownMillis: Long = EnforcementThresholds.COOLDOWN_MILLIS,
) {

    private val lastAlertedAt = mutableMapOf<Long, Long>()

    fun select(state: RiderState, nearby: List<EnforcementPoint>): EnforcementCandidate? {
        val bearing = state.bearing ?: return null
        if (state.speedKmh <= EnforcementThresholds.MIN_SPEED_KMH) return null

        return nearby
            .asSequence()
            .filter { !isCoolingDown(it.id, state.epochMillis) }
            .map { point ->
                EnforcementCandidate(
                    point = point,
                    distanceMeters = haversineMeters(state.location, point.location),
                    bearingDelta = point.bearing?.let { bearingDelta(bearing, it) },
                    overSpeed = isOverSpeed(state.speedKmh, point.speedLimitKmh),
                )
            }
            .filter { it.distanceMeters in
                EnforcementThresholds.MIN_DISTANCE_METERS..EnforcementThresholds.MAX_DISTANCE_METERS }
            // 不限方向的點不套方位角閘門 —— null 的意思正是「哪個方向來都算」。
            .filter { (it.bearingDelta ?: 0.0) <= EnforcementThresholds.MAX_BEARING_DELTA_DEGREES }
            // 最遠的先講。彈性時窗的重點就是趁早講完，把通道讓給後面的剛性警示。
            .maxWithOrNull(compareBy({ it.distanceMeters }, { -(it.bearingDelta ?: 0.0) }))
    }

    fun markAlerted(pointId: Long, epochMillis: Long) {
        lastAlertedAt[pointId] = epochMillis
    }

    /**
     * 目前該拿來對照的速限，沒有就是 null。
     *
     * 與 [select] 分開，因為它回答的是完全不同的問題。[select] 問「現在該不該
     * 開口」，受時窗與冷卻管轄；這個問「時速圓圈該拿哪個數字當基準」，那是一個
     * **持續存在的狀態**，不能因為警示播過一次就消失 —— 騎士需要的正是播完之後
     * 到通過鏡頭之間那段路上的顏色。
     *
     * 台灣沒有可靠的全路網速限資料（OSM 的 `maxspeed` 只有三成五覆蓋），所以這裡
     * 唯一誠實的答案就是「前方那台照相機的速限」。查無資料時回 null，圓圈就只
     * 顯示速度、不評價 —— 猜一個速限出來上色，比不上色危險得多。
     */
    fun contextLimit(state: RiderState, nearby: List<EnforcementPoint>): Int? {
        val bearing = state.bearing ?: return null
        return nearby
            .asSequence()
            .filter { it.speedLimitKmh != null }
            .filter { point ->
                point.bearing?.let { bearingDelta(bearing, it) }
                    ?.let { it <= EnforcementThresholds.MAX_BEARING_DELTA_DEGREES } ?: true
            }
            .filter {
                haversineMeters(state.location, it.location) <=
                    EnforcementThresholds.MAX_DISTANCE_METERS
            }
            .minByOrNull { haversineMeters(state.location, it.location) }
            ?.speedLimitKmh
    }

    private fun isCoolingDown(pointId: Long, now: Long): Boolean {
        val last = lastAlertedAt[pointId] ?: return false
        return now - last < cooldownMillis
    }

    companion object {
        fun isOverSpeed(speedKmh: Double, limitKmh: Int?): Boolean {
            if (limitKmh == null) return false
            return speedKmh > limitKmh + EnforcementThresholds.OVER_SPEED_MARGIN_KMH
        }
    }
}
