package tw.scooter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 測速警示守的是兩件事：**該播的不能漏**（漏掉是罰單），以及**不要佔到路口的
 * 語音通道**（那會讓騎士錯過待轉，比罰單危險）。
 */
class EnforcementMatcherTest {

    private val here = LatLon(25.0330, 121.5654)

    private fun rider(
        speedKmh: Double = 50.0,
        bearing: Double? = 0.0,
        at: Long = 1_000L,
    ) = RiderState(
        location = here,
        bearing = bearing,
        speedKmh = speedKmh,
        epochMillis = at,
        dayOfWeek = 3,
        minuteOfDay = 600,
    )

    private fun camera(
        id: Long = 1L,
        metresAhead: Double,
        bearing: Double? = 0.0,
        limit: Int? = 50,
    ) = EnforcementPoint(
        id = id,
        location = destination(here, 0.0, metresAhead),
        bearing = bearing,
        kind = EnforcementKind.FIXED_SPEED_CAMERA,
        speedLimitKmh = limit,
        description = "測試點",
    )

    @Test
    fun `a camera inside the elastic window is announced`() {
        val chosen = EnforcementMatcher().select(rider(), listOf(camera(metresAhead = 420.0)))
        assertNotNull(chosen)
        assertEquals(1L, chosen!!.point.id)
    }

    @Test
    fun `the elastic window closes before the turn window opens`() {
        // 這是「彈性的往前讓」的實作：測速在 320 公尺就閉嘴，而路口警示的剛性窗
        // 是 300 公尺。兩個窗不重疊，語音通道不會被搶。
        assertTrue(
            "測速窗的下緣必須大於路口警示的上緣",
            EnforcementThresholds.MIN_DISTANCE_METERS > AlertThresholds.MAX_DISTANCE_METERS,
        )
        assertNull(EnforcementMatcher().select(rider(), listOf(camera(metresAhead = 280.0))))
    }

    @Test
    fun `nothing is announced beyond the far edge`() {
        assertNull(EnforcementMatcher().select(rider(), listOf(camera(metresAhead = 900.0))))
    }

    @Test
    fun `the furthest camera goes first`() {
        // 彈性時窗的重點是趁早講完。兩個都在窗內時先講遠的，近的下一輪還有空間；
        // 反過來先講近的，遠的那個會被拖到路口窗裡才輪到。
        val chosen = EnforcementMatcher().select(
            rider(),
            listOf(camera(id = 1L, metresAhead = 350.0), camera(id = 2L, metresAhead = 480.0)),
        )
        assertEquals(2L, chosen?.point?.id)
    }

    @Test
    fun `a camera facing the other way is not announced`() {
        // 面向南的騎士遇到只取締北向的照相機。
        val chosen = EnforcementMatcher().select(
            rider(bearing = 180.0),
            listOf(camera(metresAhead = 400.0, bearing = 0.0)),
        )
        assertNull(chosen)
    }

    @Test
    fun `a camera with no direction is announced whichever way the rider faces`() {
        // 原始資料寫「雙向」或寫得判不出來，都會落在這條路徑上。寧可多播一次。
        listOf(0.0, 90.0, 180.0, 270.0).forEach { heading ->
            val chosen = EnforcementMatcher().select(
                rider(bearing = heading),
                listOf(camera(metresAhead = 400.0, bearing = null)),
            )
            assertNotNull("面向 $heading 應該仍要警示", chosen)
        }
    }

    @Test
    fun `a stationary rider is left alone`() {
        assertNull(EnforcementMatcher().select(rider(speedKmh = 3.0), listOf(camera(metresAhead = 400.0))))
    }

    @Test
    fun `no bearing means no alert at all`() {
        // 沒有行進方向就無從判斷這個點適不適用，而猜錯的方向會在錯的路上播報。
        assertNull(EnforcementMatcher().select(rider(bearing = null), listOf(camera(metresAhead = 400.0))))
    }

    @Test
    fun `the same camera is not repeated inside the cooldown`() {
        val matcher = EnforcementMatcher()
        val point = camera(metresAhead = 450.0)
        assertNotNull(matcher.select(rider(at = 1_000L), listOf(point)))
        matcher.markAlerted(point.id, 1_000L)
        assertNull(matcher.select(rider(at = 2_000L), listOf(point)))
        assertNotNull(matcher.select(rider(at = 1_000L + 6 * 60 * 1000L), listOf(point)))
    }

    @Test
    fun `over speed needs to clear the gps noise margin`() {
        // 定速 50 的實測值會在 48–53 之間跳。零容許值會讓警示在守法的騎士耳邊
        // 反覆說他超速，而那會讓他學會忽略它。
        assertFalse(EnforcementMatcher.isOverSpeed(50.0, 50))
        assertFalse(EnforcementMatcher.isOverSpeed(52.0, 50))
        assertTrue(EnforcementMatcher.isOverSpeed(58.0, 50))
    }

    @Test
    fun `a point with no speed limit can never be over speed`() {
        // 科技執法點多半沒有速限資料，那時「您已超速」是無中生有。
        assertFalse(EnforcementMatcher.isOverSpeed(120.0, null))
        val chosen = EnforcementMatcher().select(
            rider(speedKmh = 120.0),
            listOf(camera(metresAhead = 400.0, limit = null)),
        )
        assertNotNull(chosen)
        assertFalse(chosen!!.overSpeed)
    }

    @Test
    fun `the alert carries whether the rider is already speeding`() {
        val chosen = EnforcementMatcher().select(
            rider(speedKmh = 70.0),
            listOf(camera(metresAhead = 400.0, limit = 50)),
        )
        assertTrue(chosen!!.overSpeed)
        assertEquals(50, chosen.point.speedLimitKmh)
    }
}
