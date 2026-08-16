"""產生實地查核清單。

騎士能在現場驗證的只有一件事：某個方向左轉，現場標誌說的是什麼。所以清單按
**風險**排序，而不是按行政區。

風險最高的是「我們說直接左轉、實際卻要待轉」—— 那會讓騎士吃罰單。反過來
（我們說待轉、實際可直接左轉）只是慢一點。再加上規則越老越可能已經改建，
2009 年以前生效的那批是首要目標。

**影像判讀跟官方資料不一致的排在最前面。** 影像的權威不足以改規則（見
build_seed.load_image_checks），但它已經指出「這裡有兩種說法」，而騎過去看一眼
正是唯一能結案的動作。這條清單就是那些矛盾的去處。

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
CONFLICTS = BUILD / "image_conflicts.csv"

DIR = {0.0: "北", 90.0: "東", 180.0: "南", 270.0: "西"}
RULE = {0: "（已降級為中性）", 1: "待轉", 2: "直接左轉", 3: "內側專用道", 4: "外側專用道"}


def load_conflicts() -> dict[tuple[str, str, str], str]:
    """影像判讀與官方不一致的規則：{(lat, lon, 方位角): 影像說的規定}。

    用座標與方位角當鍵，不用路口原文 —— rules 表裡沒有路口原文，而這兩個欄位
    兩邊都直接來自同一個 junctions.json，是精確相等的。方位角不能省：同一個
    路口的同一條進入道路有兩個來向，少了它兩條都會被標成有矛盾。
    """
    if not CONFLICTS.exists():
        return {}
    with open(CONFLICTS, encoding="utf-8-sig", newline="") as f:
        return {
            (row["lat"], row["lon"], row["approach"]):
                f'{row["image_says"]}（{row["captured_on"] or "日期不明"}）'
            for row in csv.DictReader(f)
        }


def heading(row: dict) -> str:
    """面向的顯示字。rules 表存的是幾何實際方位角（106.5°，不是 90°），
    所以查不到正方位時要退回角度值 —— 否則整欄印成空白。"""
    return DIR.get(row["approach_bearing"], f"{row['approach_bearing']:.0f}°")


def priority(row: dict) -> tuple[int, int, int]:
    """排序鍵：數字越小越該先去。"""
    # 影像已經指出矛盾的最優先 —— 那不是「可能過期」，是「現在有兩種說法」。
    contested = 0 if row.get("image_says") else 1
    # 說「直接左轉」而現場其實要待轉 —— 這個方向的錯誤會開罰單。
    risky_direction = 0 if row["turn_rule"] == 2 else 1
    return (contested, risky_direction, row["effective_since"] or 9999)


def main() -> int:
    db = sqlite3.connect(BUILD / "scooter_seed.db")
    db.row_factory = sqlite3.Row
    rows = [dict(r) for r in db.execute("SELECT * FROM rules")]

    conflicts = load_conflicts()
    for r in rows:
        r["image_says"] = conflicts.get(
            (str(r["lat"]), str(r["lon"]), str(r["approach_bearing"])), "")
    rows.sort(key=priority)

    with open(OUT, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["順位", "面向", "進入道路", "離開道路", "我們說的規定",
                    "影像說的規定", "生效年", "信心", "降級原因", "地圖"])
        for i, r in enumerate(rows, start=1):
            w.writerow([
                i,
                heading(r),
                r["entry_road_name"], r["exit_road_name"],
                RULE.get(r["turn_rule"], "?"),
                r["image_says"],
                r["effective_since"] or "",
                r["confidence"],
                r["downgrade_reason"] or "",
                f"https://www.google.com/maps?q={r['lat']},{r['lon']}",
            ])

    contested = [r for r in rows if r["image_says"]]
    old = [r for r in rows if not r["image_says"] and r["turn_rule"] == 2
           and (r["effective_since"] or 9999) <= 2009]
    print(f"清單  {OUT}  共 {len(rows)} 筆")
    print(f"  最優先：影像與官方不一致 = {len(contested)} 筆")
    print(f"  其次：直接左轉 + 2009 年以前生效 = {len(old)} 筆")
    print(f"  已降級：{sum(1 for r in rows if r['turn_rule'] == 0)} 筆\n")
    for r in contested[:10]:
        print(f"  ⚠ 面向{heading(r)}  {r['entry_road_name']} ➔ "
              f"{r['exit_road_name']}　我們說 {RULE.get(r['turn_rule'], '?')}、"
              f"影像說 {r['image_says']}"
              f"  https://www.google.com/maps?q={r['lat']},{r['lon']}")
    print("=== 沒有影像矛盾、但規則最老的前 15 筆 ===")
    for r in old[:15]:
        print(f"  面向{heading(r)}  {r['entry_road_name']} ➔ "
              f"{r['exit_road_name']}   ({r['effective_since']}年)"
              f"  https://www.google.com/maps?q={r['lat']},{r['lon']}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
