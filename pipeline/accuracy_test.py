"""量測街景判讀的準確度，對照種子資料庫的已知規則。

先前的盲測有個致命弱點：五個路口的正確答案全是「待轉」，一個永遠回答待轉的
模型也會拿滿分。這支程式改從資料庫抽**混合題組** —— 官方認定的免待轉例外、
官方認定的待轉例外、以及實地確認過的路口 —— 這樣答對才有意義。

影像依**規則的進入方位角**挑選（相機朝向要與騎士行進方向相符），而不是隨便
取一張。規則掛在進入方位角上，朝東的照片說不了朝西那側的事。

用法：
    python accuracy_test.py            # 預設抽 20 題
    python accuracy_test.py --n 8
"""

from __future__ import annotations

import json
import pathlib
import random
import sqlite3
import sys
from datetime import datetime, timezone

import classify_streetview as cs
import config

BUILD = pathlib.Path(__file__).parent / "build"
OUT = BUILD / "accuracy_test.json"

LABEL = {-1: "禁止左轉", 0: "無法判定", 1: "待轉", 2: "直接左轉",
         3: "內側專用道", 4: "外側專用道"}

# 相機朝向與規則進入方位角的最大容差。超過就不是同一個來向的視角。
BEARING_TOLERANCE = 50.0


def sample(n: int) -> list[dict]:
    """抽混合題組：免待轉例外優先，補上待轉例外，確保兩類都有。"""
    db = sqlite3.connect(BUILD / "scooter_seed.db")
    db.row_factory = sqlite3.Row
    rows = [dict(r) for r in db.execute(
        "SELECT * FROM rules WHERE turn_rule IN (1,2,3,4)")]

    exempt = [r for r in rows if r["turn_rule"] == 2]
    hook = [r for r in rows if r["turn_rule"] == 1]
    lanes = [r for r in rows if r["turn_rule"] in (3, 4)]

    random.seed(20260815)
    random.shuffle(exempt)
    random.shuffle(hook)

    picked = exempt[: max(1, n * 2 // 3)] + hook[: max(1, n // 3)] + lanes
    return picked[:n]


def best_image(images: list[dict], approach_bearing: float) -> dict | None:
    """挑相機朝向最接近進入方位角、且最新的一張。"""
    candidates = []
    for image in images:
        angle = image.get("compass_angle")
        if angle is None or not image.get("thumb_2048_url"):
            continue
        delta = abs((angle - approach_bearing + 540.0) % 360.0 - 180.0)
        if delta <= BEARING_TOLERANCE:
            candidates.append((delta, image.get("captured_at") or 0, image))
    if not candidates:
        return None
    # 先看新舊，同期再看角度 —— 老照片的標誌可能已經換掉。
    candidates.sort(key=lambda c: (-(c[1] // 31_536_000_000), c[0]))
    return candidates[0][2]


def main() -> int:
    n = int(sys.argv[sys.argv.index("--n") + 1]) if "--n" in sys.argv else 20
    token = config.get("MAPILLARY_TOKEN")
    api_key = config.get("CGU_API_KEY")

    rules = sample(n)
    print(f"題組 {len(rules)} 題："
          f"免待轉 {sum(1 for r in rules if r['turn_rule'] == 2)}、"
          f"待轉 {sum(1 for r in rules if r['turn_rule'] == 1)}、"
          f"專用道 {sum(1 for r in rules if r['turn_rule'] in (3, 4))}\n")

    results, agree, disagree, no_image, undecided, errors = [], 0, 0, 0, 0, 0

    for i, rule in enumerate(rules, start=1):
        entry, exit_road = rule["entry_road_name"], rule["exit_road_name"]
        truth = rule["turn_rule"]
        verified = rule["verified_on"]
        head = f"[{i:>2}/{len(rules)}] {entry} ➔ {exit_road} 面向 {rule['approach_bearing']:.0f}°"

        images = cs.mapillary_images(token, rule["lat"], rule["lon"])
        image = best_image(images, rule["approach_bearing"])
        if image is None:
            no_image += 1
            print(f"{head}\n    無合適角度的影像（共 {len(images)} 張）")
            results.append({"rule_id": rule["id"], "truth": truth, "verdict": None,
                            "status": "no_image"})
            continue

        captured = image.get("captured_at")
        when = (datetime.fromtimestamp(captured / 1000, tz=timezone.utc).date().isoformat()
                if captured else "?")
        try:
            context = cs.build_context(entry, exit_road,
                                       rule["approach_bearing"], rule["exit_bearing"])
            described = cs.describe(api_key, image["thumb_2048_url"], context)
        except Exception as e:
            # 單題失敗不該中止全批 —— 前面已經花掉的額度不能白費。
            errors += 1
            print(f"{head}\n    -- 判讀失敗：{e}")
            results.append({"rule_id": rule["id"], "truth": truth,
                            "verdict": None, "status": "error", "error": str(e)})
            continue
        verdict, why = cs.classify(described["parsed"])

        if verdict in (0, -1):
            status, undecided = "undecided", undecided + 1
            mark = "??"
        elif verdict == truth:
            status, agree, mark = "agree", agree + 1, "OK"
        else:
            status, disagree, mark = "disagree", disagree + 1, "XX"

        tag = "（實地確認）" if verified else ""
        print(f"{head}\n    {mark} 官方 {LABEL[truth]}{tag} / 判讀 {LABEL[verdict]}"
              f"  影像 {when}\n        {why}")

        results.append({
            "rule_id": rule["id"], "entry": entry, "exit": exit_road,
            "approach_bearing": rule["approach_bearing"],
            "truth": truth, "verdict": verdict, "status": status,
            "verified_on": verified, "captured": when,
            "image_id": image["id"], "description": described["parsed"],
        })

    BUILD.mkdir(exist_ok=True)
    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")

    decided = agree + disagree
    print(f"\n=== 結果 ===")
    print(f"  一致    {agree}")
    print(f"  不一致  {disagree}")
    print(f"  判不出  {undecided}")
    print(f"  無影像  {no_image}")
    print(f"  請求失敗 {errors}")
    if decided:
        print(f"  有判定者的一致率 {agree / decided:.0%}（{agree}/{decided}）")
    print(f"  明細（含每張影像的原始描述）：{OUT}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
