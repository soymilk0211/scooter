package tw.scooter.route

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "SegmentDownloader"

/**
 * 下載路網圖磚。**現在抓的是我們自己建的**（ADR-0017），不是 BRouter 官方的。
 *
 * ## 為什麼不隨 APK 出貨
 *
 * 台灣兩塊共約 20 MB，塞進 APK 技術上沒問題。**但圖磚會重建**（我們一季一次，
 * 決策檔案 D9），而我們是自己發 APK、沒有自動更新（ADR-0015）—— 塞進去等於把
 * 路網更新綁在「使用者想到要來下載新版 App」上面，而那可能是永遠。
 *
 * 自建之後這件事更重要了：圖磚裡烘著用路人回報的禁止左轉，所以「多久換一次
 * 圖磚」直接決定「一筆回報多久之後才影響得了別人的路線」。
 *
 * 分開下載還有一個好處：規則資料與路網走不同通道，規則錯誤能當天修，
 * 不必等使用者重裝。這與 ADR-0008 對離線包與規則更新的分離是同一個道理。
 *
 * ## 進度與中斷
 *
 * 支援 HTTP Range 續傳。20 MB 在手機網路上不是一瞬間的事，而使用者會切走、
 * 會進電梯 —— 從頭再來一次的下載，第三次就會被放棄。
 */
object SegmentDownloader {

    /**
     * 我們自己建的圖磚（[ADR-0017](../../../../../../docs/adr/0017-build-our-own-routing-tiles.md)），
     * 裡面烘了用路人回報的禁止左轉。放 GitHub Releases 是決策檔案 D8 的結論：
     * 零新帳號、零付款方式、有 CDN，而且我們本來就在推這個 repo。
     *
     * **網址帶著版號而不是 `latest`。** `latest` 會讓「使用者裝置上是哪一版圖磚」
     * 這個問題答不出來 —— 而排查「為什麼他被導去那條路」時，那正是第一個要問的。
     */
    private const val BASE_URL =
        "https://github.com/soymilk0211/scooter/releases/download/tiles-2026.08/"

    /**
     * 退路：BRouter 官方圖磚。
     *
     * **這條退路值得留著，而它的代價是明確的**：官方圖磚沒有我們注入的規則，
     * 所以回報影響不了路線 —— 那正好是自建圖磚之前的狀態，不是壞掉。
     * 相對地，「一塊圖磚都下載不到」的狀態是連路線都算不出來。
     *
     * 官方圖磚的 `lookups.dat` 比我們的少一個標籤，但我們是**附加在最後**的，
     * 前面每個標籤的索引都沒動，所以官方圖磚照樣解得開（見 pipeline/tiles/README.md）。
     */
    private const val FALLBACK_URL = "https://brouter.de/brouter/segments4/"

    /**
     * 台灣需要的兩塊。檔名是 5x5 度網格的西南角。
     *
     * `E120_N20` 涵蓋北緯 20–25（本島大部分），`E120_N25` 涵蓋 25–30（台北）。
     * 台北市在 25.03°N，**剛好落在分界的北邊**，所以只下載一塊會讓台北到
     * 桃園以南算不出路線 —— 而那個症狀是「有些目的地就是找不到路」，
     * 看起來像資料壞掉，不像少下載了一個檔。
     */
    val REQUIRED = listOf("E120_N20.rd5", "E120_N25.rd5")

    data class Progress(val name: String, val bytes: Long, val total: Long)

    fun isInstalled(context: Context): Boolean {
        val dir = BrouterFiles.segmentDir(context)
        return REQUIRED.all { File(dir, it).length() > 0 }
    }

    /**
     * 下載缺少的圖磚。**呼叫端負責放在 IO 執行緒上。**
     *
     * 回傳成功與否；失敗不丟例外，因為呼叫端能做的事只有「稍後再試」，
     * 而把一個網路問題包成例外會誘使人在畫面上顯示堆疊。
     */
    fun downloadMissing(context: Context, onProgress: (Progress) -> Unit = {}): Boolean {
        val dir = BrouterFiles.segmentDir(context).apply { mkdirs() }
        if (REQUIRED.all { download(dir, it, BASE_URL, onProgress) }) return true

        // 自建圖磚抓不到就退回官方，**但要整組一起退**。一塊自建、一塊官方會讓
        // 路線在 N25 分界處換一套規則，而那個症狀（「有時候會繞過那個禁止左轉，
        // 有時候不會」）比純官方難查得多 —— 所以先把這一輪抓到的通通丟掉。
        Log.w(TAG, "falling back to upstream tiles")
        for (name in REQUIRED) {
            File(dir, name).delete()
            File(dir, "$name.part").delete()
        }
        return REQUIRED.all { download(dir, it, FALLBACK_URL, onProgress) }
    }

    private fun download(
        dir: File,
        name: String,
        baseUrl: String,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        val target = File(dir, name)
        if (target.length() > 0) return true

        // 下載到暫存檔再改名。半個 rd5 檔會讓 BRouter 讀到一半炸掉，
        // 而那看起來像「這個地區的資料壞了」，不像「下載沒完成」。
        val temp = File(dir, "$name.part")
        return runCatching {
            val connection = (URL(baseUrl + name).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                // GitHub Releases 的下載網址會轉去物件儲存，預設就跟著跳。
                instanceFollowRedirects = true
                if (temp.length() > 0) setRequestProperty("Range", "bytes=${temp.length()}-")
            }
            connection.use { conn ->
                // 4xx／5xx 一樣要當失敗，否則會把一頁 HTML 錯誤訊息存成 rd5 ——
                // 而那個症狀是「路線算不出來」，不是「下載失敗」。
                check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode} for $name" }
                val resuming = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                if (!resuming && temp.exists()) temp.delete()
                val already = if (resuming) temp.length() else 0L
                val total = already + conn.contentLengthLong.coerceAtLeast(0L)

                conn.inputStream.use { input ->
                    java.io.FileOutputStream(temp, resuming).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = already
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(Progress(name, written, total))
                        }
                    }
                }
            }
            check(temp.renameTo(target)) { "failed to move $name into place" }
            Log.i(TAG, "downloaded $name (${target.length()} bytes)")
            true
        }.getOrElse {
            Log.w(TAG, "download failed for $name", it)
            false
        }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }
}
