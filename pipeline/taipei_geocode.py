"""把路名配對解析成路口座標。

作法是**一次抓回所有相關道路的幾何，交叉點全部在本機算**。先前的版本是每組
路口打一次 Overpass，查不到的組合會逐一試遍名稱變體再重試，單一路口最壞情況
要跑近一小時，而且是在狂打公開 API。現在總請求數是個位數。

一次抓回幾何還帶來一個額外好處：知道每條路在路口的**走向**，因此可以判定
哪一條是進入道路（與進入方位角同軸）、哪一條是離開道路 —— 路口欄本身沒有
這個資訊。路名僅供顯示（ADR-0001），但顯示錯了一樣難看。

輸出分成 ok / suspect / failed 三類；只有 ok 會進種子資料庫。

用法：
    python taipei_geocode.py
"""

from __future__ import annotations

import hashlib
import json
import math
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from districts import Districts

BUILD = pathlib.Path(__file__).parent / "build"
CACHE = BUILD / "cache"

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

TAIPEI_BBOX = "24.95,121.45,25.22,121.67"

# 同一路口的多個節點（側車道、行人穿越號誌）若都在此半徑內即視為同一路口。
# 台北的分隔式大路（基隆路、中山北路）光是主線加側車道就能拉開百餘公尺，
# 而相鄰的兩個大路口通常相距 200 公尺以上，因此 120 公尺是安全的分界。
CLUSTER_RADIUS_M = 120.0

# 判定一條路是否與進入方位角同軸的容差。
AXIS_TOLERANCE_DEG = 45.0

ARABIC_TO_CHINESE = {
    "1": "一", "2": "二", "3": "三", "4": "四", "5": "五",
    "6": "六", "7": "七", "8": "八", "9": "九",
}


def name_variants(name: str) -> list[tuple[str, bool]]:
    """由 CSV 路名產生可能的 OSM 名稱樣式，附帶「是否仍然精確」。

    精確的轉換不損失資訊，不該被當成可疑：
    - 阿拉伯數字段號轉中文數字（新生南路3段 → 新生南路三段），OSM 台灣的慣例。
    - 剝掉尾字「路」（艋舺大道路 → 艋舺大道），那個字其實屬於原文的「路口」。

    不精確的只有一種：**丟掉段號**（新生南路三段 → 新生南路）。那會讓比對涵蓋
    整條路的所有段，可能命中錯的路口，因此必須標成可疑。
    """
    def with_chinese_numerals(n: str) -> str:
        return re.sub(r"(\d)(?=段)", lambda m: ARABIC_TO_CHINESE.get(m.group(1), m.group(1)), n)

    bases = [name]
    if name.endswith("路") and len(name) > 2:
        bases.append(name[:-1])

    out: list[tuple[str, bool]] = []
    for base in bases:
        out.append((base, True))
        out.append((with_chinese_numerals(base), True))
    for base in bases:
        trunk = re.sub(r"[0-9一二三四五六七八九]+段$", "", base).strip()
        if trunk and trunk != base:
            out.append((trunk, False))

    seen: set[str] = set()
    return [(v, p) for v, p in out if v and not (v in seen or seen.add(v))]


def overpass(query: str, timeout: int = 300) -> dict:
    key = hashlib.sha256(query.encode()).hexdigest()[:16]
    cached = CACHE / f"bulk_{key}.json"
    if cached.exists():
        return json.loads(cached.read_text(encoding="utf-8"))

    body = urllib.parse.urlencode({"data": query}).encode()
    last: Exception | None = None
    for attempt, endpoint in enumerate(ENDPOINTS):
        try:
            req = urllib.request.Request(
                endpoint, data=body, headers={"User-Agent": "scooter-pipeline/0.1"})
            data = json.loads(urllib.request.urlopen(req, timeout=timeout).read())
            CACHE.mkdir(parents=True, exist_ok=True)
            cached.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            return data
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, ValueError) as e:
            last = e
            print(f"    {endpoint.split('/')[2]} 失敗（{e}），換下一台", flush=True)
            time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"所有 Overpass 鏡像皆失敗: {last}")


def fetch_traffic_signals() -> list[tuple[float, float]]:
    """台北市所有號誌節點。

    用途是消歧義：兩條長路可能在多處相接（巷弄、側車道、分隔島缺口），但
    **有待轉規則的路口必定是有號誌的大路口**。以號誌篩選候選叢集，能把大量
    「多處相交」的可疑結果自動收斂成唯一解。
    """
    query = (
        f'[out:json][timeout:280][bbox:{TAIPEI_BBOX}];'
        f'node["highway"="traffic_signals"];'
        f'out skel;'
    )
    return [(e["lat"], e["lon"]) for e in overpass(query).get("elements", [])
            if e.get("type") == "node"]


