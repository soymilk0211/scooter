"""用街景影像判讀路口的左轉規定。

流程：路名 → 座標（Overpass）→ 街景影像（Mapillary）→ 視覺模型描述標誌 → 程式分類。

**模型只負責描述看到什麼，分類由程式做。** 這是刻意的：先前把圓形藍底的兩段式
左轉標誌讀成「可直接左轉」時，分類式提示會給出一個很有把握的錯答案，而描述式
提示會留下「我看到機車圖案、左箭頭、直行箭頭」這樣的記錄，錯了看得出錯在哪。

影像**依相機朝向分組**，每個方位各判一次 —— 規則掛在進入方位角上（ADR-0001），
一張朝東的照片說不了朝西那側的事。

用法：
    python classify_streetview.py "金山南路一段與仁愛路二段" "東豐街與復興南路一段"
"""

from __future__ import annotations

import base64
import json
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

import config
import taipei_geocode as geo

BUILD = pathlib.Path(__file__).parent / "build"
OUT = BUILD / "streetview_classification.json"

CGU_BASE = "https://air.cgu.edu.tw/cgullmapi/v1"
MODEL = "gpt-4o"

MAPILLARY_FIELDS = "id,captured_at,compass_angle,is_pano,thumb_2048_url,computed_geometry"
SEARCH_RADIUS_M = 45.0

# 相機朝向分組。規則掛在進入方位角上，所以每個方位要各判一次。
SECTORS = {0.0: "北", 90.0: "東", 180.0: "南", 270.0: "西"}

PROMPT = """你在看一張台灣街景照片，拍攝方向是朝著一個路口。

{context}

請**只描述你實際看到的東西**，不要推論規定、不要下結論。用 JSON 回答：

{
  "signs": [
    {
      "shape": "圓形/方形/長方形",
      "colour": "藍底白圖/綠底白字/紅底白字/其他",
      "symbols": ["你看到的圖案，例如：機車、左箭頭、直行箭頭、腳踏車、向下箭頭"],
      "text": "牌面上的中文字，沒有就空字串",
      "position": "門架/路側桿/地面"
    }
  ],
  "road_markings": ["地面標線，例如：待轉區方框、左轉專用道箭頭、機車彩繪道"],
  "lanes_visible": 幾條車道的整數,
  "other_directions": ["屬於其他來向或對向車道的標誌與標線"],
  "image_quality": "clear/partial/poor",
  "notes": "任何影響判讀的狀況，例如被樹遮住、逆光、路口不在畫面中"
}

重要：
- 只寫**看得見**的。看不清楚就不要寫進 signs。
- 台灣的待轉標誌有兩種，兩種都要如實記錄，不要判斷它的意思：
  (a) 圓形藍底，畫機車加上左箭頭與直行箭頭 —— 那兩個箭頭是同一個動作的兩個階段。
  (b) 直式長方形紅底白字，寫著「前方路口機車兩段左轉」（英文 Two-Stage Left-Turn for Motorcycles）。
- 左轉專用道的標誌是藍底，畫向下箭頭並寫「左轉專用道」，有時同時畫機車與腳踏車。
  請記下它是在**內側車道**還是**外側車道**上方 —— 那決定機車該走哪一條。
- 若畫面中同時有多面互相矛盾的標誌，全部都要列出來。
- **只採計適用於上述行進方向的標誌與標線。** 同一個路口的不同來向規定可能不同：
  對向車道的待轉區方框、路口另一側的指示牌，都不屬於你這個方向，請放進
  `other_directions` 而不是 `signs` 或 `road_markings`。這一點最容易出錯 ——
  大路口只要有任何一側需要待轉，畫面裡就看得到待轉區方框。
"""

DIRECTION_LABEL = {0: "北", 45: "東北", 90: "東", 135: "東南",
                   180: "南", 225: "西南", 270: "西", 315: "西北"}


def compass_label(bearing: float | None) -> str:
    """八方位標籤。實際角度仍以數值傳給模型，文字只是幫它定位。"""
    if bearing is None:
        return "未知"
    return DIRECTION_LABEL[min(DIRECTION_LABEL, key=lambda k: abs((k - bearing + 540) % 360 - 180))]


