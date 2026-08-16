package tw.scooter.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.scooter.ui.theme.AppearanceMode

/**
 * 這些測試守的是「重開之後設定還在」。落地本身（寫檔）由 DataStore 負責，
 * 會出錯的是**讀回來的那一步** —— 而讀錯的症狀是設定悄悄回到預設值，
 * 使用者只會覺得「又要再設一次」，不會知道是哪裡壞了。
 */
class SettingsStoreTest {

    @Test
    fun `a device with nothing stored gets the defaults`() {
        assertEquals(Settings(), emptyPreferences().toSettings())
    }

    @Test
    fun `a stored appearance is what comes back`() {
        val stored = mutablePreferencesOf(stringPreferencesKey("appearance") to "LIGHT")
        assertEquals(AppearanceMode.LIGHT, stored.toSettings().appearance)
    }

    @Test
    fun `every appearance the drawer offers can be stored and read back`() {
        // 少一個就代表抽屜裡有一個選項按下去之後重開會不見。
        AppearanceMode.entries.forEach { mode ->
            val stored = mutablePreferencesOf(stringPreferencesKey("appearance") to mode.name)
            assertEquals("$mode 存不回來", mode, stored.toSettings().appearance)
        }
    }

    @Test
    fun `ducking switched off is not mistaken for never set`() {
        // false 與「沒設定過」在 preferences 裡是兩件事。若把 false 讀成沒設定，
        // 關掉的衰減每次重開都會自己打開，而騎士要到有背景音樂時才會發現。
        val stored = mutablePreferencesOf(booleanPreferencesKey("duck_others") to false)
        assertEquals(false, stored.toSettings().duckOthers)
    }

    @Test
    fun `a dial that has never been dragged has no position`() {
        // NaN 是「還沒拖過」的表示法，畫面據此決定用預設落點而不是 (0, 0) ——
        // 左上角正好是狀態列與回報列的位置。
        val fresh = emptyPreferences().toSettings()
        assertTrue(fresh.dialX.isNaN() && fresh.dialY.isNaN())
    }

    @Test
    fun `a dragged dial position comes back`() {
        val stored = mutablePreferencesOf(
            floatPreferencesKey("dial_x") to 120.5f,
            floatPreferencesKey("dial_y") to 880f,
        )
        assertEquals(120.5f, stored.toSettings().dialX, 0.01f)
        assertEquals(880f, stored.toSettings().dialY, 0.01f)
    }

    @Test
    fun `a dial dragged to the very corner is not mistaken for unset`() {
        // 0 是合法的位置（拖到最左上），不是「沒設定過」。讀成未設定會讓圓圈
        // 每次重開都跳回預設落點。
        val stored = mutablePreferencesOf(
            floatPreferencesKey("dial_x") to 0f,
            floatPreferencesKey("dial_y") to 0f,
        )
        assertEquals(0f, stored.toSettings().dialX, 0.001f)
        assertFalse(stored.toSettings().dialX.isNaN())
    }

    @Test
    fun `an appearance this version does not know falls back instead of throwing`() {
        // 降版之後會讀到新版寫下的字串。為了一個外觀偏好讓導航 App 開不起來並不划算。
        val stored = mutablePreferencesOf(stringPreferencesKey("appearance") to "SEPIA")
        assertEquals(Settings().appearance, stored.toSettings().appearance)
    }
}
