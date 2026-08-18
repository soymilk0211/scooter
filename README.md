# Scooter

台灣機車導航 App。路線遵守白牌機車的路權（不走國道與白牌禁行的快速道路、高架，
遵守禁止左轉／右轉／迴轉與單行道），路口的待轉規則**由用路人在現場建檔**
（[ADR-0011](docs/adr/0011-navigation-with-rider-sourced-turn-data.md)）。

設計背景見 [CONTEXT.md](CONTEXT.md)（術語）與 [docs/adr/](docs/adr/)（決策紀錄）。
現況與待辦見 [HANDOVER.md](HANDOVER.md)。

## 這個 App 解決什麼

Google Maps 的機車路線不理解台灣機車獨有的那一層：待轉、內側車道禁行機車、
白牌禁行的高架與快速道路。而那一層裡最關鍵的資料 —— **哪個路口的哪個方向要待轉** ——
**全台灣沒有人有完整的一份**，政府開放資料只有各縣市的例外清單，
OSM 的車道級標籤在臺北的覆蓋率是 1.1%、台中是 0%。

所以這個 App 一邊導航，一邊讓騎士在停等時用一個按鈕把那筆資料建起來。

## 資料從哪裡來

| 來源 | 用途 |
| --- | --- |
| 用路人回報 | **唯一的規則來源**。停止時按一下，立即上傳，帶路口座標。**只問結果不問成因** —— 回報「待轉／直接左轉」，不問內側車道禁不禁行機車 |
| 官方開放資料（臺北 118 條） | **只當校準基準**，不對騎士播報。用來自動評分回報者的正確率 |
| 被動觀察（軌跡形狀） | 尚未實作。規模化的途徑，風險最高的假設（[ADR-0005](docs/adr/0005-passive-observation-with-asymmetric-weighting.md)） |
| OSM | 路網、路名、轉向限制（臺北市 1,204 條關聯） |

新裝置的前幾筆回報由維護者親自審核，之後以**正確率**（Wilson 分數下界）
作為信心指標（[ADR-0013](docs/adr/0013-backend-and-reporter-accuracy-as-confidence.md)）。
回報進資料庫與發布到其他人的裝置是兩件事，中間隔著發布閘門。

## 隱私

**這個 App 會記錄你騎車時的位置與行進方向。** 上傳的單位是**路口片段** ——
進入路口前約 10 秒到離開為止的那一段軌跡，連同路口座標與方位角。
待轉與直接左轉的差別只有從軌跡形狀才判得出來，這是它存在的唯一理由。

不收帳號、不收通訊錄、不收手機門號、不收廣告 ID。裝置有一個匿名編號，
用來擋重複灌票 —— 而**有這個編號，技術上就有可能把該裝置的片段拼起來**，
這一點不打算用「我們只收片段」來含糊帶過。
關閉收集的按鈕在告知畫面上按得到，關掉之後導航照常可用。
詳見 [ADR-0014](docs/adr/0014-upload-intersection-segments-and-say-so.md)。

## 怎麼發

**自己發 APK → 用路人建檔 → 覆蓋率夠高 → Play 封閉測試 → 上架**
（[ADR-0015](docs/adr/0015-ship-the-full-app-and-self-distribute-the-apk.md)）。

第一個公開版本就是完整的導航 App，不是只收資料的縮減版 ——
願意在紅燈時多按一下的人，是因為那一下會讓**他自己的**導航變準，
切掉那個回饋迴路就找不到願意建檔的人。

排序的依據是**資料成熟度**，不是平台流程的前置時間。封測沒有取消，
它等的是「經過次數加權的已建檔比例」與「正確率」同時到門檻 ——
所以後端從第一天就要有覆蓋率儀表板，否則「慢慢建」會變成永遠不發。

## 建置

本機工具鏈裝在 repo 之外，未納入版控：

