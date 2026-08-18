package tw.scooter.rules

data class TrackPoint(
    val location: LatLon,
    /** GPS 由位移推得的行進方位角。靜止或低速時系統不提供，為 null。 */
    val bearing: Double?,
    val speedKmh: Double,
    val epochMillis: Long,
)

/**
 * 近期軌跡的滑動視窗，外加一個**不受視窗管轄**的進入方位角。
 *
 * 存在的理由只有一個：**騎士停下來回報時，我們需要知道他是從哪個方向來的**，
 * 但 GPS 在靜止時不提供方位角。解法是回頭取停止前最後一段有效的行進方向。
 *
 * ## 為什麼方位角不能跟著視窗一起過期
 *
 * 早期版本把方位角直接從視窗裡撈（`points.lastOrNull { 可信 }`），
 * 而視窗只有 30 秒。騎士停紅燈時 GPS 仍然每秒回報一個靜止點，
 * **紅燈超過 30 秒之後視窗裡就只剩速度為 0 的點**，方位角一律變成 null ——
 * 偏偏長紅燈正是騎士會按回報的時候。這個缺陷在只做警示的時期不會被觸發，
 * 因為那時沒有人在停等時按任何東西。
 *
 * 現在方位角另外持有（[held]），過期的判準從**時鐘**換成**位移**：
 * 人沒有移動，車頭朝向就沒有改變，放多久都仍然成立。
 * 位移離開 [MAX_DRIFT_METERS] 才作廢 —— 用離原點的直線距離而不是累計路徑長度，
 * 否則靜止時的 GPS 抖動會一點一點累加成假的「移動」。
 *
 * ## 仍然會回傳 null 的三種情況，都是刻意的
 *
 * - **從頭到尾沒有可信的行進方位角**：剛停好車、走路過來亂按的人就是這樣，
 *   走路速度低於 [MIN_TRUSTWORTHY_SPEED_KMH]，回報因此無法成立。
 * - **停下後又移動了一段距離**：那已經不是同一個路口的停等。
 * - **定位中斷超過 [MAX_GAP_MILLIS]**：中間發生了什麼我們不知道，
 *   騎士可能已經騎了三公里又回到附近。寧可讓回報失敗。
 */
class TrackBuffer(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val points = ArrayDeque<TrackPoint>()

    /** 最後一個可信的行進方位角，以及記下它時的位置。 */
    private var held: Held? = null

    /** 進入靜止狀態的時刻。一旦動起來就歸零。 */
    private var stoppedSince: Long? = null

    private data class Held(val bearing: Double, val origin: LatLon)

    fun add(point: TrackPoint) {
        // 順序有意義：這兩個都要看「上一個點」，所以必須在 addLast 之前跑。
        updateHeldBearing(point)
        updateStopped(point)

        points.addLast(point)
        val cutoff = point.epochMillis - windowMillis
        while (points.isNotEmpty() && points.first().epochMillis < cutoff) {
            points.removeFirst()
        }
    }

    fun latest(): TrackPoint? = points.lastOrNull()

    fun size(): Int = points.size

    fun clear() {
        points.clear()
        held = null
        stoppedSince = null
    }

    /**
     * 停止前最後一個可信的行進方位角，找不到則為 null。
     *
     * 「可信」要求記錄當下的速度高於 [MIN_TRUSTWORTHY_SPEED_KMH]：低於此速度時
     * GPS 的方位角由極短位移推得，雜訊大到不能用來決定一條規則掛在哪個方向上。
     */
    fun approachBearing(): Double? = held?.bearing

    /**
     * 騎士是否已經真的停下來（低於 [STOPPED_SPEED_KMH] 持續 [STOPPED_MIN_MILLIS]）。
     *
     * 回報按鈕只在這個條件成立時出現。用「持續一段時間」而不是單一個點的速度，
     * 是因為單點會在路口前抖成一閃一閃的按鈕；而用「停止」而不是「慢」，
     * 是因為時速 9 公里仍在滑行的騎士，他的方位角可能正記錄在轉彎的半途中 ——
     * 那會把規則掛到一個不存在的來向上。
     */
    fun isStopped(): Boolean {
        val since = stoppedSince ?: return false
        val now = points.lastOrNull()?.epochMillis ?: return false
        return now - since >= STOPPED_MIN_MILLIS
    }

    private fun updateHeldBearing(point: TrackPoint) {
        val gap = points.lastOrNull()?.let { point.epochMillis - it.epochMillis }
        if (gap != null && gap > MAX_GAP_MILLIS) {
            held = null
        }

        val bearing = point.bearing
        if (bearing != null && point.speedKmh >= MIN_TRUSTWORTHY_SPEED_KMH) {
            held = Held(bearing, point.location)
            return
        }

        held?.let {
            if (haversineMeters(it.origin, point.location) > MAX_DRIFT_METERS) held = null
        }
    }

    private fun updateStopped(point: TrackPoint) {
        stoppedSince = if (point.speedKmh <= STOPPED_SPEED_KMH) {
            stoppedSince ?: point.epochMillis
        } else {
            null
        }
    }

    companion object {
        /**
         * 軌跡點的保留長度。這條窗管的是**點**不是方位角 —— 它的用途是日後上傳
         * 路口片段（進入路口前約 10 秒到離開為止），所以要比那段需求略長。
         */
        const val DEFAULT_WINDOW_MILLIS = 30_000L

        /** 低於此速度的方位角視為雜訊。 */
        const val MIN_TRUSTWORTHY_SPEED_KMH = 8.0

        /**
         * 記下方位角之後容許的位移。超過就當作已經不是同一個路口的停等。
         *
         * 抓 50 公尺的理由：最後一個可信方位角到停止線之間通常不到 20 公尺，
         * 再加上都市峽谷的 GPS 誤差仍然在這個數字以內；而牽車或走路離開一定超過。
         */
        const val MAX_DRIFT_METERS = 50.0

        /** 定位中斷超過這麼久，先前的方位角就不再可信。 */
        const val MAX_GAP_MILLIS = 15_000L

        /** 視為靜止的速度上限。 */
        const val STOPPED_SPEED_KMH = 3.0

        /** 要連續靜止這麼久才算真的停下來。 */
        const val STOPPED_MIN_MILLIS = 3_000L
    }
}
