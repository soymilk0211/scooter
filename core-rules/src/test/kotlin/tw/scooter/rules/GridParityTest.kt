package tw.scooter.rules

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 網格索引的跨語言對位測試。
 *
 * 種子資料庫的 `cell` 欄位是由 Python 管線（pipeline/build_seed.py 的 cell_of）
 * 算出來的，查詢時則由這裡的 [Grid.cellOf] 重算。兩者只要有一點不一致，App 就會
 * **安靜地查不到任何規則** —— 不會崩潰、不會報錯，只是永遠不播警示。
 *
 * 下列期望值直接取自 Python 端的實際輸出。改動任一邊的實作都必須讓這個測試繼續通過。
 */
class GridParityTest {

    @Test
    fun `matches the python pipeline cell values`() {
        assertEquals(250_412_151L, Grid.cellOf(25.0478, 121.5170))
        assertEquals(250_312_156L, Grid.cellOf(25.0330, 121.5654))
        assertEquals(249_812_156L, Grid.cellOf(24.9887588, 121.560159))
    }

    @Test
    fun `floors toward negative infinity like python`() {
        // Kotlin 的 toLong() 是向零截斷，Python 的 int(floor()) 是向下取整。
        // 負座標下兩者會分歧，必須都走 floor —— 台灣用不到，但錯誤的實作
        // 會在未來擴充到南半球時安靜地壞掉。
        assertEquals(-100_001L, Grid.cellOf(-0.005, -0.005))
    }
}
