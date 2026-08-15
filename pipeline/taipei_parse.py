"""把台北市機車例外管制路口的 CSV 正規化成規則列。

僅負責解析，不做地理編碼 —— 座標由 taipei_geocode.py 接手。分階段是刻意的：
解析是確定性的、可反覆驗證；地理編碼要打外部 API 且會失敗，兩者不該綁在一起。

用法：
    python taipei_parse.py            # 下載並解析，輸出 build/rules_raw.json
    python taipei_parse.py --selftest # 只跑方向解析的自我驗證
"""

from __future__ import annotations

import csv
import io
import json
import pathlib
import re
import sys
import urllib.request

PID = "77c7af11-5e86-462a-9f10-6fc3777ef943"
VIEW = f"https://data.taipei/api/frontstage/tpeod/dataset.view?id={PID}"
BUILD = pathlib.Path(__file__).parent / "build"

# 方位角：正北 0°，順時針。
HEADING = {"北": 0.0, "東": 90.0, "南": 180.0, "西": 270.0}
OPPOSITE = {"北": "南", "南": "北", "東": "西", "西": "東"}
AXIS = {"南北": ("南", "北"), "東西": ("東", "西")}


def left_of(bearing: float) -> float:
    """左轉後的方位角。"""
    return (bearing - 90.0) % 360.0


def parse_direction(raw: str) -> tuple[list[dict], list[str]]:
    """把「方向」欄展開成一或多組 (進入方位角, 離開方位角)。

    慣例（由 114 筆資料反推並經交叉驗證）：「A往B」= 從 A 側來、往 B 側去，
    因此車頭朝向是 A 的反向。當 B 是 A 的對向時，該值只描述進入的車道方向
    （例如「南往北」即北向車道），離開方向由「這是左轉規則」隱含推得。

    回傳 (結果, 警告)。警告不代表失敗，但該筆需要人工覆核。
    """
    warnings: list[str] = []
    out: list[dict] = []

    for part in (p.strip() for p in raw.split("、") if p.strip()):
        if part.endswith("雙向"):
            axis = part[:-2]
            if axis not in AXIS:
                warnings.append(f"未知的雙向軸線: {part}")
                continue
            for side in AXIS[axis]:
                approach = HEADING[OPPOSITE[side]]
                out.append({
                    "approach_bearing": approach,
                    "exit_bearing": left_of(approach),
                    "exit_inferred": True,
                })
            continue

        if "往" not in part:
            warnings.append(f"無法解析的方向: {part}")
            continue

        frm, to = (s.strip() for s in part.split("往", 1))
        if frm not in HEADING or to not in HEADING:
            warnings.append(f"非方位字: {part}")
            continue

        approach = HEADING[OPPOSITE[frm]]

        if to == OPPOSITE[frm]:
            # 直線型：只標示進入車道，左轉方向隱含。
            out.append({
                "approach_bearing": approach,
                "exit_bearing": left_of(approach),
                "exit_inferred": True,
            })
        else:
            exit_bearing = HEADING[to]
            if abs(exit_bearing - left_of(approach)) > 1e-6:
                # 交叉驗證：轉向型的離開方向必須是進入方向的左轉。
                warnings.append(f"{part} 不是左轉（進入 {approach:.0f}° 的左轉應為 {left_of(approach):.0f}°）")
            out.append({
                "approach_bearing": approach,
                "exit_bearing": exit_bearing,
                "exit_inferred": False,
            })

    if not out:
        warnings.append(f"方向欄無法產生任何規則: {raw!r}")
    return out, warnings


def parse_roads(raw: str) -> tuple[list[tuple[str, str]], list[str]]:
    """把「路口」欄拆成一或多組路名配對。

    「A、B與C」代表 C 分別與 A、B 相交，展開成兩組配對 —— 那是兩個相鄰路口
    或同一路口的兩條分支，交給地理編碼階段判定。

    回傳的配對**沒有進入／離開之分**：路口欄只說哪兩條路相交，沒說騎士從哪條
    來。要等取得座標後，比對哪條路的走向符合進入方位角才能決定。
    """
    text = raw.strip()
    if text.endswith("口"):
        text = text[:-1]
    # 絕大多數用「與」，少數用「及」。兩者語意相同。
    separator = next((s for s in ("與", "及") if s in text), None)
    if separator is None:
        return [], [f"路口欄找不到分隔字: {raw!r}"]

    left, right = (s.strip() for s in text.split(separator, 1))
    lefts = [s.strip() for s in left.split("、") if s.strip()]
    rights = [s.strip() for s in right.split("、") if s.strip()]
    if not lefts or not rights:
        return [], [f"路口欄拆解後為空: {raw!r}"]

    pairs = [(a, b) for a in lefts for b in rights]
    warnings = [f"展開成 {len(pairs)} 組路口配對: {raw!r}"] if len(pairs) > 1 else []
    return pairs, warnings


