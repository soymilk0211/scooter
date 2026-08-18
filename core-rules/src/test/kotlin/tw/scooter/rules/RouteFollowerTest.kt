package tw.scooter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteFollowerTest {

    private val start = LatLon(25.0400, 121.5170)

    /** 往東 400 公尺、左轉往北 400 公尺的 L 形路線，轉角有一則左轉指示。 */
    private fun lShaped(): Route {
        val corner = destination(start, 90.0, 400.0)
        val end = destination(corner, 0.0, 400.0)
        val points = buildList {
            for (d in 0..400 step 50) add(destination(start, 90.0, d.toDouble()))
            for (d in 50..400 step 50) add(destination(corner, 0.0, d.toDouble()))
        }
        return Route(
            points = points,
            distanceMeters = 800,
            maneuvers = listOf(
                Maneuver(at = corner, alongRouteMeters = 400.0, angleDegrees = -90f, isLeftTurn = true),
            ),
        )
    }

    @Test
    fun `reports distance to the next turn along the route`() {
        val follower = RouteFollower(lShaped())
        val p = follower.update(destination(start, 90.0, 150.0))!!

        assertEquals(150.0, p.alongRouteMeters, 5.0)
        assertEquals(250.0, p.metersToNextManeuver!!, 5.0)
        assertTrue(p.nextManeuver!!.isLeftTurn)
        assertFalse(p.offRoute)
    }

    @Test
    fun `distance moves smoothly between nodes, not in node-sized jumps`() {
        // 節點間距 50 公尺。若進度只取節點，這兩個位置會回報同一個數字，
        // 而播報是用「還有幾秒到」算的 —— 跳一次就可能跳過一則警示。
        val follower = RouteFollower(lShaped())
        val a = follower.update(destination(start, 90.0, 110.0))!!.alongRouteMeters
        val b = follower.update(destination(start, 90.0, 135.0))!!.alongRouteMeters

        assertTrue("進度必須在節點之間也會動：$a -> $b", b - a > 15.0)
    }

    @Test
    fun `has no next maneuver once the turn is behind`() {
        val follower = RouteFollower(lShaped())
        val corner = destination(start, 90.0, 400.0)
        val p = follower.update(destination(corner, 0.0, 200.0))!!

        assertNull(p.nextManeuver)
        assertNull(p.metersToNextManeuver)
    }

    @Test
    fun `flags off-route when the rider leaves the line`() {
        val follower = RouteFollower(lShaped())
        val aside = destination(destination(start, 90.0, 150.0), 0.0, 80.0)
        val p = follower.update(aside)!!

        assertTrue(p.offRoute)
        assertEquals(80.0, p.lateralMeters, 8.0)
    }

    /**
     * 這一條是 [RouteFollower] 記游標的理由。路線繞回起點附近時，
     * 全域搜尋最近點會把騎士判定成「還在起點」，而畫面上的症狀是
     * 「已經走的距離突然歸零、下一個轉向莫名其妙變回第一個」。
     */
    @Test
    fun `does not jump back when the route passes near its own start`() {
        // 往東 300、往北 60、往西 300 —— 終點回到起點正北 60 公尺處。
        val a = (0..300 step 50).map { destination(start, 90.0, it.toDouble()) }
        val turn = destination(start, 90.0, 300.0)
        val b = (50..60 step 10).map { destination(turn, 0.0, it.toDouble()) }
        val back = destination(turn, 0.0, 60.0)
        val c = (50..300 step 50).map { destination(back, 270.0, it.toDouble()) }
        val route = Route(a + b + c, 660, emptyList())

        val follower = RouteFollower(route)
        follower.update(destination(start, 90.0, 250.0))
        // 騎士現在在回程上，位置離起點只有 60 公尺 —— 全域搜尋會判成起點。
        val near = destination(destination(start, 90.0, 40.0), 0.0, 60.0)
        val p = follower.update(near)!!

        assertTrue("進度不該倒退回起點：${p.alongRouteMeters}", p.alongRouteMeters > 400.0)
    }

    @Test
    fun `a degenerate route yields no progress rather than a crash`() {
        assertNull(RouteFollower(Route(listOf(start), 0, emptyList())).update(start))
        assertNull(RouteFollower(Route(emptyList(), 0, emptyList())).update(start))
    }
}
