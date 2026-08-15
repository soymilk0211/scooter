package tw.scooter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
) {
    /** 回報按鈕僅在近乎靜止時解鎖 —— 門檻定義於 core-rules，UI 不自行設定。 */
    val reportUnlocked: Boolean
        get() = speedKmh <= AlertThresholds.REPORT_UNLOCK_MAX_SPEED_KMH
}

/** 回報結果，供 UI 顯示短暫提示後清除。 */
enum class ReportOutcome { SAVED, NO_BEARING }

class RideViewModel(app: Application) : AndroidViewModel(app) {

    private val database by lazy { ScooterDatabase.open(getApplication()) }

    private val overlayMode = MutableStateFlow(false)
    private val maneuver = MutableStateFlow<Pair<String?, String?>>(null to null)

    private val _outcome = MutableStateFlow<ReportOutcome?>(null)
    val outcome: StateFlow<ReportOutcome?> = _outcome.asStateFlow()

    val state: StateFlow<RideUiState> = combine(
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RideUiState())

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
