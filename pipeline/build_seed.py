"""把解析與編碼的結果組成種子 SQLite，供 App 內附。

只有地理編碼狀態為 ok 的規則會進資料庫。suspect 與 failed 一律寫進
review_coords.csv 等人工補座標 —— 自動編碼錯誤產生的規則會在錯誤的路口
播放錯誤的指示，那比沒有資料危險（ADR-0001）。

用法：
    python build_seed.py
"""

from __future__ import annotations

import csv
import json
import math
import pathlib
import re
import sqlite3
import sys

import default_rules
import taiwan

BUILD = pathlib.Path(__file__).parent / "build"
SEED = BUILD / "scooter_seed.db"

# 必須與 data/src/main/kotlin/tw/scooter/data/Schema.kt 保持一致。
# 兩邊都改到才算改完 —— 結構不一致時 App 會在讀取種子檔時炸開。
SCHEMA_VERSION = 3
CELL_DEGREES = 0.01  # 與 core-rules 的 Grid.CELL_DEGREES 相同

CREATE = [
    "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    """CREATE TABLE rules (
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
        downgrade_reason  TEXT,
        verified_on       TEXT,
        updated_at        INTEGER NOT NULL
    )""",
    "CREATE INDEX idx_rules_cell ON rules(cell)",
    """CREATE TABLE default_rules (
        id          INTEGER PRIMARY KEY,
        region_code TEXT    NOT NULL,
        min_lanes   INTEGER NOT NULL,
        max_lanes   INTEGER NOT NULL,
        turn_rule   INTEGER NOT NULL,
        confidence  INTEGER NOT NULL DEFAULT 60,
        updated_at  INTEGER NOT NULL
    )""",
    "CREATE INDEX idx_default_rules_region ON default_rules(region_code)",
    """CREATE TABLE enforcement_points (
        id          INTEGER PRIMARY KEY,
        lat         REAL    NOT NULL,
        lon         REAL    NOT NULL,
        cell        INTEGER NOT NULL,
        bearing     REAL,
        kind        INTEGER NOT NULL,
        speed_limit INTEGER,
        description TEXT,
        updated_at  INTEGER NOT NULL
    )""",
    "CREATE INDEX idx_enforcement_cell ON enforcement_points(cell)",
    # 區間測速。**這一版刻意留空**，位子先留好（見 Schema.kt 的同一段註解）：
    # 區間測速是狀態不是接近事件，用點模型只會在起點響一次然後沉默。
    # 原始資料已由 import_enforcement.py 存進 enforcement_sections_raw.json。
    """CREATE TABLE enforcement_sections (
        id          INTEGER PRIMARY KEY,
        start_lat   REAL    NOT NULL,
        start_lon   REAL    NOT NULL,
        start_cell  INTEGER NOT NULL,
        end_lat     REAL    NOT NULL,
        end_lon     REAL    NOT NULL,
        end_cell    INTEGER NOT NULL,
        bearing     REAL,
        length_m    INTEGER,
        speed_limit INTEGER,
        description TEXT,
        updated_at  INTEGER NOT NULL
    )""",
    "CREATE INDEX idx_sections_start ON enforcement_sections(start_cell)",
    "CREATE INDEX idx_sections_end ON enforcement_sections(end_cell)",
    # 全面禁行機車的路段（geocode_prohibited.py）。**這是路線層的資料**：
    # 這條路機車完全不能走，與「內側車道禁行」是兩回事。
    #
    # 存折線而不是起訖兩點：堤頂大道沿線比直線長 20%，兩點連線在中段會偏出去
    # 一百多公尺，而誤報的內容是「你正走在禁行機車的路上」。
    # 同時存 way_ids —— 折線給「騎士現在在哪」用，way 編號給路線引擎事後
    # 驗證用（ADR-0006 比對的是路網的邊）。
    """CREATE TABLE prohibited_segments (
        id          INTEGER PRIMARY KEY,
        road_name   TEXT    NOT NULL,
        bearing     REAL    NOT NULL,
        polyline    TEXT    NOT NULL,
        way_ids     TEXT,
        speed_limit INTEGER,
        reason      TEXT,
        updated_at  INTEGER NOT NULL
    )""",
    # 路段橫跨多個網格（一格約 1.1 公里，環河北路有 4.5 公里），所以不能像點位
    # 那樣在主表放一個 cell 欄位 —— 那會讓騎在中段的人查不到。改用一張對照表，
    # 把折線經過的每一格都登記進去。
    """CREATE TABLE prohibited_cells (
        cell       INTEGER NOT NULL,
        segment_id INTEGER NOT NULL
    )""",
    "CREATE INDEX idx_prohibited_cells ON prohibited_cells(cell)",
    """CREATE TABLE observations (
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
    )""",
    "CREATE INDEX idx_observations_pending ON observations(uploaded_at)",
]

STATUS_OFFICIAL = 1
TODAY_YEAR = 2026

TURN_UNKNOWN = default_rules.TURN_UNKNOWN
TURN_HOOK = default_rules.TURN_HOOK
TURN_DIRECT = default_rules.TURN_DIRECT

# 給人看的 CSV 用。複查清單是拿去騎車的，寫「1」不寫「待轉」等於逼人邊騎邊查表。
TURN_LABEL = {0: "中性播報", 1: "待轉", 2: "直接左轉", 3: "內側專用道", 4: "外側專用道"}

# 預設左轉規則搬到 default_rules.py（ADR-0004、ADR-0009）。那裡以縣市碼為鍵，
# 並把「法條」與「某個縣市的行政實務」分開 —— 原本寫在這裡的「三車道以上待轉」
# 是臺北市交通局的實務，不是 §99 的推導，而放在這裡看起來像後者。



def cell_of(lat: float, lon: float) -> int:
    """必須與 core-rules 的 Grid.cellOf 位元對位元一致。"""
    return int(math.floor(lat / CELL_DEGREES)) * 100_000 + int(math.floor(lon / CELL_DEGREES))


def confidence_for(effective_since: int | None) -> int:
    """越舊的規則信心越低 —— 官方資料也會過期（ADR-0004）。

    2009 年以前生效的路口有 42 個，十七年足以讓一個路口改建兩次。
    """
    if effective_since is None:
        return 70
    age = max(0, TODAY_YEAR - effective_since)
    return max(55, 100 - age * 2)


ARABIC_TO_CHINESE = {"1": "一", "2": "二", "3": "三", "4": "四", "5": "五",
                     "6": "六", "7": "七", "8": "八", "9": "九"}


def same_road(a: str | None, b: str | None) -> bool:
    """兩個路名是否指同一條路（忽略段號寫法差異）。

    巷與弄**不算**同一條路，即使名稱前綴相同 —— 「康寧路三段189巷」不是「康寧路三段」。
    這一點沒擋住的話，幾何會把幹道的路名換成它某條巷子的名字。
    """
    if not a or not b:
        return False
    norm = lambda s: re.sub(r"(\d)(?=段)", lambda m: ARABIC_TO_CHINESE[m.group(1)], s)
    na, nb = norm(a), norm(b)
    for marker in ("巷", "弄"):
        if (marker in na) != (marker in nb):
            return False
    strip = lambda s: re.sub(r"[0-9一二三四五六七八九十]+段", "", s)
    return na == nb or strip(na) == strip(nb) or na.startswith(nb) or nb.startswith(na)


def real_bearings(junction: dict, rule: dict) -> tuple[float, float | None]:
    """用路網實際走向取代官方資料量化過的方位角。

    官方 CSV 只寫東西南北，但塔悠路、基隆路這類斜向道路的真實走向可能是 15° 或
    340°。硬套 0°／90° 會讓警示引擎的 ±30° 閘門在邊緣情況失準 —— 一條走向 40°
    的路被記成 90°，騎士的實際行進方向與規則差 50°，永遠不會命中。

    進入方位角取「來向那條臂」的反向：騎士是從那條路過來的，行進方向與該臂
    由路口向外的方向相反。
    """
    quantised_approach = rule["approach_bearing"]
    quantised_exit = rule["exit_bearing"]
    arm_bearings = junction.get("arm_bearings") or {}

    came_from = (quantised_approach + 180.0) % 360.0
    behind = arm_bearings.get(f"{came_from:.0f}")
    approach = (behind + 180.0) % 360.0 if behind is not None else quantised_approach

    exit_bearing = quantised_exit
    if quantised_exit is not None:
        actual = arm_bearings.get(f"{quantised_exit:.0f}")
        if actual is not None:
            exit_bearing = actual

    return round(approach, 1), (round(exit_bearing, 1) if exit_bearing is not None else None)


def road_names(junction: dict, rule: dict) -> tuple[str | None, str | None]:
    """路口顯示用的（進入路名, 離開路名）。

    原始路名是預設值，**只有當它在該方位上根本不存在時才用幾何補**。這樣做是因為
    原始資料把路口命名為「A與B」，雙向規則展開成兩列時兩列都繼承同一組路名 ——
    但 B 常常只在路口的一側。興隆路三段與萬芳路即是：面向南左轉進萬芳路沒錯，
    面向北左轉進的卻是興隆路三段192巷8弄，CSV 完全沒提到那條路。

    反過來若無條件採用幾何結果，會挑到匝道、巷弄、自行車道，把本來正確的路名改壞
    ——實測會有七成的規則被動到，其中不少是改錯的。僅供顯示，不參與比對（ADR-0001）。
    """
    csv_entry = junction.get("entry_road_name") or rule["road_a"]
    csv_exit = junction.get("exit_road_name") or rule["road_b"]

    # CSV 明講了轉向（如「北往東」）時，路口名稱裡的 B 就是東側那條，可信 ——
    # 不要動它。只有 exit_inferred 為真（雙向值或對向值，左轉方向是我們推導的）
    # 才可能繼承到不適用的路名。
    if not rule.get("exit_inferred"):
        return csv_entry, csv_exit

    arms = junction.get("arms") or {}
    found = arms.get(f"{rule['exit_bearing']:.0f}") if rule["exit_bearing"] is not None else None
    if found is None or same_road(found, csv_exit) or same_road(found, csv_entry):
        return csv_entry, csv_exit

    # 只在能證明 CSV 那條路位於**別的方位**時才覆寫。
    # 萬芳路出現在東側，就證明西側不可能是萬芳路 —— 這比用路名或路等級去猜可靠得多，
    # 而且天然只命中雙向規則繼承錯路名的那一半。
    elsewhere = any(
        same_road(name, csv_exit)
        for b, name in arms.items()
        if b != f"{rule['exit_bearing']:.0f}"
    )
    if not elsewhere:
        return csv_entry, csv_exit
    return csv_entry, found


FIELD_CHECKS = pathlib.Path(__file__).parent / "field_checks.json"


def load_field_checks() -> dict[tuple[str, float], dict]:
    """實地查核結果，以（路口原文, 面向角度）為鍵。

    這是權威資料，蓋過管線推導的結果。以面向角度而非路名為鍵，是因為路名正是
    最常出錯的欄位 —— 拿它當鍵會自我矛盾。
    """
    if not FIELD_CHECKS.exists():
        return {}
    data = json.loads(FIELD_CHECKS.read_text(encoding="utf-8"))
    out: dict[tuple[str, float], dict] = {}
    for check in data.get("checks", []):
        for rule in check.get("rules", []):
            out[(check["junction_text"], float(rule["approach_bearing"]))] = {
                **rule, "checked_on": check.get("checked_on"), "note": check.get("note"),
            }
    return out


def load_image_checks() -> dict[tuple[str, float], dict]:
    """影像判讀結果，鍵同上。**這些不會改動任何規則。**

    證據覆蓋順序是「實地查核 > 官方 > 影像查核」，所以一張街景照片說的話不足以
    蓋掉官方清冊。它能做的是**指出兩者不一致**，讓那個路口排進實地查核的優先序 ——
    街景判讀的天花板在資訊本身（現場常同時有左轉專用道箭頭與待轉標誌，照片分不出
    那條車道是給汽車還是機車），當矛盾偵測器才是它的正確用法。
    """
    if not FIELD_CHECKS.exists():
        return {}
    data = json.loads(FIELD_CHECKS.read_text(encoding="utf-8"))
    out: dict[tuple[str, float], dict] = {}
    for check in data.get("image_checks", []):
        for rule in check.get("rules", []):
            out[(check["junction_text"], float(rule["approach_bearing"]))] = {
                **rule,
                "checked_on": check.get("checked_on"),
                "note": check.get("note"),
                "evidence": check.get("evidence", {}),
            }
    return out


def main() -> int:
    rules = json.loads((BUILD / "rules_raw.json").read_text(encoding="utf-8"))
    junctions = json.loads((BUILD / "junctions.json").read_text(encoding="utf-8"))
    field = load_field_checks()
    image_checks = load_image_checks()
    image_agree, image_conflicts = 0, []

    SEED.unlink(missing_ok=True)
    db = sqlite3.connect(SEED)
    for stmt in CREATE:
        db.execute(stmt)
    db.execute("INSERT INTO meta VALUES (?, ?)", ("schema_version", str(SCHEMA_VERSION)))
    db.execute("INSERT INTO meta VALUES (?, ?)", ("data_version", "1"))
    # Android 的 SQLiteOpenHelper 是看 user_version pragma 判斷版本，不是看 meta 表。
    # 不設這個，App 開啟種子檔時會誤判成從 0 升級到 1 而拋例外。
    db.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")

    inserted, needs_review, downgraded, verified = 0, [], [], 0
    seen_review: set[str] = set()

    for r in rules:
        key = f"{r['road_a']}|{r['road_b']}|{r['region']}"
        j = junctions.get(key)
        if j is None or j.get("status") != "ok":
            if key not in seen_review:
                seen_review.add(key)
                needs_review.append({
                    "district": r["region"],
                    "junction_text": r["junction_text"],
                    "road_a": r["road_a"],
                    "road_b": r["road_b"],
                    "geocode_status": (j or {}).get("status", "missing"),
                    "clusters": (j or {}).get("cluster_count", 0),
                    "best_guess_lat": (j or {}).get("lat") or "",
                    "best_guess_lon": (j or {}).get("lon") or "",
                    "candidates": json.dumps((j or {}).get("candidates", []), ensure_ascii=False),
                })
            continue

        entry_name, exit_name = road_names(j, r)
        approach, exit_bearing = real_bearings(j, r)
        turn_rule, downgrade = r["turn_rule"], None

        # 實地查核優先於一切推導。
        checked = field.get((r["junction_text"], float(r["approach_bearing"])))
        if checked:
            if checked.get("exists") is False:
                continue  # 現場沒有這個左轉動線
            entry_name = checked.get("entry_road_name", entry_name)
            exit_name = checked.get("exit_road_name", exit_name)
            turn_rule = checked.get("turn_rule", turn_rule)
            verified += 1

        blocker = (j.get("restricted_arms") or {}).get(f"{r['exit_bearing']:.0f}") \
            if r["exit_bearing"] is not None and not checked else None
        if blocker:
            # 該方位沒有機車能走的路，只有地下道／高架／快速道路。照播原本的規定
            # 會叫騎士轉進不能走的路，降級為中性播報（TurnRule.UNKNOWN）。
            turn_rule, downgrade = TURN_UNKNOWN, f"離開方向為機車禁行路段：{blocker}"
            downgraded.append({"district": r["region"], "junction": r["junction_text"],
                               "bearing": r["exit_bearing"], "blocker": blocker})

        # 影像判讀只做比對，不動 turn_rule。不一致的進複查清單由人決定 ——
        # 讓一張照片自動改掉規則，等於把 29% 一致率的判讀變成權威資料。
        seen_in_image = image_checks.get((r["junction_text"], float(r["approach_bearing"])))
        if seen_in_image:
            saw = None if seen_in_image.get("exists") is False else seen_in_image.get("turn_rule")
            if saw == turn_rule:
                image_agree += 1
            else:
                image_conflicts.append({
                    "district": r["region"],
                    "junction_text": r["junction_text"],
                    "approach_bearing": f"{r['approach_bearing']:.0f}",
                    "entry_road_name": entry_name,
                    "exit_road_name": exit_name,
                    "lat": j["lat"],
                    "lon": j["lon"],
                    # 幾何實際方位角。複查清單要用它跟 rules 表對得起來 ——
                    # 同一個路口同一條進入道路會有兩個來向，只用座標配對會兩條都中。
                    "approach": approach,
                    "we_say": TURN_LABEL.get(turn_rule, "?"),
                    "image_says": "無左轉動線" if saw is None else TURN_LABEL.get(saw, "?"),
                    "captured_on": (seen_in_image.get("evidence") or {}).get("captured_on", ""),
                    "note": seen_in_image.get("note") or "",
                    "map": f"https://www.google.com/maps?q={j['lat']},{j['lon']}",
                })

        db.execute(
            "INSERT INTO rules (lat, lon, cell, approach_bearing, exit_bearing, turn_rule,"
            " status, confidence, entry_road_name, exit_road_name, effective_since,"
            " region_code, downgrade_reason, verified_on, updated_at)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                j["lat"], j["lon"], cell_of(j["lat"], j["lon"]),
                approach, exit_bearing, turn_rule,
                STATUS_OFFICIAL,
                100 if checked else confidence_for(r["effective_since"]),
                entry_name, exit_name,
                r["effective_since"], r["region_code"], downgrade,
                (checked or {}).get("checked_on"),
                0,
            ),
        )
        inserted += 1

    # 實地查核新增的規則。官方清冊會漏、也會把動線寫錯（水源路與泉州街那筆寫的
    # 動線根本不存在），所以查核必須能新增而不只是修改既有列。
    added = 0
    for (junction_text, bearing), checked in field.items():
        if not checked.get("add"):
            continue
        origin = next((r for r in rules if r["junction_text"] == junction_text), None)
        j = junctions.get(
            f"{origin['road_a']}|{origin['road_b']}|{origin['region']}") if origin else None
        if not j or j.get("lat") is None:
            print(f"  略過新增（找不到座標）：{junction_text} 面向 {bearing:.0f}°")
            continue
        db.execute(
            "INSERT INTO rules (lat, lon, cell, approach_bearing, exit_bearing, turn_rule,"
            " status, confidence, entry_road_name, exit_road_name, effective_since,"
            " region_code, downgrade_reason, verified_on, updated_at)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                j["lat"], j["lon"], cell_of(j["lat"], j["lon"]),
                bearing, checked.get("exit_bearing"), checked.get("turn_rule", 0),
                STATUS_OFFICIAL, 100,
                checked.get("entry_road_name"), checked.get("exit_road_name"),
                origin.get("effective_since"), origin.get("region_code"), None,
                checked.get("checked_on"), 0,
            ),
        )
        added += 1
        inserted += 1

    # 預設規則涵蓋所有沒有個別建檔的路口 —— 沒有這張表，覆蓋率等於只有 114 個路口。
    # 行政區代碼直接取自開放資料，不硬編。
    region_codes = sorted({r["region_code"] for r in rules if r.get("region_code")})
    covered_regions = 0
    unregistered: dict[str, int] = {}
    for code in region_codes:
        city = default_rules.defaults_for(code)
        if city is None:
            # 沒登錄的縣市**不產生預設規則**（ADR-0009）。猜一份出來會在那個縣市的
            # 幾千個路口同時播錯，而沉默只是回到「只有個別建檔的路口有規則」。
            key = taiwan.city_of(code)
            unregistered[key] = unregistered.get(key, 0) + 1
            continue
        covered_regions += 1
        for band in city.bands:
            db.execute(
                "INSERT INTO default_rules (region_code, min_lanes, max_lanes, turn_rule,"
                " confidence, updated_at) VALUES (?,?,?,?,?,?)",
                (code, band.min_lanes, band.max_lanes, band.turn_rule, band.confidence, 0),
            )

    # 執法點位。自帶座標，不需地理編碼。
    enforcement_path = BUILD / "enforcement_raw.json"
    enforcement_count = 0
    if enforcement_path.exists():
        for p in json.loads(enforcement_path.read_text(encoding="utf-8")):
            db.execute(
                "INSERT INTO enforcement_points (lat, lon, cell, bearing, kind, speed_limit,"
                " description, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                (
                    p["lat"], p["lon"], cell_of(p["lat"], p["lon"]),
                    # 科技執法資料沒有取締方向，固定測速有（判得出來的話）。
                    # None 代表不限方向，警示對所有來向發出。
                    p.get("bearing"),
                    p["kind"], p.get("speed_limit"),
                    p["location"] or p["name"],
                    0,
                ),
            )
            enforcement_count += 1

    # 全面禁行機車的路段。只收 status 為 ok 的 —— suspect 的路口是靠幾何鄰近
    # 判出來的，可能是立體交叉，而這份資料播錯的內容是「你正走在禁行的路上」。
    prohibited_path = BUILD / "prohibited.json"
    prohibited_count = 0
    if prohibited_path.exists():
        for seg in json.loads(prohibited_path.read_text(encoding="utf-8")):
            if seg.get("status") != "ok":
                continue
            points = seg["polyline"]
            cur = db.execute(
                "INSERT INTO prohibited_segments (road_name, bearing, polyline, way_ids,"
                " speed_limit, reason, updated_at) VALUES (?,?,?,?,?,?,?)",
                (
                    seg["road"], seg["bearing"],
                    ";".join(f"{lat:.6f},{lon:.6f}" for lat, lon in points),
                    ",".join(str(w) for w in seg.get("way_ids", [])),
                    seg.get("speed_limit"), seg.get("reason"), 0,
                ),
            )
            # 折線的節點是 OSM 的路徑節點，間距遠小於一格（1.1 公里），
            # 所以逐點取格不會跳過中間的格子。
            for cell in sorted({cell_of(lat, lon) for lat, lon in points}):
                db.execute(
                    "INSERT INTO prohibited_cells (cell, segment_id) VALUES (?,?)",
                    (cell, cur.lastrowid),
                )
            prohibited_count += 1

    db.commit()

    if needs_review:
        path = BUILD / "review_coords.csv"
        with open(path, "w", encoding="utf-8-sig", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(needs_review[0]))
            w.writeheader()
            w.writerows(needs_review)

    # 複查清單。空的時候也要寫（覆蓋掉上一輪的內容）—— 留著一份過期的不一致清單，
    # 會讓人騎去一個已經解決的路口。
    conflicts_path = BUILD / "image_conflicts.csv"
    fields = ["district", "junction_text", "approach_bearing", "approach", "entry_road_name",
              "exit_road_name", "lat", "lon", "we_say", "image_says", "captured_on",
              "note", "map"]
    with open(conflicts_path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(image_conflicts)

    total_rules = len(rules)
    print(f"種子資料庫  {SEED}")
    print(f"  規則      {inserted}/{total_rules} 筆寫入")
    print(f"  待補座標  {len(needs_review)} 個路口 -> build/review_coords.csv")
    print(f"  檔案大小  {SEED.stat().st_size / 1024:.1f} KB")

    coverage = db.execute(
        "SELECT COUNT(*), MIN(confidence), MAX(confidence) FROM rules").fetchone()
    print(f"  信心範圍  {coverage[1]}–{coverage[2]}")
    defaults = db.execute("SELECT COUNT(*) FROM default_rules").fetchone()[0]
    print(f"  預設規則  {defaults} 筆（{covered_regions} 個行政區）")
    for city_code, districts in sorted(unregistered.items()):
        print(f"    縣市 {city_code} 未登錄預設規則，{districts} 個行政區只有個別規則"
              f"（見 default_rules.py）")
    print(f"  執法點位  {enforcement_count} 筆")
    print(f"  禁行路段  {prohibited_count} 筆（全面禁行機車，路線層）")
    print(f"  降級規則  {len(downgraded)} 筆（離開方向為機車禁行路段）")
    print(f"  實地查核  {verified} 筆（信心 100，蓋過自動推導），新增 {added} 筆")
    print(f"  影像判讀  一致 {image_agree} 筆、不一致 {len(image_conflicts)} 筆"
          f"（不改規則）-> build/image_conflicts.csv")
    for c in image_conflicts:
        print(f"    {c['district']} {c['junction_text']} 面向 {c['approach_bearing']}°："
              f"我們說 {c['we_say']}，影像看起來是 {c['image_says']}")
    for d in downgraded:
        print(f"    {d['district']} {d['junction']}  {d['bearing']:.0f}° -> {d['blocker']}")
    db.close()
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
