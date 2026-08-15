package tw.scooter.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val TAG = "MapCanvas"

/**
 * MapLibre 的 MapView 必須收到完整的 onCreate → onStart → onResume 才會啟動算圖器。
 * 少了 onResume 不會崩潰、不會報錯、logcat 連一行都不會有 —— 畫面只會停在
 * MapLibre 的預設底色。這個坑編譯期完全看不出來，只有真的把 App 跑起來才會發現。
 */
@Composable
fun MapCanvas(
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    startLat: Double = 25.0330,
    startLon: Double = 121.5654,
    zoom: Double = 15.0,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { ready ->
                // 樣式不在這裡設。它會隨外觀模式改變，交給下面的 LaunchedEffect
                // 統一負責，才不會有兩個地方各自設一次而互相蓋掉。
                map = ready
                ready.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(startLat, startLon))
                    .zoom(zoom)
                    .build()
                // 屬名是圖磚來源的授權條款要求，不能關。
                ready.uiSettings.isAttributionEnabled = true
                ready.uiSettings.isLogoEnabled = false
                // 騎乘中不需要轉動地圖，關掉可避免誤觸把方向轉歪。
                ready.uiSettings.isRotateGesturesEnabled = false
                ready.uiSettings.isTiltGesturesEnabled = false
                // 拖曳平移、滾輪與雙指縮放、雙擊放大 —— 桌面上滑鼠滾輪會對應到縮放。
                ready.uiSettings.isScrollGesturesEnabled = true
                ready.uiSettings.isZoomGesturesEnabled = true
                ready.uiSettings.isDoubleTapGesturesEnabled = true
                ready.uiSettings.isQuickZoomGesturesEnabled = true
            }
        }
    }

    // 外觀一改就換一次皮。深淺兩版的路網幾何完全相同，所以換皮時地圖不會跳動，
    // 只有顏色淡入 —— 樣式裡的 raster-fade-duration 就是為了這 180 毫秒。
    LaunchedEffect(map, dark) {
        val target = map ?: return@LaunchedEffect
        target.setStyle(Style.Builder().fromJson(MapStyle.json(dark))) { style ->
            Log.i(TAG, "style loaded dark=$dark layers=${style.layers.size}")
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        // 補上已經錯過的事件：這個 Composable 掛載時 Activity 通常已經 RESUMED，
        // 那些生命週期事件不會再發一次。
        val state = lifecycleOwner.lifecycle.currentState
        if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
