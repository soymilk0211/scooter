package tw.scooter.ride

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tw.scooter.rules.AlertCandidate
import tw.scooter.rules.EnforcementCandidate
import tw.scooter.rules.LatLon
import tw.scooter.rules.ProhibitedCandidate
import tw.scooter.rules.RiderState
import tw.scooter.rules.Route
import tw.scooter.rules.RouteFollower
import tw.scooter.rules.RouteProgress
import tw.scooter.rules.TrackBuffer
import tw.scooter.rules.TrackPoint
import java.time.Instant
import java.time.ZoneId

/**
 * 定位資料的單一來源。
 *
 * 前景服務寫入、UI 讀取。做成 object 是因為服務與 Activity 的生命週期各自獨立 ——
 * 騎士切到 Google Maps 時 Activity 會被銷毀，但軌跡不能斷。
 */
object RideRepository {

    private val buffer = TrackBuffer()

    private val _state = MutableStateFlow<RiderState?>(null)
    val state: StateFlow<RiderState?> = _state.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    /**
     * 現在能不能回報。回報介面只在這個條件成立時出現。
     *
     * 兩個條件缺一不可：**真的停下來了**，而且**取得到進入方位角**。
     * 少了後者按鈕會出現然後必定失敗 —— 那比按鈕不出現更糟，因為騎士會以為
     * 是自己按錯了，在一個他只有幾秒的空檔裡反覆試。
     *
     * 兩者都判在 [TrackBuffer]（要看連續幾個點，不是單一個速度值），
     * 這裡只是把結論攤成 UI 讀得到的流。
     */
    private val _canReport = MutableStateFlow(false)
    val canReport: StateFlow<Boolean> = _canReport.asStateFlow()

    private fun refreshCanReport() {
        _canReport.value = buffer.isStopped() && buffer.approachBearing() != null
    }

    /** 當前應播報的警示。語音尚未接上，UI 先直接顯示它以便驗證。 */
    private val _alert = MutableStateFlow<AlertCandidate?>(null)
    val alert: StateFlow<AlertCandidate?> = _alert.asStateFlow()

    /** 最近一次的測速警示，供畫面顯示。 */
    private val _enforcement = MutableStateFlow<EnforcementCandidate?>(null)
    val enforcement: StateFlow<EnforcementCandidate?> = _enforcement.asStateFlow()

    /**
     * 騎士正走在一段全面禁行機車的路上。
     *
     * 與其他警示不同，這一個是**狀態**：離開那條路才該消失，所以它不會自己
     * 過期，由 [onProhibited] 傳 null 清掉。
     */
    private val _prohibited = MutableStateFlow<ProhibitedCandidate?>(null)
    val prohibited: StateFlow<ProhibitedCandidate?> = _prohibited.asStateFlow()

    fun onProhibited(candidate: ProhibitedCandidate?) {
        _prohibited.value = candidate
    }

    /** 當前導航路線。null 代表沒有在導航。 */
    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()

    /** 在路線上的進度。沒有路線、或還沒收到定位時為 null。 */
    private val _progress = MutableStateFlow<RouteProgress?>(null)
    val progress: StateFlow<RouteProgress?> = _progress.asStateFlow()

    /**
     * 跟隨器與路線是一組的，換路線就要換一個新的 —— 它記著游標，
     * 沿用舊的會讓新路線的進度從舊路線的位置開始算。
     */
    private var follower: RouteFollower? = null

    fun onRoute(newRoute: Route?) {
        _route.value = newRoute
        follower = newRoute?.let { RouteFollower(it) }
        _progress.value = null
    }

    /**
     * 時速圓圈用來上色的速限。null 代表這一帶沒有速限資料 —— 圓圈那時只顯示
     * 速度、不評價。
     */
    private val _speedLimit = MutableStateFlow<Int?>(null)
    val speedLimit: StateFlow<Int?> = _speedLimit.asStateFlow()

    fun onEnforcement(candidate: EnforcementCandidate) {
        _enforcement.value = candidate
    }

    fun onSpeedLimit(limitKmh: Int?) {
        _speedLimit.value = limitKmh
    }

    /**
     * 語音警示的可用狀態。這是本 App 唯一一個「壞掉時看不出來」的環節 ——
     * 其餘失效（沒定位、服務沒跑）畫面上都有跡象，只有聽不到沒有。
     */
    private val _voiceStatus = MutableStateFlow(VoiceStatus.CHECKING)
    val voiceStatus: StateFlow<VoiceStatus> = _voiceStatus.asStateFlow()

    /** 設定頁的「背景音量衰減」。放在這裡是因為服務與 UI 各自存活，
     *  誰都不該持有對方。 */
    private val _duckOthers = MutableStateFlow(true)
    val duckOthers: StateFlow<Boolean> = _duckOthers.asStateFlow()

    fun setDuckOthers(enabled: Boolean) {
        _duckOthers.value = enabled
    }

    fun onVoiceStatus(status: VoiceStatus) {
        _voiceStatus.value = status
    }

    fun onAlert(candidate: AlertCandidate?) {
        if (candidate != null) _alert.value = candidate
    }

    fun clearAlert() {
        _alert.value = null
    }

    /**
     * 直接注入一個位置，繞過 GPS。僅供軌跡回放測試使用。
     *
     * 真機上要驗證警示得先騎到那個路口，模擬器又給不出可信的速度與方位角，
     * 所以測試路徑必須能跳過定位硬體。
     */
    fun injectForReplay(state: RiderState) {
        buffer.add(
            TrackPoint(
                location = state.location,
                bearing = state.bearing,
                speedKmh = state.speedKmh,
                epochMillis = state.epochMillis,
            ),
        )
        refreshCanReport()
        _state.value = state
    }

    fun onLocation(location: Location, zone: ZoneId = ZoneId.systemDefault()) {
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        val bearing = if (location.hasBearing()) location.bearing.toDouble() else null
        val at = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()

        buffer.add(
            TrackPoint(
                location = LatLon(location.latitude, location.longitude),
                bearing = bearing,
                speedKmh = speedKmh,
                epochMillis = at,
            ),
        )
        refreshCanReport()
        follower?.let { _progress.value = it.update(LatLon(location.latitude, location.longitude)) }

        val local = Instant.ofEpochMilli(at).atZone(zone)
        _state.value = RiderState(
            location = LatLon(location.latitude, location.longitude),
            bearing = bearing,
            speedKmh = speedKmh,
            epochMillis = at,
            dayOfWeek = local.dayOfWeek.value,
            minuteOfDay = local.hour * 60 + local.minute,
        )
    }

    /**
     * 回報時使用的進入方位角。
     *
     * 刻意不回退到當下的 [RiderState.bearing]：騎士靜止時那個值不存在或是雜訊，
     * 拿它建立規則會把規則掛到錯的來向上。寧可讓回報失敗。
     */
    fun approachBearingForReport(): Double? = buffer.approachBearing()

    fun onServiceStateChanged(running: Boolean) {
        _serviceRunning.value = running
        if (!running) {
            buffer.clear()
            _canReport.value = false
            // 服務停了就不是在導航了。留著路線會讓畫面上有一條沒有人在跟隨的線。
            onRoute(null)
            // 服務停了就沒人在檢查語音了，先前的結論隨即過期。留著它會讓騎士
            // 看到一則沒有東西在維護的警告，或更糟 —— 一則早就不成立的安心。
            _voiceStatus.value = VoiceStatus.CHECKING
        }
    }
}
