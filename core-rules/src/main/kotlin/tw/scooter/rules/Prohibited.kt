package tw.scooter.rules

/**
 * 一段**全面禁行機車**的道路。
 *
 * 與「內側車道禁行機車」不是同一件事：那是車道級的，本專案不收集
 * （ADR-0011，收結果不收成因）。這裡說的是**整條路機車都不能走** ——
 * 臺北市有五段，例如忠孝西路（重慶南路到中山北路）與環河北路。
 *
 * [bearing] 是**面向**，而且禁行是**單向登錄**的：忠孝西路兩個方向是兩筆，
 * 各自有各自的起訖。不要合併成雙向，那會讓某些路段的反向被誤判成禁行。
 */
data class ProhibitedSegment(
    val id: Long,
    val roadName: String,
    val bearing: Double,
    val polyline: List<LatLon>,
    val speedLimitKmh: Int?,
    val reason: String?,
)

data class ProhibitedCandidate(
    val segment: ProhibitedSegment,
    /** 騎士離折線的側向距離，公尺。 */
    val lateralMeters: Double,
    val bearingDelta: Double,
)

object ProhibitedThresholds {

    /**
     * 騎士離折線多近才算「在這條路上」。
     *
     * 這個數字是兩個誤差的和：道路半寬（大路約 15 公尺）加上都市峽谷裡的 GPS
     * 誤差。放寬會把隔壁的側車道與平行道路吃進來，收緊會在 GPS 飄掉時漏掉。
     *
     * **這是本專案目前最脆弱的一個判定**，理由見下面 [ProhibitedMatcher] 的說明。
     */
    const val MAX_LATERAL_METERS = 25.0

    /**
     * 行進方向與登錄方向的容許夾角。
     *
     * 比路口規則的 ±30° 寬，因為這裡比對的是一整條路的登錄走向，
     * 而騎士在彎道上（環河北路沿著淡水河彎）的瞬時方向可以差不少。
     */
    const val MAX_BEARING_DELTA_DEGREES = 45.0

    /**
     * 低於這個速度不判定。
     *
     * 比警示的 15 km/h 低：騎士在禁行路段上塞車也還是在禁行路段上。
     * 但仍然要有一個門檻 —— 靜止時沒有可信的方位角，而方向正是這裡唯一
     * 分得出「這個方向禁行」與「反向合法」的東西。
     */
    const val MIN_SPEED_KMH = 8.0

    /** 同一段的重播冷卻。整段路可能有好幾公里，不冷卻會一路唸。 */
    const val COOLDOWN_MILLIS = 5 * 60 * 1000L
}

/**
 * 判斷騎士是不是正走在一段全面禁行機車的路上。
 *
 * ## 這個判定的已知弱點，用之前要知道
 *
 * 它是「點到折線的距離 + 方位角閘門」，**不是 map matching**。
 * 也就是說它分不出**垂直分離**的道路：環河北路的正上方就是環河快速道路，
 * 兩者的水平距離是零、走向完全相同。騎在高架上的人與騎在平面的人，
 * 在這個判定眼裡一模一樣。
 *
 * 目前這件事**剛好不致命**，因為白牌機車本來就不能上那條快速道路 ——
 * 會被誤判的人本來就不該在那裡。但同樣的結構在別的路段可能反過來
 * （合法的平面道路被上方的禁行高架蓋到），所以：
 *
 * - **這則提示的措辭必須是提醒，不能是指控。** 「這條路禁行機車」而不是
 *   「你違規了」—— 我們沒有把握到可以指控的程度。
 * - 真正的解法是路網圖上機之後改用 map matching（Valhalla 的 Meili），
 *   那時 way 編號就能直接比對，垂直分離也不再是問題。
 *   `prohibited_segments.way_ids` 就是為那一天存的。
 */
class ProhibitedMatcher(
    private val cooldownMillis: Long = ProhibitedThresholds.COOLDOWN_MILLIS,
) {

    private val lastAlertedAt = mutableMapOf<Long, Long>()

    fun select(state: RiderState, nearby: List<ProhibitedSegment>): ProhibitedCandidate? {
        val bearing = state.bearing ?: return null
        if (state.speedKmh < ProhibitedThresholds.MIN_SPEED_KMH) return null

        return nearby
            .asSequence()
            .filter { !isCoolingDown(it.id, state.epochMillis) }
            // 方向先篩。它比距離便宜得多，而忠孝西路那種兩向都登錄的路段，
            // 每次都有一半的候選會在這裡被刷掉。
            .filter {
                bearingDelta(bearing, it.bearing) <= ProhibitedThresholds.MAX_BEARING_DELTA_DEGREES
            }
            .map {
                ProhibitedCandidate(
                    segment = it,
                    lateralMeters = distanceToPolylineMeters(state.location, it.polyline),
                    bearingDelta = bearingDelta(bearing, it.bearing),
                )
            }
            .filter { it.lateralMeters <= ProhibitedThresholds.MAX_LATERAL_METERS }
            // 最近的贏。兩段路都命中時，騎士比較可能在近的那一條上。
            .minByOrNull { it.lateralMeters }
    }

    fun markAlerted(segmentId: Long, epochMillis: Long) {
        lastAlertedAt[segmentId] = epochMillis
    }

    private fun isCoolingDown(segmentId: Long, now: Long): Boolean {
        val last = lastAlertedAt[segmentId] ?: return false
        return now - last < cooldownMillis
    }
}
