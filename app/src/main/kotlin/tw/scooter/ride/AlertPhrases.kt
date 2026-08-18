package tw.scooter.ride

import kotlin.math.abs

import tw.scooter.rules.TurnRule

/**
 * 警示語句。畫面與語音共用同一份 —— 兩邊各寫一份遲早會分岔，
 * 而騎士聽到的和看到的不一致比任何一邊錯更糟。
 *
 * 這些字串會在首次啟動時被預先合成成音檔，所以**改動文案等於讓快取失效**，
 * [VERSION] 就是為此存在：值一變，舊音檔全部重新合成。
 */
object AlertPhrases {

    const val VERSION = 1

    fun of(rule: TurnRule): String = when (rule) {
        TurnRule.HOOK -> "前方路口，請兩段式左轉"
        TurnRule.DIRECT -> "前方路口，機車可直接左轉"
        TurnRule.INNER_LANE -> "前方路口，請走內側左轉專用道"
        TurnRule.OUTER_LANE -> "前方路口，請走外側左轉專用道"
        TurnRule.UNKNOWN -> "前方路口即將左轉，請依現場標誌指示行駛"
    }

    /** 預先合成的對象。UNKNOWN 也要 —— 資料缺漏時它是最常播的一句。 */
    val all: List<TurnRule> = TurnRule.entries.toList()

    fun cacheName(rule: TurnRule): String = "alert_v${VERSION}_${rule.id}.wav"

    /**
     * 測速照相的播報。**一律講速限** —— 只說「前方測速照相」等於要騎士自己回想
     * 這條路限速多少，而他正在騎車。
     *
     * 超速才加「您已超速」。「現在有沒有超速」平時由時速圓圈用顏色表達，不佔語音
     * 通道；只有在**這一刻確實超速又正要進入鏡頭**時，那句話才值得說出口。
     *
     * 這些句子**不預先合成**，走即時 TTS。預合成是為了轉向指示那個硬期限而存在的，
     * 而測速是彈性時窗（500–320 公尺）—— 晚三秒沒有差別。速限有七八種值，
     * 乘上超速與否，預合成等於為了一個沒有期限的警示去撐大快取。
     */
    fun speedCamera(limitKmh: Int?, overSpeed: Boolean): String = when {
        limitKmh == null -> "前方測速照相"
        overSpeed -> "前方測速照相，速限 $limitKmh，您已超速"
        else -> "前方測速照相，速限 $limitKmh"
    }

    /**
     * 全面禁行機車的路段。
     *
     * **是提醒不是指控。** 判定方式是「點到折線的距離 + 方位角閘門」，
     * 它分不出垂直分離的道路（環河北路的正上方就是環河快速道路，
     * 水平距離是零、走向相同）。說「這條路禁行機車」我們有把握，
     * 說「你違規了」沒有 —— 而對一個正在騎車的人做錯誤的指控，
     * 換來的是他從此不再相信這個 App 說的任何一句話。
     *
     * 路名沒有就不講路名。「這條路」在騎士的處境裡已經沒有歧義了，
     * 硬補一個可能錯的名字只會讓他懷疑我們講的是不是別條路。
     */
    /**
     * 轉向播報。**沒有路名**（決策檔案 D5：路線引擎不提供）。
     *
     * 回傳 null 代表這一則不值得開口 —— 微幅的靠左靠右（角度小於
     * [MIN_ANNOUNCED_ANGLE]）在騎士的感受上就是直行，播出來只會變成噪音，
     * 而噪音會讓他學會忽略真正重要的那幾則。
     *
     * **判斷「有沒有話講」在這裡，判斷「什麼時候講」在 `ManeuverAnnouncer`。**
     * 兩件事分開，因為時機是幾何問題、措辭是語言問題。
     *
     * 距離只有幾個級距（[Announcement.distanceBucketMeters] 給的），
     * 所以整個句子集合是有限的，可以全部預先合成 —— 那正是不講路名換來的好處。
     */
    fun maneuver(angleDegrees: Float, distanceBucketMeters: Int?): String? {
        if (abs(angleDegrees) < MIN_ANNOUNCED_ANGLE) return null
        val direction = if (angleDegrees < 0) "左轉" else "右轉"
        return if (distanceBucketMeters == null) "這裡$direction"
        else "前方 $distanceBucketMeters 公尺，$direction"
    }

    /** 小於這個角度的轉向不播報。 */
    const val MIN_ANNOUNCED_ANGLE = 20f

    fun prohibitedRoad(roadName: String?): String =
        if (roadName.isNullOrBlank()) "這條路禁行機車，請於下個路口改道"
        else "$roadName 禁行機車，請於下個路口改道"
}