def fetch_arms(points: list[tuple[float, float]], radius_m: int = 70) -> list[dict]:
    """抓回所有路口周邊的具名道路，用來判定各方位上實際是哪條路。

    必要性：原始資料把路口命名為「A與B」，但「南北雙向」展開成兩個方向時，
    兩列會繼承同一組路名 —— 而 B 常常只存在於路口的一側。例如興隆路三段與
    萬芳路，面向南左轉進萬芳路沒錯，面向北左轉進的卻是興隆路三段192巷8弄。
    CSV 沒有西側那條路的名字，只能從路網幾何取得。
    """
    coords = ",".join(f"{lat},{lon}" for lat, lon in points)
    query = (
        f"[out:json][timeout:280];"
        f'way["highway"]["name"](around:{radius_m},{coords});'
        f"out body geom;"
    )
    return [e for e in overpass(query).get("elements", []) if e.get("type") == "way"]


def is_rideable_surface(way: dict) -> bool:
    """排除白牌機車不會騎、也不該顯示為路口臂的道路。

    高架與匝道常常在平面道路正上方，方位角完全相同，若不排除就會蓋掉真正的路名 ——
    實測「承德路 ➔ 市民大道」會被判成「市民大道高架道路」，那是機車禁行的路。
    """
    tags = way.get("tags") or {}
    name = tags.get("name", "")
    highway = str(tags.get("highway", ""))
    if tags.get("bridge") == "yes" and tags.get("layer", "0") not in ("0", ""):
        return False
    if highway.endswith("_link") or highway in ("motorway", "trunk", "cycleway", "footway", "path"):
        return False
    return not any(k in name for k in ("高架", "匝道", "地下道", "隧道", "快速道路", "自行車道"))


# 路口的臂偏好幹道。分數越高越優先 —— 幾何上小巷弄常常正好從路口起算，
# 距離分數會贏過幹道，不靠等級加權就會挑到巷子。
ROAD_CLASS_BONUS = {
    "primary": 60, "secondary": 50, "tertiary": 40,
    "unclassified": 20, "residential": 15,
    "service": 0, "living_street": 0,
}


def road_at(arms: list[dict], point: tuple[float, float], bearing: float,
            prefer: list[str] | None = None, skip_filter: bool = False,
            tolerance_deg: float = 40.0, max_dist_m: float = 22.0) -> str | None:
    """回傳該方位上的路名。實際走向請用 [arm_at]。"""
    found = arm_at(arms, point, bearing, prefer, skip_filter, tolerance_deg, max_dist_m)
    return found[0] if found else None


def arm_at(arms: list[dict], point: tuple[float, float], bearing: float,
           prefer: list[str] | None = None, skip_filter: bool = False,
           tolerance_deg: float = 40.0, max_dist_m: float = 22.0
           ) -> tuple[str, float] | None:
    """回傳 (路名, 該臂的實際方位角)。

    實際方位角很重要：官方資料只寫東西南北，但塔悠路、基隆路這類斜向道路的真實
    走向可能是 15° 或 340°。硬套 0°／90° 會讓警示引擎的 ±30° 判定閘門在邊緣
    情況失準 —— 一條走向 40° 的路被記成 90°，騎士實際行進方向與規則差 50°，
    永遠不會命中。
    """
    """路口在指定方位上的那條路叫什麼。

    `prefer` 是原始資料給的路名（含變體）。**原始路名優先** —— CSV 對它提到的兩條路
    是可信的，幾何只負責補 CSV 沒講的那一臂（雙向規則的另一側）。不加這層限制的話，
    幾何會挑到匝道、巷弄或高架，把本來正確的路名改壞。

    `max_dist_m` 必須小。線段的兩個方向都要納入判斷（OSM 的 way 方向是任意的），
    但起點若容許離路口太遠，東側那條路在數十公尺外的反方向就會被算成西側的臂 ——
    實測 60 公尺時，萬芳路同時佔據了興隆路三段路口的東側與西側。

    找不到相符的臂就回傳 None —— 寧可留白，也不要掛一個錯的路名。
    """
    preferred: tuple[float, str, float] | None = None
    fallback: tuple[float, str, float] | None = None

    for w in arms:
        tags = w.get("tags") or {}
        name = tags.get("name")
        if not name or (not skip_filter and not is_rideable_surface(w)):
            continue
        bonus = ROAD_CLASS_BONUS.get(str(tags.get("highway", "")), 10)
        if "弄" in name:
            bonus -= 18
        elif "巷" in name:
            bonus -= 12

        for arm_bearing in arm_directions(w, point, max_near_m=max_dist_m):
            delta = abs((arm_bearing - bearing + 540.0) % 360.0 - 180.0)
            if delta > tolerance_deg:
                continue
            score = delta - bonus
            if prefer and any(name.startswith(p_) or p_.startswith(name) for p_ in prefer):
                if preferred is None or score < preferred[0]:
                    preferred = (score, name, arm_bearing)
            elif fallback is None or score < fallback[0]:
                fallback = (score, name, arm_bearing)

    best = preferred or fallback
    return (best[1], best[2]) if best else None


