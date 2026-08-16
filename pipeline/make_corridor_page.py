"""產生廊道判讀頁：一條路、一個方向，沿路每個有左轉動線的路口一張街景影像。

判資料的瓶頸從來不是資料量，是「一個路口一個路口去查」的動線。這頁把一條廊道的
路口按**騎過去的順序**排好，每個路口配上朝著行進方向拍的 Mapillary 影像、經緯度
與面向角度，使用者點一下就記下規定，最後匯出成 field_checks.json 的形狀。

**影像預設內嵌進 HTML**，所以那一個檔案自己就是完整的：直接雙擊開、丟進任何檢視器、
搬去別的資料夾、寄給別人，圖都在。相對路徑（`--files`）只有「從影像資料夾旁邊開」
才成立，而破圖跟「這個路口沒有街景」在畫面上長得一模一樣 —— 那是這個工具最不該
搞錯的事。內嵌用 1024 寬、每個路口三張，把單頁壓在幾 MB 以內。

`--files` 改成相對路徑加 2048 寬、每個路口六張：頁面只有幾十 KB、放大看得更清楚，
代價是那個 HTML 必須跟 `build/corridor_images/` 放在一起。搭 `--serve` 用最省事。

**這頁不可以發布成 Artifact** —— 那裡的 CSP 擋外部主機，而 16 MB 上限與這頁的用途
（本機判資料）也不合。

判讀出來的東西**不是實地查核**。證據覆蓋順序是「實地查核 > 官方 > 影像查核」，
所以匯出的每一筆都帶 `evidence.kind = "image"`，build_seed.py 看到它不會拿去蓋掉
官方資料，只會在不一致時寫進複查清單。詳見 apply_image_checks.py。

用法：
    python make_corridor_page.py --list              # 有哪些廊道可以判
    python make_corridor_page.py 內湖路一段           # 該路每個面向各一頁
    python make_corridor_page.py 內湖路一段 --facing 東
    python make_corridor_page.py --all               # 全部廊道 + 目錄頁
    python make_corridor_page.py --serve             # 不重產，把 build/ 開成本機網站
    python make_corridor_page.py 內湖路一段 --files   # 相對路徑 + 2048 寬（頁面很小）
    python make_corridor_page.py 內湖路一段 --link    # 影像連 Mapillary（連結會過期）
    python make_corridor_page.py 內湖路一段 --refetch # 忽略快取重新查

**目錄頁的連結要用 --serve 開。** 從 file:// 跳到另一個 file:// 會被某些瀏覽器
與檢視器擋掉（畫面變成 `about:blank#blocked`），而目錄頁的用途正是跳轉。
單頁直接雙擊開沒問題，多頁互跳就起伺服器。
"""

from __future__ import annotations

import base64
import html
import json
import math
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timezone

import build_seed
import config

BUILD = pathlib.Path(__file__).parent / "build"
TEMPLATE_PATH = pathlib.Path(__file__).parent / "corridor_template.html"
INDEX_TEMPLATE_PATH = pathlib.Path(__file__).parent / "corridor_index_template.html"
INDEX_NAME = "corridor_index.html"
CACHE = BUILD / "cache"
IMAGE_DIR = BUILD / "corridor_images"
FIELD_CHECKS = pathlib.Path(__file__).parent / "field_checks.json"

ENDPOINT = "https://graph.mapillary.com/images"
# computed_compass_angle 是 Mapillary 由影像重建算出來的朝向；compass_angle 是上傳
# 裝置寫的，**大量影像那一欄是 0**（沒有羅盤資料就填 0，跟正北無法區分）。實測某個
# 路口 86 張全部 compass_angle=0，而 computed 那欄 100 張都有值、七成落在 105° 附近。
# 拿 compass_angle 篩方向等於把整條路的照片都當成朝北。
FIELDS = ("id,captured_at,compass_angle,computed_compass_angle,is_pano,"
          "thumb_1024_url,thumb_2048_url,computed_geometry")

