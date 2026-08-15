"""查 Mapillary 在各路口有沒有街景影像。

這是動工前的可行性確認：如果台北的路口根本沒有影像，後面整套自動判讀都是空的。
選 Mapillary 而非 Google 街景，是因為它的授權明確允許衍生資料集 —— 拿影像產生
一份路口規則資料庫正是我們要做的事。

查的是**已定位**的路口。尚待補座標的那些沒有座標可查，它們的瓶頸不是影像而是定位。

用法：
    python mapillary_coverage.py            # 查全部已定位路口
    python mapillary_coverage.py --limit 20
"""

from __future__ import annotations

import json
import math
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

import config

BUILD = pathlib.Path(__file__).parent / "build"
OUT = BUILD / "mapillary_coverage.json"
ENDPOINT = "https://graph.mapillary.com/images"

# 路口周邊的搜尋半徑。太大會撈到隔壁路口的影像，太小會漏掉停在路口前拍的照片。
SEARCH_RADIUS_M = 45.0
REQUEST_INTERVAL_S = 0.4


def bbox_around(lat: float, lon: float, radius_m: float) -> str:
    dlat = radius_m / 111_000.0
    dlon = radius_m / (111_000.0 * max(0.01, math.cos(math.radians(lat))))
    return f"{lon - dlon},{lat - dlat},{lon + dlon},{lat + dlat}"


def query(token: str, lat: float, lon: float) -> list[dict]:
    params = urllib.parse.urlencode({
        "access_token": token,
        "fields": "id,captured_at,compass_angle,is_pano,computed_geometry",
        "bbox": bbox_around(lat, lon, SEARCH_RADIUS_M),
        "limit": 50,
    })
    req = urllib.request.Request(f"{ENDPOINT}?{params}",
                                 headers={"User-Agent": "scooter-pipeline/0.1"})
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.loads(response.read()).get("data", [])


def year_of(captured_at: int | None) -> int | None:
    if not captured_at:
        return None
    return datetime.fromtimestamp(captured_at / 1000, tz=timezone.utc).year


def main() -> int:
    token = config.get("MAPILLARY_TOKEN")
    print(f"使用 token {config.masked(token)}\n")

    junctions = json.loads((BUILD / "junctions.json").read_text(encoding="utf-8"))
    located = {k: v for k, v in junctions.items() if v.get("lat") is not None}
    keys = sorted(located)
    if "--limit" in sys.argv:
        keys = keys[: int(sys.argv[sys.argv.index("--limit") + 1])]

    results: dict[str, dict] = {}
    covered = 0
    for i, key in enumerate(keys, start=1):
        v = located[key]
        road_a, road_b, district = key.split("|")
        try:
            images = query(token, v["lat"], v["lon"])
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")[:200]
            print(f"HTTP {e.code}：{body}")
            if e.code in (401, 403):
                print("token 無效或權限不足 —— 確認註冊時有勾選 READ。")
                return 1
            images = []
        except (urllib.error.URLError, TimeoutError) as e:
            print(f"  連線失敗 {key}: {e}")
            images = []

        years = sorted({y for y in (year_of(im.get("captured_at")) for im in images) if y})
        panos = sum(1 for im in images if im.get("is_pano"))
        results[key] = {
            "lat": v["lat"], "lon": v["lon"], "district": district,
            "image_count": len(images), "years": years, "panoramic": panos,
        }
        if images:
            covered += 1

        mark = "  " if images else "XX"
        span = f"{years[0]}–{years[-1]}" if years else "-"
        print(f"{mark} [{i:>3}/{len(keys)}] {district} {road_a} × {road_b}"
              f"  影像 {len(images):>2} 張（{span}）全景 {panos}", flush=True)
        time.sleep(REQUEST_INTERVAL_S)

    BUILD.mkdir(exist_ok=True)
    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")

    total_images = sum(r["image_count"] for r in results.values())
    recent = sum(1 for r in results.values() if r["years"] and r["years"][-1] >= 2022)
    print(f"\n覆蓋率 {covered}/{len(keys)}（{covered / max(1, len(keys)):.0%}）")
    print(f"  影像總數    {total_images}")
    print(f"  2022 年後有影像的路口  {recent}")
    print(f"  明細        {OUT}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
