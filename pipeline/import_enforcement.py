"""匯入執法設備點位。

臺北市的科技執法資料**自帶 WGS84 座標**，不需要地理編碼 —— 這是它比路口規則
好處理的地方。取締項目是一串頓號分隔的中文，需要分類成本 App 在意的類別。

刻意排除：
- 純違規停車的點位。騎乘中警示停車執法沒有意義。
- 國道測速照相。白牌機車本來就禁止進入國道（ADR-0006），那份資料對我們無用。

用法：
    python import_enforcement.py
"""

from __future__ import annotations

import csv
import io
import json
import pathlib
import sys
import urllib.request

BUILD = pathlib.Path(__file__).parent / "build"
TAIPEI_SMART_PID = "986fa73e-c470-4ebf-9f35-3a1c9d2a8788"

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
    points = fetch_taipei_smart()
    relevant = [p for p in points if p["riding_relevant"]]
    turn = [p for p in points if p["enforces_turn"]]

    BUILD.mkdir(exist_ok=True)
    (BUILD / "enforcement_raw.json").write_text(
        json.dumps(relevant, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"臺北市科技執法  {len(points)} 筆")
    print(f"  騎乘相關      {len(relevant)}（其餘為純違規停車，已排除）")
    print(f"  取締轉彎      {len(turn)}  <- 與待轉規則重疊者構成最高等級警示")
    counts: dict[str, int] = {}
    for p in points:
        for c in p["categories"]:
            counts[c] = counts.get(c, 0) + 1
    print("  分類分布      " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
