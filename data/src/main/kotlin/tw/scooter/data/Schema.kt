package tw.scooter.data

/**
 * 本機資料庫結構。
 *
 * 設計依據：
 * - [ADR-0001] 規則身分為（座標, 進入方位角）；路名僅供顯示，不參與比對。
 * - [ADR-0004] 儲存的是「例外」；多數路口的規則由 default_rules 推導。
 * - [ADR-0005] observations 同時容納被動觀察與主動回報，兩者權重不同。
 *
 * 空間索引使用量化網格欄位 `cell` 而非 R-tree —— R-tree 並非所有 Android
 * 內建 SQLite 版本都啟用，而全台數千筆例外用網格索引已足夠。
 *
 * rules 有兩個獨立的時間欄位，不要混淆：
 * - `effective_since` 是規則**開始生效的西元年**，用來衰減老舊資料的信心。
 *   台北市 114 筆例外中有 42 筆生效於 2009 年以前，那些路口很可能已改建。
 * - `period_*` 是規則**每天何時適用**（如平日 07–09）。目前無資料來源填入，
 *   保留是因為它與生效年份是不同維度的概念。
 */
object Schema {

    const val DATABASE_NAME = "scooter.db"

    /** 結構版本。與 meta 表中的 data_version（資料版本）是不同的東西。 */
    const val SCHEMA_VERSION = 3

