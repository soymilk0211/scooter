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
}