# 45 公尺是 mapillary_coverage.py 用的半徑，兩邊保持一致，覆蓋率數字才可比。
# 找不到才放大到 90 —— 那個距離的照片通常看不清牌面，所以會標出距離讓人自己判斷。
SEARCH_RADIUS_M = 45.0
FALLBACK_RADIUS_M = 90.0
REQUEST_INTERVAL_S = 0.4
# 少於這個數量就再查一次。台北的大路口動輒上百張，個位數通常是被截斷的回應。
SUSPICIOUSLY_FEW = 5

# 相機朝向與行進方向差多少還算「朝著路口拍」。超過這個角度的照片拍的是側面，
# 看不到前方路口的牌子。全景不受這個限制 —— 它本來就每個方向都拍到了。
ANGLE_TOLERANCE = 55.0
MAX_IMAGES_PER_JUNCTION = 6
# 內嵌模式少放幾張。base64 會把檔案再撐大三分之一，六張一個路口的頁面實測 9 MB，
# 而太大的檔案某些檢視器會直接開不起來 —— 那比少三張候選照片嚴重得多。
# 排序是好的在前，所以砍掉的是最不可能用到的那幾張。
MAX_EMBEDDED_PER_JUNCTION = 3

FACING = {0.0: "北", 90.0: "東", 180.0: "南", 270.0: "西"}
FACING_BY_LABEL = {v: k for k, v in FACING.items()}
RULE_LABEL = {0: "中性播報", 1: "待轉", 2: "直接左轉", 3: "內側專用道", 4: "外側專用道"}

ARABIC_TO_CHINESE = {"1": "一", "2": "二", "3": "三", "4": "四", "5": "五",
                     "6": "六", "7": "七", "8": "八", "9": "九"}


def canonical(name: str) -> str:
    """路名比對用的正規化形式。

    `臺`／`台` 與阿拉伯／中文段號在同一份資料裡混用（`康寧路3段` 與 `內湖路一段`
    是同一個 CSV 出來的）。命令列打哪一種都該找得到同一條廊道。
    """
    out = name.strip().replace("臺", "台")
    return re.sub(r"(\d)(?=段)", lambda m: ARABIC_TO_CHINESE.get(m.group(1), m.group(1)), out)


def along(lat: float, lon: float, bearing: float) -> float:
    """座標沿著行進方向的投影（公尺）。

    用它排序，路口就會按**騎過去會遇到的順序**出現。按緯度或經度排在斜向的路上
    會亂掉，而台北斜的路不少。

    投影軸一律用正方位（0/90/180/270），不用各路口的實際方位角 —— 一條路的每個
    路口走向都差幾度，各投影各的會讓順序自相矛盾，而且同一條路的兩個方向不再是
    彼此的反序。沿路的分量夠大，正方位就足以定序。
    """
    north = lat * 111_320.0
    east = lon * 111_320.0 * math.cos(math.radians(lat))
    radians = math.radians(bearing)
    return north * math.cos(radians) + east * math.sin(radians)


