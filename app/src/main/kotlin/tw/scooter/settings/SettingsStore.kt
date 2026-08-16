package tw.scooter.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import tw.scooter.ui.theme.AppearanceMode
import java.io.IOException

/**
 * 使用者設定。整包一起讀寫，因為讀的一方（畫面、服務）要的從來不是單一個欄位，
 * 而是「現在該用哪一組設定」。
 */
data class Settings(
    val appearance: AppearanceMode = AppearanceMode.DARK,
    val duckOthers: Boolean = true,
    /**
     * 時速圓圈被拖到哪裡，畫面左上角起算的像素。兩個都是 NaN 代表沒拖過。
     *
     * 存絕對像素而不是比例，是因為這支 App 鎖直向、單一裝置的畫面尺寸不會變。
     * 換手機會落在奇怪的位置，但畫面會把它夾回版面內，拖一下就好。
     */
    val dialX: Float = Float.NaN,
    val dialY: Float = Float.NaN,
)

private val Context.settingsFile: DataStore<Preferences> by preferencesDataStore(name = "settings")

private val APPEARANCE = stringPreferencesKey("appearance")
private val DUCK_OTHERS = booleanPreferencesKey("duck_others")
private val DIAL_X = floatPreferencesKey("dial_x")
private val DIAL_Y = floatPreferencesKey("dial_y")

/**
 * 存下來的偏好轉成 [Settings]。
 *
 * 認不得的外觀字串一律回退到預設值，不丟例外 —— 那代表這支 App 讀到了更新版寫下的
 * 設定（使用者降版），而為了一個外觀偏好讓導航 App 起不來並不划算。
 */
internal fun Preferences.toSettings(): Settings {
    val fallback = Settings()
    return Settings(
        appearance = AppearanceMode.entries.firstOrNull { it.name == this[APPEARANCE] }
            ?: fallback.appearance,
        duckOthers = this[DUCK_OTHERS] ?: fallback.duckOthers,
        dialX = this[DIAL_X] ?: fallback.dialX,
        dialY = this[DIAL_Y] ?: fallback.dialY,
    )
}

/**
 * 設定的落地。
 *
 * 這些值原本寫成 `remember`，App 一重開就回到預設 —— 騎士每次出門都要重設一次外觀。
 * 換成 DataStore 之後，寫入是背景執行緒上的原子操作，讀出來是一條 Flow，
 * 所以「改設定」與「畫面跟著變」仍然是同一條路徑，只是中間多了磁碟。
 */
object SettingsStore {

    fun flow(context: Context): Flow<Settings> = context.applicationContext.settingsFile.data
        .catch { cause ->
            // 檔案毀損時 DataStore 丟 IOException。這裡吞掉退回預設值 ——
            // 少一個外觀偏好無所謂，開不起來的導航 App 才是問題。
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { it.toSettings() }

    suspend fun setAppearance(context: Context, mode: AppearanceMode) {
        context.applicationContext.settingsFile.edit { it[APPEARANCE] = mode.name }
    }

    suspend fun setDuckOthers(context: Context, enabled: Boolean) {
        context.applicationContext.settingsFile.edit { it[DUCK_OTHERS] = enabled }
    }

    /**
     * 記住時速圓圈被拖到哪裡。
     *
     * 拖曳每一幀都會呼叫，所以呼叫端必須節流 —— DataStore 每次寫入都是一次
     * 完整的檔案改寫，一次拖曳幾十幀就是幾十次磁碟寫入。
     */
    suspend fun setDialPosition(context: Context, x: Float, y: Float) {
        context.applicationContext.settingsFile.edit {
            it[DIAL_X] = x
            it[DIAL_Y] = y
        }
    }
}
