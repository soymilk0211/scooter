package tw.scooter.ui

import android.content.Context
import android.graphics.Point
import android.util.Log
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
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
        WheelSafeMapView(context).apply {
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
                // 拖曳平移、雙指縮放、雙擊放大。滑鼠滾輪不在這幾個開關的管轄內，
                // 見 [WheelSafeMapView] 與 [wheelZoom]。
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

    AndroidView(factory = { mapView }, modifier = modifier.wheelZoom(map))
}

/**
 * 只改一件事：把滑鼠滾輪擋在**觸控**通道外。
 *
 * MapLibre 自己會處理滾輪（`MapView.onGenericMotionEvent` 依 `AXIS_VSCROLL` 縮放），
 * 但那個方法在 Compose 裡永遠不會被呼叫 —— `AndroidComposeView.dispatchGenericMotionEvent`
 * 攔下 `ACTION_SCROLL` 走自己的 pointer input，不再往子 View 分發。真正到得了 MapView 的，
 * 是 `AndroidView` 的 interop filter 把同一顆事件**經由 `dispatchTouchEvent`** 送回來的
 * 那一份，於是滾輪被手勢辨識讀成拖曳，變成上下平移。
 *
 * 縮放改由 [wheelZoom] 在 Compose 那一層做，這裡把觸控通道裡的滾輪丟掉，
 * 免得同一個動作既縮放又平移。
 */
private class WheelSafeMapView(context: Context) : MapView(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        event.actionMasked != MotionEvent.ACTION_SCROLL && super.dispatchTouchEvent(event)
}

/**
 * 滑鼠滾輪縮放。
 *
 * 只有桌面與模擬器用得到 —— 真機上沒有滾輪。但開發時整天都在用它看圖磚，
 * 而內建的那份收不到事件（見 [WheelSafeMapView]）。
 *
 * 一格滾輪等於一級縮放，與 MapLibre 桌面版的手感一致。
 *
 * **刻意不 consume 這些事件。** consume 會讓 interop filter 轉進 NotDispatching，
 * 而它要等到一次「所有指標都放開」才會復位 —— 也就是滾一次滾輪之後，
 * 下一個觸控手勢會被整個吃掉。平移已經在 [WheelSafeMapView] 擋掉了，這裡不需要再擋一次。
 */
private fun Modifier.wheelZoom(map: MapLibreMap?): Modifier = pointerInput(map) {
    val target = map ?: return@pointerInput
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            // scrollDelta.y 是 Compose 反轉過的 AXIS_VSCROLL：往使用者的方向滾為正，
            // 而那一邊要縮小，所以這裡再翻一次號。
            val notches = -event.changes.sumOf { it.scrollDelta.y.toDouble() }
            if (notches == 0.0) continue
            val focus = event.changes.first().position
            target.moveCamera(
                CameraUpdateFactory.zoomBy(notches, Point(focus.x.toInt(), focus.y.toInt())),
            )
            Log.i(TAG, "wheel zoom by $notches -> ${target.cameraPosition.zoom}")
        }
    }
}
