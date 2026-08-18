"""把全面禁行機車的 5 個路段解成折線。

`import_prohibited.py` 解出來的是文字（路名 + 起訖路名 + 面向），
這一步把它變成幾何。輸出 `build/prohibited.json`，每筆帶：

- `start` / `end`：起訖交叉點座標
- `polyline`：沿著道路節點裁出來的折線
- `way_ids`：構成該路段的 OSM way 編號
- `status`：`ok` / `suspect` / `failed`

**為什麼要折線不要兩個端點。** 堤頂大道1段是彎的，兩點連成直線會在中段偏出去
一百多公尺 —— 拿它判「騎士是不是在這條路上」會在**平行的其他路**上誤報，
而誤報的內容是「你正走在禁行機車的路上」，那是最不能亂講的一句話。

**為什麼要 `way_ids`。** 折線是給「騎士現在在哪」用的；路線引擎事後驗證
（ADR-0006）要比對的是**路網的邊**，而 way 編號才是那一邊的身分。
兩種用途、兩種鍵，一起存下來比日後回頭重算便宜。

沿用 `taipei_geocode` 的 `RoadIndex` 與那裡累積的所有教訓（幹道優先、
巷弄排除、共用節點優先於幾何鄰近）。這個模組只加兩件它沒有的事：
沿路徑裁切，以及分隔島道路的車道側選擇。
"""

from __future__ import annotations

import csv
import json
import math
import pathlib
import sys

from taipei_geocode import (
    RoadIndex,
    axis_delta,
    bearing_deg,
    centroid,
    cluster,
    fetch_ways,
    haversine_m,
    name_variants,
)

BUILD = pathlib.Path(__file__).parent / "build"
SOURCE = BUILD / "prohibited_raw.json"
OUT = BUILD / "prohibited.json"
REVIEW = BUILD / "review_prohibited.csv"

# 路口群聚半徑。分隔式大路一個路口有多個號誌節點，沿用 taipei_geocode 的量級。
JUNCTION_CLUSTER_M = 60.0

# 取點時容許的側向偏移。堤頂大道那種弧線要吃得下，但不能大到把平行的另一條路吃進來。
CORRIDOR_HALF_WIDTH_M = 200.0

# 兩側平均偏移差落在這個區間才當成分隔島道路。
#
# **上限是必要的，不是保險。** 只設下限的話，彎道會誤觸：環河北路沿著淡水河
# 彎過去，兩側平均偏移差 240 公尺，被判成分隔島而丟掉了一半的節點。
# 真正的分隔島道路兩個方向相距就是一個中央分隔帶的寬度，不會有幾百公尺。
DIVIDED_ROAD_GAP_M = 15.0
DIVIDED_ROAD_MAX_GAP_M = 60.0


def _plane(origin: tuple[float, float]):
    """本地平面近似。幾百公尺的尺度上誤差遠小於 GPS 本身。"""
    lat0 = math.radians(origin[0])
    mx = 111_320.0 * math.cos(lat0)
    my = 110_540.0

    def to_xy(p: tuple[float, float]) -> tuple[float, float]:
        return ((p[1] - origin[1]) * mx, (p[0] - origin[0]) * my)

    return to_xy


def project(points, a, b):
    """把每個點投影到 a→b 這條線上，回傳 (t, 側向偏移, 點)。

    `t` 是沿線的比例（0 在 a、1 在 b），側向偏移的正負代表在 a→b 的左邊還是右邊。
    """
    to_xy = _plane(a)
    ax, ay = to_xy(a)
    bx, by = to_xy(b)
    dx, dy = bx - ax, by - ay
    length2 = dx * dx + dy * dy
    if length2 <= 0:
        return []

    out = []
    for p in points:
        px, py = to_xy(p)
        t = ((px - ax) * dx + (py - ay) * dy) / length2
        # 二維外積除以長度＝帶正負號的垂距。左正右負。
        offset = ((px - ax) * dy - (py - ay) * dx) / math.sqrt(length2)
        out.append((t, offset, p))
    return out


def junction(index: RoadIndex, road: str, cross: str) -> tuple[tuple[float, float] | None, str]:
    """兩條路的交叉點。回傳 (座標, 品質)，品質是 exact / near / none。"""
    a = index.trunk_ways_for(road) or index.ways_for(road)[0]
    b = index.trunk_ways_for(cross) or index.ways_for(cross)[0]
    if not a or not b:
        return None, "none"

    shared = index.shared_nodes(a, b)
    if shared:
        pts = [index.node_coords[n] for n in shared if n in index.node_coords]
        if pts:
            groups = cluster(pts, JUNCTION_CLUSTER_M)
            return centroid(max(groups, key=len)), "exact"

    # 分隔島道路常以短連接段銜接，主線之間因此沒有共點。幾何鄰近救得回來，
    # 但立體交叉也會被判成相交 —— 所以標成 near，由呼叫端降級成 suspect。
    hits = RoadIndex.near_misses(a, b)
    if hits:
        groups = cluster(hits, JUNCTION_CLUSTER_M)
        return centroid(max(groups, key=len)), "near"
    return None, "none"