def build_context(entry: str | None = None, exit_road: str | None = None,
                  approach: float | None = None, exit_bearing: float | None = None) -> str:
    """告訴模型它在判哪一個來向。

    先前的提示詞完全沒有這段，模型只看到「一個路口」，於是把畫面裡所有標誌
    一起報上來 —— 包含對向車道的待轉區方框。實測五筆不一致全部源自這個缺口：
    官方寫免待轉、模型看到別側的方框就判待轉。
    """
    if approach is None:
        return "這張照片沒有指定行進方向，請描述整個路口看得到的標誌。"
    parts = [f"拍攝者正朝 {compass_label(approach)}（{approach:.0f}°）行駛"]
    if entry:
        parts.append(f"行駛於{entry}")
    if exit_bearing is not None:
        parts.append(f"即將左轉朝 {compass_label(exit_bearing)}（{exit_bearing:.0f}°）")
    if exit_road:
        parts.append(f"進入{exit_road}")
    return "、".join(parts) + "。"


def fetch_json(url: str, headers: dict | None = None, body: bytes | None = None,
               retries: int = 3) -> dict:
    """帶重試的 JSON 請求。

    視覺模型的呼叫偶爾會回 408 —— 那是暫時性的，重試就過。不重試的話一次逾時
    會拖垮整批，而每一題都花掉了額度。
    """
    import time
    last: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(
                url, data=body,
                headers={"User-Agent": "scooter-pipeline/0.1", **(headers or {})})
            with urllib.request.urlopen(req, timeout=180) as response:
                return json.loads(response.read())
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as e:
            code = getattr(e, "code", None)
            if code in (400, 401, 403):
                raise  # 設定錯誤，重試沒有意義
            last = e
            time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"{retries} 次嘗試皆失敗: {last}")


def mapillary_images(token: str, lat: float, lon: float) -> list[dict]:
    params = urllib.parse.urlencode({
        "access_token": token,
        "fields": MAPILLARY_FIELDS,
        "bbox": mapillary_bbox(lat, lon),
        "limit": 100,
    })
    return fetch_json(f"https://graph.mapillary.com/images?{params}").get("data", [])


def mapillary_bbox(lat: float, lon: float) -> str:
    import math
    dlat = SEARCH_RADIUS_M / 111_000.0
    dlon = SEARCH_RADIUS_M / (111_000.0 * max(0.01, math.cos(math.radians(lat))))
    return f"{lon - dlon},{lat - dlat},{lon + dlon},{lat + dlat}"


def pick_per_sector(images: list[dict]) -> dict[str, dict]:
    """每個方位取最新的一張。全景優先 —— 全景能一次看到路口各側的標誌。"""
    best: dict[str, dict] = {}
    for image in images:
        angle = image.get("compass_angle")
        if angle is None or not image.get("thumb_2048_url"):
            continue
        sector = min(SECTORS, key=lambda s: geo.axis_delta(s, angle) if False else
                     abs((s - angle + 540) % 360 - 180))
        label = SECTORS[sector]
        current = best.get(label)
        score = (bool(image.get("is_pano")), image.get("captured_at") or 0)
        if current is None or score > (bool(current.get("is_pano")),
                                       current.get("captured_at") or 0):
            best[label] = image
    return best


def describe(api_key: str, image_url: str, context: str = "") -> dict:
    """讓視覺模型描述影像中的標誌。回傳解析後的 JSON 與原始文字。

    context 說明拍攝者的行進方向與即將的轉向 —— 少了它，模型會把對向車道的
    標線也算進來。
    """
    with urllib.request.urlopen(
        urllib.request.Request(image_url, headers={"User-Agent": "scooter-pipeline/0.1"}),
        timeout=120,
    ) as response:
        image_bytes = response.read()
    data_url = "data:image/jpeg;base64," + base64.b64encode(image_bytes).decode()

    payload = {
        "model": MODEL,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": PROMPT.replace("{context}", context)},
                {"type": "image_url", "image_url": {"url": data_url}},
            ],
        }],
        "max_tokens": 900,
        "temperature": 0,
    }
    result = fetch_json(
        f"{CGU_BASE}/chat/completions",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        body=json.dumps(payload).encode(),
    )
    text = result["choices"][0]["message"]["content"]
    cleaned = text.strip().removeprefix("```json").removeprefix("```").removesuffix("```")
    try:
        return {"parsed": json.loads(cleaned), "raw": text}
    except json.JSONDecodeError:
        return {"parsed": None, "raw": text}


# 由描述推規定。這一步在程式裡，不在模型裡 —— 模型讀錯圖案時，我們看得到它
# 看到了什麼；模型直接分類時，只會拿到一個很有把握的錯答案。
NO_LEFT_TURN = -1


