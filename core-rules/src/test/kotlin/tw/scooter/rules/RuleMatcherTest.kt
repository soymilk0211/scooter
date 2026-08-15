package tw.scooter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun `bearing delta wraps around north`() {
        assertEquals(2.0, bearingDelta(359.0, 1.0), 0.001)
        assertEquals(180.0, bearingDelta(0.0, 180.0), 0.001)
        assertEquals(90.0, bearingDelta(350.0, 80.0), 0.001)
    }

    @Test
    fun `haversine matches known short distance`() {
        // 台北車站往東約 1 公里
        val a = LatLon(25.0478, 121.5170)
        val b = LatLon(25.0478, 121.5269)
        val d = haversineMeters(a, b)
        assertTrue("expected ~1000m, got $d", d in 950.0..1050.0)
    }

    @Test
    fun `destination round-trips with haversine and bearing`() {
        val start = LatLon(25.0478, 121.5170)
        for (bearing in listOf(0.0, 90.0, 180.0, 270.0, 37.5)) {
            val end = destination(start, bearing, 300.0)
            assertEquals(300.0, haversineMeters(start, end), 0.5)
            assertEquals(bearing, bearingDegrees(start, end), 0.1)
        }
    }

    @Test
    fun `grid cells cover the query radius`() {
        val cells = Grid.cellsWithin(25.0478, 121.5170, 300.0)
        assertTrue(cells.contains(Grid.cellOf(25.0478, 121.5170)))
    }
}

class RuleMatcherTest {

    private fun rule(
        id: Long,
        lat: Double,
        lon: Double,
        approach: Double,
        period: EffectivePeriod? = null,
        status: RuleStatus = RuleStatus.OFFICIAL,
    ) = IntersectionRule(
        id = id,
        location = LatLon(lat, lon),
        approachBearing = approach,
        exitBearing = null,
        rule = TurnRule.HOOK,
        status = status,
        confidence = 100,
        entryRoadName = "忠孝東路",
        exitRoadName = "復興南路",
        effectivePeriod = period,
    )

    /** 正北行進，路口在正北方約 200 公尺。 */
    private fun approaching(speedKmh: Double = 40.0, at: Long = 0L) = RiderState(
        location = LatLon(25.0400, 121.5170),
        bearing = 0.0,
        speedKmh = speedKmh,
        epochMillis = at,
        dayOfWeek = 3,
        minuteOfDay = 8 * 60,
    )

    private val ahead = rule(1, 25.0418, 121.5170, approach = 0.0)

    @Test
    fun `alerts when approaching within range on matching bearing`() {
        val picked = RuleMatcher().select(approaching(), listOf(ahead))
        assertEquals(1L, picked?.rule?.id)
    }

    @Test
    fun `stays silent below the speed gate`() {
        assertNull(RuleMatcher().select(approaching(speedKmh = 5.0), listOf(ahead)))
    }

    @Test
    fun `stays silent when bearing is invalid`() {
        val stopped = approaching().copy(bearing = null)
        assertNull(RuleMatcher().select(stopped, listOf(ahead)))
    }

    @Test
    fun `ignores a rule approached from the wrong direction`() {
        val fromBehind = rule(2, 25.0418, 121.5170, approach = 180.0)
        assertNull(RuleMatcher().select(approaching(), listOf(fromBehind)))
    }

    @Test
    fun `picks the soonest of several candidates`() {
        val further = rule(3, 25.0424, 121.5170, approach = 0.0)
        val picked = RuleMatcher().select(approaching(), listOf(further, ahead))
        assertEquals("nearest in time should win", 1L, picked?.rule?.id)
    }

    @Test
    fun `does not repeat within the cooldown`() {
        val matcher = RuleMatcher()
        matcher.markAlerted(1L, 0L)
        assertNull(matcher.select(approaching(at = 60_000L), listOf(ahead)))
        assertEquals(1L, matcher.select(approaching(at = 6 * 60_000L), listOf(ahead))?.rule?.id)
    }

    @Test
    fun `skips a rule outside its effective period`() {
        val weekdayMorning = rule(
            4, 25.0418, 121.5170, approach = 0.0,
            period = EffectivePeriod(DaySet.WEEKDAY, 7 * 60, 9 * 60),
        )
        val sundayRide = approaching().copy(dayOfWeek = 7)
        assertNull(RuleMatcher().select(sundayRide, listOf(weekdayMorning)))
        assertEquals(4L, RuleMatcher().select(approaching(), listOf(weekdayMorning))?.rule?.id)
    }

    @Test
    fun `skips disputed rules`() {
        val disputed = rule(5, 25.0418, 121.5170, approach = 0.0, status = RuleStatus.DISPUTED)
        assertNull(RuleMatcher().select(approaching(), listOf(disputed)))
    }
}