def carriageway_points(index: RoadIndex, road: str, travel_bearing: float,
                       start: tuple[float, float], end: tuple[float, float]):
    """裁出 start→end 之間、屬於行進那一側車道的節點，以及它們所屬的 way。"""
    ways = index.trunk_ways_for(road) or index.ways_for(road)[0]

    candidates = []
    for w in ways:
        geometry = [(p["lat"], p["lon"]) for p in w["geometry"]]
        if len(geometry) < 2:
            continue
        # 走向不同軸的一律丟掉。同一個路名在別的行政區還有一段時，這一步先擋掉。
        heading = bearing_deg(geometry[0], geometry[-1])
        if axis_delta(heading, travel_bearing) > 45.0:
            continue
        for p in geometry:
            candidates.append((w["id"], p))

    projected = [
        (t, off, p, wid)
        for (t, off, p), wid in zip(
            project([p for _, p in candidates], start, end),
            [wid for wid, _ in candidates],
        )
        if -0.02 <= t <= 1.02 and abs(off) <= CORRIDOR_HALF_WIDTH_M
    ]
    if not projected:
        return [], [], "沒有節點落在起訖點之間"

    note = ""
    left = [q for q in projected if q[1] > 0]
    right = [q for q in projected if q[1] <= 0]
    if left and right:
        gap = abs(
            sum(q[1] for q in left) / len(left) - sum(q[1] for q in right) / len(right)
        )
        if DIVIDED_ROAD_GAP_M < gap <= DIVIDED_ROAD_MAX_GAP_M:
            # 分隔島道路：兩條平行的線都在走廊裡，照 t 排序會織成鋸齒。
            # 台灣靠右行駛，所以行進方向 start→end 的那一側車道在中線的**右邊**。
            projected = right
            note = f"分隔島道路（兩側相距 {gap:.0f} m），取右側車道"

    projected.sort(key=lambda q: q[0])
    polyline = [q[2] for q in projected]
    way_ids = sorted({q[3] for q in projected})
    return polyline, way_ids, note


def length_of(polyline) -> float:
    return sum(haversine_m(a, b) for a, b in zip(polyline, polyline[1:]))


def main() -> int:
    if not SOURCE.exists():
        print(f"缺少 {SOURCE.name}，先跑 python import_prohibited.py")
        return 1

    rows = json.loads(SOURCE.read_text(encoding="utf-8"))
    raw_names = {r["road"] for r in rows} | {r["from_road"] for r in rows} | {r["to_road"] for r in rows}
    # **查詢時要一併帶上名稱變體。** `trunk_ways_for` 會在索引裡試變體，
    # 但索引只裝得下 Overpass 回傳過的東西 —— 「堤頂大道1段」在 OSM 裡是
    # 「堤頂大道一段」，不把中文數字那個變體放進查詢，索引裡就永遠沒有它，
    # 而症狀是「這條路查不到」，不是「名字對不上」。
    #
    # 不再用路名尾字白名單過濾。「匝道入口處」這種非路名留著也只是多幾個
    # 匹配不到的替代項，而白名單擋掉了「堤頂大道1段」—— 它結尾是「段」。
    names = {v for n in raw_names if n for v, _ in name_variants(n)}
    print(f"查詢 {len(raw_names)} 個路名（展開成 {len(names)} 個變體）的幾何 …")
    index = RoadIndex(fetch_ways(names))

    out, review = [], []
    for r in rows:
        record = dict(r)
        start, q_start = junction(index, r["road"], r["from_road"])
        end, q_end = junction(index, r["road"], r["to_road"])

        if start is None or end is None:
            record |= {"status": "failed", "note": f"起點 {q_start}、迄點 {q_end}"}
            out.append(record)
            review.append(record)
            continue

        polyline, way_ids, note = carriageway_points(index, r["road"], r["bearing"], start, end)
        straight = haversine_m(start, end)
        along = length_of(polyline)

        status = "ok"
        notes = [note] if note else []
        if q_start == "near" or q_end == "near":
            status = "suspect"
            notes.append("路口靠幾何鄰近判定，可能是立體交叉")
        if len(polyline) < 2:
            status = "failed"
            notes.append("折線點數不足")
        elif along < straight * 0.9:
            # 折線比直線還短代表中間有大段沒取到，不是彎道。
            status = "suspect"
            notes.append(f"折線 {along:.0f} m 短於直線 {straight:.0f} m，中段可能有缺口")

        record |= {
            "start": list(start),
            "end": list(end),
            "polyline": [list(p) for p in polyline],
            "way_ids": way_ids,
            "straight_m": round(straight, 1),
            "along_m": round(along, 1),
            "geocoded": status != "failed",
            "status": status,
            "note": "；".join(notes),
        }
        out.append(record)
        if status != "ok":
            review.append(record)

    BUILD.mkdir(exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")

    print()
    for r in out:
        mark = {"ok": "✓", "suspect": "?", "failed": "✗"}[r["status"]]
        line = f"  {mark} {r['district']} {r['road']} 面向 {r['bearing']:.0f}°"
        if r["status"] != "failed":
            line += (f"  直線 {r['straight_m']:.0f} m / 沿線 {r['along_m']:.0f} m"
                     f"  {len(r['polyline'])} 點 / {len(r['way_ids'])} 條 way")
        print(line)
        if r.get("note"):
            print(f"      {r['note']}")

    if review:
        with REVIEW.open("w", encoding="utf-8-sig", newline="") as f:
            w = csv.writer(f)
            w.writerow(["行政區", "路名", "方向", "起點", "迄點", "狀態", "說明"])
            for r in review:
                w.writerow([r["district"], r["road"], r["direction_raw"],
                            r["from_road"], r["to_road"], r["status"], r.get("note", "")])
        print(f"\n{len(review)} 筆需要人工看過 -> {REVIEW.name}")

    ok = sum(1 for r in out if r["status"] == "ok")
    print(f"\n{ok}/{len(out)} 筆可用，寫入 {OUT.name}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
