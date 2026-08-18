"""建立「座標 + 方位角 → 路名」索引。

**存在的理由**：路線引擎不提供路名。BRouter 的 `lookups.dat` 是一份標籤字典，
圖磚裡存的是索引對，那只對「值可以列舉」的標籤成立 —— 路名幾乎每條都不同，
字典會跟資料一樣大。整份 lookups.dat 裡 `name` 的條目數是 0。
**那是格式的限制，自建圖磚也解不了**（ADR-0017）。

所以路名走我們自己的管線。這個模組做的事與 `taipei_geocode.py` 是同一類 ——
向 Overpass 要帶路名的道路幾何 —— 差別只在它要的是**全部**的路，不是特定幾條。

輸出 `build/road_names.json`：一條路一筆，帶名稱與折線。
`build_seed.py` 再把它切成線段、按網格索引寫進種子庫。

**只收導航指示會唸到的路。** service／footway／path／steps／track／cycleway
一律排除 —— 沒有人會被導航說「請轉進某某巷的服務道路」，而它們佔的量很大。
"""

from __future__ import annotations

import json
import pathlib
import sys
import urllib.parse
import urllib.request

BUILD = pathlib.Path(__file__).parent / "build"
OUT = BUILD / "road_names.json"

ENDPOINTS = [
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass-api.de/api/interpreter",
]

# 臺北市大致範圍。與 taipei_geocode 的 TAIPEI_BBOX 是同一個東西，
# 但這裡刻意不 import —— 那個模組會連帶拉進一堆路口解析的機制。
BBOX = "25.01,121.45,25.15,121.63"

# 導航指示會唸到的路型。排除的那些不是「不重要」，是**不會被唸出來** ——
# 沒有人會被導航說「請轉進某某服務道路」。
WANTED = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street"


def fetch() -> list[dict]:
    query = (
        f'[out:json][timeout:280];'
        f'way({BBOX})["highway"~"^({WANTED})$"]["name"];'
        f'out geom;'
    )
    last = None
    for url in ENDPOINTS:
        try:
            data = urllib.parse.urlencode({"data": query}).encode()
            req = urllib.request.Request(url, data=data, headers={"User-Agent": "scooter-pipeline/1"})
            with urllib.request.urlopen(req, timeout=300) as resp:
                return json.loads(resp.read().decode("utf-8")).get("elements", [])
        except Exception as exc:  # noqa: BLE001
            last = exc
            print(f"  {url} 失敗：{exc}")
    raise SystemExit(f"全部端點失敗：{last}")


def main() -> int:
    print("向 Overpass 要臺北市有名字的道路幾何 …")
    ways = fetch()

    roads = []
    for w in ways:
        geometry = w.get("geometry") or []
        name = (w.get("tags") or {}).get("name")
        if not name or len(geometry) < 2:
            continue
        roads.append({
            "id": w["id"],
            "name": name,
            "highway": w["tags"].get("highway"),
            "points": [[round(p["lat"], 6), round(p["lon"], 6)] for p in geometry],
        })

    BUILD.mkdir(exist_ok=True)
    OUT.write_text(json.dumps(roads, ensure_ascii=False), encoding="utf-8")

    segments = sum(len(r["points"]) - 1 for r in roads)
    names = {r["name"] for r in roads}
    print(f"  道路 {len(roads)} 條、線段 {segments} 段、不重複路名 {len(names)} 個")
    print(f"  原始檔 {OUT.stat().st_size / 1_048_576:.1f} MB -> {OUT.name}")

    by_type: dict[str, int] = {}
    for r in roads:
        by_type[r["highway"]] = by_type.get(r["highway"], 0) + 1
    print("  路型分布：" + "、".join(f"{k}={v}" for k, v in sorted(by_type.items(), key=lambda kv: -kv[1])))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
