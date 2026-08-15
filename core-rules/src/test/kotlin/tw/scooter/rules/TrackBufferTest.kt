package tw.scooter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackBufferTest {

    private fun point(t: Long, bearing: Double?, speed: Double) = TrackPoint(
        location = LatLon(25.0400, 121.5170),
        bearing = bearing,
        speedKmh = speed,
        epochMillis = t,
    )

    @Test
    fun `recovers the bearing from before the rider stopped`() {
        val buffer = TrackBuffer()
        buffer.add(point(0, bearing = 90.0, speed = 35.0))
        buffer.add(point(1_000, bearing = 92.0, speed = 20.0))
        buffer.add(point(2_000, bearing = null, speed = 2.0))
        buffer.add(point(3_000, bearing = null, speed = 0.0))

        assertEquals(92.0, buffer.approachBearing()!!, 0.001)
    }

    @Test
    fun `ignores bearings recorded at untrustworthy speeds`() {
        val buffer = TrackBuffer()
        buffer.add(point(0, bearing = 90.0, speed = 30.0))
        // 低速時系統仍可能給出方位角，但那是雜訊，不該覆蓋前一個可信值。
        buffer.add(point(1_000, bearing = 270.0, speed = 3.0))

        assertEquals(90.0, buffer.approachBearing()!!, 0.001)
    }

    @Test
    fun `a rider who parked and walked over has no approach bearing`() {
        val buffer = TrackBuffer()
        buffer.add(point(0, bearing = null, speed = 0.0))
        buffer.add(point(1_000, bearing = 45.0, speed = 4.0))

        assertNull(buffer.approachBearing())
    }

    @Test
    fun `drops points older than the window`() {
        val buffer = TrackBuffer(windowMillis = 10_000)
        buffer.add(point(0, bearing = 90.0, speed = 40.0))
        buffer.add(point(20_000, bearing = null, speed = 0.0))

        assertEquals(1, buffer.size())
        assertNull("stale bearing must not leak through", buffer.approachBearing())
    }

    @Test
    fun `latest reflects the most recent point`() {
        val buffer = TrackBuffer()
        buffer.add(point(0, bearing = 10.0, speed = 30.0))
        buffer.add(point(1_000, bearing = 20.0, speed = 31.0))

        assertEquals(1_000L, buffer.latest()!!.epochMillis)
    }
}