def arm_directions(way: dict, point: tuple[float, float],
                   reach_m: float = 14.0, max_near_m: float = 25.0) -> list[float]:
    """一條路從路口延伸出去的方向，最多兩個（通過型的路有兩臂）。

    做法是從離路口最近的頂點沿路往外走到 [reach_m] 之外，取「路口指向該點」的方位。
    不用線段自身的方向 —— 橫跨路口中心的短線段，兩端都離中心只有幾公尺，正反兩個
    方向都會被當成臂，實測讓萬芳路同時佔據了路口的東側與西側。往外走則自然只會
    產生真實存在的臂：路在該側終止時，那一側就走不出去。
    """
    geometry = [(p["lat"], p["lon"]) for p in (way.get("geometry") or [])]
    if len(geometry) < 2:
        return []
    distances = [haversine_m(point, g) for g in geometry]
    nearest = min(range(len(geometry)), key=distances.__getitem__)
    if distances[nearest] > max_near_m:
        return []

    out: list[float] = []
    for step in (1, -1):
        i = nearest
        while 0 <= i + step < len(geometry):
            i += step
            if distances[i] >= reach_m:
                out.append(bearing_deg(point, geometry[i]))
                break
    return out


def fetch_ways(names: set[str]) -> list[dict]:
    """一次抓回所有指定路名的道路幾何。"""
    alternation = "|".join(sorted(re.escape(n) for n in names))
    query = (
        f'[out:json][timeout:280][bbox:{TAIPEI_BBOX}];'
        f'way["highway"]["name"~"^({alternation})"];'
        f'out body geom;'
    )
    return [e for e in overpass(query).get("elements", []) if e.get("type") == "way"]


def haversine_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    r = 6_371_000.0
    dlat = math.radians(b[0] - a[0])
    dlon = math.radians(b[1] - a[1])
    h = (math.sin(dlat / 2) ** 2
         + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dlon / 2) ** 2)
    return 2 * r * math.asin(math.sqrt(min(1.0, h)))


def bearing_deg(a: tuple[float, float], b: tuple[float, float]) -> float:
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    dlon = math.radians(b[1] - a[1])
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def axis_delta(a: float, b: float) -> float:
    """兩方位角的軸線夾角（0..90）。同軸的反向視為 0。"""
    d = abs((a - b + 180.0) % 360.0 - 180.0)
    return min(d, 180.0 - d)


def cluster(nodes: list[tuple[float, float]], radius_m: float) -> list[list[tuple[float, float]]]:
    """單一連結群聚。兩條路可能真的在多處相交，那種情況必須分開，不能取全體形心。"""
    remaining = list(nodes)
    out: list[list[tuple[float, float]]] = []
    while remaining:
        group = [remaining.pop()]
        changed = True
        while changed:
            changed = False
            for node in list(remaining):
                if any(haversine_m(node, m) <= radius_m for m in group):
                    group.append(node)
                    remaining.remove(node)
                    changed = True
        out.append(group)
    return out


def centroid(group: list[tuple[float, float]]) -> tuple[float, float]:
    return (sum(n[0] for n in group) / len(group), sum(n[1] for n in group) / len(group))


