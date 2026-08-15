package tw.scooter.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * 開發階段的暫用底圖樣式。
 *
 * **不可上線。** OSM 官方圖磚伺服器的使用條款禁止一般應用程式取用，這裡只是為了
 * 在真機／模擬器上驗證算圖管線 —— MapLibre 的 demotiles 只有世界地圖等級的資料
 * （約 zoom 5 以下），在台北的騎乘縮放層級一片空白，看不出算圖到底有沒有動。
 *
 * 正式版需換成自有或商用向量圖磚，並改回深色配色。
 */
private const val DEV_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    { "id": "bg", "type": "background", "paint": { "background-color": "#101512" } },
    { "id": "osm", "type": "raster", "source": "osm" }
  ]
}
"""

private const val TAG = "MapCanvas"

/**
 * MapLibre 的 MapView 必須收到完整的 onCreate → onStart → onResume 才會啟動算圖器。
 * 少了 onResume 不會崩潰、不會報錯、logcat 連一行都不會有 —— 畫面只會停在
 * MapLibre 的預設底色。這個坑編譯期完全看不出來，只有真的把 App 跑起來才會發現。
 */
@Composable
fun MapCanvas(
    modifier: Modifier = Modifier,
    startLat: Double = 25.0330,
    startLon: Double = 121.5654,
    zoom: Double = 15.0,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                map.setStyle(Style.Builder().fromJson(DEV_STYLE_JSON)) { style ->
                    Log.i(TAG, "style loaded, layers=${style.layers.size}")
                }
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(startLat, startLon))
                    .zoom(zoom)
                    .build()
                map.uiSettings.isAttributionEnabled = true
                map.uiSettings.isLogoEnabled = false
                // 騎乘中不需要轉動地圖，關掉可避免誤觸把方向轉歪。
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                // 拖曳平移、滾輪與雙指縮放、雙擊放大 —— 桌面上滑鼠滾輪會對應到縮放。
                map.uiSettings.isScrollGesturesEnabled = true
                map.uiSettings.isZoomGesturesEnabled = true
                map.uiSettings.isDoubleTapGesturesEnabled = true
                map.uiSettings.isQuickZoomGesturesEnabled = true
            }
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
