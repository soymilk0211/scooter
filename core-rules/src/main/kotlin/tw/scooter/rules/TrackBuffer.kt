package tw.scooter.rules

data class TrackPoint(
    val location: LatLon,
    /** GPS 由位移推得的行進方位角。靜止或低速時系統不提供，為 null。 */
    val bearing: Double?,
    val speedKmh: Double,
    val epochMillis: Long,
)

/**
 * 近期軌跡的滑動視窗。
 *
 * 存在的理由只有一個：**騎士停下來回報時，我們需要知道他是從哪個方向來的**，
 * 但 GPS 在靜止時不提供方位角。解法是回頭取停止前最後一段有效的行進方向。
 *
 * 這同時濾掉一種壞資料 —— 剛停好車、走路過來亂按的使用者不會有有效行進軌跡，
 * [approachBearing] 會回傳 null，回報因此無法成立。
 */
class TrackBuffer(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val points = ArrayDeque<TrackPoint>()

    fun add(point: TrackPoint) {
        points.addLast(point)
        val cutoff = point.epochMillis - windowMillis
        while (points.isNotEmpty() && points.first().epochMillis < cutoff) {
            points.removeFirst()
        }
    }

    fun latest(): TrackPoint? = points.lastOrNull()

    fun size(): Int = points.size

    fun clear() = points.clear()

    /**
     * 停止前最後一個可信的行進方位角，找不到則為 null。
     *
     * 「可信」要求速度高於 [MIN_TRUSTWORTHY_SPEED_KMH]：低於此速度時 GPS 的
     * 方位角由極短位移推得，雜訊大到不能用來決定一條規則掛在哪個方向上。
     */
    fun approachBearing(): Double? = points
        .lastOrNull { it.bearing != null && it.speedKmh >= MIN_TRUSTWORTHY_SPEED_KMH }
        ?.bearing

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 30_000L

        /** 低於此速度的方位角視為雜訊。 */
        const val MIN_TRUSTWORTHY_SPEED_KMH = 8.0
    }
}