class RoadIndex:
    """依路名索引道路幾何，並提供路口與走向查詢。"""

    def __init__(self, ways: list[dict]) -> None:
        self.by_name: dict[str, list[dict]] = {}
        self.node_coords: dict[int, tuple[float, float]] = {}
        for w in ways:
            name = (w.get("tags") or {}).get("name")
            geometry = w.get("geometry") or []
            node_ids = w.get("nodes") or []
            if not name or not geometry or len(node_ids) != len(geometry):
                continue
            self.by_name.setdefault(name, []).append(w)
            for nid, pt in zip(node_ids, geometry):
                self.node_coords[nid] = (pt["lat"], pt["lon"])

    def ways_for(self, csv_name: str) -> tuple[list[dict], bool]:
        """回傳 (道路清單, 是否損失了精確度)。

        精確名稱與前綴名稱**一併取用**，不因為精確命中就短路。OSM 裡常同時存在
        一條剛好叫「木柵路」的短路段和「木柵路一段」「木柵路二段」——先前只取
        精確命中的那一小段，導致它跟真正的交叉路口差了五百公尺。
        """
        for variant, precise in name_variants(csv_name):
            matched = [
                w for n, ws in self.by_name.items()
                if n == variant or n.startswith(variant)
                for w in ws
            ]
            if matched:
                return matched, not precise
        return [], False

    def trunk_ways_for(self, csv_name: str) -> list[dict]:
        """只取幹道本身，排除它的巷與弄。

        前綴比對會把「民族東路512巷」算成「民族東路」，於是兩條路的**巷口**
        也成了合法的交叉點 —— 實測讓民族東路與復興北路的路口偏了 230 公尺。
        判定路口位置時必須先用幹道，找不到才退回寬鬆比對。
        """
        for variant, _ in name_variants(csv_name):
            matched = [
                w for n, ws in self.by_name.items()
                if (n == variant or n.startswith(variant)) and "巷" not in n and "弄" not in n
                for w in ws
            ]
            if matched:
                return matched
        return []

    def shared_nodes(self, a: list[dict], b: list[dict]) -> list[int]:
        na = {n for w in a for n in w["nodes"]}
        nb = {n for w in b for n in w["nodes"]}
        return sorted(na & nb)

    @staticmethod
    def near_misses(a: list[dict], b: list[dict], radius_m: float = 25.0
                    ) -> list[tuple[float, float]]:
        """兩組道路的幾何最接近點。

        並非每個路口在 OSM 裡都有共用節點 —— 分隔島道路常以短連接段銜接，
        主線之間因此沒有共點。改用幾何鄰近判定可以救回這類。

        代價是立體交叉（高架跨越平面道路）也會被判為相交，所以呼叫端必須把
        這個結果標成 suspect，不能直接採用。
        """
        pts_b = [(p["lat"], p["lon"]) for w in b for p in w["geometry"]]
        hits: list[tuple[float, float]] = []
        for w in a:
            for p in w["geometry"]:
                pa = (p["lat"], p["lon"])
                if any(haversine_m(pa, pb) <= radius_m for pb in pts_b):
                    hits.append(pa)
        return hits

    def orientation_at(self, ways: list[dict], point: tuple[float, float]) -> float | None:
        """該組道路在指定點附近的走向（方位角）。找不到鄰近線段時回傳 None。"""
        best: tuple[float, float] | None = None
        for w in ways:
            geometry = [(p["lat"], p["lon"]) for p in w["geometry"]]
            for p, q in zip(geometry, geometry[1:]):
                mid = ((p[0] + q[0]) / 2, (p[1] + q[1]) / 2)
                d = haversine_m(mid, point)
                if best is None or d < best[0]:
                    best = (d, bearing_deg(p, q))
        if best is None or best[0] > 120.0:
            return None
        return best[1]


class SignalIndex:
    """號誌節點的粗網格索引，供快速鄰近查詢。"""

    CELL = 0.005  # 約 550 公尺

    def __init__(self, signals: list[tuple[float, float]]) -> None:
        self.cells: dict[tuple[int, int], list[tuple[float, float]]] = {}
        for lat, lon in signals:
            self.cells.setdefault(
                (int(lat / self.CELL), int(lon / self.CELL)), []).append((lat, lon))

    def near(self, point: tuple[float, float], radius_m: float) -> int:
        cy, cx = int(point[0] / self.CELL), int(point[1] / self.CELL)
        count = 0
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for s in self.cells.get((cy + dy, cx + dx), ()):
                    if haversine_m(point, s) <= radius_m:
                        count += 1
        return count


