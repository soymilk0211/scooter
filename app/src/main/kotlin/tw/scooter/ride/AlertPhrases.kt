package tw.scooter.ride

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
}