    val CREATE: List<String> = listOf(
        """
        CREATE TABLE meta (
            key   TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """.trimIndent(),

        // 例外規則。多數路口不在此表中。
        """
        CREATE TABLE rules (
            id                INTEGER PRIMARY KEY,
            lat               REAL    NOT NULL,
            lon               REAL    NOT NULL,
            cell              INTEGER NOT NULL,
            approach_bearing  REAL    NOT NULL,
            exit_bearing      REAL,
            turn_rule         INTEGER NOT NULL,
            status            INTEGER NOT NULL,
            confidence        INTEGER NOT NULL DEFAULT 100,
            entry_road_name   TEXT,
            exit_road_name    TEXT,
            period_days       INTEGER,
            period_start_min  INTEGER,
            period_end_min    INTEGER,
            effective_since   INTEGER,
            region_code       TEXT,
            -- 非 NULL 代表這條規則被降級為中性播報，值是降級原因。
            -- 例：該方向左轉的離開路是機車禁行的地下道，原本的「直接左轉」
            -- 照播會害騎士轉進不能走的路。降級而非刪除 —— 這種路口通常更複雜、
            -- 更需要提醒，刪掉等於把最需要幫助的地方變成空白。
            downgrade_reason  TEXT,
            -- 實地查核日期。非 NULL 代表有人到現場看過牌子，該筆凌駕一切自動推導。
            verified_on       TEXT,
            updated_at        INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX idx_rules_cell ON rules(cell)",

        // 各行政區的預設規則，依車道數推導。見 ADR-0004。
        """
        CREATE TABLE default_rules (
            id          INTEGER PRIMARY KEY,
            region_code TEXT    NOT NULL,
            min_lanes   INTEGER NOT NULL,
            max_lanes   INTEGER NOT NULL,
            turn_rule   INTEGER NOT NULL,
            confidence  INTEGER NOT NULL DEFAULT 60,
            updated_at  INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX idx_default_rules_region ON default_rules(region_code)",

        // 固定測速與科技執法。流動桿刻意不做。
        """
        CREATE TABLE enforcement_points (
            id          INTEGER PRIMARY KEY,
            lat         REAL    NOT NULL,
            lon         REAL    NOT NULL,
            cell        INTEGER NOT NULL,
            bearing     REAL,
            kind        INTEGER NOT NULL,
            speed_limit INTEGER,
            description TEXT,
            updated_at  INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX idx_enforcement_cell ON enforcement_points(cell)",

        // 區間測速。**這一版刻意留空** —— 位子先留好，實作延後（使用者明說上線前
        // 一定要加回去）。
        //
        // 它不能塞進 enforcement_points，因為區間測速是**狀態**不是接近事件：
        // 騎士進入區間之後，接下來好幾公里的平均速度都在被計算。用點模型表達，
        // 警示只會在起點響一次，然後在真正該提醒的那幾公里裡完全沉默。
        // 所以這張表存的是頭尾兩點，兩端都建索引 —— 進入與離開都要偵測得到。
        """
        CREATE TABLE enforcement_sections (
            id          INTEGER PRIMARY KEY,
            start_lat   REAL    NOT NULL,
            start_lon   REAL    NOT NULL,
            start_cell  INTEGER NOT NULL,
            end_lat     REAL    NOT NULL,
            end_lon     REAL    NOT NULL,
            end_cell    INTEGER NOT NULL,
            -- 行進方位角。NULL 代表雙向都取締。
            bearing     REAL,
            length_m    INTEGER,
            speed_limit INTEGER,
            description TEXT,
            updated_at  INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX idx_sections_start ON enforcement_sections(start_cell)",
        "CREATE INDEX idx_sections_end ON enforcement_sections(end_cell)",

        // 全面禁行機車的路段。**這是路線層的資料** —— 這條路機車完全不能走，
        // 與「內側車道禁行機車」是兩回事，後者本專案不收集（ADR-0011）。
        // 來源是臺北市交通局的開放資料，全市 5 筆，其中 4 筆解得出座標。
        //
        // 存折線而不是起訖兩點：堤頂大道沿線比直線長 20%，兩點連線在中段會偏出去
        // 一百多公尺，而誤報的內容是「你正走在禁行機車的路上」—— 那是最不能
        // 亂講的一句話。格式是 `lat,lon;lat,lon;…`，因為 SQLite 沒有幾何型別，
        // 而為了四筆資料引進空間擴充不划算。
        //
        // way_ids **不是**事後驗證用的鍵。BRouter 不帶 OSM way 編號（rd5 是重新
        // 編碼過的圖），所以 ADR-0006 的事後驗證只能用幾何 —— 也就是
        // ProhibitedMatcher 的點到折線距離。這個欄位留著是為了將來自建 rd5
        // 圖磚時，拿它當注入自訂標籤的鍵。
        """
        CREATE TABLE prohibited_segments (
            id          INTEGER PRIMARY KEY,
            road_name   TEXT    NOT NULL,
            -- 面向。禁行是單向登錄的，忠孝西路兩個方向是兩筆。
            bearing     REAL    NOT NULL,
            polyline    TEXT    NOT NULL,
            way_ids     TEXT,
            speed_limit INTEGER,
            reason      TEXT,
            updated_at  INTEGER NOT NULL
        )
        """.trimIndent(),

        // 路段橫跨多個網格（一格約 1.1 公里，環河北路有 4.5 公里），所以不能像
        // 點位那樣在主表放一個 cell 欄位 —— 那會讓騎在中段的人查不到，
        // 而症狀是「這條路有時候會警告、有時候不會」，比完全不做更難查。
        """
        CREATE TABLE prohibited_cells (
            cell       INTEGER NOT NULL,
            segment_id INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX idx_prohibited_cells ON prohibited_cells(cell)",

        // 本機待上傳的觀察與回報。上傳後保留一段時間供除錯，再由清理工作刪除。
        """
        CREATE TABLE observations (
            id               INTEGER PRIMARY KEY AUTOINCREMENT,
            lat              REAL    NOT NULL,
            lon              REAL    NOT NULL,
            approach_bearing REAL    NOT NULL,
            exit_bearing     REAL,
            observed_rule    INTEGER NOT NULL,
            kind             INTEGER NOT NULL,
            disputed_rule_id INTEGER,
            observed_at      INTEGER NOT NULL,
            uploaded_at      INTEGER
        )
        """.trimIndent(),
        "CREATE INDEX idx_observations_pending ON observations(uploaded_at)",
    )

    /** observations.kind */
    object ObservationKind {
        /** 由行進軌跡被動推得，權重依行為種類不對稱。 */
        const val PASSIVE = 1

        /** 騎士主動按下回報按鈕的新增。 */
        const val REPORT = 2

        /** 騎士指出既有規則與現場不符。 */
        const val CORRECTION = 3
    }

    /** enforcement_points.kind */
    object EnforcementKind {
        const val FIXED_SPEED_CAMERA = 1
        const val SMART_ENFORCEMENT = 2
    }

    /**
     * 由舊版升級的腳本，索引即為「從第幾版升上來」。
     *
     * 必須逐版累加，不得重建資料表 —— observations 內可能存有尚未上傳的回報，
     * 而那些是騎士親手按下去的東西。
     */
    val MIGRATIONS: Map<Int, List<String>> = mapOf(
        // 1 -> 2：新增區間測速的表。純粹新增，既有資料不動。
        1 to CREATE.filter { it.contains("enforcement_sections") },
        // 2 -> 3：新增全面禁行機車的路段。同樣是純新增。
        //
        // **升級後這兩張表會是空的**，而且不會自己填起來 —— SeedInstaller 只在
        // 資料庫不存在時安裝種子（為了保護未上傳的回報）。既有裝置要等資料同步
        // 端點做出來才拿得到這四筆。這不是遺漏，是那條保護的必然代價；
        // 寫在這裡是因為「表建好了卻查不到東西」看起來像故障。
        2 to CREATE.filter { it.contains("prohibited_") },
    )

    object MetaKey {
        const val SCHEMA_VERSION = "schema_version"

        /** 資料版本，同步時據此向後端索取增量。 */
        const val DATA_VERSION = "data_version"
    }
}
