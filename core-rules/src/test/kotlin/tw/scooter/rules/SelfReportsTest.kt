package tw.scooter.rules

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfReportsTest {

    private val junction = LatLon(25.0418, 121.5327)

    private fun rule(
        id: Long,
        location: LatLon = junction,
        approachBearing: Double = 90.0,
        turnRule: TurnRule = TurnRule.DIRECT,
        status: RuleStatus = RuleStatus.OFFICIAL,
    ) = IntersectionRule(
        id = id,
        location = location,
        approachBearing = approachBearing,
        exitBearing = null,
        rule = turnRule,
        status = status,
        confidence = 100,
        entryRoadName = null,
        exitRoadName = null,
        effectivePeriod = null,
    )

    @Test
    fun `自己的回報取代同一路口同一來向的官方規則`() {
        val official = rule(1, turnRule = TurnRule.DIRECT)
        val own = rule(-1, turnRule = TurnRule.HOOK, status = RuleStatus.SELF_REPORTED)

        val merged = SelfReports.merge(listOf(official), listOf(own))

        assertEquals(1, merged.size)
        assertEquals(TurnRule.HOOK, merged.single().rule)
    }

    @Test
    fun `不同來向的規則不會被取代`() {
        // 同一個路口的東向與西向是兩條規則（ADR-0001），回報其中一個
        // 不該把另一個吃掉 —— 那會讓對向的騎士突然沒有規則可播。
        val eastbound = rule(1, approachBearing = 90.0, turnRule = TurnRule.DIRECT)
        val westbound = rule(2, approachBearing = 270.0, turnRule = TurnRule.DIRECT)
        val own = rule(-1, approachBearing = 90.0, turnRule = TurnRule.HOOK,
            status = RuleStatus.SELF_REPORTED)

        val merged = SelfReports.merge(listOf(eastbound, westbound), listOf(own))

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.id == 2L && it.rule == TurnRule.DIRECT })
        assertTrue(merged.any { it.status == RuleStatus.SELF_REPORTED })
    }

    @Test
    fun `隔壁路口的規則不會被取代`() {
        val faraway = rule(1, location = destination(junction, 90.0, 200.0))
        val own = rule(-1, turnRule = TurnRule.HOOK, status = RuleStatus.SELF_REPORTED)

        val merged = SelfReports.merge(listOf(faraway), listOf(own))

        assertEquals(2, merged.size)
    }

    @Test
    fun `沒有回報時原樣回傳`() {
        val official = listOf(rule(1), rule(2, approachBearing = 180.0))
        assertEquals(official, SelfReports.merge(official, emptyList()))
    }

    @Test
    fun `回報位置換算成路口中心是往前一個退距`() {
        // 騎士停在停止線、車頭朝東，路口中心在他前方一個退距。
        val stopLine = LatLon(25.0418, 121.5327)
        val centre = SelfReports.junctionCentre(stopLine, approachBearing = 90.0)

        val moved = haversineMeters(stopLine, centre)
        assertTrue(
            "移動了 $moved 公尺，應該接近退距",
            abs(moved - AlertThresholds.STOP_LINE_SETBACK_METERS) < 0.5,
        )
        assertTrue("朝東回報，中心應該在東邊", centre.lon > stopLine.lon)
        assertTrue(abs(bearingDelta(bearingDegrees(stopLine, centre), 90.0)) < 1.0)
    }

    @Test
    fun `換算過後的回報配得上官方那一筆`() {
        // 這是整條鏈真正要成立的性質：騎士在停止線回報，換算成中心之後，
        // 必須與官方存在路口中心的那一筆認得出是同一條規則。不成立的話，
        // 同一個路口會同時存在官方與自己的兩筆，播報變成擲骰子。
        val centre = junction
        val official = rule(1, location = centre, approachBearing = 90.0)

        val stopLine = destination(centre, 270.0, AlertThresholds.STOP_LINE_SETBACK_METERS)
        val own = rule(
            id = -1,
            location = SelfReports.junctionCentre(stopLine, approachBearing = 90.0),
            approachBearing = 90.0,
            turnRule = TurnRule.HOOK,
            status = RuleStatus.SELF_REPORTED,
        )

        val merged = SelfReports.merge(listOf(official), listOf(own))
        assertEquals(1, merged.size)
        assertEquals(RuleStatus.SELF_REPORTED, merged.single().status)
    }
}