def metres_between(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    dlat = (lat2 - lat1) * 111_320.0
    dlon = (lon2 - lon1) * 111_320.0 * math.cos(math.radians(lat1))
    return math.hypot(dlat, dlon)


def angle_gap(a: float, b: float) -> float:
    return abs((a - b + 540.0) % 360.0 - 180.0)


def year_of(captured_at: int | None) -> int | None:
    return datetime.fromtimestamp(captured_at / 1000, tz=timezone.utc).year if captured_at else None


def date_of(captured_at: int | None) -> str:
    if not captured_at:
        return "拍攝日期不明"
    return datetime.fromtimestamp(captured_at / 1000, tz=timezone.utc).date().isoformat()


def bbox(lat: float, lon: float, radius_m: float) -> str:
    dlat = radius_m / 111_000.0
    dlon = radius_m / (111_000.0 * max(0.01, math.cos(math.radians(lat))))
    return f"{lon - dlon},{lat - dlat},{lon + dlon},{lat + dlat}"


def query_images(token: str, lat: float, lon: float, radius: float) -> list[dict]:
    """打一次 Mapillary。錯誤會重試，設定類的錯誤（401/403）不重試。"""
    params = urllib.parse.urlencode({
        "access_token": token, "fields": FIELDS,
        "bbox": bbox(lat, lon, radius), "limit": 100,
    })
    last: Exception | None = None
    for attempt in range(3):
        try:
            request = urllib.request.Request(f"{ENDPOINT}?{params}",
                                             headers={"User-Agent": "scooter-pipeline/0.1"})
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.loads(response.read()).get("data", [])
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                raise SystemExit(f"Mapillary 回 {e.code} —— token 無效或權限不足。")
            last = e
        except (urllib.error.URLError, TimeoutError) as e:
            last = e
        time.sleep(2 * (attempt + 1))
    print(f"    查詢失敗（{last}），當作沒有影像")
    return []


def fetch_images(token: str, lat: float, lon: float, radius: float,
                 refetch: bool = False) -> list[dict]:
    """查一個路口周邊的影像。回應快取在 build/cache/，重跑不會重打 API。

    **會查兩次取多的那次。** Mapillary 偶爾回傳被截斷的結果 —— 實測同一個 bbox
    一次回 1 張、隔一分鐘回 86 張，沒有任何錯誤碼。而截斷的結果一旦寫進快取，
    這個路口就會永遠顯示成「沒有街景影像」，看起來像事實而不像故障。
    只有在第一次拿到的量少得可疑時才複查，正常情況仍是一個路口一次請求。
    """
    CACHE.mkdir(parents=True, exist_ok=True)
    # 檔名帶欄位版本。少了它，改 FIELDS 之後舊快取會照樣被讀進來，而缺欄位的症狀
    # 是「篩不出任何影像」—— 一樣看起來像沒有街景，不像快取過期。
    cached = CACHE / f"mapillary_v2_{lat:.6f}_{lon:.6f}_{radius:.0f}.json"
    if cached.exists() and not refetch:
        return json.loads(cached.read_text(encoding="utf-8"))

    data = query_images(token, lat, lon, radius)
    time.sleep(REQUEST_INTERVAL_S)
    if len(data) < SUSPICIOUSLY_FEW:
        time.sleep(1.5)
        second = query_images(token, lat, lon, radius)
        if len(second) > len(data):
            data = second
        time.sleep(REQUEST_INTERVAL_S)

    cached.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return data


def heading_of(image: dict) -> float | None:
    """影像的朝向。重建值優先，因為裝置寫的那欄常常是 0（見 FIELDS 的註解）。"""
    computed = image.get("computed_compass_angle")
    if computed is not None:
        return float(computed)
    raw = image.get("compass_angle")
    # 恰好是 0 的裝置值分不出「正北」與「沒有羅盤」，寧可當成未知 ——
    # 誤判成朝北會讓一整條東西向的路都通過方向篩選。
    return float(raw) if raw else None


def pick_images(images: list[dict], bearing: float, lat: float, lon: float,
                radius: float) -> list[dict]:
    """挑出朝著行進方向、能看到這個路口的影像，好的排前面。

    排序以**年份遞減**為主，這是訪談定下的證據強弱（影像查核依影像年份遞減）。
    同一年之內才比對準度與距離。不直接只留最好的一張 —— 街景照被公車擋住、
    逆光、路口不在畫面裡都很常見，多給幾張讓人自己翻，比挑錯一張再讓人放棄好。
    """
    picked = []
    for image in images:
        url = image.get("thumb_2048_url")
        if not url:
            continue
        angle = heading_of(image)
        pano = bool(image.get("is_pano"))
        if not pano and (angle is None or angle_gap(angle, bearing) > ANGLE_TOLERANCE):
            continue
        point = (image.get("computed_geometry") or {}).get("coordinates")
        distance = metres_between(lat, lon, point[1], point[0]) if point else radius
        picked.append({
            "id": image["id"],
            "url": url,
            "url_small": image.get("thumb_1024_url") or url,
            "captured_on": date_of(image.get("captured_at")),
            "year": year_of(image.get("captured_at")) or 0,
            "compass_angle": angle,
            "gap": round(angle_gap(angle, bearing), 1) if angle is not None else None,
            "is_pano": pano,
            "distance_m": round(distance),
        })
    picked.sort(key=lambda i: (-i["year"], i["gap"] if i["gap"] is not None else 999,
                               i["distance_m"]))
    return picked[:MAX_IMAGES_PER_JUNCTION]


def download(url: str, target: pathlib.Path) -> bool:
    """抓一張影像存到 target。已經在的就不重抓。"""
    if target.exists() and target.stat().st_size > 0:
        return True
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "scooter-pipeline/0.1"})
        with urllib.request.urlopen(request, timeout=120) as response:
            target.write_bytes(response.read())
        return True
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as e:
        print(f"    影像下載失敗 {url[:60]}…: {e}")
        return False


