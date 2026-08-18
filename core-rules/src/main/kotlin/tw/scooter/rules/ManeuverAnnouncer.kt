package tw.scooter.rules

/** 一次該播出去的轉向播報。 */
data class Announcement(
    val maneuver: Maneuver,
    val stage: Stage,
    /** 播報當下離轉向還有多遠，四捨五入到播報用的級距。 */
    val distanceBucketMeters: Int?,
) {
    enum class Stage {
        /** 主指示，約 20 秒前。含距離。 */
        PRIMARY,

        /** 確認短句，約 5 秒前。不含距離 —— 那時距離已經沒有意義了。 */
        CONFIRM,
    }
}

/**
 * 決定什麼時候該播轉向指示。
 *
 * ## 為什麼是兩段
 *
 * 一段不夠。只播 20 秒那一則，騎士在路口前那幾秒會不確定「是這個路口嗎」；
 * 只播 5 秒那一則，來不及變換車道。所以主指示給資訊、確認短句給時機。
 *
 * ## 為什麼距離用級距而不是實際值
 *
 * 播「前方 287 公尺左轉」沒有比「前方 300 公尺左轉」更有用，而**級距讓句子
 * 變成有限集合，可以全部預先合成** —— 即時 TTS 首句延遲實測 2.8–3.6 秒，
 * 而 5 秒那一則吸收不了。這是決策檔案 D5 的直接後果：既然路線引擎不給路名，
 * 句子裡就沒有動態成分，那就全部預合成。
 *
 * ## 每個轉向只播一次
 *
 * 兩個階段各一次。不做「距離變遠又變近就重播」—— 那在塞車時會一直響。
 */
class ManeuverAnnouncer(
    private val leadSeconds: Double = AlertThresholds.LEAD_SECONDS,
    private val confirmSeconds: Double = DEFAULT_CONFIRM_SECONDS,
) {
    private val announced = mutableSetOf<Pair<Double, Announcement.Stage>>()

    fun reset() = announced.clear()

    /**
     * 這一次定位更新該不該播。不該播就回 null。
     *
     * [progress] 為 null（沒有路線或還沒定位）一律不播。
     * 偏離路線時也不播 —— 那時該做的是重算，而對著一條已經不成立的路線
     * 喊「前方左轉」會把騎士帶到錯的地方。
     */
    fun update(progress: RouteProgress?, speedKmh: Double): Announcement? {
        if (progress == null || progress.offRoute) return null
        val maneuver = progress.nextManeuver ?: return null
        val distance = progress.metersToNextManeuver ?: return null

        val mps = (speedKmh / 3.6).coerceAtLeast(MIN_SPEED_FOR_TIMING_MPS)
        val seconds = distance / mps

        val stage = when {
            seconds <= confirmSeconds -> Announcement.Stage.CONFIRM
            seconds <= leadSeconds -> Announcement.Stage.PRIMARY
            else -> return null
        }

        val key = maneuver.alongRouteMeters to stage
        if (!announced.add(key)) return null

        // 確認短句不講距離：那時候「還有 30 公尺」對騎士沒有用，
        // 而且多一個詞就多一秒，那一秒正是他要看路口的時候。
        val bucket = if (stage == Announcement.Stage.CONFIRM) null else bucketOf(distance)
        return Announcement(maneuver, stage, bucket)
    }

    /**
     * 把距離收成播報用的級距。
     *
     * 級距的疏密照人講話的習慣 —— 沒有人說「前方兩百五十公尺」，
     * 而三百與四百之間的差別在騎士的感受上也不重要。
     */
    private fun bucketOf(meters: Double): Int {
        val buckets = intArrayOf(50, 100, 200, 300, 500)
        return buckets.minByOrNull { kotlin.math.abs(it - meters) } ?: buckets.last()
    }

    companion object {
        /** 確認短句的提前量。五秒約是騎士看一眼路口再轉的時間。 */
        const val DEFAULT_CONFIRM_SECONDS = 5.0

        /**
         * 換算秒數時的速度下限（公尺／秒），約時速 7 公里。
         *
         * 沒有下限的話，塞車時「還有幾秒」會變成幾百秒，主指示永遠不會播 ——
         * 而騎士照樣會抵達那個路口。
         */
        const val MIN_SPEED_FOR_TIMING_MPS = 2.0
    }
}
