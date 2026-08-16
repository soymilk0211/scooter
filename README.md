# Scooter

台灣機車左轉規則警示 App，**疊在 Google Maps 上的外掛，不自己做導航**
（[ADR-0010](docs/adr/0010-overlay-on-google-maps-not-a-navigation-app.md)）。
設計背景見 [CONTEXT.md](CONTEXT.md) 與 [docs/adr/](docs/adr/)。

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
[資料管線](pipeline/README.md)。**57 項單元測試全綠**（core-rules 31、app 26）。

**已驗證的假設**：台灣 Valhalla 圖磚 309 MB（ADR-0003）；`motor_scooter` costing
確實避開國道（ADR-0006）。兩者在 ADR-0010 之後暫時用不到，結論留著備查。

**未接上**：懸浮視窗（收斂之後唯一還缺的功能）、被動觀察偵測、資料同步、區間測速。

## 已知待辦

- **從未在真機上跑過**，只有模擬器。GPS 精度、廠牌 ROM 背景清殺、太陽下過熱都還沒驗。
- 底圖借用 CARTO 的公開端點（Positron／Dark Matter），其條款**不供公開免費使用**，
  上架前必須換成自建向量圖磚 —— 換的時候只動 `app/.../ui/MapStyle.kt`。
- 19 個路口無法自動定位，需人工補座標 —— 見 `pipeline/build/review_coords.csv`。
- 被動偵測待轉的演算法尚未以真實軌跡驗證，是目前風險最高的假設（[ADR-0005](docs/adr/0005-passive-observation-with-asymmetric-weighting.md)）。
  ADR-0010 之後它同時也是唯一的規模化途徑，風險因此更高。

## 免責

本 App 提供的左轉規則資訊僅供參考，請以現場標誌與號誌為準。
