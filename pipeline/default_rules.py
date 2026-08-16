"""預設規則的兩層結構：法源層與代理層（ADR-0009）。

多數路口沒有個別建檔，規則來自預設規則（ADR-0004）。問題在於**這些預設值
是怎麼推導出來的**，而那件事以前只寫在 `build_seed.py` 的一行註解裡。

## 法源層

道路交通安全規則 §99 強制兩段式左轉的觸發條件是**內側車道禁行機車**或
現場標誌。那個「三快車道以上」的說法**限定單行道**，不是一般道路的條件。

而這一層**目前推導不出任何東西**，因為觸發條件所需的資料不存在：
OSM 的 `motorcycle:lanes`（車道級禁行機車）在台中主要幹道的覆蓋率是 **0%**，
路段級的 `motorcycle` 只有 9% 且已知不可信（復北地下道實際禁行機車，
OSM 標成 `motorcycle=yes`）。

它仍然寫在這裡，是為了讓下面那張表的性質不被誤讀 —— 那張表不是法條。

## 代理層

各縣市自己的行政實務，以**縣市碼**為鍵（`taiwan.city_of()`）。臺北市交通局
的說明是「二車道原則上開放直接左轉，三車道以上原則上須待轉」，在臺北實務上
幾乎都對（三車道以上內側幾乎都禁行機車），但**那是巧合不是推導** ——
換一個縣市就不成立。

因此：**沒有登錄的縣市不產生預設規則**，那個縣市只會有個別建檔的路口。
沉默是刻意的。一個沒查證過的縣市，猜出來的預設規則會在幾千個路口同時播錯，
那是傷害不是保守（保守是在「縣市已登錄、單一路口缺資料」時偏向待轉）。

加一個縣市要做的事只有一件：在 `CITY_DEFAULTS` 加一筆，並在 `basis`
寫清楚依據是什麼。結構不必再改。
"""

from __future__ import annotations

from dataclasses import dataclass

import taiwan

TURN_UNKNOWN, TURN_HOOK, TURN_DIRECT = 0, 1, 2

# 法源層。目前沒有任何縣市推導得出來，見模組 docstring。
STATUTORY_TRIGGER = (
    "道路交通安全規則 §99：內側車道禁行機車或現場標誌。"
    "「三快車道以上」限定單行道，不適用於一般道路。"
    "所需的車道級禁行機車資料目前不存在（OSM motorcycle:lanes 覆蓋率 0%）。"
)


@dataclass(frozen=True)
class LaneBand:
    """一個車道數區間對應的左轉規則。

    「車道數」是單向還是雙向計算，官方說明沒有寫死。OSM 的 `lanes` 通常是
    雙向總數，`lanes:forward` 才是單向。比對時取單向數，因為法規談的是騎士
    所在方向的車道配置 —— 這是推論，尚未查證，所以信心給得保守。
    """

    min_lanes: int
    max_lanes: int
    turn_rule: int
    confidence: int


@dataclass(frozen=True)
class CityDefaults:
    """一個縣市的代理層推導。

    `basis` 是給人看的依據說明，不進資料庫 —— App 的播報內容不會因為依據是
    市府實務還是法條而改變，所以那是**建檔期**的資訊，不是騎乘期的。
    """

    city_name: str
    basis: str
    bands: tuple[LaneBand, ...]


CITY_DEFAULTS: dict[str, CityDefaults] = {
    "63000": CityDefaults(
        city_name="臺北市",
        basis=(
            "臺北市交通局說明：二車道道路原則上開放機車直接左轉，"
            "三車道以上原則上須待轉、個案檢討才免待轉。"
            "這是行政實務，不是 §99 的推導 —— 不可外推到其他縣市。"
        ),
        bands=(
            LaneBand(1, 2, TURN_DIRECT, 60),
            LaneBand(3, 99, TURN_HOOK, 70),
        ),
    ),
}


def defaults_for(region_code: str) -> CityDefaults | None:
    """查該行政區所屬縣市的預設規則。未登錄的縣市回傳 None（不產生預設規則）。"""
    return CITY_DEFAULTS.get(taiwan.city_of(region_code))


def _selftest() -> int:
    failed = 0

    taipei = defaults_for("63000010")
    checks: list[tuple[str, object, object]] = [
        ("臺北市查得到", taipei is not None, True),
        ("臺北市兩條 band", len(taipei.bands) if taipei else 0, 2),
        ("二車道直接左轉", taipei.bands[0].turn_rule if taipei else None, TURN_DIRECT),
        ("三車道以上待轉", taipei.bands[1].turn_rule if taipei else None, TURN_HOOK),
        # Q7：沒登錄的縣市沉默，不是猜一個
        ("臺中市未登錄", defaults_for("66000010"), None),
        ("宜蘭縣未登錄", defaults_for("10002010"), None),
        # 同一縣市的不同行政區共用同一份推導
        ("同縣市不同區一致", defaults_for("63000120"), taipei),
    ]
    for label, got, want in checks:
        ok = got == want
        failed += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {label}: {got!r}" + ("" if ok else f"（預期 {want!r}）"))

    # band 不重疊也不留空隙，否則同一個車道數會查到兩條規則或查不到
    for code, city in CITY_DEFAULTS.items():
        bands = sorted(city.bands, key=lambda b: b.min_lanes)
        for earlier, later in zip(bands, bands[1:]):
            ok = earlier.max_lanes + 1 == later.min_lanes
            failed += not ok
            print(f"  {'ok  ' if ok else 'FAIL'} {city.city_name} band 相接："
                  f"{earlier.max_lanes} -> {later.min_lanes}")
        ok = bands[0].min_lanes == 1
        failed += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {city.city_name} 從一車道起算")

    print("全部通過" if not failed else f"{failed} 項失敗")
    return 1 if failed else 0


if __name__ == "__main__":
    import sys

    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(_selftest())
