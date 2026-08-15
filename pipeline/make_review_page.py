"""產生規則審查頁，供人工掃視自動判定的進入／離開道路順序。

順序是由地理編碼階段依道路走向推得的，無法自動驗證 —— 顯示錯了不影響安全
（路名不參與比對，見 ADR-0001），但會很難看。這頁把 99 筆全部攤開讓人掃。

用法：
    python make_review_page.py
"""

from __future__ import annotations

import html
import json
import pathlib
import sqlite3
import sys

BUILD = pathlib.Path(__file__).parent / "build"
TEMPLATE_PATH = pathlib.Path(__file__).parent / "review_template.html"
OUT = BUILD / "rule_review.html"

RULE_LABEL = {1: "待轉", 2: "直接左轉", 3: "內側專用道", 4: "外側專用道"}
RULE_CLASS = {1: "hook", 2: "direct", 3: "lane-rule", 4: "lane-rule"}
COMPASS_LABEL = {0.0: "北", 90.0: "東", 180.0: "南", 270.0: "西"}
ORDER_LABEL = {"verified": "已實地確認", "auto": "走向判定",
               "lane": "同路名巷弄", "check": "待確認"}

COMPASS = (
    '<svg viewBox="0 0 24 24" class="glyph" aria-hidden="true">'
    '<g transform="rotate({rot} 12 12)">'
    '<path d="M12 22 L12 12 L6 12" fill="none" stroke="currentColor" stroke-width="2.1"'
    ' stroke-linecap="round" stroke-linejoin="round"/>'
    '<path d="M2.4 12 L7.4 9.3 L7.4 14.7 Z" fill="currentColor"/>'
    "</g></svg>"
)


def load() -> list[dict]:
    junctions = json.loads((BUILD / "junctions.json").read_text(encoding="utf-8"))
    raw = json.loads((BUILD / "rules_raw.json").read_text(encoding="utf-8"))

    district_by_code: dict[str, str] = {}
    raw_direction: dict[tuple[str, str, float], str] = {}
    for r in raw:
        district_by_code.setdefault(r["region_code"], r["region"])
        raw_direction[(r["road_a"], r["road_b"], r["approach_bearing"])] = r.get("direction_raw", "")
        raw_direction[(r["road_b"], r["road_a"], r["approach_bearing"])] = r.get("direction_raw", "")

    ordered_by_pair: dict[tuple[str, str], bool] = {}
    for v in junctions.values():
        if v.get("status") != "ok":
            continue
        entry, exit_road = v.get("entry_road_name"), v.get("exit_road_name")
        if entry and exit_road:
            ordered_by_pair[(entry, exit_road)] = bool(v.get("roads_ordered"))

    db = sqlite3.connect(BUILD / "scooter_seed.db")
    db.row_factory = sqlite3.Row
    rows = []
    for r in db.execute(
        "SELECT * FROM rules ORDER BY region_code, entry_road_name, approach_bearing"
    ):
        d = dict(r)
        entry, exit_road = d["entry_road_name"], d["exit_road_name"]
        auto = ordered_by_pair.get((entry, exit_road), False)
        # 「幹道 × 該幹道的巷」判定不出來，是因為兩條路在路口幾乎平行；
        # 但預設順序（幹道在前）本來就正確，不需人工確認。
        lane_of_same_road = "巷" in exit_road and exit_road.startswith(entry)
        d["district"] = district_by_code.get(d["region_code"], d["region_code"])
        # 實地查核凌駕一切自動推導 —— 有人到現場看過牌子，就沒什麼好「待確認」的。
        d["order_state"] = (
            "verified" if d["verified_on"]
            else "auto" if auto
            else "lane" if lane_of_same_road
            else "check"
        )
        d["direction_raw"] = raw_direction.get((entry, exit_road, d["approach_bearing"]), "")
        rows.append(d)
    return rows


def render_row(r: dict) -> str:
    rule_cls = RULE_CLASS.get(r["turn_rule"], "direct")
    heading = COMPASS_LABEL.get(r["approach_bearing"], f"{r['approach_bearing']:.0f}°")
    exit_head = COMPASS_LABEL.get(r["exit_bearing"], f"{r['exit_bearing']:.0f}°")
    return (
        f'<tr data-order="{r["order_state"]}" data-rule="{r["turn_rule"]}">'
        f'<td class="c-glyph {rule_cls}">{COMPASS.format(rot=r["approach_bearing"])}</td>'
        f'<td class="c-roads"><span class="entry">{html.escape(r["entry_road_name"])}</span>'
        f'<span class="arrow">➔</span>'
        f'<span class="exit">{html.escape(r["exit_road_name"])}</span></td>'
        f'<td class="c-head">{heading}<span class="sep">→</span>{exit_head}</td>'
        f'<td class="c-raw">{html.escape(r["direction_raw"]) or "—"}</td>'
        f'<td><span class="badge {rule_cls}">{RULE_LABEL.get(r["turn_rule"], "?")}</span></td>'
        f'<td><span class="chip {r["order_state"]}">{ORDER_LABEL[r["order_state"]]}</span></td>'
        f'<td class="c-num">{r["effective_since"] or "—"}</td>'
        f'<td class="c-num">{r["confidence"]}</td>'
        f"</tr>"
    )


def build() -> str:
    rows = load()
    counts = {
        "total": len(rows),
        "check": sum(1 for r in rows if r["order_state"] == "check"),
        "verified": sum(1 for r in rows if r["order_state"] == "verified"),
        "lane": sum(1 for r in rows if r["order_state"] == "lane"),
        "auto": sum(1 for r in rows if r["order_state"] == "auto"),
        "hook": sum(1 for r in rows if r["turn_rule"] == 1),
        "direct": sum(1 for r in rows if r["turn_rule"] == 2),
        "districts": len({r["district"] for r in rows}),
    }

    groups: dict[str, list[dict]] = {}
    for r in rows:
        groups.setdefault(r["district"], []).append(r)

    sections = []
    for district, items in sorted(groups.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        need = sum(1 for i in items if i["order_state"] == "check")
        flag = f'<span class="sec-flag">{need} 待確認</span>' if need else ""
        body = "\n".join(render_row(r) for r in items)
        sections.append(
            f'<section class="district">'
            f'<h2>{html.escape(district)}<span class="sec-count">{len(items)}</span>{flag}</h2>'
            f'<div class="scroll"><table>'
            f'<thead><tr><th class="th-glyph"><span class="sr">行進方向</span></th>'
            f"<th>進入道路 ➔ 離開道路</th><th>騎士面向</th><th>原始標示</th>"
            f"<th>規定</th><th>順序來源</th>"
            f'<th class="c-num">生效</th><th class="c-num">信心</th></tr></thead>'
            f"<tbody>{body}</tbody></table></div></section>"
        )

    page = TEMPLATE_PATH.read_text(encoding="utf-8")
    for key, value in counts.items():
        page = page.replace("{{" + key + "}}", str(value))
    return page.replace("{{sections}}", "\n".join(sections))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    BUILD.mkdir(exist_ok=True)
    OUT.write_text(build(), encoding="utf-8")
    print(f"wrote {OUT}  ({OUT.stat().st_size / 1024:.0f} KB)")
