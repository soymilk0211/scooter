---
status: proposed (would supersede ADR-0003 if the embedding spike passes)
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

## 還沒確認的三件事，第一件是決定性的

1. **能不能當函式庫嵌進我們的 App。** BRouter 的常見用法是
   「service interface」—— 一個獨立安裝的 App 在背景提供路徑，別的地圖工具呼叫它。
   **那對我們不成立**：不能要求騎士先裝第二個 App。核心是 Java、MIT 授權、
   模組化（`brouter-core` / `brouter-mapaccess` / `brouter-expressions`），
   理論上直接編進來就好，**但沒有任何文件說明這個用法，也沒有 Maven artifact**。
   這是要先做的 spike。
2. **白牌機車的 profile 要自己寫。** moped profile 是歐洲的，台灣的白牌路權
   （禁行國道與多數快速道路、禁行高架）要自己表達。這是工作量，不是風險 ——
   profile 是文字檔。
3. **逐向指示的品質。** BRouter 有 voicehints，但沒有人在導航 App 的情境下
   驗證過它夠不夠用。

## Consequences

- **這份 ADR 的狀態是 proposed，不是 accepted。** 第 1 點沒過就不成立，
  而沒過的話要重新比較的是「Valhalla 的 NDK 工作」與「BRouter 的核心自己抽出來編」，
  兩者都是不確定的工作，但後者是 Java，失敗了看得懂為什麼。
- **ADR-0003 的 309 MB 與 `motor_scooter` 實測結論仍然有效**，只是它們回答的是
  「Valhalla 行不行」，而現在的問題是「Valhalla 上不上得了 Android」。
- 如果 spike 通過，ADR-0008 的分縣市離線包可以大幅簡化 —— 33 MB 不需要分縣市。
- 不論選哪一個，[ADR-0006](0006-white-plate-scooters-only.md) 的禁行路段事後
  驗證都留著。那是安全網，不因為引擎換人而不需要。