def as_data_uri(path: pathlib.Path) -> str:
    return "data:image/jpeg;base64," + base64.b64encode(path.read_bytes()).decode("ascii")


def already_field_checked() -> dict[tuple[str, float], dict]:
    """既有的**實地查核**，以（路口原文, 面向角度）為鍵。

    影像查核不算 —— 它們是這個工具自己產生的，重判一次沒有問題。實地查核則是
    有人騎到現場看過牌子，那條不該再拿一張 2016 年的照片去問一次。
    """
    if not FIELD_CHECKS.exists():
        return {}
    data = json.loads(FIELD_CHECKS.read_text(encoding="utf-8"))
    out: dict[tuple[str, float], dict] = {}
    for check in data.get("checks", []):
        if (check.get("evidence") or {}).get("kind") == "image":
            continue
        for rule in check.get("rules", []):
            out[(check["junction_text"], float(rule["approach_bearing"]))] = check
    return out


def corridors() -> dict[str, dict[float, list[dict]]]:
    """可判讀的廊道：{路名: {面向: [規則…]}}，只含已定位的路口。

    **同一條路的不同寫法會合併。** `成功路2段` 與 `成功路二段` 是同一條路，官方
    清冊兩種都用。不合併的話它們會產生同名的輸出檔互相覆蓋 —— 目錄上看得到兩列，
    點進去卻是同一頁，而使用者會以為兩邊都判過了。
    顯示名取資料裡出現最多次的那一種寫法。
    """
    raw = json.loads((BUILD / "rules_raw.json").read_text(encoding="utf-8"))
    junctions = json.loads((BUILD / "junctions.json").read_text(encoding="utf-8"))

    spellings: dict[str, dict[str, int]] = {}
    for rule in raw:
        counts = spellings.setdefault(canonical(rule["road_a"]), {})
        counts[rule["road_a"]] = counts.get(rule["road_a"], 0) + 1
    display = {key: max(counts, key=counts.get) for key, counts in spellings.items()}

    out: dict[str, dict[float, list[dict]]] = {}
    for rule in raw:
        key = f"{rule['road_a']}|{rule['road_b']}|{rule['region']}"
        junction = junctions.get(key)
        if junction is None or junction.get("status") != "ok" or junction.get("lat") is None:
            continue
        entry, exit_road = build_seed.road_names(junction, rule)
        approach, exit_bearing = build_seed.real_bearings(junction, rule)
        road = display[canonical(rule["road_a"])]
        out.setdefault(road, {}).setdefault(float(rule["approach_bearing"]), []).append({
            "junction_text": rule["junction_text"],
            "district": rule["region"],
            "lat": junction["lat"],
            "lon": junction["lon"],
            "entry_road_name": entry,
            "exit_road_name": exit_road,
            "facing": float(rule["approach_bearing"]),
            "approach_bearing": approach,
            "exit_bearing": exit_bearing,
            "turn_rule": rule["turn_rule"],
            "effective_since": rule["effective_since"],
            "direction_raw": rule.get("direction_raw", ""),
        })
    return out


