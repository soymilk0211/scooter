package tw.scooter.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import tw.scooter.rules.DaySet
import tw.scooter.rules.EffectivePeriod
import tw.scooter.rules.EnforcementKind
import tw.scooter.rules.EnforcementPoint
import tw.scooter.rules.Grid
import tw.scooter.rules.IntersectionRule
import tw.scooter.rules.LatLon
import tw.scooter.rules.RuleStatus
import tw.scooter.rules.TurnRule

class ScooterDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context,
    Schema.DATABASE_NAME,
    null,
    Schema.SCHEMA_VERSION,
) {

    companion object {
        /**
         * 開啟資料庫，必要時先安裝種子檔。
         *
         * 一律走這個入口而非直接建構 —— 種子檔必須在 SQLiteOpenHelper 第一次
         * 開檔之前就位，否則它會建出一個空的資料庫，使用者將看不到任何官方資料。
         */
        fun open(context: Context): ScooterDatabase {
            SeedInstaller.ensureInstalled(context)
            return ScooterDatabase(context.applicationContext)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        Schema.CREATE.forEach(db::execSQL)
        db.insert("meta", null, ContentValues().apply {
            put("key", Schema.MetaKey.SCHEMA_VERSION)
            put("value", Schema.SCHEMA_VERSION.toString())
        })
        db.insert("meta", null, ContentValues().apply {
            put("key", Schema.MetaKey.DATA_VERSION)
            put("value", "0")
        })
    }

    /**
     * 逐版套用遷移腳本。
     *
     * 一次跑完中間每一版，而不是寫「從 1 直接到 3」的捷徑 —— 捷徑的數量是版本數
     * 的平方，而且只有跳過那些版本的裝置會踩到，測試最不容易涵蓋。
     *
     * 缺腳本就丟例外。安靜地放行會留下一個結構對不上的資料庫，症狀是之後某次
     * 查詢突然找不到欄位，那時已經離現場很遠了。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (version in oldVersion until newVersion) {
            val statements = Schema.MIGRATIONS[version]
                ?: throw IllegalStateException("no migration from $version to ${version + 1}")
            statements.forEach(db::execSQL)
        }
        db.execSQL("UPDATE meta SET value = ? WHERE key = ?",
            arrayOf(newVersion.toString(), Schema.MetaKey.SCHEMA_VERSION))
    }

    /**
     * 取出可能與騎士當前位置相關的例外規則。
     *
     * 只做粗篩：網格涵蓋範圍大於實際半徑，精確的距離與方位角比對交由
     * core-rules 的 RuleMatcher 處理，那裡沒有 Android 依賴、可完整單元測試。
     */
    fun rulesNear(lat: Double, lon: Double, radiusMeters: Double): List<IntersectionRule> {
        val cells = Grid.cellsWithin(lat, lon, radiusMeters)
        if (cells.isEmpty()) return emptyList()
        val placeholders = cells.joinToString(",") { "?" }
        val args = cells.map { it.toString() }.toTypedArray()

        readableDatabase.rawQuery(
            "SELECT id, lat, lon, approach_bearing, exit_bearing, turn_rule, status, " +
                "confidence, entry_road_name, exit_road_name, " +
                "period_days, period_start_min, period_end_min " +
                "FROM rules WHERE cell IN ($placeholders)",
            args,
        ).use { c ->
            val out = ArrayList<IntersectionRule>(c.count)
            while (c.moveToNext()) {
                out += IntersectionRule(
                    id = c.getLong(0),
                    location = LatLon(c.getDouble(1), c.getDouble(2)),
                    approachBearing = c.getDouble(3),
                    exitBearing = if (c.isNull(4)) null else c.getDouble(4),
                    rule = TurnRule.fromId(c.getInt(5)),
                    status = RuleStatus.fromId(c.getInt(6)),
                    confidence = c.getInt(7),
                    entryRoadName = c.getString(8),
                    exitRoadName = c.getString(9),
                    effectivePeriod = readPeriod(c.isNull(10), c.getInt(10), c.getInt(11), c.getInt(12)),
                )
            }
            return out
        }
    }

    /**
     * 取出可能相關的執法點。粗篩方式與 [rulesNear] 相同，精確比對留給 core-rules。
     *
     * 半徑要用測速的**彈性時窗**上緣（500 公尺），比路口規則的 300 公尺遠 ——
     * 用同一個半徑查會讓測速警示永遠等到路口窗裡才出現，正好是要避免的事。
     */
    fun enforcementNear(lat: Double, lon: Double, radiusMeters: Double): List<EnforcementPoint> {
        val cells = Grid.cellsWithin(lat, lon, radiusMeters)
        if (cells.isEmpty()) return emptyList()
        val placeholders = cells.joinToString(",") { "?" }
        val args = cells.map { it.toString() }.toTypedArray()

        readableDatabase.rawQuery(
            "SELECT id, lat, lon, bearing, kind, speed_limit, description " +
                "FROM enforcement_points WHERE cell IN ($placeholders)",
            args,
        ).use { c ->
            val out = ArrayList<EnforcementPoint>(c.count)
            while (c.moveToNext()) {
                out += EnforcementPoint(
                    id = c.getLong(0),
                    location = LatLon(c.getDouble(1), c.getDouble(2)),
                    bearing = if (c.isNull(3)) null else c.getDouble(3),
                    kind = EnforcementKind.fromId(c.getInt(4)),
                    speedLimitKmh = if (c.isNull(5)) null else c.getInt(5),
                    description = c.getString(6),
                )
            }
            return out
        }
    }

    fun insertObservation(
        lat: Double,
        lon: Double,
        approachBearing: Double,
        exitBearing: Double?,
        observedRule: TurnRule,
        kind: Int,
        disputedRuleId: Long? = null,
        observedAt: Long = System.currentTimeMillis(),
    ): Long = writableDatabase.insert("observations", null, ContentValues().apply {
        put("lat", lat)
        put("lon", lon)
        put("approach_bearing", approachBearing)
        exitBearing?.let { put("exit_bearing", it) }
        put("observed_rule", observedRule.id)
        put("kind", kind)
        disputedRuleId?.let { put("disputed_rule_id", it) }
        put("observed_at", observedAt)
    })

    fun dataVersion(): Long =
        readableDatabase.rawQuery(
            "SELECT value FROM meta WHERE key = ?",
            arrayOf(Schema.MetaKey.DATA_VERSION),
        ).use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L }

    private fun readPeriod(isNull: Boolean, days: Int, start: Int, end: Int): EffectivePeriod? {
        if (isNull) return null
        val daySet = when (days) {
            1 -> DaySet.WEEKDAY
            2 -> DaySet.WEEKEND
            else -> DaySet.ALL
        }
        return EffectivePeriod(daySet, start, end)
    }
}
