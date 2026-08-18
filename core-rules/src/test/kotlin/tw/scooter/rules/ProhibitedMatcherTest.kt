package tw.scooter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineDistanceTest {

    private val a = LatLon(25.0472, 121.5133)

    @Test
    fun `distance to a segment is measured perpendicular, not to the endpoints`() {
        val b = destination(a, 90.0, 200.0)
        // 線段中點正北 30 公尺處。
        val p = destination(destination(a, 90.0, 100.0), 0.0, 30.0)
        assertEquals(30.0, distanceToSegmentMeters(p, a, b), 1.0)
    }

    @Test
    fun `beyond the ends it falls back to the endpoint distance`() {
        val b = destination(a, 90.0, 200.0)
        // 線段起點的西邊 50 公尺 —— 垂足落在線段之外。
        val p = destination(a, 270.0, 50.0)
        assertEquals(50.0, distanceToSegmentMeters(p, a, b), 1.0)
    }

    @Test
    fun `a polyline takes the closest of its segments`() {
        // 往東 200 公尺再往北 200 公尺的 L 形。
        val corner = destination(a, 90.0, 200.0)
        val end = destination(corner, 0.0, 200.0)
        val line = listOf(a, corner, end)

        // 靠近第二段的中點。
        val p = destination(destination(corner, 0.0, 100.0), 90.0, 15.0)
        assertEquals(15.0, distanceToPolylineMeters(p, line), 1.0)
    }

    @Test
    fun `a degenerate polyline is infinitely far, not zero`() {
        // 折線只有一個點時回傳 0 會讓「資料壞掉」看起來像「騎士就在線上」——
        // 而那是要播「這條路禁行機車」的判定。
        assertTrue(distanceToPolylineMeters(a, listOf(a)) > 1e6)
        assertTrue(distanceToPolylineMeters(a, emptyList()) > 1e6)
    }
}

class ProhibitedMatcherTest {

    /** 忠孝西路那一段的簡化版：由重慶南路往東 650 公尺到中山北路。 */
    private val west = LatLon(25.0472, 121.5133)
    private val east = destination(west, 90.0, 650.0)

    private fun segment(id: Long, bearing: Double) = ProhibitedSegment(
        id = id,
        roadName = "忠孝西路",
        bearing = bearing,
        polyline = listOf(west, destination(west, 90.0, 325.0), east),
        speedLimitKmh = 50,
        reason = "公車進出站與機慢車動線交織",
    )

    private fun riding(
        at: LatLon = destination(west, 90.0, 300.0),
        bearing: Double? = 90.0,
        speedKmh: Double = 30.0,
        epochMillis: Long = 0L,
    ) = RiderState(
        location = at,
        bearing = bearing,
        speedKmh = speedKmh,
        epochMillis = epochMillis,
        dayOfWeek = 3,
        minuteOfDay = 8 * 60,
    )

    private val eastbound = segment(1, bearing = 90.0)
    private val westbound = segment(2, bearing = 270.0)

    @Test
    fun `flags a rider travelling along the prohibited direction`() {
        val hit = ProhibitedMatcher().select(riding(), listOf(eastbound, westbound))
        assertEquals("只有同向的那一筆該命中", 1L, hit?.segment?.id)
    }

    @Test
    fun `the opposite direction is a separate entry, not a match`() {
        val westward = riding(bearing = 270.0)
        assertEquals(2L, ProhibitedMatcher().select(westward, listOf(eastbound, westbound))?.segment?.id)
    }

    @Test
    fun `a parallel road outside the lateral tolerance is not flagged`() {
        // 北邊 60 公尺的平行道路 —— 忠孝西路旁邊就有北平西路。
        val elsewhere = riding(
            at = destination(destination(west, 90.0, 300.0), 0.0, 60.0),
        )
        assertNull(ProhibitedMatcher().select(elsewhere, listOf(eastbound, westbound)))
    }

    @Test
    fun `stays silent without a trustworthy bearing`() {
        // 方向是這裡唯一分得出「這個方向禁行」與「反向合法」的東西。
        assertNull(ProhibitedMatcher().select(riding(bearing = null), listOf(eastbound)))
    }

    @Test
    fun `still flags a rider stuck in traffic on the prohibited road`() {
        // 門檻比警示的 15 km/h 低：塞車也還是在禁行路段上。
        assertEquals(1L, ProhibitedMatcher().select(riding(speedKmh = 10.0), listOf(eastbound))?.segment?.id)
        assertNull(ProhibitedMatcher().select(riding(speedKmh = 3.0), listOf(eastbound)))
    }

    @Test
    fun `does not repeat within the cooldown`() {
        val matcher = ProhibitedMatcher()
        matcher.markAlerted(1L, 0L)
        assertNull(matcher.select(riding(epochMillis = 60_000L), listOf(eastbound)))
        assertEquals(1L, matcher.select(riding(epochMillis = 6 * 60_000L), listOf(eastbound))?.segment?.id)
    }

    @Test
    fun `follows the polyline round a bend instead of the straight chord`() {
        // 環河北路沿著淡水河彎，沿線比直線長 17%。騎士在彎道外側的路面上，
        // 離折線很近、離兩端連成的直線很遠 —— 用直線判會漏掉他。
        val corner = destination(west, 45.0, 400.0)
        val bent = eastbound.copy(polyline = listOf(west, corner, destination(corner, 135.0, 400.0)))
        val onTheBend = destination(corner, 135.0, 10.0)

        assertTrue(
            "直線距離應該遠大於折線距離，否則這個測試沒有測到東西",
            distanceToSegmentMeters(onTheBend, bent.polyline.first(), bent.polyline.last()) > 100.0,
        )
        assertEquals(
            1L,
            ProhibitedMatcher().select(riding(at = onTheBend, bearing = 135.0), listOf(bent))?.segment?.id,
        )
    }
}
