"""產生實地查核清單。

騎士能在現場驗證的只有一件事：某個方向左轉，現場標誌說的是什麼。所以清單按
**風險**排序，而不是按行政區。

風險最高的是「我們說直接左轉、實際卻要待轉」—— 那會讓騎士吃罰單。反過來
（我們說待轉、實際可直接左轉）只是慢一點。再加上規則越老越可能已經改建，
2009 年以前生效的那批是首要目標。

用法：
    python make_ride_list.py
"""

from __future__ import annotations

import csv
import pathlib
import sqlite3
import sys

BUILD = pathlib.Path(__file__).parent / "build"
OUT = BUILD / "ride_check.csv"

DIR = {0.0: "北", 90.0: "東", 180.0: "南", 270.0: "西"}
RULE = {0: "（已降級為中性）", 1: "待轉", 2: "直接左轉", 3: "內側專用道", 4: "外側專用道"}


def priority(row: dict) -> tuple[int, int]:
    """排序鍵：數字越小越該先去。"""
    # 說「直接左轉」而現場其實要待轉 —— 這個方向的錯誤會開罰單。
    risky_direction = 0 if row["turn_rule"] == 2 else 1
    return (risky_direction, row["effective_since"] or 9999)


def main() -> int:
    db = sqlite3.connect(BUILD / "scooter_seed.db")
    db.row_factory = sqlite3.Row
    rows = [dict(r) for r in db.execute("SELECT * FROM rules")]
    rows.sort(key=priority)

    with open(OUT, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["順位", "面向", "進入道路", "離開道路", "我們說的規定",
                    "生效年", "信心", "降級原因", "地圖"])
        for i, r in enumerate(rows, start=1):
            w.writerow([
                i,
                DIR.get(r["approach_bearing"], f"{r['approach_bearing']:.0f}°"),
                r["entry_road_name"], r["exit_road_name"],
                RULE.get(r["turn_rule"], "?"),
                r["effective_since"] or "",
                r["confidence"],
                r["downgrade_reason"] or "",
                f"https://www.google.com/maps?q={r['lat']},{r['lon']}",
            ])

    old = [r for r in rows if r["turn_rule"] == 2 and (r["effective_since"] or 9999) <= 2009]
    print(f"清單  {OUT}  共 {len(rows)} 筆")
    print(f"  最優先：直接左轉 + 2009 年以前生效 = {len(old)} 筆")
    print(f"  已降級：{sum(1 for r in rows if r['turn_rule'] == 0)} 筆\n")
    print("=== 最優先的前 15 筆 ===")
    for r in old[:15]:
        print(f"  面向{DIR.get(r['approach_bearing'], '')}  {r['entry_road_name']} ➔ "
              f"{r['exit_road_name']}   ({r['effective_since']}年)"
              f"  https://www.google.com/maps?q={r['lat']},{r['lon']}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
