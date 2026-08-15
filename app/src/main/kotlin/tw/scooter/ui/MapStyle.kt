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
 * 屬名（attribution）是條款要求，不能關 —— `MapCanvas` 裡的
 * `isAttributionEnabled` 保持開啟。
 */
internal object MapStyle {

    /** 深色模式的底色。與 [tw.scooter.ui.theme.ScooterColors.Ink] 一致，圖磚載入前不會閃色。 */
    private const val DARK_GROUND = "#000000"

    /** 淺色模式的底色。 */
    private const val LIGHT_GROUND = "#FFFFFF"

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
    }
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
        "raster-contrast": ${if (dark) "0.06" else "0.02"}
      }
    }
  ]
}
"""

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
