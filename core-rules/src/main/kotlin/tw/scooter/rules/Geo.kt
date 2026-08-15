package tw.scooter.rules

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_000.0

data class LatLon(val lat: Double, val lon: Double)

/** 兩點間大圓距離，公尺。 */
fun haversineMeters(a: LatLon, b: LatLon): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** 由 a 指向 b 的方位角，0..360 度，0 為正北。 */
fun bearingDegrees(a: LatLon, b: LatLon): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** 自 [from] 沿 [bearingDeg] 前進 [distanceMeters] 後的座標。 */
fun destination(from: LatLon, bearingDeg: Double, distanceMeters: Double): LatLon {
    val angular = distanceMeters / EARTH_RADIUS_M
    val bearing = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(from.lat)
    val lon1 = Math.toRadians(from.lon)
    val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
    val lon2 = lon1 + atan2(
        sin(bearing) * sin(angular) * cos(lat1),
        cos(angular) - sin(lat1) * sin(lat2),
    )
    return LatLon(Math.toDegrees(lat2), (Math.toDegrees(lon2) + 540.0) % 360.0 - 180.0)
}

/**
 * 兩方位角的最小夾角，0..180 度。
 *
 * 359 度與 1 度相差 2 度，不是 358 度 —— 直接相減是這類比對最常見的錯誤。
 */
fun bearingDelta(a: Double, b: Double): Double {
    val d = abs((a - b + 540.0) % 360.0 - 180.0)
    return d
}

/**
 * 以固定經緯度網格量化座標，作為空間索引鍵。
 *
 * 未使用 SQLite R-tree —— 該模組並非所有 Android 內建 SQLite 版本都啟用，
 * 而規則資料量級（全台數千筆例外）用網格索引已綽綽有餘。
 */
object Grid {
    /** 約 0.01 度，在台灣緯度下約略是 1.1 公里見方。 */
    const val CELL_DEGREES = 0.01

    fun cellOf(lat: Double, lon: Double): Long {
        val y = floor(lat / CELL_DEGREES).toLong()
        val x = floor(lon / CELL_DEGREES).toLong()
        return y * 100_000L + x
    }

    /** 涵蓋以該點為中心、指定半徑的所有網格，供 `WHERE cell IN (...)` 使用。 */
    fun cellsWithin(lat: Double, lon: Double, radiusMeters: Double): List<Long> {
        val latSpan = radiusMeters / 111_000.0
        val lonSpan = radiusMeters / (111_000.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val steps = mutableListOf<Long>()
        var y = floor((lat - latSpan) / CELL_DEGREES).toLong()
        val yMax = floor((lat + latSpan) / CELL_DEGREES).toLong()
        while (y <= yMax) {
            var x = floor((lon - lonSpan) / CELL_DEGREES).toLong()
            val xMax = floor((lon + lonSpan) / CELL_DEGREES).toLong()
            while (x <= xMax) {
                steps += y * 100_000L + x
                x++
            }
            y++
        }
        return steps
    }
}
