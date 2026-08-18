package tw.scooter.route

import android.content.Context
import android.util.Log
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.VoiceHintAccess
import tw.scooter.rules.LatLon
import tw.scooter.rules.Maneuver
import tw.scooter.rules.Route
import tw.scooter.rules.bearingDegrees
import tw.scooter.rules.haversineMeters

private const val TAG = "ScooterRouter"

/**
 * 用 BRouter 算白牌機車的路線（ADR-0016）。
 *
 * 路線層遵守的是：不走國道、不走 `motorroad=yes` 的快速道路、不走
 * `motorcycle=no` 的路段、遵守 OSM 的轉向限制與單行道。那些規則寫在
 * `scooter-tw.brf` 裡，不寫在這個類別裡 —— 這裡只負責把資料餵進去、
 * 把結果拿出來。
 *
 * **待轉與騎士回報的禁止左轉不在路線裡。** BRouter 的 profile 讀的是 OSM 標籤，
 * 讀不到我們的資料庫，所以那兩者目前只能播報不能繞路。要讓它們影響路線，
 * 得自建 rd5 圖磚並在建圖時注入 —— 見 HANDOVER 第六節。
 */
class ScooterRouter(private val context: Context) {

    /**
     * 算一條路線。**這個呼叫會擋住執行緒**（台北市內實測約 280 毫秒，
     * 台北到台中約 2.7 秒），呼叫端必須把它放在 IO 上。
     *
     * 回傳 null 代表算不出來：圖磚還沒下載、起訖點附近沒有機車走得的路、
     * 或者兩點之間在白牌的路權下真的不連通。三者對使用者的意義不同，
     * 但都不是「稍後再試」，所以這裡不區分 —— 呼叫端該檢查的是
     * [SegmentDownloader.isInstalled]。
     */
    fun route(from: LatLon, to: LatLon): Route? {
        val profile = BrouterFiles.ensureInstalled(context).getOrNull() ?: run {
            Log.w(TAG, "profile not installed")
            return null
        }
        if (!SegmentDownloader.isInstalled(context)) {
            Log.w(TAG, "segments not downloaded")
            return null
        }

        val rc = RoutingContext().apply {
            localFunction = profile.absolutePath
            // 產生轉向指示。即使現在讀不到內容，開著它會影響路線的選擇
            // （BRouter 為了產生指示會保留更多節點），關掉再打開路線會變。
            turnInstructionMode = 1
            // 明確打開，不依賴「car profile 預設就開」這個沒寫在文件裡的行為。
            considerTurnRestrictions = true
        }

        val waypoints = listOf(node("from", from), node("to", to))
        val engine = RoutingEngine(null, null, BrouterFiles.segmentDir(context), waypoints, rc)
        engine.quite = true
        engine.doRun(0)

        engine.errorMessage?.let {
            Log.w(TAG, "routing failed: $it")
            return null
        }
        val track = engine.foundTrack ?: return null

        val points = track.nodes.map {
            LatLon(it.iLat / 1_000_000.0 - 90.0, it.iLon / 1_000_000.0 - 180.0)
        }

        // 沿路線的累積距離。逐向導航要的是「沿路走多遠」而不是直線距離 ——
        // 先算好一份，之後每次定位更新才不必重算整條。
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + haversineMeters(points[i - 1], points[i])
        }

        val maneuvers = ArrayList<Maneuver>()
        for (i in track.nodes.indices) {
            val hint = track.getVoiceHint(i) ?: continue
            // indexInTrack 由 BRouter 填，理論上一定落在範圍內；夾一次是因為
            // 越界會是 crash 而不是錯誤的指示，而 crash 發生在騎乘中。
            val index = VoiceHintAccess.indexInTrack(hint).coerceIn(points.indices)
            maneuvers += Maneuver(
                at = points[index],
                alongRouteMeters = cumulative[index],
                angleDegrees = VoiceHintAccess.angle(hint),
                isLeftTurn = VoiceHintAccess.isLeftTurn(hint),
                wayTags = VoiceHintAccess.wayTags(hint),
                // 轉完之後往哪走。往後看一個節點就夠 —— 再往後會被下一個
                // 彎道帶偏，而我們要的是「剛轉進去時車頭朝哪」。
                exitBearingDegrees = points.getOrNull(index + 1)
                    ?.let { bearingDegrees(points[index], it) },
            )
        }

        return Route(points = points, distanceMeters = track.distance, maneuvers = maneuvers)
    }

    /** BRouter 的整數座標：微度再加 180/90 的偏移，避免負數。 */
    private fun node(name: String, at: LatLon) = OsmNodeNamed().apply {
        this.name = name
        ilon = ((at.lon + 180.0) * 1_000_000.0 + 0.5).toInt()
        ilat = ((at.lat + 90.0) * 1_000_000.0 + 0.5).toInt()
    }
}
