"""匯入執法設備點位：臺北市科技執法 + 全國固定測速。

兩份資料都**自帶 WGS84 座標**，不需要地理編碼 —— 這是它們比路口規則好處理的地方。

刻意排除：
- 純違規停車的點位。騎乘中警示停車執法沒有意義。
- 國道、快速道路與高架。白牌機車禁行（ADR-0006），那些點位對我們無用，
  而且它們常常就懸在平面道路的正上方 —— 留著會在騎士根本不在那條路上時響，
  而一個會亂響的警示，最後換來的是騎士學會忽略所有警示。
- 區間測速。那是**狀態**不是接近事件，硬塞進點模型只會在起點響一次然後沉默。
  這裡把它挑出來另外存檔，等 enforcement_sections 實作時用（見 HANDOVER 第七節）。

用法：
    python import_enforcement.py
"""

from __future__ import annotations

import csv
import io
import json
import pathlib
import re
import sys
import urllib.request

import taiwan

BUILD = pathlib.Path(__file__).parent / "build"
TAIPEI_SMART_PID = "986fa73e-c470-4ebf-9f35-3a1c9d2a8788"

# 內政部警政署「測速執法設置點」（data.gov.tw dataset 7320），全國一份。
SPEED_CAMERA_URL = (
    "https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/"
    "EA5E6FCD-B82D-43B7-A5CF-E9893253187E/resource/"
    "18C2419F-552D-4684-919D-0DF3AF4D57ED/download"
)

# 測速點寫入全國，**不跟著路口規則的縣市範圍走**（ADR-0009）。兩者的邊界不同，
# 因為兩者的失敗方式不同：測速點自帶座標、速限與拍攝方向，不依賴任何推導，
# 多收一個縣市只會多一則正確的警示；路口的預設規則靠推導，沒查證過的縣市會
# 整批播錯。全國 1,523 筆只讓種子庫多幾百 KB。
SEED_CITIES: set[str] | None = None  # None = 不篩選

# enforcement_points.kind，與 Schema.kt 的 EnforcementKind 一致。
KIND_FIXED_SPEED = 1
KIND_SMART = 2

# 取締項目關鍵字 → 本 App 的分類。
# 「不依規定轉彎」在台灣涵蓋機車未依規定兩段式左轉 —— 那是本 App 的核心主題，
# 因此與待轉規則重疊的執法點構成最高等級警示。
CATEGORIES = {
    "turn": ("不依規定轉彎",),
    "red_light": ("闖紅燈",),
    "speed": ("超速",),
    "scooter_lane": ("機車違規行駛", "汽車行駛機車道", "機車行駛人行道", "行駛路肩"),
    "yield": ("不停讓行人",),
    "markings": ("不依標誌標線號誌指示行駛", "跨越雙白線", "跨越槽化線", "路口未淨空"),
    "parking": ("違規停車",),
}

# 只有這些類別值得在騎乘中警示。
RIDING_RELEVANT = {"turn", "red_light", "speed", "scooter_lane", "markings"}


def classify(raw: str) -> list[str]:
    return sorted(
        name for name, keywords in CATEGORIES.items()
        if any(k in raw for k in keywords)
    )


# --- 固定測速 ---------------------------------------------------------------

COMPASS = {"北": 0.0, "東北": 45.0, "東": 90.0, "東南": 135.0,
           "南": 180.0, "西南": 225.0, "西": 270.0, "西北": 315.0}

# 國道與快速道路的線號。台61～台88 這個區塊是快速公路系列（西濱、台64、台68…），
# 而它們的地址常常只寫「台61線60.1公里處」，沒有任何「快速」字樣 —— 只靠關鍵字
# 會漏掉 11 筆速限 90 的點位。
#
# 臺／台兩種寫法在同一份資料裡都有，但這裡不再用字元類別去吃 —— 地址一進來就先過
# `taiwan.normalize_tw()`，正規化只做一次，之後的樣式都只認「台」。
EXPRESSWAY_NUMBER = re.compile(r"台(6[1-9]|7[0-9]|8[0-8])線")