def build_items(facing: float, rules: list[dict], token: str,
                mode: str, refetch: bool) -> list[dict]:
    """一個面向的所有路口，按騎過去的順序，各自配好影像。

    刻意分成兩段：先把所有 Mapillary 查詢做完，再一次下載所有影像。把 CDN 的
    下載夾在 API 呼叫中間會踩到節流，而節流的回應**不是錯誤，是一份比較短的清單**。
    """
    settled = already_field_checked()
    items = []
    # 同一個路口的同一個面向在 CSV 裡可能出現多次（雙向規則展開）。判一次就夠。
    seen: set[tuple[str, float]] = set()

    for rule in sorted(rules, key=lambda r: along(r["lat"], r["lon"], facing)):
        key = (rule["junction_text"], rule["facing"])
        if key in seen:
            continue
        seen.add(key)

        radius = SEARCH_RADIUS_M
        images = fetch_images(token, rule["lat"], rule["lon"], radius, refetch)
        chosen = pick_images(images, rule["approach_bearing"], rule["lat"], rule["lon"], radius)
        if not chosen:
            radius = FALLBACK_RADIUS_M
            images = fetch_images(token, rule["lat"], rule["lon"], radius, refetch)
            chosen = pick_images(images, rule["approach_bearing"], rule["lat"], rule["lon"], radius)

        prior = settled.get(key)
        items.append({
            **rule,
            "radius_m": radius,
            "nearby": len(images),
            "images": chosen,
            "settled": bool(prior),
            "settled_note": (prior or {}).get("note", ""),
            "settled_on": (prior or {}).get("checked_on", ""),
        })
        far = "（半徑 90m）" if radius > SEARCH_RADIUS_M else ""
        mark = "  ★已實地確認" if prior else ""
        print(f"  {FACING[facing]}向 {rule['junction_text']}"
              f"  朝向合用 {len(chosen)} / 周邊 {len(images)} 張{far}{mark}", flush=True)

    attach_sources(items, mode)
    return items


def attach_sources(items: list[dict], mode: str) -> None:
    """決定每張影像在 HTML 裡的 src。

    預設抓 2048 寬存進 corridor_images/，HTML 用相對路徑指過去：頁面只有幾十 KB，
    開起來即時，放大也還有細節可看。

    `embed` 改抓 1024 寬內嵌成 data URI，換來單檔可攜。用 1024 是因為內嵌會把
    base64 的三分之一膨脹算進檔案大小 —— 六個路口的 2048 版做出來 12 MB，
    某些檢視器會直接開不起來，而顯示寬度本來就只有一千出頭。
    """
    IMAGE_DIR.mkdir(parents=True, exist_ok=True)
    for item in items:
        wanted = item["images"]
        if mode == "embed":
            wanted = wanted[:MAX_EMBEDDED_PER_JUNCTION]
        kept = []
        for image in wanted:
            if mode == "link":
                image["src"] = image["url"]
                kept.append(image)
                continue
            wide = mode == "files"
            target = IMAGE_DIR / (f"{image['id']}.jpg" if wide else f"{image['id']}_1024.jpg")
            if not download(image["url"] if wide else image["url_small"], target):
                continue
            image["src"] = f"corridor_images/{target.name}" if wide else as_data_uri(target)
            kept.append(image)
        item["images"] = kept


COMPASS = (
    '<svg viewBox="0 0 24 24" class="glyph" aria-hidden="true">'
    '<g transform="rotate({rot} 12 12)">'
    '<path d="M12 22 L12 12 L6 12" fill="none" stroke="currentColor" stroke-width="2.1"'
    ' stroke-linecap="round" stroke-linejoin="round"/>'
    '<path d="M2.4 12 L7.4 9.3 L7.4 14.7 Z" fill="currentColor"/>'
    "</g></svg>"
)