def resolve(index: RoadIndex, road_a: str, road_b: str, district: str,
            areas: Districts, approach_bearings: list[float],
            signals: SignalIndex | None = None) -> dict:
    ways_a, relaxed_a = index.ways_for(road_a)
    ways_b, relaxed_b = index.ways_for(road_b)
    missing = [n for n, w in ((road_a, ways_a), (road_b, ways_b)) if not w]
    if missing:
        return {"status": "failed", "reason": f"OSM 查無路名: {', '.join(missing)}",
                "lat": None, "lon": None, "candidates": [], "district": district}

    # 先用幹道找路口。找不到才退回含巷弄的寬鬆集合 —— 巷口不是路口。
    trunk_a, trunk_b = index.trunk_ways_for(road_a), index.trunk_ways_for(road_b)
    if trunk_a and trunk_b and index.shared_nodes(trunk_a, trunk_b):
        ways_a, ways_b = trunk_a, trunk_b

    node_ids = index.shared_nodes(ways_a, ways_b)
    geometric = False
    if node_ids:
        coords = [index.node_coords[n] for n in node_ids if n in index.node_coords]
    else:
        coords = index.near_misses(ways_a, ways_b)
        geometric = True
        if not coords:
            return {"status": "failed", "reason": "兩條路既無共用節點也無鄰近點，應不相交",
                    "lat": None, "lon": None, "candidates": [], "district": district}
    groups = sorted(cluster(coords, CLUSTER_RADIUS_M), key=len, reverse=True)
    scored = [(g, centroid(g)) for g in groups]

    in_district = [(g, c) for g, c in scored if areas.contains(district, c[0], c[1]) is True]
    filtered = len(scored) - len(in_district)
    if in_district:
        scored = in_district

    # 有號誌的叢集才可能是有待轉規則的大路口。只在「有些有、有些沒有」時才篩選 ——
    # 全部都沒號誌時篩選只會把候選清空，那反而丟失資訊。
    signalled = []
    if signals is not None and len(scored) > 1:
        signalled = [(g, c) for g, c in scored if signals.near(c, 60.0) > 0]
        if signalled and len(signalled) < len(scored):
            scored = signalled

    best_group, best = scored[0]
    candidates = [{"lat": round(c[0], 7), "lon": round(c[1], 7), "nodes": len(g)}
                  for g, c in scored]

    # 判定哪一條是進入道路：與進入方位角同軸的那條。
    orient_a = index.orientation_at(ways_a, best)
    orient_b = index.orientation_at(ways_b, best)
    entry, exit_road, ordered = road_a, road_b, False
    if orient_a is not None and orient_b is not None and approach_bearings:
        approach = approach_bearings[0]
        da, db = axis_delta(orient_a, approach), axis_delta(orient_b, approach)
        if min(da, db) <= AXIS_TOLERANCE_DEG and abs(da - db) > 15.0:
            ordered = True
            if db < da:
                entry, exit_road = road_b, road_a

    if len(scored) > 1:
        status, reason = "suspect", f"同區內有 {len(scored)} 處相交，需人工挑選"
    elif filtered:
        # 行政區過濾刪掉過候選就不能算 ok —— 被刪掉的可能才是對的。
        # 民族東路與復興北路位於中山／松山交界，正確的路口被判在界外刪除，
        # 只剩一個錯的巷口，結果卻標成 ok，座標偏了 230 公尺。
        status, reason = "suspect", f"行政區過濾刪去 {filtered} 個候選，可能刪錯"
    elif not in_district:
        status, reason = "suspect", f"無候選點落在{district}區內"
    elif geometric:
        # 幾何鄰近可能誤判立體交叉，一律需人工確認。
        status, reason = "suspect", "無共用節點，以幾何鄰近推得，須確認非立體交叉"
    elif relaxed_a or relaxed_b:
        status, reason = "suspect", "路名丟失段號，可能命中錯的路段"
    else:
        status, reason = "ok", ""

    return {
        "status": status,
        "reason": reason,
        "lat": round(best[0], 7),
        "lon": round(best[1], 7),
        "node_count": len(coords),
        "cluster_count": len(scored),
        "filtered_out_of_district": filtered,
        "district": district,
        "entry_road_name": entry,
        "exit_road_name": exit_road,
        "roads_ordered": ordered,
        "candidates": candidates,
    }


