package tw.scooter.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.scooter.rules.TurnRule

/**
 * 播出去的句子是騎士唯一收到的東西。這裡守的是**內容**，不是措辭 ——
 * 少講速限、或在沒有速限資料時無中生有，都是騎士照著做會出事的錯。
 */
class AlertPhrasesTest {

    @Test
    fun `a speed camera announcement always carries the limit`() {
        // 只說「前方測速照相」等於要騎士自己回想這條路限速多少，而他正在騎車。
        assertTrue(AlertPhrases.speedCamera(50, overSpeed = false).contains("50"))
        assertTrue(AlertPhrases.speedCamera(40, overSpeed = true).contains("40"))
    }

    @Test
    fun `the over speed line is added only when over speed`() {
        assertFalse(AlertPhrases.speedCamera(50, overSpeed = false).contains("超速"))
        assertTrue(AlertPhrases.speedCamera(50, overSpeed = true).contains("超速"))
    }

    @Test
    fun `a camera with no known limit does not invent one`() {
        // 科技執法點多半沒有速限資料。這時講出一個數字比不講危險得多。
        val phrase = AlertPhrases.speedCamera(null, overSpeed = false)
        assertEquals("前方測速照相", phrase)
        assertFalse(phrase.any { it.isDigit() })
    }

    @Test
    fun `every turn rule has something to say`() {
        // UNKNOWN 也要 —— 資料缺漏時它是最常播的一句，沉默等於在最需要指示的
        // 瞬間什麼都沒說。
        TurnRule.entries.forEach {
            assertTrue("$it 沒有對應的語句", AlertPhrases.of(it).isNotBlank())
        }
    }

    @Test
    fun `pre-synthesised cache names are unique per phrase`() {
        // 撞名會讓兩句共用同一個音檔，而騎士會在待轉路口聽到「可直接左轉」。
        val names = AlertPhrases.all.map { AlertPhrases.cacheName(it) }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every phrase key that the app looks up actually exists in the cache set`() {
        // 查一個不存在的 key 不會編譯錯誤，也不會崩潰 —— 它會安靜地退回即時合成，
        // 而那正是「五秒前那一則遲到三秒」的樣子。
        val keys = AlertPhrases.all.map { it.key }.toSet()
        TurnRule.entries.forEach {
            assertTrue("${'$'}it 的 key 不在預合成集合裡", AlertPhrases.keyFor(it) in keys)
        }
        assertTrue(AlertPhrases.keyForConfirm(-90f) in keys)
        assertTrue(AlertPhrases.keyForConfirm(90f) in keys)
        AlertPhrases.SPEED_LIMITS.forEach { limit ->
            assertTrue(AlertPhrases.keyForCamera(limit, false)!! in keys)
            assertTrue(AlertPhrases.keyForCamera(limit, true)!! in keys)
        }
        assertNull("罕見速限沒有預合成，退回即時合成", AlertPhrases.keyForCamera(37, false))
    }
}
