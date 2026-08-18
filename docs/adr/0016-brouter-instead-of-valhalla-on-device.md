---
status: accepted (supersedes ADR-0003; spike passed 2026-08-18)
---

# 裝置上的路線引擎改押 BRouter，不押 Valhalla

ADR-0003 選了 Valhalla 的「官方 Android 離線函式庫」，並實測了台灣圖磚 309 MB。
**2026-08-18 去確認那個函式庫存不存在，答案是：沒有。**

Valhalla 的 README 說它「is also used on iOS and Android devices」，
overview 說 C++ 的架構「should allow for cross compilation」—— 兩句都是可能性，
不是產品。**沒有官方 AAR、沒有 NDK build target、沒有 Android binding**
（Node.js 與 Python bindings 都有文件，Android 沒有）。搜到的
`mapzen/on-the-road` 是 Valhalla **服務**的 HTTP 客戶端，而 Mapzen 2018 就收了。

也就是說 ADR-0003 那條路的第一步，是一段沒有人維護、沒有文件、我們也沒有真機
可以驗證的 NDK 交叉編譯工作。那不是「工程量大」，那是**風險未知**。

## 量出來的對照

| | Valhalla | BRouter |
| --- | --- | --- |
| 台灣圖資 | **309 MB**（ADR-0003 實測） | **約 33 MB** —— `E120_N20.rd5` 21.0 MB + `E120_N25.rd5` 11.9 MB |
| Android | 無官方 binding，要自己用 NDK 建 | **純 Java**，官方自己就有 Android App |
| 維護 | 引擎活躍，但不含 Android | 圖磚**每週重建**（實測 last-modified 就是查詢當天） |
| 轉向限制 | 原生 | **1.4.8（2016）起支援**，`considerTurnRestrictions`；1.5.0 加 no_entry/no_exit |
| 轉向成本 | 有 | `turncost`、`initialcost` |
| 機車 | `motor_scooter` costing 實測避開國道 | 有 moped profile，但要自己改 |

**33 MB 對 309 MB 不只是省流量。** ADR-0008 因為 309 MB 而設計了一整套
分縣市離線包與首次啟動下載流程；33 MB 是「隨 APK 出貨或首次啟動順手抓」的量級，
那一整套機制連帶不需要了。

而 `turncost` 正好是 [ADR-0012](0012-hook-turn-is-a-cost-not-a-restriction.md)
需要的東西：待轉要表達成成本不是禁止，BRouter 的 profile 是一份可自訂的文字檔，
這件事在它上面是**設定**，不是要改引擎。

## Spike 通過了（2026-08-18）

決定性的那一題 —— 能不能不裝 BRouter App、當函式庫直接呼叫 —— **可以**。

`brouter-1.7.10-ro.jar` 是 **346 KB、128 個 class、零個 android 參照**的純 Java
路由核心。用一般的 `java -cp` 餵它一份 `E120_N25.rd5`，台北車站到市府：

| | |
| --- | --- |
| 距離 | 5,714 公尺 |
| 耗時 | **279 毫秒**（JVM，冷啟動含載入圖磚） |
| 節點 | 157 |
| 轉向指示 | 6 則 |

沒有安裝 BRouter App，沒有走 service interface，沒有 NDK。
API 只有四行：

```java
RoutingContext rc = new RoutingContext();
rc.localFunction = "…/moped.brf";   // profile 路徑；lookups.dat 要放得到
rc.turnInstructionMode = 1;          // 產生轉向指示
RoutingEngine engine = new RoutingEngine(null, null, segmentDir, waypoints, rc);
engine.doRun(0);
OsmTrack track = engine.getFoundTrack();
```

座標是整數微度加偏移：`ilon = (int)((lon + 180) * 1e6 + 0.5)`。

## 三件實作時會遇到的事

**一、轉向指示的欄位是 package-private。** `VoiceHint` 的 `cmd`、`angle`、
`distanceToNext` 都不公開，公開的只有 `getTime()`、`getExitNumber()`、
`formatGeometry()`、`hasGiveWay()`。也就是說**官方預期你走 GPX／GeoJSON
序列化那條路**，不是直接讀欄位。我們要逐向指示，所以得選一個：
把 adapter 放進 `btools.router` 套件（Java 的老招，能用但難看）、
序列化再解析回來（更難看）、或者 fork。**這是這條路唯一一塊真正的整合成本。**

**二、內建的 `moped.brf` 沒有禁止走高速公路。** 它的成本表裡
`if (highway=motorway) then 30` —— 是「貴」不是「不能走」。實測台北到台中，
moped 156.6 公里、car-fast 166.9 公里，兩者分不出誰上了國道，
所以**不能沿用內建 profile**。好消息是這件事在 profile 語言裡是一行：
`car-fast.brf` 就有 `assign avoid_motorways` 這個布林參數的寫法可以照抄。
白牌 profile 要自己寫，那是工作量不是風險。

**三、`turncost` 與節點的 `initialcost` 都在 profile 裡。**
`moped.brf` 開頭就是 `assign turncost = if junction=roundabout then 0 …`，
而節點區段另有一個 `initialcost`。**這正是 [ADR-0012](0012-hook-turn-is-a-cost-not-a-restriction.md)
需要的東西** —— 待轉要表達成「這個轉彎多花一個號誌週期」，
在 BRouter 上那是改一個設定，不是改引擎。

## 原本還沒確認的三件事

1. ~~**能不能當函式庫嵌進我們的 App。**~~ **✅ 可以，見上。** 仍然要記得
   常見用法是 service interface（一個獨立安裝的 App），那對我們不成立 ——
   要用的是 `-ro.jar` 這個 routing-only 的 artifact，而它沒有 Maven 座標，
   得從 release zip 取或自己建。
2. **白牌機車的 profile 要自己寫。** moped profile 是歐洲的，台灣的白牌路權
   （禁行國道與多數快速道路、禁行高架）要自己表達。這是工作量，不是風險 ——
   profile 是文字檔。
3. **逐向指示的品質。** BRouter 有 voicehints，但沒有人在導航 App 的情境下
   驗證過它夠不夠用。

## Consequences

- **jar 沒有 Maven 座標。** `-ro.jar` 只在 GitHub release 的 zip 裡，
  所以要嘛把它放進 `app/libs/`（版本升級變成手動作業），要嘛從原始碼建。
  兩種都要在 build 檔裡寫清楚版本，否則哪天沒人知道那個 jar 是哪來的。
- **ADR-0003 的 309 MB 與 `motor_scooter` 實測結論仍然有效**，只是它們回答的是
  「Valhalla 行不行」，而現在的問題是「Valhalla 上不上得了 Android」。
- 如果 spike 通過，ADR-0008 的分縣市離線包可以大幅簡化 —— 33 MB 不需要分縣市。
- 不論選哪一個，[ADR-0006](0006-white-plate-scooters-only.md) 的禁行路段事後
  驗證都留著。那是安全網，不因為引擎換人而不需要。