def main() -> int:
    rules = json.loads((BUILD / "rules_raw.json").read_text(encoding="utf-8"))
    areas = Districts()

    names: set[str] = set()
    for r in rules:
        names.update(v for v, _ in name_variants(r["road_a"]))
        names.update(v for v, _ in name_variants(r["road_b"]))
    print(f"抓取 {len(names)} 個路名的路網幾何（單一請求）…", flush=True)
    ways = fetch_ways(names)
    index = RoadIndex(ways)
    print(f"  取得 {len(ways)} 條道路，{len(index.by_name)} 個相異名稱，"
          f"{len(index.node_coords)} 個節點", flush=True)

    signals = SignalIndex(fetch_traffic_signals())
    print(f"  號誌節點 {sum(len(v) for v in signals.cells.values())} 個\n", flush=True)

    approaches: dict[tuple[str, str, str], list[float]] = {}
    for r in rules:
        approaches.setdefault((r["road_a"], r["road_b"], r["region"]), []).append(
            r["approach_bearing"])

    resolved: dict[str, dict] = {}
    counts = {"ok": 0, "suspect": 0, "failed": 0}
    for i, key in enumerate(sorted(approaches), start=1):
        a, b, district = key
        result = resolve(index, a, b, district, areas, approaches[key], signals)
        resolved[f"{a}|{b}|{district}"] = result
        counts[result["status"]] += 1
        flag = {"ok": "  ", "suspect": "??", "failed": "XX"}[result["status"]]
        where = f"{result['lat']},{result['lon']}" if result["lat"] else "-"
        print(f"{flag} [{i:>3}/{len(approaches)}] {district} {a} × {b} -> {where}"
              f"  {result.get('reason', '')}", flush=True)

    annotate_arms(resolved, rules)

    (BUILD / "junctions.json").write_text(
        json.dumps(resolved, ensure_ascii=False, indent=1), encoding="utf-8")
    ordered = sum(1 for r in resolved.values() if r.get("roads_ordered"))
    print(f"\nok={counts['ok']}  suspect={counts['suspect']}  failed={counts['failed']}"
          f"  （{len(approaches)} 組，其中 {ordered} 組已判定進入／離開道路順序）")
    return 0


def annotate_arms(resolved: dict[str, dict], rules: list[dict]) -> None:
    """為每個路口的每個方位查出實際的路名，寫進 `arms`。

    這取代了原先「用兩條路名的走向猜順序」的做法 —— 直接看路口那個方位上是哪條路，
    比從路口名稱推論可靠得多，而且能修正雙向規則裡繼承錯誤路名的那一半。
    """
    located = {k: v for k, v in resolved.items() if v.get("lat") is not None}
    if not located:
        return

    points = [(v["lat"], v["lon"]) for v in located.values()]
    print(f"\n查詢 {len(points)} 個路口周邊的道路，判定各方位的實際路名…", flush=True)
    arms = fetch_arms(points)
    print(f"  取得 {len(arms)} 條周邊道路", flush=True)

    wanted: dict[str, set[float]] = {}
    for r in rules:
        key = f"{r['road_a']}|{r['road_b']}|{r['region']}"
        if key not in located:
            continue
        bucket = wanted.setdefault(key, set())
        bucket.add(r["approach_bearing"])
        bucket.add((r["approach_bearing"] + 180.0) % 360.0)
        if r["exit_bearing"] is not None:
            bucket.add(r["exit_bearing"])

    rideable = [w for w in arms if is_rideable_surface(w)]
    restricted = [w for w in arms if not is_rideable_surface(w)]

    filled = blocked = 0
    for key, bearings in wanted.items():
        v = located[key]
        road_a, road_b, _ = key.split("|")
        prefer = [n for name in (road_a, road_b) for n, _ in name_variants(name)]
        point = (v["lat"], v["lon"])
        v["arms"] = {}
        v["arm_bearings"] = {}
        v["restricted_arms"] = {}
        for b in sorted(bearings):
            # 每圈都要重設 —— 只在 if 裡指派會讓上一圈的值殘留，
            # 底下的 `not name` 就永遠不成立，禁行判定會全部被吃掉。
            name = None
            found = arm_at(rideable, point, b, prefer=prefer, skip_filter=True)
            if found:
                name, actual = found
                v["arms"][f"{b:.0f}"] = name
                # 該臂的實際走向，取代官方資料量化過的東西南北。
                v["arm_bearings"][f"{b:.0f}"] = round(actual, 1)
                filled += 1
            # 只有「該方位沒有可騎的路，卻有結構性禁行的路」才算封閉 ——
            # 地下道與平面道路常常並存，並存時機車走平面，規則依然成立。
            blocker = road_at(restricted, point, b, skip_filter=True)
            if blocker and not name:
                v["restricted_arms"][f"{b:.0f}"] = blocker
                blocked += 1
    print(f"  判定出 {filled} 個方位的路名，其中 {blocked} 個方位只有機車禁行的路")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
