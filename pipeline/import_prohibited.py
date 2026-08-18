"""匯入臺北市**平面道路全面禁行機車**路段。

這份資料 2026-08-18 才被找到，此前整個專案都以為「機車禁行路段清單要自己建」
（ADR-0006 的原文就是這樣假設的）。它其實是開放資料，而且只有 **5 筆**：

    堤頂大道1段（北往南）、大直橋往明水路匝道（南往北）、
    環河北路（北往南）、忠孝西路（西往東、東往西）

**這是路線層的資料，不是轉向層的。** 它說的是「這條路機車完全不能走」，
用途是 ADR-0006 那道安全網：路線算完之後驗證有沒有踩到，踩到就重算。
不要跟「內側車道禁行機車」混為一談 —— 那是車道級的東西，本專案不收集
（ADR-0011：收結果不收成因）。

**與待轉規則的資料來源是同一個機關的不同清單**，所以方向欄的慣例也一樣：
用**來向**（「北往南」＝從北邊過來），匯入時就轉成本專案統一的**面向**。

目前只做到解析。**座標還沒解**（起訖點是路名，要走 taipei_geocode 的
RoadIndex），因此輸出裡的 `geocoded` 一律是 false，也還沒有任何東西讀它 ——
下一步見 HANDOVER 第一節 1b。
"""

from __future__ import annotations

import csv
import io
import json
import pathlib
import sys
import urllib.request

from taipei_parse import HEADING, OPPOSITE
from taiwan import normalize_tw

BUILD = pathlib.Path(__file__).parent / "build"
CACHE = BUILD / "cache"

# data.taipei「臺北市平面道路禁行機車」，交通局交工處。不定期更新。
RESOURCE_ID = "2c833533-071f-4b3c-9d17-39662d805b66"
URL = (
    "https://data.taipei/api/frontstage/tpeod/dataset/resource.download"
    f"?rid={RESOURCE_ID}"
)

# 這份 CSV 是 Big5。與待轉那三份一樣 —— 同一個機關出的，編碼慣例也一樣。
ENCODING = "big5"


def travel_bearing(raw: str) -> tuple[float | None, str | None]:
    """把「北往南」這種來向表述轉成行進的**面向**方位角。

    刻意不重用 `taipei_parse.parse_direction`：那個函式會一併算出「左轉後的
    離開方位角」，因為它服務的是路口規則。路段沒有離開方位角，硬套會在輸出裡
    多出一個看起來有意義、其實沒有的欄位 —— 那種欄位遲早會被人拿去用。
    """
    part = raw.strip()
    if "往" not in part:
        return None, f"無法解析的方向: {part!r}"
    frm, to = (s.strip() for s in part.split("往", 1))
    # 「大直橋往 明水路匝道」這種欄位裡，方向欄本身仍然是純方位字。
    if frm not in HEADING or to not in HEADING:
        return None, f"非方位字: {part!r}"
    return HEADING[OPPOSITE[frm]], None


def fetch_csv(refetch: bool = False) -> str:
    """下載並快取。1 KB 的東西，但快取讓重跑管線不必連外。"""
    CACHE.mkdir(parents=True, exist_ok=True)
    cached = CACHE / f"prohibited_{RESOURCE_ID}.csv"
    if cached.exists() and not refetch:
        return cached.read_text(encoding="utf-8")

    req = urllib.request.Request(URL, headers={"User-Agent": "scooter-pipeline/1"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read()
    text = raw.decode(ENCODING)
    cached.write_text(text, encoding="utf-8")
    return text


def parse(text: str) -> tuple[list[dict], list[str]]:
    rows: list[dict] = []
    warnings: list[str] = []

    for row in csv.DictReader(io.StringIO(text)):
        road = normalize_tw((row.get("路名") or "").strip())
        raw_dir = (row.get("方向") or "").strip()
        bearing, warn = travel_bearing(raw_dir)
        if warn:
            warnings.append(f"{road}: {warn}")
            continue

        rows.append({
            "district": (row.get("行政區") or "").strip(),
            "region_code": (row.get("地址-行政區域代碼") or "").strip(),
            "road": road,
            # 統一成面向。原始欄位一併留著，出問題時要查得回去。
            "bearing": bearing,
            "direction_raw": raw_dir,
            "from_road": normalize_tw((row.get("起點") or "").strip()),
            "to_road": normalize_tw((row.get("迄點") or "").strip()),
            "speed_limit": int(row["速限"]) if (row.get("速限") or "").strip().isdigit() else None,
            "reason": (row.get("管制原因") or "").strip(),
            # 替代動線是給人看的，不進資料庫；留在原始檔裡供日後做「為什麼繞路」的說明。
            "alternative": (row.get("建議替代動線") or "").strip(),
            "geocoded": False,
        })

    if not rows:
        warnings.append("一筆都沒解析出來 —— 檢查編碼與欄位名是否改過")
    return rows, warnings


def selftest() -> int:
    cases = {
        "北往南": 180.0,   # 從北邊過來，車頭朝南
        "南往北": 0.0,
        "西往東": 90.0,
        "東往西": 270.0,
    }
    bad = 0
    for raw, expected in cases.items():
        got, warn = travel_bearing(raw)
        if warn or got != expected:
            print(f"  ✗ {raw} -> {got}（應為 {expected}）{warn or ''}")
            bad += 1
    # 來向與面向的第一個字恆為相反，這是整個專案最常搞錯的一件事。
    for side in ("東", "西", "南", "北"):
        raw = f"{side}往{OPPOSITE[side]}"
        got, _ = travel_bearing(raw)
        if got != HEADING[OPPOSITE[side]]:
            print(f"  ✗ {raw} 的面向應為 {OPPOSITE[side]}")
            bad += 1
    print("selftest:", "全部通過" if not bad else f"{bad} 項失敗")
    return 1 if bad else 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()

    text = fetch_csv(refetch="--refetch" in sys.argv)
    rows, warnings = parse(text)

    BUILD.mkdir(exist_ok=True)
    (BUILD / "prohibited_raw.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"平面道路全面禁行機車  {len(rows)} 筆")
    for r in rows:
        limit = f"{r['speed_limit']} km/h" if r["speed_limit"] else "無速限資料"
        print(f"  {r['district']} {r['road']}  面向 {r['bearing']:.0f}°"
              f"（原始「{r['direction_raw']}」）  {r['from_road']} → {r['to_road']}  {limit}")
    for w in warnings:
        print("  ⚠", w)

    print("\n座標尚未解析（geocoded 全部為 false）——")
    print("  起訖點是路名，要走 taipei_geocode 的 RoadIndex 才解得出交叉點座標。")
    print("  在那之前這份輸出不會進種子庫，也沒有任何東西讀它。")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
