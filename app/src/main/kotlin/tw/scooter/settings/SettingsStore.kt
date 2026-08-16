package tw.scooter.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
)

private val Context.settingsFile: DataStore<Preferences> by preferencesDataStore(name = "settings")

private val APPEARANCE = stringPreferencesKey("appearance")
private val DUCK_OTHERS = booleanPreferencesKey("duck_others")

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
}
