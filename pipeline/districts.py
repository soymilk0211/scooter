"""某個縣市的行政區界，用來把路口候選點限制在正確的區內。

存在的理由：涵蓋一個縣市的矩形**必然包含鄰縣**的一大塊。新北同樣有仁愛路、
安和路、中正路這些名字，會產生根本不存在的假交叉點。CSV 每列都有「行政區」，
拿它過濾就能把多數歧義自動解掉。

**查詢不用 bbox，用縣市界本身**（`area` 過濾）。原本這裡寫死了台北的矩形，
換一個縣市就要再抄一組座標進來 —— 而那組座標正是問題的來源，不是解法。
以縣市界當範圍，這個模組就沒有任何一個縣市專屬的常數了。

行政區界只抓一次並依縣市快取，之後的判定都在本機做。
"""

from __future__ import annotations

import json
import pathlib
import urllib.parse
import urllib.request

import taiwan

BUILD = pathlib.Path(__file__).parent / "build"
ENDPOINT = "https://overpass-api.de/api/interpreter"
DEFAULT_CITY = "臺北市"

# 台灣 OSM：縣市為 admin_level 4，鄉鎮市區為 admin_level 7。
#
# 臺／台兩種寫法各查一次，不用正規表示式 —— Overpass 對 `name` 的 regex 比對
# 走不到索引，`^[臺台]北市$` 實測直接回 504，而精確比對是即時的。
QUERY_TEMPLATE = """
[out:json][timeout:180];
area["boundary"="administrative"]["admin_level"="4"]["name"="{city}"]->.city;
rel(area.city)["boundary"="administrative"]["admin_level"="7"];
out geom;
""".strip()


def _cache_path(city: str) -> pathlib.Path:
    # 檔名帶縣市，否則第二個縣市會讀到第一個的快取，而且看起來像「這個縣市
    # 沒有這些區」——是錯誤的答案，不是錯誤訊息。
    return BUILD / f"districts_{taiwan.normalize_tw(city)}.json"


def _query(city_spelling: str) -> dict:
    query = QUERY_TEMPLATE.format(city=city_spelling)
    body = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(ENDPOINT, data=body, headers={"User-Agent": "scooter-pipeline/0.1"})
    return json.loads(urllib.request.urlopen(req, timeout=300).read())


def _fetch(city: str) -> dict:
    cache = _cache_path(city)
    if cache.exists():
        return json.loads(cache.read_text(encoding="utf-8"))
    data: dict = {}
    for spelling in taiwan.tw_variants(city):
        data = _query(spelling)
        if data.get("elements"):
            break
    if not data.get("elements"):
        # 空結果不快取。快取一份空的，下一次執行會把「查不到」當成「這個縣市
        # 沒有行政區」，而那個答案永遠不會自己好起來。
        raise SystemExit(f"Overpass 查不到 {city} 的行政區界（試過 {taiwan.tw_variants(city)}）")
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return data


def _rings(relation: dict) -> list[list[tuple[float, float]]]:
    """把 relation 的 outer 成員線段接成封閉環。

    OSM 的行政區界是由多條 way 組成的，順序與方向都不保證，必須自己接。
    """
    segments = [
        [(p["lat"], p["lon"]) for p in m["geometry"]]
        for m in relation.get("members", [])
        if m.get("type") == "way" and m.get("role") in ("outer", "") and m.get("geometry")
    ]
    rings: list[list[tuple[float, float]]] = []
    while segments:
        ring = segments.pop(0)
        joined = True
        while joined and ring[0] != ring[-1]:
            joined = False
            for i, seg in enumerate(segments):
                if seg[0] == ring[-1]:
                    ring += seg[1:]
                elif seg[-1] == ring[-1]:
                    ring += seg[-2::-1]
                elif seg[-1] == ring[0]:
                    ring = seg[:-1] + ring
                elif seg[0] == ring[0]:
                    ring = seg[:0:-1] + ring
                else:
                    continue
                segments.pop(i)
                joined = True
                break
        if len(ring) >= 4:
            rings.append(ring)
    return rings


def _in_ring(point: tuple[float, float], ring: list[tuple[float, float]]) -> bool:
    """射線法。點在環內回傳 True。"""
    lat, lon = point
    inside = False
    n = len(ring)
    for i in range(n):
        y1, x1 = ring[i]
        y2, x2 = ring[(i + 1) % n]
        if (y1 > lat) != (y2 > lat):
            x_cross = x1 + (lat - y1) / (y2 - y1) * (x2 - x1)
            if lon < x_cross:
                inside = not inside
    return inside


class Districts:
    def __init__(self, city: str = DEFAULT_CITY) -> None:
        self.city = city
        self._rings: dict[str, list[list[tuple[float, float]]]] = {}
        for el in _fetch(city).get("elements", []):
            name = (el.get("tags") or {}).get("name")
            if not name:
                continue
            rings = _rings(el)
            if rings:
                self._rings.setdefault(name, []).extend(rings)

    def names(self) -> list[str]:
        return sorted(self._rings)

    def resolve_name(self, csv_district: str) -> str | None:
        """CSV 寫「大安」，OSM 寫「大安區」。"""
        for candidate in (csv_district, f"{csv_district}區"):
            if candidate in self._rings:
                return candidate
        return None

    def contains(self, csv_district: str, lat: float, lon: float) -> bool | None:
        """點是否落在該行政區內。查無此區時回傳 None（無法判定，不等於否）。"""
        name = self.resolve_name(csv_district)
        if name is None:
            return None
        return any(_in_ring((lat, lon), ring) for ring in self._rings[name])


if __name__ == "__main__":
    import sys

    sys.stdout.reconfigure(encoding="utf-8")
    city = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_CITY
    d = Districts(city)
    print(f"{d.city} 載入 {len(d.names())} 個行政區")
    print(", ".join(d.names()))
    # 台北車站應在中正區內、不在大安區內。這組定錨點只對臺北市有意義 ——
    # 換縣市時只印出區名，讓人自己看區數對不對，不假造一個會永遠通過的檢查。
    if taiwan.normalize_tw(d.city) == taiwan.normalize_tw(DEFAULT_CITY):
        for district, expected in (("中正", True), ("大安", False)):
            got = d.contains(district, 25.0478, 121.5170)
            print(f"  {'ok  ' if got == expected else 'FAIL'} 台北車站 in {district}? "
                  f"{got} (預期 {expected})")
