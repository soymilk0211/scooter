"""把廊道判讀頁匯出的 JSON 併回 field_checks.json。

**影像判讀不是實地查核。** 訪談定下的證據覆蓋順序是

    實地查核 > 官方 > 影像查核（依影像年份遞減）> 回報共識 > 觀察

所以判讀結果寫進 field_checks.json 的 `image_checks`，不是 `checks`。兩個陣列在
結構上就分開，權威差異才不會退化成一個要記得去讀的旗標。build_seed.py 讀
`checks` 會蓋掉官方資料，讀 `image_checks` 只會比對 —— 不一致的寫進複查清單，
等人騎過去看牌子。

已經有實地查核的（路口, 面向）一律略過。有人到現場看過牌子之後，一張 2020 年的
照片沒有資格覆寫它。

用法：
    python apply_image_checks.py build/image_checks_內湖路一段_東.json
    python apply_image_checks.py *.json --dry-run
"""

from __future__ import annotations

import json
import pathlib
import sys

BUILD = pathlib.Path(__file__).parent / "build"
FIELD_CHECKS = pathlib.Path(__file__).parent / "field_checks.json"

RULE_LABEL = {0: "中性播報", 1: "待轉", 2: "直接左轉", 3: "內側專用道", 4: "外側專用道"}
FACING = {0.0: "北", 90.0: "東", 180.0: "南", 270.0: "西"}

IMAGE_COMMENT = [
    "影像判讀結果。**權威低於官方資料**，與 checks（實地查核）不是同一個等級。",
    "",
    "證據覆蓋順序：實地查核 > 官方 > 影像查核（依影像年份遞減）> 回報共識 > 觀察。",
    "build_seed.py 不會拿這裡的東西去改規則，只會比對；不一致的寫進",
    "build/image_conflicts.csv，由人決定要不要騎一趟。",
    "",
    "由 make_corridor_page.py 產生的頁面判讀後，用 apply_image_checks.py 併入。",
]


def official_rules() -> dict[tuple[str, float], dict]:
    """官方清冊現在說什麼，以（路口原文, 面向角度）為鍵。"""
    raw = json.loads((BUILD / "rules_raw.json").read_text(encoding="utf-8"))
    return {(r["junction_text"], float(r["approach_bearing"])): r for r in raw}


def load_document() -> dict:
    return json.loads(FIELD_CHECKS.read_text(encoding="utf-8"))


def settled_keys(document: dict) -> set[tuple[str, float]]:
    """已經有實地查核的（路口, 面向）。"""
    out = set()
    for check in document.get("checks", []):
        for rule in check.get("rules", []):
            out.add((check["junction_text"], float(rule["approach_bearing"])))
    return out


def key_of(check: dict) -> tuple[str, float]:
    rule = check["rules"][0]
    return check["junction_text"], float(rule["approach_bearing"])


def described(rule: dict) -> str:
    if rule.get("exists") is False:
        return "無左轉動線"
    return RULE_LABEL.get(rule.get("turn_rule"), "?")


def main() -> int:
    paths = [pathlib.Path(a) for a in sys.argv[1:] if not a.startswith("--")]
    dry_run = "--dry-run" in sys.argv
    if not paths:
        print(__doc__)
        return 1

    incoming: list[dict] = []
    for path in paths:
        if not path.exists():
            print(f"找不到 {path}")
            return 1
        payload = json.loads(path.read_text(encoding="utf-8"))
        found = payload.get("image_checks", [])
        if not found:
            print(f"{path.name}：沒有 image_checks，跳過"
                  f"{'（這個檔看起來是舊格式的 checks）' if payload.get('checks') else ''}")
        incoming.extend(found)

    if not incoming:
        return 1

    document = load_document()
    settled = settled_keys(document)
    official = official_rules()
    existing = {key_of(c): i for i, c in enumerate(document.get("image_checks", []))}

    added, replaced, skipped = 0, 0, 0
    agree, disagree, unknown = [], [], []
    checks = list(document.get("image_checks", []))

    for check in incoming:
        key = key_of(check)
        junction, bearing = key
        if key in settled:
            print(f"略過（已有實地查核）：{junction} 面向{FACING.get(bearing, bearing)}")
            skipped += 1
            continue

        source = official.get(key)
        if source is None:
            unknown.append(key)
        else:
            said = source["turn_rule"]
            now = check["rules"][0]
            same = now.get("exists") is not False and now.get("turn_rule") == said
            (agree if same else disagree).append(
                (junction, bearing, RULE_LABEL.get(said, "?"), described(now)))

        if key in existing:
            checks[existing[key]] = check
            replaced += 1
        else:
            existing[key] = len(checks)
            checks.append(check)
            added += 1

    checks.sort(key=lambda c: (c["junction_text"], float(c["rules"][0]["approach_bearing"])))
    document["_image_comment"] = IMAGE_COMMENT
    document["image_checks"] = checks

    print(f"\n新增 {added}、更新 {replaced}、略過 {skipped}")
    print(f"  與官方一致  {len(agree)}")
    print(f"  與官方不同  {len(disagree)}")
    for junction, bearing, said, saw in disagree:
        print(f"    {junction} 面向{FACING.get(bearing, bearing)}："
              f"官方說 {said}，影像看起來是 {saw}")
    if unknown:
        print(f"  對不上官方清冊 {len(unknown)} 筆（路口原文改過？）")
        for junction, bearing in unknown:
            print(f"    {junction} 面向{FACING.get(bearing, bearing)}")

    if dry_run:
        print("\n--dry-run，沒有寫檔。")
        return 0

    # newline="\n"：.gitattributes 要求整個 repo 用 LF。Windows 上 write_text
    # 預設會轉成 CRLF，於是每跑一次這支程式就整檔 churn 一次。
    FIELD_CHECKS.write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8", newline="\n")
    print(f"\n已寫入 {FIELD_CHECKS}")
    print("接著跑 python build_seed.py，不一致的會列進 build/image_conflicts.csv。")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
