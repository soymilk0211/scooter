package tw.scooter.data

import android.content.Context
import java.io.File

/**
 * 首次啟動時把 App 內附的種子資料庫複製到可寫入位置。
 *
 * 存在的理由是 ADR-0003 的初衷：山區離線。一台在山上全新安裝、完全沒有訊號的
 * 裝置，如果資料庫是空的，每個路口都會回傳「查無資料」—— 那正是最需要它的時候。
 * 因此種子資料隨 APK 出貨，同步只負責從種子版本往後遞增修補。
 */
object SeedInstaller {

    const val ASSET_NAME = "scooter_seed.db"

    /**
     * 確保資料庫檔案存在。已存在則不動 —— 使用者本機的 observations
     * 可能還沒上傳，覆寫會直接丟掉他們的回報。
     */
    fun ensureInstalled(context: Context): Result<File> = runCatching {
        val target = context.getDatabasePath(Schema.DATABASE_NAME)
        if (target.exists()) return@runCatching target

        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${Schema.DATABASE_NAME}.tmp")

        context.assets.open(ASSET_NAME).use { input ->
            temp.outputStream().use(input::copyTo)
        }
        // 先寫暫存檔再改名：複製到一半被中斷時，不會留下一個看似完整的壞資料庫。
        check(temp.renameTo(target)) { "failed to move seed database into place" }
        target
    }
}
