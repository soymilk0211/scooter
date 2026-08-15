package tw.scooter.ride

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tw.scooter.rules.AlertCandidate
import tw.scooter.rules.LatLon
import tw.scooter.rules.RiderState
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

    /** 當前應播報的警示。語音尚未接上，UI 先直接顯示它以便驗證。 */
    private val _alert = MutableStateFlow<AlertCandidate?>(null)
    val alert: StateFlow<AlertCandidate?> = _alert.asStateFlow()

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
            // 服務停了就沒人在檢查語音了，先前的結論隨即過期。留著它會讓騎士
            // 看到一則沒有東西在維護的警告，或更糟 —— 一則早就不成立的安心。
            _voiceStatus.value = VoiceStatus.CHECKING
        }
    }
}
