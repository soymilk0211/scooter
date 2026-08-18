package tw.scooter.rules

/**
 * 路線上的一次轉向。
 *
 * **沒有路名。** 路線引擎（BRouter）的圖資只存路由用得到的標籤，`name` 不在其中。
 * 播報因此講不出「忠孝東路左轉」—— 而那反過來讓所有句子變成有限集合，
 * 可以全部預先合成（見決策檔案 D5）。
 *
 * [alongRouteMeters] 是從起點**沿著路線**走到這裡的距離，不是直線距離。
 * 導航跟隨要的是前者，因為騎士是沿著路走的。
 */
data class Maneuver(
    val at: LatLon,
    val alongRouteMeters: Double,
    /** 轉向角度，負為左、正為右。 */
    val angleDegrees: Float,
    /**
     * 是不是左轉類（含大左轉與斜左轉）。
     *
     * 本專案只對左轉有台灣專屬的規則 —— 待轉掛在左轉上，禁止左轉也是。
     */
    val isLeftTurn: Boolean,
    /** 轉入那條路的標籤，形如 `highway=secondary oneway=yes`。**不含路名。** */
    val wayTags: String = "",
)

/** 一條算好的路線。 */
data class Route(
    val points: List<LatLon>,
    val distanceMeters: Int,
    val maneuvers: List<Maneuver>,
) {
    val turnCount: Int get() = maneuvers.size
}

/** 騎士在路線上的當下位置。 */
data class RouteProgress(
    /** 沿路線已經走了多遠。 */
    val alongRouteMeters: Double,
    /** 離路線多遠（側向）。 */
    val lateralMeters: Double,
    /** 下一個轉向，已經走完全部轉向時為 null。 */
    val nextManeuver: Maneuver?,
    /** 到下一個轉向還有多遠，沿路線算。沒有下一個轉向時為 null。 */
    val metersToNextManeuver: Double?,
    /** 離路線太遠，需要重算。 */
    val offRoute: Boolean,
)

/**
 * 跟著一條路線走。
 *
 * ## 為什麼要記游標，不能每次從頭找最近點
 *
 * 台北的路線很容易繞回自己附近 —— 迴轉、環狀、平行的來回路段。
 * 每次定位更新都在整條折線上找最近點，會在這些地方**跳到路線的另一段**，
 * 症狀是「導航突然說你已經走了三公里」或「下一個轉向莫名其妙變了」。
 *
 * 所以只在游標前後一個窗內搜尋，而且游標只前進不後退。代價是 GPS 大幅漂移
 * 時可能跟丟，那時 [RouteProgress.offRoute] 會成立，由呼叫端決定要不要重算。
 */
class RouteFollower(
    private val route: Route,
    /** 側向超過這個距離就算偏離路線。 */
    private val offRouteMeters: Double = DEFAULT_OFF_ROUTE_METERS,
    /** 每次更新往前搜尋的節點數。 */
    private val lookAheadPoints: Int = DEFAULT_LOOK_AHEAD_POINTS,
) {
    private var cursor = 0

    /** 供測試與除錯用。 */
    fun cursorIndex(): Int = cursor

    fun update(location: LatLon): RouteProgress? {
        val points = route.points
        if (points.size < 2) return null

        // 只看游標往前的一段。往回一格是為了容忍剛好停在節點上的抖動。
        val from = (cursor - 1).coerceAtLeast(0)
        val to = (cursor + lookAheadPoints).coerceAtMost(points.size - 2)

        var bestIndex = from
        var bestDistance = Double.MAX_VALUE
        for (i in from..to) {
            val d = distanceToSegmentMeters(location, points[i], points[i + 1])
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
            }
        }
        // 游標只前進。後退會讓「已經走了多遠」在原地抖動，
        // 而那個數字是播報時機的依據。
        if (bestIndex > cursor) cursor = bestIndex

        val along = alongRouteAt(bestIndex, location)
        val next = route.maneuvers.firstOrNull { it.alongRouteMeters > along }

        return RouteProgress(
            alongRouteMeters = along,
            lateralMeters = bestDistance,
            nextManeuver = next,
            metersToNextManeuver = next?.let { it.alongRouteMeters - along },
            offRoute = bestDistance > offRouteMeters,
        )
    }

    /**
     * 沿路線走到「騎士投影在第 [index] 段上的位置」為止的距離。
     *
     * 把騎士投影到線段上再算，而不是直接用節點的累積距離 ——
     * 節點間距可能有幾十公尺，直接取節點會讓進度一格一格跳，
     * 而播報是用「還有幾秒到」算的，跳一次就可能跳過一則警示。
     */
    private fun alongRouteAt(index: Int, location: LatLon): Double {
        var acc = 0.0
        for (i in 0 until index) {
            acc += haversineMeters(route.points[i], route.points[i + 1])
        }
        val a = route.points[index]
        val b = route.points[index + 1]
        val segment = haversineMeters(a, b)
        if (segment <= 0.0) return acc
        // 投影比例用兩端距離推：騎士到 a 與到 b 的距離，配上線段長度。
        val da = haversineMeters(location, a)
        val db = haversineMeters(location, b)
        val t = ((segment + (da * da - db * db) / segment) / 2.0 / segment).coerceIn(0.0, 1.0)
        return acc + segment * t
    }

    companion object {
        /**
         * 40 公尺。都市峽谷的 GPS 誤差可以到 20–30 公尺，門檻太緊會在
         * 高樓之間誤判偏航，而每一次誤判都是一次重算與一次多餘的播報。
         */
        const val DEFAULT_OFF_ROUTE_METERS = 40.0

        /** 時速 60 時每秒 17 公尺，節點間距通常幾十公尺 —— 60 個節點綽綽有餘。 */
        const val DEFAULT_LOOK_AHEAD_POINTS = 60
    }
}