MOTORWAY_KEYWORDS = ("國道", "高速公路", "快速道路", "快速公路", "匝道", "高架")

# 一般道路在台灣最高就是 70–80。90 以上必然是白牌不能上的路，即使地址沒寫。
MOTORWAY_MIN_LIMIT = 90

SECTION_MARKER = "區間測速"


def facing_of(raw: str) -> float | None:
    """把「拍攝方向」轉成騎士的**面向**角度。判不出來回 None。

    None 的意思是「不限方向」，警示會對所有來向發出。這是刻意選的保守側：
    對著反方向多播一次只是吵，該播沒播是騎士收到罰單。

    這個欄位的寫法有十幾種（`北向南`、`北往南`、`往南`、`南下車道`、`西南向東北`、
    `南向`、`往大溪方向`…）。**只解析能確定終點的那幾種**，其餘一律 None ——
    `南向` 到底是「往南」還是「南向北」的簡寫，資料本身沒有說，猜錯就是把警示
    掛到相反的來向上。
    """
    text = re.sub(r"[（(].*?[）)]", "", raw).strip()
    if not text or "雙向" in text:
        return None

    # 「南下 / 北上」是國道式的講法，指的就是行進方向。
    for word, bearing in (("南下", 180.0), ("北上", 0.0), ("東行", 90.0), ("西行", 270.0)):
        if word in text:
            return bearing

    # `A向B` 與 `A往B` 的 B 是終點，也就是面向。`往B` 的 A 是空的，一樣適用。
    for separator in ("向", "往"):
        if separator in text:
            destination = text.split(separator, 1)[1].strip()
            if destination in COMPASS:
                return COMPASS[destination]

    return None


def is_motorway(address: str, limit: int | None) -> bool:
    address = taiwan.normalize_tw(address)
    if any(k in address for k in MOTORWAY_KEYWORDS):
        return True
    if EXPRESSWAY_NUMBER.search(address):
        return True
    return limit is not None and limit >= MOTORWAY_MIN_LIMIT


def fetch_speed_cameras() -> tuple[list[dict], list[dict], dict[str, int]]:
    """回傳（點位, 區間測速原始列, 統計）。點位已排除國道與快速道路。"""
    request = urllib.request.Request(SPEED_CAMERA_URL,
                                     headers={"User-Agent": "scooter-pipeline"})
    text = urllib.request.urlopen(request, timeout=120).read().decode("utf-8-sig")

    points: list[dict] = []
    sections: list[dict] = []
    stats = {"total": 0, "motorway": 0, "section": 0, "bad_coords": 0, "no_bearing": 0}

    for row in csv.DictReader(io.StringIO(text)):
        city = (row.get("CityName") or "").strip()
        # 這份 CSV 的第二列是中文欄名，不是資料。
        if city == "設置縣市":
            continue
        stats["total"] += 1

        address = (row.get("Address") or "").strip()
        try:
            limit = int((row.get("limit") or "").strip())
        except ValueError:
            limit = None
        try:
            lon, lat = float(row["Longitude"]), float(row["Latitude"])
        except (KeyError, TypeError, ValueError):
            stats["bad_coords"] += 1
            continue
        if not (21.5 <= lat <= 25.5 and 119.5 <= lon <= 122.5):
            stats["bad_coords"] += 1
            continue

        direction = (row.get("direct") or "").strip()
        record = {
            "lat": lat, "lon": lon, "kind": KIND_FIXED_SPEED,
            "city": city, "location": address,
            "name": f"{row.get('RegionName', '').strip()}{address}",
            "speed_limit": limit,
            "bearing": facing_of(direction),
            "direction_raw": direction,
            "categories": ["speed"],
            "riding_relevant": True,
            "enforces_turn": False,
        }

        if is_motorway(address, limit):
            stats["motorway"] += 1
            continue
        if SECTION_MARKER in direction or SECTION_MARKER in address:
            stats["section"] += 1
            sections.append(record)
            continue
        if record["bearing"] is None:
            stats["no_bearing"] += 1
        points.append(record)

    return points, sections, stats


