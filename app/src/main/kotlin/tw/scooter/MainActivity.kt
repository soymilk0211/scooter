package tw.scooter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import tw.scooter.ride.RideService
import tw.scooter.ui.AlertBanner
import tw.scooter.ui.DialPosition
import tw.scooter.ui.MapCanvas
import tw.scooter.ui.SpeedDial
import tw.scooter.ui.MenuButton
import tw.scooter.ui.ReportOutcome
import tw.scooter.ui.RideViewModel
import tw.scooter.ui.SettingsDrawer
import tw.scooter.ui.TopReportBar
import tw.scooter.ui.VoiceWarning
import tw.scooter.ui.theme.ScooterTheme
import tw.scooter.ui.theme.resolvesToDark

/**
 * 圓圈還沒被拖過時的落點：左側、離底部四分之一畫面高。
 *
 * 用畫面尺寸算而不是寫死 dp，是因為它必須跟著版面走 —— 底部中央是警示列，
 * 右側留給之後的導航指示。
 */
private fun defaultDialPosition(canvas: IntSize) = DialPosition(
    x = canvas.width * 0.06f,
    y = canvas.height * 0.72f,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScooterApp() }
    }
}

private val requiredPermissions: Array<String>
    get() = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

@Composable
private fun ScooterApp(viewModel: RideViewModel = viewModel()) {
    val context = LocalContext.current

    // 設定還沒從磁碟讀回來之前，這裡什麼都不畫。視窗底色本來就是黑的
    // （Theme.Scooter 的 windowBackground），所以看起來只是啟動畫面多停一下 ——
    // 而不是先用預設值畫一次深色、讀到偏好後才閃成淺色。
    val settings = viewModel.settings.collectAsState().value ?: return

    val state by viewModel.state.collectAsState()
    val outcome by viewModel.outcome.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            RideService.start(context)
        }
    }

    LaunchedEffect(Unit) {
        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        // 背景定位是第二階段的獨立授權，尚未請求 —— 那需要先說明用途，
        // 否則系統會直接拒絕。待「騎乘前檢查」畫面完成後再一併處理。
        if (hasLocation) RideService.start(context) else permissionLauncher.launch(requiredPermissions)
    }

    LaunchedEffect(outcome) {
        if (outcome != null) {
            delay(2_000)
            viewModel.consumeOutcome()
        }
    }

    // 騎士會切出去調音量或裝語音資料，然後回來看警告有沒有消失。回到前景時重問一次，
    // 否則他修好了卻還看著同一則紅色警告，下次就不會相信它。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) RideService.refreshVoiceStatus(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ScooterTheme(mode = settings.appearance) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // 整個畫面底下就是一張可以拖的地圖，而抽屜的開啟手勢吃掉了左緣往右拖 ——
            // 騎士想把地圖往右推，拉出來的是設定。開著的時候仍然可以滑掉，
            // 那個手勢沒有東西跟它搶。
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet {
                    SettingsDrawer(
                        duckingEnabled = settings.duckOthers,
                        appearance = settings.appearance,
                        onDuckingChanged = viewModel::onDuckingChanged,
                        onAppearanceChanged = viewModel::onAppearanceChanged,
                        onSync = { /* 同步實作待後端 diff 端點就緒 */ },
                    )
                }
            },
        ) {
            var canvas by remember { mutableStateOf(IntSize.Zero) }
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvas = it },
            ) {
                val prohibitedLines by viewModel.prohibitedLines.collectAsState()
                val route by tw.scooter.ride.RideRepository.route.collectAsState()
                MapCanvas(
                    Modifier.fillMaxSize(),
                    dark = settings.appearance.resolvesToDark(),
                    prohibited = prohibitedLines,
                    route = route?.points.orEmpty(),
                    onDestinationPicked = viewModel::onDestinationPicked,
                )

                // 用 Column 讓選單按鈕自然排在回報列下方。先前用固定位移量去猜
                // 回報列的高度，結果兩者疊在一起 —— 版面高度該由版面決定，不該手算。
                // 狀態列的高度在這裡讓掉一次就好。子元件各自也呼叫
                // statusBarsPadding()，consumeWindowInsets 讓那些呼叫變成無作用 ——
                // 否則多疊一個警告列，下面所有東西就會被推開兩倍的狀態列高度。
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .consumeWindowInsets(WindowInsets.statusBars),
                ) {
                    // 語音警告排在最上面，連懸浮視窗模式也照顯示 —— 它說的不是
                    // 路口的事，而是這台裝置接下來都不會出聲。
                    if (state.showVoiceWarning) {
                        VoiceWarning(state.voiceStatus, viewModel::onVoiceRemedy)
                    }

                    // 只有騎士真的停下來時才畫回報列 —— 騎乘中連按鈕都不該出現，
                    // 變灰的按鈕仍然邀請人去按。懸浮視窗模式下一樣隱藏，
                    // 避免與警示視窗重複資訊。
                    if (!state.inOverlayMode && state.reportUnlocked) {
                        TopReportBar(
                            entryRoad = state.entryRoad,
                            exitRoad = state.exitRoad,
                            onReport = viewModel::onReport,
                        )
                    }
                    MenuButton(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier
                            .then(if (state.inOverlayMode) Modifier.statusBarsPadding() else Modifier)
                            .padding(start = 12.dp, top = 4.dp),
                    )
                }

                // 時速圓圈。第一次出現在左下角上方一點的位置 —— 右邊留給
                // 導航指示（路線圖第 5 項），而底部中央是警示列的位置。
                // 拖過之後就聽騎士的，那個位置存在設定裡。
                val dial by viewModel.dialPosition.collectAsState()
                if (canvas != IntSize.Zero) {
                    SpeedDial(
                        speedKmh = state.speedKmh,
                        speedLimitKmh = state.speedLimitKmh,
                        position = if (dial.isSet) dial else defaultDialPosition(canvas),
                        onPositionChanged = viewModel::onDialMoved,
                        onPositionSettled = viewModel::onDialSettled,
                        bounds = canvas,
                    )
                }

                state.alert?.let { alert ->
                    AlertBanner(alert, Modifier.align(Alignment.BottomCenter))
                }

                outcome?.let { result ->
                    Snackbar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(16.dp),
                    ) {
                        Text(
                            stringResource(
                                when (result) {
                                    ReportOutcome.SAVED -> R.string.report_saved
                                    ReportOutcome.NO_BEARING -> R.string.report_no_bearing
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}
