package tw.scooter.route

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "SegmentDownloader"

/**
 * 下載 BRouter 的路網圖磚。
 *
 * ## 為什麼不隨 APK 出貨
 *
 * 台灣兩塊共約 33 MB，塞進 APK 技術上沒問題。**但那些圖磚是每週重建的**，
 * 而我們是自己發 APK、沒有自動更新（ADR-0015）—— 塞進去等於把路網更新綁在
 * 「使用者想到要來下載新版 App」上面，而那可能是永遠。
 *
 * 分開下載還有一個好處：規則資料與路網走不同通道，規則錯誤能當天修，
 * 不必等使用者重裝。這與 ADR-0008 對離線包與規則更新的分離是同一個道理。
 *
 * ## 進度與中斷
 *
 * 支援 HTTP Range 續傳。33 MB 在手機網路上不是一瞬間的事，而使用者會切走、
 * 會進電梯 —— 從頭再來一次的下載，第三次就會被放棄。
 */
object SegmentDownloader {

    private const val BASE_URL = "https://brouter.de/brouter/segments4/"

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
        return REQUIRED.all { name -> download(dir, name, onProgress) }
    }

    private fun download(dir: File, name: String, onProgress: (Progress) -> Unit): Boolean {
        val target = File(dir, name)
        if (target.length() > 0) return true

        // 下載到暫存檔再改名。半個 rd5 檔會讓 BRouter 讀到一半炸掉，
        // 而那看起來像「這個地區的資料壞了」，不像「下載沒完成」。
        val temp = File(dir, "$name.part")
        return runCatching {
            val connection = (URL(BASE_URL + name).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                if (temp.length() > 0) setRequestProperty("Range", "bytes=${temp.length()}-")
            }
            connection.use { conn ->
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