def render_frame(index: int, image: dict) -> str:
    """一張影像。說明列擺的是**判斷這張照片值不值得信**所需要的三件事：
    什麼時候拍的、離路口多遠、鏡頭偏了幾度。"""
    facts = [image["captured_on"], f'{image["distance_m"]} m']
    if image["is_pano"]:
        facts.insert(1, "全景")
    if image["gap"] is not None:
        facts.append(f'偏 {image["gap"]:.0f}°')
    link = (f'<a href="https://www.mapillary.com/app/?focus=photo&pKey={html.escape(image["id"])}"'
            f' target="_blank" rel="noreferrer">在 Mapillary 開啟</a>')
    # data-id 與 data-captured 是匯出時要寫進 evidence 的東西 —— 判斷是看著
    # **哪一張**照片下的，換一張看就換一份證據。
    return (f'<figure class="frame" data-index="{index}" hidden'
            f' data-id="{html.escape(image["id"])}"'
            f' data-captured="{image["captured_on"]}">'
            f'<img loading="lazy" src="{html.escape(image["src"])}" alt="">'
            f'<figcaption>{html.escape("　".join(facts))}　{link}</figcaption></figure>')


def render_card(index: int, item: dict) -> str:
    frames = "".join(render_frame(n, image) for n, image in enumerate(item["images"]))
    if not item["images"]:
        frames = (f'<div class="noimage">半徑 {item["radius_m"]:.0f}m 內找到 '
                  f'{item["nearby"]} 張影像，沒有一張是朝這個方向拍的。'
                  f'{"要判它只能親自騎一趟 —— 用上面的座標連結。" if item["nearby"] else ""}'
                  f'</div>')

    settled = (
        f'<p class="settled">★ 已實地確認（{html.escape(item["settled_on"])}）'
        f'{"：" + html.escape(item["settled_note"][:120]) if item["settled_note"] else ""}</p>'
        if item["settled"] else ""
    )

    buttons = "".join(
        f'<button type="button" class="pick" data-rule="{value}">{label}</button>'
        for value, label in ((1, "待轉"), (2, "直接左轉"), (3, "內側專用道"),
                             (4, "外側專用道"))
    )

    return f"""
<article class="card{' is-settled' if item['settled'] else ''}" id="j{index}"
         data-junction="{html.escape(item['junction_text'])}"
         data-facing="{item['facing']:.0f}"
         data-current="{item['turn_rule']}">
  <header>
    <span class="seq">{index + 1}</span>
    <div class="who">
      <h2>{html.escape(item['junction_text'])}</h2>
      <p class="roads">{html.escape(item['entry_road_name'] or '?')}
        <span class="arrow">➔</span>{html.escape(item['exit_road_name'] or '?')}</p>
    </div>
    <div class="facing">{COMPASS.format(rot=item['approach_bearing'])}
      <span>面向{FACING[item['facing']]}<small>{item['approach_bearing']:.0f}°</small></span>
    </div>
  </header>
  <p class="says">目前資料說 <b class="rule-{item['turn_rule']}">
     {RULE_LABEL.get(item['turn_rule'], '?')}</b>
     <span class="meta">{item['effective_since'] or '—'} 年生效
     ・原始標示 {html.escape(item['direction_raw']) or '—'}
     ・<a href="https://www.google.com/maps?q={item['lat']},{item['lon']}"
          target="_blank" rel="noreferrer">{item['lat']:.6f}, {item['lon']:.6f}</a></span></p>
  {settled}
  <div class="viewer">
    {frames}
    <div class="strip">
      <button type="button" class="step" data-step="-1" aria-label="上一張">‹</button>
      <span class="counter"></span>
      <button type="button" class="step" data-step="1" aria-label="下一張">›</button>
      <span class="nearby">半徑 {item['radius_m']:.0f}m 內共 {item['nearby']} 張</span>
    </div>
  </div>
  <div class="picker">
    {buttons}
    <button type="button" class="pick wide" data-rule="none">這個方向沒有左轉動線</button>
    <button type="button" class="pick wide ghost" data-rule="unclear">看不出來</button>
  </div>
  <input class="note" type="text" placeholder="看到什麼？（會寫進 note，留白也行）">
</article>"""


