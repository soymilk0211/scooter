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
    DERIVED(5);    // 由行政區預設規則推導，非個別建檔

    companion object {
        fun fromId(id: Int): RuleStatus = entries.firstOrNull { it.id == id } ?: PENDING
    }
}
