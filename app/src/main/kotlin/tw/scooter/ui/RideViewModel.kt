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
import tw.scooter.ride.VoiceRemedy
import tw.scooter.ride.VoiceStatus
import tw.scooter.ride.performRemedy
import tw.scooter.rules.AlertCandidate
import tw.scooter.rules.AlertThresholds
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
) {
    /** 回報按鈕僅在近乎靜止時解鎖 —— 門檻定義於 core-rules，UI 不自行設定。 */
    val reportUnlocked: Boolean
        get() = speedKmh <= AlertThresholds.REPORT_UNLOCK_MAX_SPEED_KMH

    val showVoiceWarning: Boolean
        get() = voiceStatus.needsWarning && !voiceWarningDismissed
}

/** 回報結果，供 UI 顯示短暫提示後清除。 */
enum class ReportOutcome { SAVED, NO_BEARING }

class RideViewModel(app: Application) : AndroidViewModel(app) {

    private val database by lazy { ScooterDatabase.open(getApplication()) }

    private val overlayMode = MutableStateFlow(false)
    private val maneuver = MutableStateFlow<Pair<String?, String?>>(null to null)

    /**
     * 已被按掉的那個語音狀態。存狀態而不是存一個 Boolean，是為了讓它自己過期：
     * 情況一改變（例如降級變成完全沒聲音），警告就重新出現，不必額外清旗標。
     */
    private val dismissedVoiceStatus = MutableStateFlow<VoiceStatus?>(null)

    private val _outcome = MutableStateFlow<ReportOutcome?>(null)
    val outcome: StateFlow<ReportOutcome?> = _outcome.asStateFlow()

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
    ) { base, voice, dismissed ->
        base.copy(voiceStatus = voice, voiceWarningDismissed = dismissed == voice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RideUiState())

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