| 元件 | 版本 | 路徑 |
| --- | --- | --- |
| JDK | Temurin 21.0.12 | `C:\Users\user\Android\jdk\jdk-21.0.12+8` |
| Android SDK | platform 36 / build-tools 36 | `C:\Users\user\Android\Sdk` |
| Gradle | 8.14.3（wrapper 自動下載） | — |

系統另裝有 JDK 26，但 Android Gradle Plugin 尚不支援，**務必指定 JDK 21**：

```bash
JAVA_HOME=/c/Users/user/Android/jdk/jdk-21.0.12+8 ANDROID_HOME=C:/Users/user/Android/Sdk ./gradlew build
```

`local.properties`（含 `sdk.dir`）已被 gitignore，換機器需重新產生。

## 模組

| 模組 | 內容 | 平台 |
| --- | --- | --- |
| `:core-rules` | 規則模型、幾何運算、警示判定 | 純 Kotlin/JVM |
| `:data` | SQLite 結構與存取 | Android library |
| `:app` | Compose UI、MapLibre 地圖 | Android app |

`:core-rules` 刻意不依賴 Android —— 警示判定是最需要測試、也最不該綁平台的部分，
它可以在一般 JVM 上完整單元測試。

```bash
./gradlew :core-rules:test
```

## 目前完成度

**可用且已在模擬器上實測**：規則引擎（距離／方位角／速度／生效時段／冷卻）、
SQLite 結構與種子資料庫（臺北 118 條規則、1,523 個固定測速點）、定位前景服務、
語音警示與失效警告、固定測速警示、可拖曳時速圓圈、深淺兩色底圖與設定落地、
[資料管線](pipeline/README.md)。**79 項單元測試全綠**（core-rules 53、app 26）。

**尚未開始**：導航（路線、轉向指示、地址搜尋）、停止時的回報介面、
後端與上傳、審核與發布閘門、告知畫面、被動觀察。

**路線引擎還沒選定。** ADR-0003 押的是「Valhalla 的官方 Android 離線函式庫」，
而那個東西**不存在**（沒有 AAR、沒有 NDK build target、沒有 Android binding）。
替代方案是 BRouter：純 Java、台灣圖資約 33 MB（Valhalla 是 309 MB）、
圖磚每週重建、`turncost` 正好能表達待轉成本。要先驗證它能不能當函式庫嵌入 ——
見 [ADR-0016](docs/adr/0016-brouter-instead-of-valhalla-on-device.md)。

**已驗證的假設**：台灣 Valhalla 圖磚 309 MB、`motor_scooter` costing 確實避開國道
（[ADR-0003](docs/adr/0003-on-device-valhalla-routing.md)、
[ADR-0006](docs/adr/0006-white-plate-scooters-only.md)）；
即時 TTS 首句延遲 2.8–3.6 秒；臺北市 OSM 有 1,204 條轉向限制關聯，
但車道級禁行機車只有 1.1%。

## 已知待辦

- **回報存的座標仍是騎士的 GPS 位置，不是路口節點**。要等路網圖上機才修得對 ——
  沒有圖就沒有節點可取，而用方位角往前推一個猜測值是在製造看起來像資料的錯誤輸出。
- **從未在真機上跑過**，只有模擬器。GPS 精度、廠牌 ROM 背景清殺、太陽下過熱都還沒驗。
- 底圖借用 CARTO 的公開端點（Positron／Dark Matter），其條款**不供公開免費使用**，
  **公開發布 APK 前必須換成自建向量圖磚** —— 換的時候只動 `app/.../ui/MapStyle.kt`。
- 被動偵測待轉的演算法尚未以真實軌跡驗證，是目前風險最高的假設。

## 免責

本 App 提供的左轉規則資訊僅供參考，**請以現場標誌與號誌為準**。
規則資料由用路人共同建立，可能不完整或過期；沒有資料的路口不會發出提示，
那代表「我們不知道」，不代表「這裡沒有規則」。
