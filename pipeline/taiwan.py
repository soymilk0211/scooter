"""台灣的行政區代碼與路名寫法，管線各處共用。

存在的理由：臺／台的正規化原本在 `make_corridor_page` 與 `import_enforcement`
各有一份，而**兩份的語義並不相同** —— 前者是「臺→台 **加上** 段號中文化」，
後者只是讓線號的正規表示式同時吃兩種寫法。直接合成一份會把段號中文化套到
測速點的地址上，改到不該改的字。所以這裡拆成兩層：

- `normalize_tw()`  只統一臺／台，供地址、線號、縣市名比對使用
- `canonical_road()` 在前者之上再做段號中文化，只供**路名**比對使用

縣市這一層不用名字當鍵。規則本身帶的是內政部的 8 碼行政區代碼
（`63000010` = 臺北市松山區），而**前 5 碼就是縣市**，資料裡本來就有。
拿名字當鍵要一路處理「臺北市／台北市／台北」的變體 —— 那正是
`field_checks.json` 不用路名當鍵的同一個理由：最容易出錯的欄位不該當鍵。

**刻意不硬編 22 個縣市的代碼對照表。** 那張表在這裡沒有辦法驗證，
而一個打錯的代碼不會報錯，只會安靜地對不上。縣市名跟著登錄資料一起寫
（見 `default_rules.py`），加縣市的人手上就有那份資料可以核對。
"""

from __future__ import annotations

import re

ARABIC_TO_CHINESE = {"1": "一", "2": "二", "3": "三", "4": "四", "5": "五",
                     "6": "六", "7": "七", "8": "八", "9": "九"}

CITY_CODE_LENGTH = 5


def normalize_tw(text: str) -> str:
    """統一臺／台的寫法，取「台」。

    政府開放資料同一個欄位裡兩種寫法都有（`台61線` 與 `臺61線`），
    OSM 則慣用「臺」（查 `台灣大道` 只回一段，實際標的是 `臺灣大道`）。
    比對前一律先過這一層，否則台中會整批對不上（台北剛好沒踩到）。
    """
    return text.replace("臺", "台")


def canonical_road(name: str) -> str:
    """路名比對用的正規化形式：臺／台 + 段號中文化。

    `康寧路3段` 與 `內湖路一段` 是同一份 CSV 出來的，命令列打哪一種
    都該找得到同一條廊道。**只用在路名上** —— 地址裡的數字（門牌、里程）
    不是段號，套下去會把 `台61線60.1公里處` 改壞。
    """
    out = normalize_tw(name.strip())
    return re.sub(r"(\d)(?=段)", lambda m: ARABIC_TO_CHINESE.get(m.group(1), m.group(1)), out)


def tw_variants(name: str) -> tuple[str, ...]:
    """名稱的臺／台兩種寫法，「臺」在前。

    給 Overpass 這類**精確比對**的查詢用：我們不知道 OSM 那一筆用的是哪一種
    寫法（慣例是「臺」，但那是慣例不是保證），所以兩種都試。
    不做成正規表示式，是因為 Overpass 對 `name` 的 regex 比對走不到索引 ——
    實測 `^[臺台]北市$` 直接回 504，而精確比對是即時的。
    """
    first = name.replace("台", "臺")
    second = normalize_tw(name)
    return (first,) if first == second else (first, second)


def city_of(region_code: str) -> str:
    """從 8 碼行政區代碼取出縣市碼（前 5 碼）。

    直轄市與縣市的碼長一致，都是「5 碼縣市 + 3 碼鄉鎮市區」：
    臺北市 `63000`、臺中市 `66000`、宜蘭縣 `10002`、基隆市 `10017`。
    """
    code = region_code.strip()
    if len(code) < CITY_CODE_LENGTH or not code.isdigit():
        raise ValueError(f"不是合法的行政區代碼：{region_code!r}")
    return code[:CITY_CODE_LENGTH]


def _selftest() -> int:
    cases: list[tuple[str, object, object]] = [
        ("normalize_tw 線號", normalize_tw("臺61線"), "台61線"),
        ("normalize_tw 已是台", normalize_tw("台61線"), "台61線"),
        ("normalize_tw 縣市", normalize_tw("臺北市"), "台北市"),
        ("canonical_road 阿拉伯段號", canonical_road("康寧路3段"), "康寧路三段"),
        ("canonical_road 中文段號不動", canonical_road("內湖路一段"), "內湖路一段"),
        ("canonical_road 臺台", canonical_road("臺灣大道"), "台灣大道"),
        # 地址裡的數字不是段號，不該被改（這正是兩份不能合成一份的理由）
        ("canonical_road 只動段號前一位", canonical_road("台61線60.1公里處"), "台61線60.1公里處"),
        ("tw_variants 從台", tw_variants("台北市"), ("臺北市", "台北市")),
        ("tw_variants 從臺", tw_variants("臺北市"), ("臺北市", "台北市")),
        ("tw_variants 無臺台", tw_variants("宜蘭縣"), ("宜蘭縣",)),
        ("city_of 直轄市", city_of("63000010"), "63000"),
        ("city_of 縣", city_of("10002010"), "10002"),
    ]
    failed = 0
    for label, got, want in cases:
        ok = got == want
        failed += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {label}: {got!r}" + ("" if ok else f"（預期 {want!r}）"))
    for bad in ("", "abc", "630"):
        try:
            city_of(bad)
        except ValueError:
            print(f"  ok   city_of 拒絕 {bad!r}")
        else:
            failed += 1
            print(f"  FAIL city_of 應該拒絕 {bad!r}")
    print("全部通過" if not failed else f"{failed} 項失敗")
    return 1 if failed else 0


if __name__ == "__main__":
    import sys

    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(_selftest())
