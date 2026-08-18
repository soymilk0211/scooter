package tw.scooter.ui

/**
 * 底圖樣式。
 *
 * ## 為什麼不是「調一下顏色」就好
 *
 * 先前用的是 OSM 官方的**光柵圖磚**（raster）—— 伺服器送來的是已經畫好的 PNG，
 * 顏色烘在像素裡。光柵圖磚**改不了配色**：能做的只有整張圖去飽和、調亮度對比，
 * 那會連道路、水域、綠地一起洗掉，得到的是一張灰濛濛的照片，不是極簡設計。
 *
 * 所以這裡換成 CARTO 的 Positron（淺）與 Dark Matter（深）。它們是同一套設計語言的
 * 兩個版本：只留下路網、水域與地名，POI 圖示全部拿掉，配色壓到近乎無彩度 ——
 * 正是「極簡」在地圖設計裡的既有解答，而且深淺兩版的路網粗細與標籤位置完全一致，
 * 切換模式時地圖不會跳動。
 *
 * ## 授權（沿用先前那條警告，換了來源但性質相同）
 *
 * **仍不算正式方案。** CARTO 的公開 basemap 端點是免費但有使用條款的，商用需要方案；
 * 這裡與先前直連 OSM 圖磚伺服器屬於同一類「開發期借用」。正式上線前要換成自有或
 * 商用向量圖磚 —— 換的時候只要動這個檔案裡的 [source]，其餘不受影響。
 *
 * 向量圖磚才是最終答案：那時配色能真正由我們決定（例如把待轉路口的路網加粗、
 * 把與騎乘無關的圖層整層關掉），而不是接受別人畫好的圖。
 *
 * ## 對比：光柵圖磚能做到的就這麼多
 *
 * 「整張圖都黑黑的、看不出哪裡是路」是 Dark Matter 的既有問題：它的背景約
 * 0.05 亮度、路網約 0.20–0.28，兩者都在暗部，差距在手機螢幕上又被反光吃掉。
 *
 * 光柵圖磚**改不了個別元素的顏色**，唯一的槓桿是 `raster-contrast` ——
 * 它讓亮度往兩端推，背景被壓成真正的黑、路網被拉亮，相對差距因此放大。
 * 所以深色的對比從 0.06 拉到 **0.45**（淺色 0.02 → 0.15，淺色底圖本來就夠分）。
 * 代價是暗部細節整個消失，而那些細節（POI、地形陰影）本來就不該在騎乘畫面上。
 *
 * **這是止血不是解法。** 真正要讓「路」跟「背景」分開，得等向量圖磚 ——
 * 那時可以直接把 road 圖層指定成任何顏色，不必透過整張圖的亮度曲線去繞。
 *
 * 屬名（attribution）是條款要求，不能關 —— `MapCanvas` 裡的
 * `isAttributionEnabled` 保持開啟。
 */
internal object MapStyle {

    /** 深色模式的底色。與 [tw.scooter.ui.theme.ScooterColors.Ink] 一致，圖磚載入前不會閃色。 */
    private const val DARK_GROUND = "#000000"

    /** 淺色模式的底色。 */
    private const val LIGHT_GROUND = "#FFFFFF"

    /**
     * 全面禁行機車的路段。紅色，而且畫在最上層。
     *
     * 這是整張地圖上唯一「你不能走」的東西，配色不跟任何其他元素共用 ——
     * 騎士只會瞄一眼，紅色線條在他認出那是什麼之前就已經傳達了「別過去」。
     */
    private const val PROHIBITED_COLOR = "#FF453A"

    /**
     * 導航路線。**目前沒有東西餵它** —— 路線引擎還沒上機（HANDOVER 路線圖第 5 項）。
     *
     * 圖層與資料來源先建好，是因為「路線用什麼顏色」是個設計決定，
     * 而不是等到有路線那天再隨手挑一個。琥珀色的理由：
     * 它與底圖的灰階完全分離（深淺兩版都是），與禁行的紅色也分得開 ——
     * 一條是「照這裡走」，一條是「別走這裡」，那兩件事在陽光下瞄一眼就要分得出來。
     *
     * 接上的時候只要對 `route` 這個 source 餵 GeoJSON，不必動樣式。
     */
    private const val ROUTE_COLOR = "#FFB020"
    private const val ROUTE_CASING_COLOR = "#000000"

    fun json(dark: Boolean): String = """
{
  "version": 8,
  "name": "Scooter ${if (dark) "Dark" else "Light"}",
  "sources": {
    "basemap": {
      "type": "raster",
      "tiles": [${source(dark)}],
      "tileSize": 256,
      "maxzoom": 20,
      "attribution": "© OpenStreetMap contributors © CARTO"
    },
    "prohibited": { "type": "geojson", "data": $EMPTY_FEATURES },
    "route": { "type": "geojson", "data": $EMPTY_FEATURES }
  },
  "layers": [
    {
      "id": "ground",
      "type": "background",
      "paint": { "background-color": "${if (dark) DARK_GROUND else LIGHT_GROUND}" }
    },
    {
      "id": "basemap",
      "type": "raster",
      "source": "basemap",
      "paint": {
        "raster-opacity": 1,
        "raster-fade-duration": 180,
        "raster-contrast": ${if (dark) "0.45" else "0.15"},
        "raster-saturation": ${if (dark) "-0.15" else "0"}
      }
    },
    {
      "id": "prohibited",
      "type": "line",
      "source": "prohibited",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": {
        "line-color": "$PROHIBITED_COLOR",
        "line-opacity": 0.85,
        "line-width": ["interpolate", ["linear"], ["zoom"], 12, 2, 16, 6, 19, 12]
      }
    },
    {
      "id": "route-casing",
      "type": "line",
      "source": "route",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": {
        "line-color": "$ROUTE_CASING_COLOR",
        "line-opacity": ${if (dark) "0.9" else "0.35"},
        "line-width": ["interpolate", ["linear"], ["zoom"], 12, 6, 16, 14, 19, 26]
      }
    },
    {
      "id": "route",
      "type": "line",
      "source": "route",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": {
        "line-color": "$ROUTE_COLOR",
        "line-width": ["interpolate", ["linear"], ["zoom"], 12, 3, 16, 9, 19, 18]
      }
    }
  ]
}
"""

    /** 空的 GeoJSON。圖層先建好，資料之後餵進來。 */
    private const val EMPTY_FEATURES = """{ "type": "FeatureCollection", "features": [] }"""

    /** 供 `MapCanvas` 更新用的 source 名稱。改名要連樣式一起改，所以放在這裡。 */
    const val SOURCE_PROHIBITED = "prohibited"
    const val SOURCE_ROUTE = "route"

    /**
     * 四個子網域輪流用，這是 CARTO 端點原本就預期的取用方式。
     *
     * 用 `@2x` 的高解析圖磚但 `tileSize` 維持 256 —— 這組合是「同一塊地畫成兩倍像素」，
     * 手機的高 DPI 螢幕上字才不會糊。寫成 512 會讓地圖以為每塊磚涵蓋四倍面積，
     * 縮放層級整個錯位。
     */
    private fun source(dark: Boolean): String {
        val theme = if (dark) "dark_all" else "light_all"
        return listOf("a", "b", "c", "d").joinToString(", ") { sub ->
            "\"https://$sub.basemaps.cartocdn.com/$theme/{z}/{x}/{y}@2x.png\""
        }
    }
}