def parse_year(raw: str) -> tuple[int | None, list[str]]:
    """民國年 → 西元年。「98年以前」取 98；「98-101年間」取 98。"""
    m = re.search(r"(\d+)", raw or "")
    if not m:
        return None, [f"無法解析年份: {raw!r}"]
    return int(m.group(1)) + 1911, []


def fetch_resources() -> list[dict]:
    req = urllib.request.Request(VIEW, headers={"User-Agent": "scooter-pipeline"})
    payload = json.loads(urllib.request.urlopen(req, timeout=60).read())["payload"]
    return payload["resources"]


def read_csv(resource: dict) -> list[dict]:
    url = "https://data.taipei" + resource["url"]
    req = urllib.request.Request(url, headers={"User-Agent": "scooter-pipeline"})
    raw = urllib.request.urlopen(req, timeout=60).read()
    text = raw.decode(resource.get("encoding") or "utf-8")
    return list(csv.DictReader(io.StringIO(text)))


# TurnRule 的序數值，與 core-rules 的 TurnRule enum 一致。
HOOK, DIRECT = 1, 2


def build_rules() -> tuple[list[dict], list[dict]]:
    """回傳 (可用規則, 需人工覆核的列)。"""
    resources = {r["name"]: r for r in fetch_resources()}
    sources = [
        ("臺北市三(含)車道以上例外開放機車直接左轉路口", DIRECT, "開放時間"),
        ("臺北市二車道路段例外實施兩段式左轉管制清冊", HOOK, "實施時間"),
    ]

    rules, review = [], []
    for name, turn_rule, year_col in sources:
        resource = next(r for k, r in resources.items() if k == name)
        for line_no, row in enumerate(read_csv(resource), start=2):
            warnings: list[str] = []
            pairs, w = parse_roads(row["路口"])
            warnings += w
            year, w = parse_year(row.get(year_col, ""))
            warnings += w
            directions, w = parse_direction(row["方向"])
            warnings += w

            for road_a, road_b in pairs:
                for d in directions:
                    rules.append({
                        "source": name,
                        "line": line_no,
                        "region": row["行政區"],
                        "region_code": row["地址-行政區域代碼"],
                        "junction_text": row["路口"],
                        # 原始標示，僅供人工審查對照。它的第一個字是「從哪來」，
                        # 與我們儲存的 approach_bearing（騎士面向）必然相反。
                        "direction_raw": row["方向"],
                        # 尚未區分進入／離開，待地理編碼比對走向後決定。
                        "road_a": road_a,
                        "road_b": road_b,
                        "turn_rule": turn_rule,
                        "effective_since": year,
                        **d,
                    })
            if warnings:
                review.append({"line": line_no, "source": name, "row": row, "warnings": warnings})

    return rules, review


def selftest() -> int:
    cases = {
        # 轉向型：四種左轉樣態
        "北往東": [(180.0, 90.0, False)],
        "西往北": [(90.0, 0.0, False)],
        "南往西": [(0.0, 270.0, False)],
        "東往南": [(270.0, 180.0, False)],
        # 直線型：只標進入車道，左轉隱含
        "南往北": [(0.0, 270.0, True)],
        "北往南": [(180.0, 90.0, True)],
        # 雙向：展開成兩條，順序為「從南側來（北向車道）」、「從北側來（南向車道）」
        "南北雙向": [(0.0, 270.0, True), (180.0, 90.0, True)],
        "東西雙向": [(270.0, 180.0, True), (90.0, 0.0, True)],
        # 多值
        "南往西、 西往北": [(0.0, 270.0, False), (90.0, 0.0, False)],
    }
    failures = 0
    for raw, expected in cases.items():
        got, warnings = parse_direction(raw)
        actual = [(g["approach_bearing"], g["exit_bearing"], g["exit_inferred"]) for g in got]
        ok = actual == expected and not warnings
        print(f"  {'ok  ' if ok else 'FAIL'} {raw:<16} -> {actual}")
        if warnings:
            print(f"       warnings: {warnings}")
        failures += 0 if ok else 1
    print(f"\n{len(cases) - failures}/{len(cases)} passed")
    return failures


def main() -> int:
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0

    rules, review = build_rules()
    BUILD.mkdir(exist_ok=True)
    (BUILD / "rules_raw.json").write_text(
        json.dumps(rules, ensure_ascii=False, indent=1), encoding="utf-8")
    (BUILD / "review.json").write_text(
        json.dumps(review, ensure_ascii=False, indent=1), encoding="utf-8")

    inferred = sum(1 for r in rules if r["exit_inferred"])
    print(f"規則列    {len(rules)}（其中 {inferred} 筆離開方向為推導）")
    print(f"待覆核    {len(review)}")
    print(f"最舊生效  {min((r['effective_since'] for r in rules if r['effective_since']), default='-')}")
    for item in review[:10]:
        print(f"  line {item['line']}: {'; '.join(item['warnings'])}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
