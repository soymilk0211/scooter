package tw.scooter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.scooter.data.Schema
import tw.scooter.data.ScooterDatabase
import tw.scooter.ride.RideRepository
import tw.scooter.settings.Settings
import tw.scooter.settings.SettingsStore
import tw.scooter.ui.theme.AppearanceMode
import tw.scooter.ride.VoiceRemedy
import tw.scooter.ride.VoiceStatus
import tw.scooter.ride.performRemedy
import tw.scooter.route.ScooterRouter
import tw.scooter.rules.AlertCandidate
import tw.scooter.rules.LatLon
import tw.scooter.rules.TurnRule

data class RideUiState(
    val speedKmh: Double = 0.0,
    val entryRoad: String? = null,
    val exitRoad: String? = null,
    val tracking: Boolean = false,
    /** 當前警示。語音尚未接上，先以畫面顯示驗證整條路徑。 */
    val alert: AlertCandidate? = null,
    /** 懸浮視窗模式下，頂部回報列必須隱藏。 */
    val inOverlayMode: Boolean = false,
    /** 語音警示的可用狀態。 */
    val voiceStatus: VoiceStatus = VoiceStatus.CHECKING,
    /** 騎士已對**當前這個**狀態按過「知道了」。狀態一變就重新顯示。 */
    val voiceWarningDismissed: Boolean = false,
    /** 時速圓圈用來上色的速限。null 代表這一帶沒有速限資料，圓圈就不評價。 */
    val speedLimitKmh: Int? = null,
    /**
     * 回報介面是否該出現：**已連續靜止，而且取得到進入方位角**。
     *
     * 條件是真的停下來，不是「速度夠慢」。舊版用時速 10 公里當門檻把按鈕變灰，
     * 有兩個問題：變灰的按鈕仍然邀請人去按；而時速 9 公里還在滑行的騎士，
     * 他當下記到的方位角可能正在轉彎的半途，那會把規則掛到一個不存在的來向上。
     *
     * 判定在 core-rules 的 `TrackBuffer`，UI 不自行設定。
     */
    val reportUnlocked: Boolean = false,
) {

    val showVoiceWarning: Boolean
        get() = voiceStatus.needsWarning && !voiceWarningDismissed
}

/** 回報結果，供 UI 顯示短暫提示後清除。 */
enum class ReportOutcome { SAVED, NO_BEARING }

class RideViewModel(app: Application) : AndroidViewModel(app) {

    private val database by lazy { ScooterDatabase.open(getApplication()) }

    /**
     * 地圖上要畫的禁行路段折線。
     *
     * 只讀一次就夠 —— 這份資料只會隨種子庫或同步更新，而那兩件事都伴隨重啟。
     * 讀在 IO 執行緒上：第一次觸碰資料庫會安裝種子檔（複製檔案），
     * 放在主執行緒上是 ANR。
     */
    private val _prohibitedLines = MutableStateFlow<List<List<tw.scooter.rules.LatLon>>>(emptyList())
    val prohibitedLines: StateFlow<List<List<tw.scooter.rules.LatLon>>> = _prohibitedLines.asStateFlow()