MODE_LABEL = {
    "embed": "影像已內嵌，這個檔自己就完整",
    "files": "影像在 build/corridor_images/，要跟這個檔放在一起",
    "link": "影像連 Mapillary，需要網路且連結會過期",
}


def build_page(road: str, facing: float, items: list[dict], mode: str) -> str:
    with_images = sum(1 for i in items if i["images"])
    counts = {
        "road": html.escape(road),
        "facing": FACING[facing],
        "total": len(items),
        "withimages": with_images,
        "noimages": len(items) - with_images,
        "settled": sum(1 for i in items if i["settled"]),
        "generated": date.today().isoformat(),
        "storagekey": html.escape(f"corridor:{canonical(road)}:{facing:.0f}"),
        "offline": MODE_LABEL[mode],
    }
    cards = "\n".join(render_card(n, item) for n, item in enumerate(items))
    page = TEMPLATE_PATH.read_text(encoding="utf-8")
    for key, value in counts.items():
        page = page.replace("{{" + key + "}}", str(value))
    return page.replace("{{cards}}", cards)


def write_index(pages: list[dict]) -> pathlib.Path:
    """一次產多頁時的目錄。

    判資料是斷斷續續做上好幾天的事，而「我判到哪了」不該靠記憶 —— 每一列的進度
    直接讀同一份 localStorage，所以目錄上看到的數字就是那一頁裡真正按過的數量。
    """
    rows = "".join(
        f'<tr><td><a href="{html.escape(p["file"])}">'
        f'{html.escape(p["road"])}　面向{FACING[p["facing"]]}</a></td>'
        f'<td class="n">{p["total"]}</td>'
        f'<td class="n">{p["withimages"]}</td>'
        f'<td class="n">{p["settled"] or ""}</td>'
        f'<td class="n done" data-key="corridor:{html.escape(canonical(p["road"]))}'
        f':{p["facing"]:.0f}"></td></tr>'
        for p in sorted(pages, key=lambda p: (-p["withimages"], p["road"]))
    )
    total = sum(p["total"] for p in pages)
    with_images = sum(p["withimages"] for p in pages)
    page = INDEX_TEMPLATE_PATH.read_text(encoding="utf-8")
    for key, value in {
        "pages": len(pages), "total": total, "withimages": with_images,
        "generated": date.today().isoformat(),
    }.items():
        page = page.replace("{{" + key + "}}", str(value))
    out = BUILD / INDEX_NAME
    out.write_text(page.replace("{{rows}}", rows), encoding="utf-8")
    return out


def list_corridors() -> int:
    available = corridors()
    print(f"{'路名':<16}{'面向':<22}{'路口數'}")
    for road, by_facing in sorted(available.items(), key=lambda kv: -sum(
            len(v) for v in kv[1].values())):
        facings = "、".join(
            f"{FACING[f]}({len({r['junction_text'] for r in rules})})"
            for f, rules in sorted(by_facing.items()))
        total = sum(len({r["junction_text"] for r in rules}) for rules in by_facing.values())
        print(f"{road:<16}{facings:<22}{total}")
    return 0


