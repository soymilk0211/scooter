package tw.scooter.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import tw.scooter.rules.DaySet
import tw.scooter.rules.EffectivePeriod
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 結構尚未有第二版。遷移腳本必須逐版累加，不得重建資料表 ——
        // observations 內可能存有尚未上傳的回報。
        throw IllegalStateException("no migration from $oldVersion to $newVersion")
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
