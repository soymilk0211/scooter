package tw.scooter.rules

/**
 * 把這台裝置自己的回報，變成立刻會播報的規則。
 *
 * ## 為什麼自己的回報要立刻生效
 *
 * 發布閘門管的是「回報 → **別人的**裝置」（CONTEXT.md），不是「回報 → 自己的
 * 裝置」。騎士剛剛看著那面牌子按下按鈕，對那個路口而言他手上就是最好的資訊 ——
 * 要他等共識門檻才聽得到自己的更正，說不過去。
 *
 * 這同時是冷啟動唯一有效的誘因：沒有這一條，前一千筆回報按下去像丟進一口井，
 * 而那正好會流失掉最早期、最需要留住的那批人。
 *
 * ## 兩個容易做錯的地方
 *
 * **一、回報的座標是停止線，規則的座標是路口中心。** 騎士只有停下來才按得到
 * 按鈕（CONTEXT.md 的「回報」），所以他的 GPS 位置就是停止線，而規則表存的是
 * 路口中心。差距是一個退距（十幾到二十幾公尺）。不換算的話，同一個路口會出現
 * 兩筆座標差二十公尺的規則，而且播報距離會系統性地偏一個退距。
 *
 * **二、同一個路口同一個來向只能留一筆。** 自己的回報要**取代**官方或推導出來
 * 的那一筆，不是疊上去 —— 兩筆都留會讓 RuleMatcher 挑到其中一筆，而挑哪一筆
 * 取決於距離的小數點，那是隨機的。
 */
object SelfReports {

    /**
     * 判定「同一個路口同一個來向」的距離上限。
     *
     * 比停止線退距（15 公尺）寬得多，因為要吸收的不只是退距：大路口本身有寬度、
     * 官方座標是 OSM 節點群聚的形心、GPS 停等時還有幾公尺誤差。取 45 公尺，
     * 與自建圖磚注入時比對路口節點用的容忍量一致（pipeline/tiles）。
     *
     * 寬一點的代價是可能吃掉隔壁很近的另一個路口；窄一點的代價是同一個路口
     * 留下兩筆互相矛盾的規則。**後者比較糟** —— 它的症狀是「同一個路口有時候
     * 播待轉、有時候播直接左轉」，而那會讓騎士不再相信任何一則播報。
     */
    const val SAME_JUNCTION_METERS = 45.0

    /**
     * 把一筆回報的位置換算成路口中心：沿著進入方位角往前推一個退距。
     *
     * 這是 CONTEXT.md 那句話的程式版本 —— 「路口中心是停止線沿進入方位角
     * 再往前一個退距」。
     */
    fun junctionCentre(reportedAt: LatLon, approachBearing: Double): LatLon =
        destination(reportedAt, approachBearing, AlertThresholds.STOP_LINE_SETBACK_METERS)

    /**
     * 合併已發布的規則與本機自己的回報。自己的回報優先。
     *
     * @param published 來自 rules 表的規則（官方、社群通過、行政區推導）
     * @param own 這台裝置自己的回報，座標**已經**是路口中心
     */
    fun merge(published: List<IntersectionRule>, own: List<IntersectionRule>): List<IntersectionRule> {
        if (own.isEmpty()) return published
        return published.filterNot { isSupersededBy(it, own) } + own
    }

    /**
     * [candidate] 是否已經被 [existing] 裡的某一筆蓋掉（同一路口、同一來向）。
     *
     * 同一個路口同一個來向按了兩次以上時，用它只留最新的那筆 —— 第二次按通常是
     * 在更正第一次，不是在投第二票。本機沒有共識這回事，共識是後端的工作。
     */
    fun isSupersededBy(candidate: IntersectionRule, existing: List<IntersectionRule>): Boolean =
        existing.any { it.sameApproachAs(candidate) }

    /**
     * 同一個路口、同一個來向嗎。
     *
     * 方位角的容忍量沿用 [AlertThresholds.MAX_BEARING_DELTA_DEGREES] —— 那是
     * 「這條規則適用於我現在的行進方向」用的同一把尺，兩處用同一個值才不會出現
     * 「配得上播報、卻配不上取代」的縫。
     */
    private fun IntersectionRule.sameApproachAs(other: IntersectionRule): Boolean =
        haversineMeters(location, other.location) <= SAME_JUNCTION_METERS &&
            bearingDelta(approachBearing, other.approachBearing) <=
            AlertThresholds.MAX_BEARING_DELTA_DEGREES
}
