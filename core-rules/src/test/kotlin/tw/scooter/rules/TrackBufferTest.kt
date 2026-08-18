package tw.scooter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackBufferTest {

    private val stopLine = LatLon(25.0400, 121.5170)

    private fun point(
        t: Long,
        bearing: Double?,
        speed: Double,
        at: LatLon = stopLine,
    ) = TrackPoint(
        location = at,
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

    /**
     * 這一條是本類別存在的理由。臺北的長紅燈超過 90 秒很常見，而軌跡視窗只有
     * 30 秒 —— 方位角若跟著視窗過期，回報按鈕就會在騎士最可能按它的那一刻失效。
     */
    @Test
    fun `keeps the approach bearing through a 90 second red light`() {
        val buffer = TrackBuffer()
        buffer.add(point(0, bearing = 90.0, speed = 30.0))
        // 每秒一個靜止點，位置只有 GPS 抖動。
        for (second in 1..90) {
            val jitter = LatLon(stopLine.lat + 0.00003 * (second % 3 - 1), stopLine.lon)
            buffer.add(point(second * 1_000L, bearing = null, speed = 0.0, at = jitter))
        }

        assertEquals(90.0, buffer.approachBearing()!!, 0.001)
        assertTrue("視窗仍然只留最近 30 秒的點", buffer.size() <= 31)
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
    fun `forgets the bearing once the rider has moved away`() {
        val buffer = TrackBuffer()
        buffer.add(point(0, bearing = 90.0, speed = 30.0))
        // 以低速離開，所以不會被新的可信方位角覆蓋 —— 只能靠位移判定作廢。
        buffer.add(
            point(
                2_000,
                bearing = null,
                speed = 2.0,
                at = destination(stopLine, 90.0, TrackBuffer.MAX_DRIFT_METERS + 20.0),
            ),
        )

        assertNull(buffer.approachBearing())
    }

    @Test
    fun `forgets the bearing after a gap in positioning`() {
        val buffer = TrackBuffer()
        buffer.add(point(0, bearing = 90.0, speed = 40.0))
        // 進了隧道，出來時人在原地附近 —— 但中間發生了什麼沒有人知道。
        buffer.add(point(TrackBuffer.MAX_GAP_MILLIS + 1_000, bearing = null, speed = 0.0))

        assertNull(buffer.approachBearing())
    }

    @Test
    fun `drops points older than the window`() {
        val buffer = TrackBuffer(windowMillis = 10_000)
        buffer.add(point(0, bearing = 90.0, speed = 40.0))
        buffer.add(point(20_000, bearing = null, speed = 0.0))

        assertEquals(1, buffer.size())
    }

    @Test
    fun `is not stopped until the rider has been still long enough`() {
        val buffer = TrackBuffer()
        buffer.add(point(0, bearing = 90.0, speed = 30.0))
        assertFalse(buffer.isStopped())

        buffer.add(point(1_000, bearing = null, speed = 1.0))
        assertFalse("停了一秒還不算停下來", buffer.isStopped())

        buffer.add(point(4_000, bearing = null, speed = 0.0))
        assertTrue(buffer.isStopped())
    }

    @Test
    fun `rolling forward cancels the stop`() {
        val buffer = TrackBuffer()
        buffer.add(point(0, bearing = null, speed = 0.0))
        buffer.add(point(4_000, bearing = null, speed = 0.0))
        assertTrue(buffer.isStopped())

        // 前車動了，騎士跟著往前滑一小段 —— 這時按鈕必須收回去。
        buffer.add(point(5_000, bearing = 90.0, speed = 9.0))
        assertFalse(buffer.isStopped())
    }

    @Test
    fun `latest reflects the most recent point`() {
        val buffer = TrackBuffer()
        buffer.add(point(0, bearing = 10.0, speed = 30.0))
        buffer.add(point(1_000, bearing = 20.0, speed = 31.0))

        assertEquals(1_000L, buffer.latest()!!.epochMillis)
    }

    @Test
    fun `clear forgets the held bearing too`() {
        val buffer = TrackBuffer()
        buffer.add(point(0, bearing = 90.0, speed = 30.0))
        buffer.clear()

        assertNull("服務停掉之後不該留著上一趟的方位角", buffer.approachBearing())
        assertFalse(buffer.isStopped())
    }
}