    init {
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) {
                runCatching { database.allProhibited().map { it.polyline } }.getOrDefault(emptyList())
            }
            _prohibitedLines.value = lines
        }
    }

    private val router by lazy { ScooterRouter(getApplication()) }

    /** 正在算路線。畫面用它顯示「計算中」，避免使用者以為長按沒反應。 */
    private val _routing = MutableStateFlow(false)
    val routing: StateFlow<Boolean> = _routing.asStateFlow()

    /**
     * 設定目的地並算一條路線。
     *
     * 起點用騎士**當下的位置**，不讓使用者選 —— 導航的起點就是他人在的地方，
     * 給一個可選的起點只會讓人選錯。沒有定位就算不了，這時什麼都不做，
     * 因為畫面上本來就會顯示沒有定位。
     */
    fun onDestinationPicked(destination: LatLon) {
        val here = RideRepository.state.value?.location ?: return
        _routing.value = true
        viewModelScope.launch {
            // 台北市內實測約 280 毫秒、台北到台中約 2.7 秒 —— 一定要離開主執行緒。
            val route = withContext(Dispatchers.IO) { router.route(here, destination) }
            RideRepository.onRoute(route)
            _routing.value = false
        }
    }

    fun clearRoute() = RideRepository.onRoute(null)

    private val overlayMode = MutableStateFlow(false)
    private val maneuver = MutableStateFlow<Pair<String?, String?>>(null to null)

    /**
     * 已被按掉的那個語音狀態。存狀態而不是存一個 Boolean，是為了讓它自己過期：
     * 情況一改變（例如降級變成完全沒聲音），警告就重新出現，不必額外清旗標。
     */
    private val dismissedVoiceStatus = MutableStateFlow<VoiceStatus?>(null)

    private val _outcome = MutableStateFlow<ReportOutcome?>(null)
    val outcome: StateFlow<ReportOutcome?> = _outcome.asStateFlow()

    /**
     * 落地的設定。**還沒從磁碟讀回來時是 null**，畫面在那之前不畫任何東西。
     *
     * 大可先用預設值畫一次再換過去，但那會讓選了淺色的騎士每次冷啟動都看到一閃的
     * 深色。這裡等的是一次本機小檔讀取，通常在第一幀之前就結束了。
     */
    val settings: StateFlow<Settings?> =
        SettingsStore.flow(app).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val ride: Flow<RideUiState> = combine(
        RideRepository.state,
        RideRepository.serviceRunning,
        overlayMode,
        maneuver,
        RideRepository.alert,
    ) { rider, running, overlay, maneuverPair, alert ->
        val (entry, exit) = maneuverPair as Pair<String?, String?>
        RideUiState(
            speedKmh = rider?.speedKmh ?: 0.0,
            entryRoad = entry,
            exitRoad = exit,
            tracking = running,
            inOverlayMode = overlay,
            alert = alert,
        )
    }

    // combine 的具名多載最多五個來源，這裡疊第二層而不是改用 vararg 版本 ——
    // vararg 版會把每個來源退化成 Any?，之後每次讀取都要轉型。
    val state: StateFlow<RideUiState> = combine(
        ride,
        RideRepository.voiceStatus,
        dismissedVoiceStatus,
        RideRepository.speedLimit,
        RideRepository.canReport,
    ) { base, voice, dismissed, limit, canReport ->
        base.copy(
            voiceStatus = voice,
            voiceWarningDismissed = dismissed == voice,
            speedLimitKmh = limit,
            reportUnlocked = canReport,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RideUiState())

    /**
     * 拖曳中的圓圈位置。落地的那份在設定裡，這一份只在手指按著的時候有值 ——
     * 每一幀都寫 DataStore 等於每一幀改寫一次檔案。
     */
    private val draggedDial = MutableStateFlow<DialPosition?>(null)

    val dialPosition: StateFlow<DialPosition> = combine(settings, draggedDial) { saved, dragging ->
        dragging ?: saved?.let { DialPosition(it.dialX, it.dialY) } ?: DialPosition.UNSET
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DialPosition.UNSET)

    fun onDialMoved(position: DialPosition) {
        draggedDial.value = position
    }

    fun onDialSettled(position: DialPosition) {
        draggedDial.value = position
        viewModelScope.launch { SettingsStore.setDialPosition(getApplication(), position.x, position.y) }
    }

    /**
     * 執行騎士按下的補救動作。
     *
     * 「知道了」記在這裡而不是畫面裡，因為它必須在 Activity 被重建後仍然有效 ——
     * 騎士切去系統設定再回來，重看一次同一則警告只會讓他學會忽略它。
     */
    fun onVoiceRemedy(remedy: VoiceRemedy) {
        if (remedy == VoiceRemedy.DISMISS) {
            dismissedVoiceStatus.value = RideRepository.voiceStatus.value
        }
        performRemedy(getApplication(), remedy)
    }

    fun onAppearanceChanged(mode: AppearanceMode) {
        viewModelScope.launch { SettingsStore.setAppearance(getApplication(), mode) }
    }

    /**
     * 背景音量衰減。只寫進設定，不直接改 [RideRepository] ——
     * 服務自己訂閱同一份設定，讓「誰是權威」只有一個答案。
     */
    /** 北方朝上 ⇄ 車頭朝上。落地，因為這是個人偏好不是當下狀態。 */
    fun onOrientationToggled(headingUp: Boolean) {
        viewModelScope.launch { SettingsStore.setHeadingUp(getApplication(), headingUp) }
    }

    fun onDuckingChanged(enabled: Boolean) {
        viewModelScope.launch { SettingsStore.setDuckOthers(getApplication(), enabled) }
    }

    fun onManeuverChanged(entryRoad: String?, exitRoad: String?) {
        maneuver.value = entryRoad to exitRoad
    }

    fun onOverlayModeChanged(inOverlay: Boolean) {
        overlayMode.update { inOverlay }
    }

    /**
     * 記錄一筆主動回報。
     *
     * 進入方位角取自停止前最後一段有效軌跡；取不到就讓回報失敗，
     * 不用當下的雜訊方位角湊數 —— 掛錯來向的規則會在錯的方向播錯的話。
     */
    fun onReport(rule: TurnRule) {
        val rider = RideRepository.state.value
        val bearing = RideRepository.approachBearingForReport()
        if (rider == null || bearing == null) {
            _outcome.value = ReportOutcome.NO_BEARING
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                database.insertObservation(
                    lat = rider.location.lat,
                    lon = rider.location.lon,
                    approachBearing = bearing,
                    exitBearing = null,
                    observedRule = rule,
                    kind = Schema.ObservationKind.REPORT,
                    observedAt = rider.epochMillis,
                )
            }
            _outcome.value = ReportOutcome.SAVED
        }
    }

    fun consumeOutcome() {
        _outcome.value = null
    }
}
