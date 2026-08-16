package tw.scooter.ride

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.scooter.data.ScooterDatabase
import tw.scooter.rules.AlertThresholds
import tw.scooter.rules.EnforcementThresholds
import tw.scooter.rules.RiderState
import tw.scooter.rules.destination
import tw.scooter.rules.haversineMeters
import java.time.Instant
import java.time.ZoneId

/**
 * 軌跡回放，只存在於 debug 版。
 *
 * 用途是驗證「定位 → 查規則 → 發警示」整條路徑真的接通了。真機上要驗證得先騎到
 * 那個路口，模擬器又給不出可信的速度與方位角，所以測試必須能繞過定位硬體。
 *
 * 起點取自種子資料庫裡真實的規則，不寫死座標 —— 這樣它同時也在測資料庫查詢與
 * 網格索引，而不只是測判定邏輯。
 *
 *     adb shell am broadcast -a tw.scooter.REPLAY
 *     adb shell am broadcast -a tw.scooter.REPLAY --es road 興隆路三段
 *     adb shell am broadcast -a tw.scooter.REPLAY --es road 建國南路 --ei speed 70
 */
class ReplayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val roadFilter = intent.getStringExtra("road")
        // 速度可以指定，因為有些行為只在特定速度下才看得到 —— 超速警示與時速圓圈
        // 的顏色都是。固定 40 的話，那兩條路徑在模擬器上永遠驗不到。
        val speedKmh = intent.getIntExtra("speed", DEFAULT_SPEED_KMH.toInt()).toDouble()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch { replay(app, roadFilter, speedKmh) }
    }

    private suspend fun replay(context: Context, roadFilter: String?, speedKmh: Double) {
        val database = ScooterDatabase.open(context)

        // 從資料庫挑一條真實規則當目標。
        val target = database.readableDatabase.rawQuery(
            "SELECT lat, lon, approach_bearing, entry_road_name, exit_road_name, turn_rule " +
                "FROM rules" + if (roadFilter != null) " WHERE entry_road_name LIKE ?" else "",
            roadFilter?.let { arrayOf("%$it%") },
        ).use { c ->
            if (!c.moveToFirst()) null
            else Target(c.getDouble(0), c.getDouble(1), c.getDouble(2),
                c.getString(3), c.getString(4), c.getInt(5))
        }

        if (target == null) {
            Log.w(TAG, "找不到可回放的規則（filter=$roadFilter）")
            return
        }
        Log.i(TAG, "回放目標：${target.entry} ➔ ${target.exit}，" +
            "面向 ${target.approachBearing}°，規定 ${target.turnRule}")

        val junction = tw.scooter.rules.LatLon(target.lat, target.lon)
        val engine = AlertEngine(database)
        val voice = AlertVoice(context).also { it.prepare() }
        val now = Instant.now()
        val zone = ZoneId.systemDefault()

        var distance = START_DISTANCE_M
        var elapsedMs = 0L
        var fired = false

        while (distance > 0) {
            // 從路口沿「進入方位角的反向」退開，就是騎士的來向。
            val here = destination(junction, (target.approachBearing + 180.0) % 360.0, distance)
            val at = now.plusMillis(elapsedMs).atZone(zone)
            val state = RiderState(
                location = here,
                bearing = target.approachBearing,
                speedKmh = speedKmh,
                epochMillis = now.toEpochMilli() + elapsedMs,
                dayOfWeek = at.dayOfWeek.value,
                minuteOfDay = at.hour * 60 + at.minute,
            )
            RideRepository.injectForReplay(state)

            val alerts = engine.evaluate(state)
            RideRepository.onSpeedLimit(alerts.speedLimitKmh)
            alerts.enforcement?.let { seen ->
                RideRepository.onEnforcement(seen)
                voice.speakEnforcement(seen.point.speedLimitKmh, seen.overSpeed)
                Log.i(TAG, "測速警示！距離 ${"%.0f".format(seen.distanceMeters)} m，" +
                    "速限 ${seen.point.speedLimitKmh}，超速 ${seen.overSpeed}，" +
                    seen.point.description)
            }
            alerts.turn?.let { alert ->
                fired = true
                RideRepository.onAlert(alert)
                voice.speak(alert.rule.rule)
                Log.i(TAG, "警示觸發！距離 ${"%.0f".format(alert.distanceMeters)} m，" +
                    "方位差 ${"%.1f".format(alert.bearingDelta)}°，" +
                    "規定 ${alert.rule.rule}，路口 ${alert.rule.entryRoadName} ➔ " +
                    alert.rule.exitRoadName)
            }

            val actual = haversineMeters(here, junction)
            Log.d(TAG, "距路口 ${"%.0f".format(actual)} m")

            distance -= STEP_M
            elapsedMs += (STEP_M / (speedKmh / 3.6) * 1000).toLong()
            delay(TICK_MS)
        }

        Log.i(TAG, if (fired) "回放結束：警示有觸發" else "回放結束：警示未觸發（有問題）")
        delay(4_000)  // 讓最後一句播完再釋放
        voice.release()
    }

    private data class Target(
        val lat: Double, val lon: Double, val approachBearing: Double,
        val entry: String?, val exit: String?, val turnRule: Int,
    )

    companion object {
        private const val TAG = "Replay"

        /**
         * 起點要涵蓋**最遠**的那個時窗，不是路口那個。
         *
         * 測速的彈性時窗上緣是 500 公尺，比路口的 300 公尺遠 —— 從 400 公尺起跑
         * 會讓測速警示永遠沒有機會出現，而回放的用途正是證明它會出現。
         */
        private val START_DISTANCE_M =
            maxOf(AlertThresholds.MAX_DISTANCE_METERS, EnforcementThresholds.MAX_DISTANCE_METERS) + 150.0
        private const val STEP_M = 20.0
        private const val DEFAULT_SPEED_KMH = 40.0

        /** 每步之間的真實等待，讓 log 讀得出順序。 */
        private const val TICK_MS = 150L
    }
}
