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
        TurnRule.NO_LEFT_TURN -> "前方路口禁止左轉"
        TurnRule.UNKNOWN -> "前方路口即將左轉，請依現場標誌指示行駛"
    }

    /** 台灣平面道路實際會出現的速限。超出這個集合的退回即時合成。 */
    val SPEED_LIMITS = listOf(30, 40, 50, 60, 70, 80)

    /** 一句要預先合成的固定句子。key 同時是快取檔名的一部分。 */
    data class Phrase(val key: String, val text: String)

    /**
     * 全部要預先合成的固定句子。
     *
     * **收進來的條件是「句子固定」，不是「重要」。** 帶路名的 20 秒主指示進不來
     * （路名 5,279 個，句子無限），但它有 20 秒可以吸收即時合成的 2.8–3.6 秒延遲。
     *
     * **一句話一個檔，永遠不拼接。** 會讓語音走鐘的是拼接 ——「前方」+「300」+
     * 「公尺」+「左轉」四段各有各的語調，接起來像四個人輪流講話。
     * 整句合成的語調由引擎自己算，與即時唸出來的完全一樣。
     *
     * **新增句子不必動 VERSION**（檔名帶 key，舊句沒改就仍然有效）。
     * VERSION 是給「改了既有文案」用的 —— 不動它的症狀是「講的跟畫面上寫的不一樣」。
     */
    val all: List<Phrase> = buildList {
        TurnRule.entries.forEach { add(Phrase("rule_" + it.id, of(it))) }
        // 轉向確認短句。**唯一有硬期限的一則**，五秒吸收不了即時合成的延遲。
        add(Phrase("confirm_left", "這裡左轉"))
        add(Phrase("confirm_right", "這裡右轉"))
        // 測速。速限值有限，所以預合成得了 —— 先前判斷「不值得」是在還沒決定
        // 全部預合成的時候。順帶解掉一個實際問題：測速走即時、待轉走音檔，
        // 兩者響度可能差一截，而騎士會以為「有些警示比較小聲」。
        add(Phrase("cam_none", speedCamera(null, false)))
        SPEED_LIMITS.forEach { limit ->
            add(Phrase("cam_" + limit, speedCamera(limit, false)))
            add(Phrase("cam_" + limit + "_over", speedCamera(limit, true)))
        }
    }

    fun keyFor(rule: TurnRule): String = "rule_" + rule.id

    fun keyForConfirm(angleDegrees: Float): String =
        if (angleDegrees < 0) "confirm_left" else "confirm_right"

    fun keyForCamera(limitKmh: Int?, overSpeed: Boolean): String? = when {
        limitKmh == null -> if (overSpeed) null else "cam_none"
        limitKmh !in SPEED_LIMITS -> null
        overSpeed -> "cam_" + limitKmh + "_over"
        else -> "cam_" + limitKmh
    }

    fun cacheName(phrase: Phrase): String = "alert_v${VERSION}_${phrase.key}.wav"

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
    fun maneuver(angleDegrees: Float, distanceBucketMeters: Int?, roadName: String? = null): String? {
        if (abs(angleDegrees) < MIN_ANNOUNCED_ANGLE) return null
        val direction = if (angleDegrees < 0) "左轉" else "右轉"
        // 五秒前那一則**永遠不帶路名也不帶距離**：那時候騎士要看的是路口不是聽字，
        // 而且它是唯一有硬期限的一則 —— 固定句子才能預先合成，
        // 即時合成的首句延遲實測 2.8–3.6 秒，五秒吸收不了。
        if (distanceBucketMeters == null) return "這裡$direction"
        // 二十秒那一則有時間，所以帶路名 —— 它要回答的是「是哪個路口」。
        return if (roadName.isNullOrBlank()) "前方 $distanceBucketMeters 公尺，$direction"
        else "前方 $distanceBucketMeters 公尺，$roadName$direction"
    }

    /** 小於這個角度的轉向不播報。 */
    const val MIN_ANNOUNCED_ANGLE = 20f

    fun prohibitedRoad(roadName: String?): String =
        if (roadName.isNullOrBlank()) "這條路禁行機車，請於下個路口改道"
        else "$roadName 禁行機車，請於下個路口改道"
}