def fetch_taipei_smart() -> list[dict]:
    def get(url: str) -> bytes:
        req = urllib.request.Request(url, headers={"User-Agent": "scooter-pipeline"})
        return urllib.request.urlopen(req, timeout=60).read()

    meta = json.loads(
        get(f"https://data.taipei/api/frontstage/tpeod/dataset.view?id={TAIPEI_SMART_PID}")
    )["payload"]
    resource = meta["resources"][0]
    text = get("https://data.taipei" + resource["url"]).decode(resource.get("encoding") or "utf-8")

    out = []
    for row in csv.DictReader(io.StringIO(text)):
        try:
            lon = float(row["座標-X"])
            lat = float(row["座標-Y"])
        except (KeyError, TypeError, ValueError):
            continue
        # 台灣的合理經緯度範圍。座標系標錯（例如給了 TWD97 平面座標）會落在範圍外。
        if not (21.5 <= lat <= 25.5 and 119.5 <= lon <= 122.5):
            continue

        categories = classify(row.get("取締項目", ""))
        out.append({
            "lat": lat,
            "lon": lon,
            "kind": KIND_SMART,
            "location": (row.get("取締路段") or "").strip(),
            "name": (row.get("名稱") or "").strip(),
            "categories": categories,
            "riding_relevant": bool(set(categories) & RIDING_RELEVANT),
            "enforces_turn": "turn" in categories,
            "raw_items": row.get("取締項目", ""),
            "since": (row.get("啟用日期") or "").strip(),
        })
    return out


def main() -> int:
    smart = fetch_taipei_smart()
    relevant = [p for p in smart if p["riding_relevant"]]
    turn = [p for p in smart if p["enforces_turn"]]

    cameras, sections, stats = fetch_speed_cameras()
    in_seed = (lambda item: True) if SEED_CITIES is None else (lambda item: item["city"] in SEED_CITIES)
    seed_cameras = [c for c in cameras if in_seed(c)]
    seed_sections = [s for s in sections if in_seed(s)]

    BUILD.mkdir(exist_ok=True)
    (BUILD / "enforcement_raw.json").write_text(
        json.dumps(relevant + seed_cameras, ensure_ascii=False, indent=1), encoding="utf-8")
    # 區間測速另外存。它進不了 enforcement_points（點模型只會在起點響一次），
    # 而 enforcement_sections 這一版刻意留空表 —— 有資料等著，實作時不必重跑管線。
    (BUILD / "enforcement_sections_raw.json").write_text(
        json.dumps(sections, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"臺北市科技執法  {len(smart)} 筆")
    print(f"  騎乘相關      {len(relevant)}（其餘為純違規停車，已排除）")
    print(f"  取締轉彎      {len(turn)}  <- 與待轉規則重疊者構成最高等級警示")
    counts: dict[str, int] = {}
    for p in smart:
        for c in p["categories"]:
            counts[c] = counts.get(c, 0) + 1
    print("  分類分布      " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))

    print(f"\n全國固定測速    {stats['total']} 筆")
    print(f"  國道／快速／高架  {stats['motorway']} 筆已排除（ADR-0006 白牌禁行）")
    print(f"  區間測速          {stats['section']} 筆另存 enforcement_sections_raw.json")
    print(f"  座標無效          {stats['bad_coords']} 筆")
    print(f"  可用點位          {len(cameras)} 筆，其中 {stats['no_bearing']} 筆判不出方向"
          f"（不限方向警示）")
    limits = sorted({c["speed_limit"] for c in cameras if c["speed_limit"]})
    print(f"  速限範圍          {limits[0]}–{limits[-1]}")

    scope = "全國" if SEED_CITIES is None else "、".join(sorted(SEED_CITIES))
    print(f"\n寫入種子庫（{scope}）")
    print(f"  涵蓋縣市      {len({c['city'] for c in seed_cameras})} 個")
    print(f"  科技執法      {len(relevant)} 筆")
    print(f"  固定測速      {len(seed_cameras)} 筆"
          f"（{sum(1 for c in seed_cameras if c['bearing'] is None)} 筆不限方向）")
    print(f"  區間測速      {len(seed_sections)} 筆（本版不寫入，表留空）")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
