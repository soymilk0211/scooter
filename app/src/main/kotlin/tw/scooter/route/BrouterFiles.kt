package tw.scooter.route

import android.content.Context
import java.io.File

/**
 * BRouter 在磁碟上要看到的東西。
 *
 * 它的 API 吃的是**檔案路徑**（`RoutingContext.localFunction` 是一個 .brf 的
 * 路徑，圖磚是一個目錄），而 Android 的 assets 不是檔案系統上的路徑 ——
 * 所以 profile 與 lookups 必須先複製出來。這與 [tw.scooter.data.SeedInstaller]
 * 是同一個理由、同一個做法。
 *
 * 目錄結構照 BRouter 自己的慣例（`profiles2/`、`segments4/`），不是自己發明的：
 * 哪天要拿 BRouter App 的圖磚來對照、或照它的文件除錯，路徑一樣才對得起來。
 */
object BrouterFiles {

    private const val ASSET_DIR = "brouter"
    const val PROFILE_NAME = "scooter-tw.brf"

    /** 標籤字典。**必須與 jar 同版本** —— 版本對不上的症狀是 profile 解析出錯。 */
    private const val LOOKUPS_NAME = "lookups.dat"

    fun profileDir(context: Context) = File(context.filesDir, "brouter/profiles2")

    /** 圖磚目錄。BRouter 直接吃這個目錄，檔名必須是 `E120_N25.rd5` 這種格式。 */
    fun segmentDir(context: Context) = File(context.filesDir, "brouter/segments4")

    fun profile(context: Context) = File(profileDir(context), PROFILE_NAME)

    /**
     * 把 profile 與 lookups 複製到可寫位置。已存在且大小相同就不動。
     *
     * 不像種子資料庫那樣「存在就跳過」—— 這兩個檔沒有使用者資料，
     * App 升級時本來就該換成新版。用大小比對而不是版本號，是因為
     * 它們本來就沒有版本號可讀，而任何內容改動都會改變大小。
     */
    fun ensureInstalled(context: Context): Result<File> = runCatching {
        val dir = profileDir(context).apply { mkdirs() }
        segmentDir(context).mkdirs()

        for (name in listOf(PROFILE_NAME, LOOKUPS_NAME)) {
            val target = File(dir, name)
            val expected = context.assets.openFd("$ASSET_DIR/$name").use { it.length }
            if (target.exists() && target.length() == expected) continue

            val temp = File(dir, "$name.tmp")
            context.assets.open("$ASSET_DIR/$name").use { input ->
                temp.outputStream().use(input::copyTo)
            }
            // 先寫暫存再改名：複製到一半被中斷時，不會留下一個看似完整的壞檔。
            check(temp.renameTo(target)) { "failed to move $name into place" }
        }
        profile(context)
    }
}
