package tw.scooter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManeuverAnnouncerTest {

    private val turn = Maneuver(
        at = LatLon(25.0400, 121.5170),
        alongRouteMeters = 1000.0,
        angleDegrees = -90f,
        isLeftTurn = true,
    )

    private fun progressAt(metersToTurn: Double, offRoute: Boolean = false) = RouteProgress(
        alongRouteMeters = 1000.0 - metersToTurn,
        lateralMeters = if (offRoute) 80.0 else 3.0,
        nextManeuver = turn,
        metersToNextManeuver = metersToTurn,
        offRoute = offRoute,
    )

    @Test
    fun `announces the primary instruction about twenty seconds out`() {
        val a = ManeuverAnnouncer()
        // 時速 40 = 11.1 m/s。20 秒 = 222 公尺。
        assertNull("太遠不播", a.update(progressAt(400.0), speedKmh = 40.0))
        val hit = a.update(progressAt(200.0), speedKmh = 40.0)!!
        assertEquals(Announcement.Stage.PRIMARY, hit.stage)
        assertEquals(200, hit.distanceBucketMeters)
    }

    @Test
    fun `each stage fires once, not on every fix`() {
        val a = ManeuverAnnouncer()
        assertEquals(Announcement.Stage.PRIMARY, a.update(progressAt(200.0), 40.0)!!.stage)
        assertNull("同一則不重播", a.update(progressAt(190.0), 40.0))
        assertNull(a.update(progressAt(120.0), 40.0))
        assertEquals(Announcement.Stage.CONFIRM, a.update(progressAt(50.0), 40.0)!!.stage)
        assertNull(a.update(progressAt(30.0), 40.0))
    }

    @Test
    fun `the confirmation carries no distance`() {
        val a = ManeuverAnnouncer()
        val hit = a.update(progressAt(40.0), speedKmh = 40.0)!!
        assertEquals(Announcement.Stage.CONFIRM, hit.stage)
        assertNull("五秒前講距離沒有用，而且多一個詞就多一秒", hit.distanceBucketMeters)
    }

    @Test
    fun `stays quiet when the rider has left the route`() {
        // 對著一條已經不成立的路線喊「前方左轉」會把騎士帶到錯的地方。
        assertNull(ManeuverAnnouncer().update(progressAt(200.0, offRoute = true), 40.0))
    }

    @Test
    fun `still announces in stop-and-go traffic`() {
        // 時速 3 公里時，沒有速度下限的話「還有幾秒」會變成幾百秒，
        // 主指示永遠不會播 —— 而騎士照樣會到那個路口。
        val a = ManeuverAnnouncer()
        assertEquals(Announcement.Stage.PRIMARY, a.update(progressAt(40.0), speedKmh = 3.0)!!.stage)
    }

    @Test
    fun `says nothing without a route`() {
        assertNull(ManeuverAnnouncer().update(null, 40.0))
    }
}