def main() -> int:
    args = sys.argv[1:]
    if "--list" in args:
        return list_corridors()

    names = [a for a in args if not a.startswith("--")]
    flags = {a for a in args if a.startswith("--")}

    # 單獨的 --serve：不重產，直接把已經產好的頁面開成本機網站。
    # 需要它是因為 file:// 之間的跳轉會被某些檢視器擋掉（畫面是 about:blank#blocked），
    # 而目錄頁的用途正是跳轉。http 沒有那個限制。
    if "--serve" in flags and not names and "--all" not in flags:
        existing = sorted(p for p in BUILD.glob("corridor_*.html")
                          if p.name != INDEX_NAME)
        if not existing:
            print("build/ 裡沒有廊道頁。先跑 python make_corridor_page.py --all")
            return 1
        index = BUILD / INDEX_NAME
        return serve([index] if index.exists() else existing[:1])

    if "--all" in flags:
        names = list(corridors())
    if "--facing" in args:
        wanted = args[args.index("--facing") + 1]
        names = [n for n in names if n != wanted]
        if wanted not in FACING_BY_LABEL:
            print(f"面向要是 北／東／南／西 之一，收到「{wanted}」。")
            return 1
        facings = [FACING_BY_LABEL[wanted]]
    else:
        facings = None
    if not names:
        print(__doc__)
        return 1

    mode = "files" if "--files" in flags else "link" if "--link" in flags else "embed"
    refetch = "--refetch" in flags
    token = config.get("MAPILLARY_TOKEN")
    print(f"Mapillary {config.masked(token)}（{MODE_LABEL[mode]}）\n")

    available = corridors()
    by_canonical = {canonical(k): k for k in available}

    written = []
    pages: list[dict] = []
    for name in names:
        road = by_canonical.get(canonical(name))
        if road is None:
            print(f"找不到廊道「{name}」。用 --list 看有哪些。")
            continue
        for facing, rules in sorted(available[road].items()):
            if facings is not None and facing not in facings:
                continue
            print(f"=== {road}　面向{FACING[facing]} ===")
            items = build_items(facing, rules, token, mode, refetch)
            if not items:
                continue
            out = BUILD / f"corridor_{canonical(road)}_{FACING[facing]}.html"
            out.write_text(build_page(road, facing, items, mode), encoding="utf-8")
            written.append(out)
            pages.append({
                "road": road, "facing": facing, "file": out.name,
                "total": len(items),
                "withimages": sum(1 for i in items if i["images"]),
                "settled": sum(1 for i in items if i["settled"]),
                "size_kb": round(out.stat().st_size / 1024),
            })
            print(f"  → {out.name}（{pages[-1]['size_kb']} KB）\n")

    if not written:
        return 1

    if len(pages) > 1:
        index = write_index(pages)
        written.insert(0, index)
        print(f"目錄頁 → {index.name}\n")

    print("判完按頁尾的「匯出 JSON」，再執行："
          "\n  python apply_image_checks.py <下載的 json>\n")

    if "--serve" in flags:
        return serve(written)

    print("用瀏覽器開啟（不要發布成 Artifact，CSP 會擋掉影像）：")
    for out in written:
        print(f"  {out}")
    if mode == "files":
        print("\n圖沒出來的話，這個 HTML 大概被搬離了 corridor_images/ 旁邊。"
              "\n加 --serve 重跑一次最省事。")
    return 0


def serve(pages: list[pathlib.Path], port: int = 8765) -> int:
    """把 build/ 開成本機網站，印出網址。

    「用瀏覽器開那個檔」在不同環境下的意思差很多 —— 有些檢視器會把 HTML 轉成
    內嵌快照，那時相對路徑一律解析不到，畫面上就是一排空白框。起一個伺服器，
    路徑的行為就只有一種。
    """
    import functools
    import http.server
    import urllib.parse

    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(BUILD))
    with http.server.ThreadingHTTPServer(("127.0.0.1", port), handler) as httpd:
        print(f"本機伺服器 http://127.0.0.1:{port}/　（Ctrl+C 結束）")
        for page in pages:
            print(f"  http://127.0.0.1:{port}/{urllib.parse.quote(page.name)}")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n伺服器已停止。")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    BUILD.mkdir(exist_ok=True)
    raise SystemExit(main())
