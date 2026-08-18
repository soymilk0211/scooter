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
    /**
     * 到**停止線**的距離，不是到路口中心。
     *
     * 規則的座標是路口中心（OSM 節點群聚的形心），但騎士要做決定、要停下來的
     * 地方是停止線，而大路口的停止線在中心前方十幾二十公尺。用中心算的話，
     * 每一則警示都會晚一點點 —— 而那個「一點點」在時速 40 是一秒多。
     *
     * 扣掉的量見 [AlertThresholds.STOP_LINE_SETBACK_METERS]。
     */
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

    /**
     * 剛性警示的提前量。**以時間計，不以距離計。**
     *
     * 舊版寫死 300 公尺，而同樣 300 公尺在時速 20 時是 54 秒、在時速 50 時只有
     * 21.6 秒 —— 差了兩倍以上，偏偏「來不來得及反應」正是這則警示唯一要保證的事。
     * 二十秒是取「聽完一句話 + 確認車況 + 變換車道」的量，同時也吸收得了即時
     * TTS 首句 2.8–3.6 秒的延遲。
     */
    const val LEAD_SECONDS = 20.0

    /**
     * 時窗的距離下限。
     *
     * 塞車時時速 5 公里，二十秒只有 28 公尺 —— 那句話會在路口正中央才播出來。
     * 低速時騎士有的是反應時間，缺的是距離，所以下限由距離而不是時間決定。
     */
    const val MIN_LEAD_METERS = 80.0

    /**
     * 時窗的距離上限。
     *
     * 時速 60 時二十秒是 333 公尺，再遠就會開始把中間的路口算進來 ——
     * 而「前方路口」如果不是騎士看到的下一個路口，那句話比不講更糟。
     */
    const val MAX_LEAD_METERS = 350.0

    /**
     * 路口中心到停止線的退距。
     *
     * **規則的座標是路口中心，但騎士的決定點是停止線。** 中心來自 OSM 節點
     * 群聚的形心；停止線在它前方，距離是「橫向道路的半寬 + 行人穿越道 + 緩衝」。
     * 臺北的幹道路口：橫向四車道約半寬 6.5 公尺，加上行人穿越道與退縮約 6–8 公尺，
     * 合計十幾公尺；大路口會更多。
     *
     * **這裡用單一常數，不做每個路口各自的值。** 想過用 `junctions.json` 的
     * `node_count`（群聚了幾個號誌節點）去推路口大小，但那個對應關係我們沒有
     * 任何方法驗證 —— 那會是一個編出來的校準，而編出來的校準比一個誠實的常數
     * 更難發現是錯的。真要做得更準，該做的是從 OSM 的 `lanes` 取橫向道路寬度
     * （臺北覆蓋 68%），那要新增一個欄位，等有辦法驗證再說。
     *
     * 取 15 公尺的偏誤方向是**保守的**：估太多會讓警示提早，估太少會讓它遲到，
     * 而遲到的警示是騎士錯過路口。
     */
    const val STOP_LINE_SETBACK_METERS = 15.0

    /**
     * 資料庫粗篩半徑。**要比時窗上限再多一個退距** —— 規則存的是路口中心，
     * 而時窗量的是停止線；中心在 360 公尺的路口，停止線已經在 345 公尺、
     * 落在窗內了。用 350 去查會把它整批漏掉，而症狀是「高速時偶爾不響」。
     */
    const val MAX_DISTANCE_METERS = MAX_LEAD_METERS + STOP_LINE_SETBACK_METERS

    /** 目前速度下的剛性時窗，公尺。 */
    fun leadDistanceMeters(speedKmh: Double): Double =
        ((speedKmh / 3.6) * LEAD_SECONDS).coerceIn(MIN_LEAD_METERS, MAX_LEAD_METERS)

    /**
     * 放寬至 30 度而非 PRD 原訂的 20 度：彎道接近時，300 公尺外的行進方向
     * 與最終進入方向可以差很多。寧可多播一次，不要漏播。
     */
    const val MAX_BEARING_DELTA_DEGREES = 30.0

    const val MIN_SPEED_KMH = 15.0

    // 回報介面的門檻不在這裡。它的條件是「真的停下來」（連續靜止一段時間），
    // 而那要看好幾個點才判得出來，所以定義在 TrackBuffer.STOPPED_SPEED_KMH／
    // STOPPED_MIN_MILLIS。舊版在這裡放過一個 10 km/h 的單點門檻，
    // 那讓還在滑行的騎士也能回報，而滑行中的方位角可能正記在轉彎的半途。

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

        val window = AlertThresholds.leadDistanceMeters(state.speedKmh)

        return nearby
            .asSequence()
            .filter { it.effectivePeriod?.covers(state.dayOfWeek, state.minuteOfDay) ?: true }
            .filter { it.status != RuleStatus.DISPUTED }
            .filter { !isCoolingDown(it.id, state.epochMillis) }
            .map {
                AlertCandidate(
                    rule = it,
                    // 規則的座標是路口中心，播報要以**停止線**為準。騎士是沿著
                    // 進入方位角過來的（±30° 閘門保證了這一點），所以退距直接
                    // 從距離上扣，不必另外算一個停止線座標。
                    distanceMeters = (
                        haversineMeters(state.location, it.location) -
                            AlertThresholds.STOP_LINE_SETBACK_METERS
                        ).coerceAtLeast(0.0),
                    bearingDelta = bearingDelta(bearing, it.approachBearing),
                )
            }
            .filter { it.distanceMeters <= window }
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