def classify(description: dict | None) -> tuple[int, str]:
    """由描述推規定。回傳 (turn_rule, 理由)，-1 代表該方向禁止左轉。

    判定順序是有講究的。**待轉區方框優先於左轉專用道箭頭** —— 一個路口可以
    同時畫著左轉專用道箭頭和待轉區方框（左轉道給汽車、機車仍須待轉），先判
    專用道會把這種路口全部判錯。實測五個路口中有三個踩到這個坑。
    """
    if not description:
        return 0, "模型輸出無法解析"

    signs = description.get("signs") or []
    markings = description.get("road_markings") or []
    marking_blob = " ".join(markings)
    # other_directions 是對向或其他來向的東西，不屬於本方向 —— 排除掉，
    # 否則加了那個欄位等於白加，關鍵字照樣被比對到。
    relevant = {k: v for k, v in description.items() if k != "other_directions"}
    blob = json.dumps(relevant, ensure_ascii=False)

    def sign_text(s: dict) -> str:
        return f"{s.get('text', '')} {json.dumps(s.get('symbols', []), ensure_ascii=False)}"

    # 禁止左轉最優先 —— 那個方向根本沒有左轉動線，不需要任何規則。
    if any("禁左" in sign_text(s) or "禁止左轉" in sign_text(s) for s in signs):
        return NO_LEFT_TURN, "看到禁止左轉標誌，該方向無左轉動線"

    hook_box = any("待轉區" in m for m in markings)
    hook_sign = any(
        all(k in sign_text(s) for k in ("機車", "左箭頭", "直行箭頭"))
        for s in signs
    ) or "兩段式" in blob
    exempt = "免二段左轉" in blob or "免待轉" in blob
    left_lane = "左轉專用" in marking_blob or "左轉專用道" in blob

    if (hook_box or hook_sign) and exempt:
        return 1, "待轉證據與免待轉標誌並存 —— 標誌落差，取保守值"
    if hook_box:
        return 1, "地面有待轉區方框 —— 機車須待轉，即使同時有左轉專用道箭頭"
    if hook_sign:
        return 1, "圓形藍底、機車配左箭頭與直行箭頭 —— 兩段式左轉"
    if exempt:
        return 2, "看到免二段左轉標誌"
    if left_lane:
        return 4, "有左轉專用道且無待轉證據（內外側需人工確認）"
    return 0, "沒有可判定的標誌"


def main() -> int:
    names = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not names:
        print(__doc__)
        return 1

    token = config.get("MAPILLARY_TOKEN")
    api_key = config.get("CGU_API_KEY")
    print(f"Mapillary {config.masked(token)} / CGU {config.masked(api_key)}\n")

    areas = geo.Districts()
    all_names: set[str] = set()
    pairs = []
    for name in names:
        separator = next((s for s in ("與", "及", "×") if s in name), None)
        if not separator:
            print(f"跳過（找不到分隔字）：{name}")
            continue
        a, b = (s.strip() for s in name.replace("路口", "").split(separator, 1))
        pairs.append((name, a, b))
        for road in (a, b):
            all_names.update(v for v, _ in geo.name_variants(road))

    print(f"抓取 {len(all_names)} 個路名的路網幾何…")
    index = geo.RoadIndex(geo.fetch_ways(all_names))
    signals = geo.SignalIndex(geo.fetch_traffic_signals())

    results = {}
    for original, a, b in pairs:
        print(f"\n=== {original} ===")
        located = geo.resolve(index, a, b, "", areas, [], signals)
        if located.get("lat") is None:
            print(f"  定位失敗：{located.get('reason')}")
            results[original] = {"error": located.get("reason")}
            continue
        print(f"  座標 {located['lat']},{located['lon']}")

        images = mapillary_images(token, located["lat"], located["lon"])
        chosen = pick_per_sector(images)
        print(f"  影像 {len(images)} 張，取各方位最新共 {len(chosen)} 張")

        per_sector = {}
        for label, image in sorted(chosen.items()):
            captured = image.get("captured_at")
            when = (datetime.fromtimestamp(captured / 1000, tz=timezone.utc).date().isoformat()
                    if captured else "?")
            description = describe(api_key, image["thumb_2048_url"])
            rule, why = classify(description["parsed"])
            per_sector[label] = {
                "image_id": image["id"],
                "captured": when,
                "compass_angle": image.get("compass_angle"),
                "is_pano": bool(image.get("is_pano")),
                "description": description["parsed"],
                "raw": description["raw"],
                "turn_rule": rule,
                "reason": why,
            }
            names_seen = [s.get("text", "") for s in
                          (description["parsed"] or {}).get("signs", []) if s.get("text")]
            print(f"    朝{label}（{when}）-> 規定 {rule}：{why}")
            if names_seen:
                print(f"        牌面文字：{'、'.join(names_seen)}")

        results[original] = {"lat": located["lat"], "lon": located["lon"], "sectors": per_sector}

    BUILD.mkdir(exist_ok=True)
    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n完整結果（含每張影像的原始描述）：{OUT}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
