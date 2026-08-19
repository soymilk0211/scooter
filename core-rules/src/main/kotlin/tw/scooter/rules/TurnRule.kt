package tw.scooter.rules

/**
 * 左轉規定。序數值即為資料庫中的儲存值，變更會破壞既有資料與 diff 相容性。
 *
 * [UNKNOWN] 不是一種規定，而是「查無資料」。它存在於此是因為警示引擎必須能表達
 * 「我不知道」並退回中性播報 —— 猜測比沉默危險。
 */
enum class TurnRule(val id: Int) {
    HOOK(1),          // 待轉
    DIRECT(2),        // 直接左轉
    INNER_LANE(3),    // 內側左轉專用道
    OUTER_LANE(4),    // 外側左轉專用道

    /**
     * 禁止左轉。**與上面四個性質不同，別把它當成第五種左轉方式。**
     *
     * 待轉與直接左轉改的是「怎麼轉」，播錯只是慢一點或吃一張單；
     * 禁止左轉改的是「能不能轉」，它屬於**路線層**（ADR-0012）——
     * 一筆錯誤的禁止左轉會讓所有人在那個路口繞遠路，**而且不會有人抱怨**，
     * 因為繞遠的路線看起來仍然合法，騎士無從得知它為什麼繞。
     *
     * 所以它的回報門檻與其他四個不同：**一律進審核佇列，不論回報者的正確率**
     * （ADR-0013）。這是目前唯一一個不因信用分數而放行的例外。
     */
    NO_LEFT_TURN(5),

    UNKNOWN(0);

    companion object {
        fun fromId(id: Int): TurnRule = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** 規則的來源與驗證狀態，決定播報語氣與是否可被糾錯推翻。 */
enum class RuleStatus(val id: Int) {
    OFFICIAL(1),   // 政府開放資料
    VERIFIED(2),   // 社群多數決通過
    PENDING(3),    // 已回報但未達門檻
    DISPUTED(4),   // 遭糾錯，暫不信任
    DERIVED(5),    // 由行政區預設規則推導，非個別建檔

    /**
     * 這台裝置自己回報的，還沒經過發布閘門。
     *
     * **它只在回報者本人的裝置上存在**，不會下發給任何人 —— 發布閘門管的是
     * 「回報 → 別人的裝置」，不是「回報 → 自己的裝置」（見 CONTEXT.md）。
     *
     * 為什麼自己的回報要立刻生效：騎士剛剛看著那面牌子按下按鈕，對那個路口
     * 而言他手上就是最好的資訊，要他等共識才聽得到自己的更正是說不過去的。
     * 而且這是冷啟動唯一有效的誘因 —— 沒有這一條，前一千筆回報按下去像丟進
     * 一口井，而那正好會流失掉最早期、最需要留住的那批人。
     */
    SELF_REPORTED(6);

    companion object {
        fun fromId(id: Int): RuleStatus = entries.firstOrNull { it.id == id } ?: PENDING
    }
}
