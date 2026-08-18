"""自建 BRouter rd5 圖磚，把用路人回報的路口規則烘進去。

ADR-0017：BRouter 的 profile 讀的是 OSM 標籤、讀不到我們的資料庫，所以回報要
改變得了路線，唯一的位置是**建圖之前的 OSM 資料**。這支程式就是那條分支。

    python build_tiles.py                 # 全套：下載、注入、建圖、驗收
    python build_tiles.py --skip-fetch    # 已經下載過就別再抓 326 MB
    python build_tiles.py --hook-turns    # 連待轉一起注入（預設不注入，見下）

**待轉預設不注入。** 標籤進得了圖磚（實測），但目前的 profile 用的是 BRouter 的
KinematicModel，而 KinematicPath 整支沒有呼叫過 getTurncost() —— 也就是說待轉
成本這一刻無論設多少都是零。既然沒有效果，就不要為它在圖上多切幾條 way。
換掉成本模型那天把這個旗標打開即可，見 scooter-tw.brf 裡的說明與決策檔案 D10。

只用標準函式庫。中間產物全部落在 --work 目錄（預設 build/tiles/），
那個目錄已經在 .gitignore 裡。
"""

import argparse
import os
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PIPELINE = HERE.parent
REPO = PIPELINE.parent

BROUTER_VERSION = "1.7.10"
BROUTER_ZIP = f"https://github.com/abrensch/brouter/releases/download/v{BROUTER_VERSION}/brouter-{BROUTER_VERSION}.zip"
BROUTER_RAW = f"https://raw.githubusercontent.com/abrensch/brouter/v{BROUTER_VERSION}/misc/profiles2/"

OSM_PBF = "https://download.geofabrik.de/asia/taiwan-latest.osm.pbf"

PROFILE = REPO / "app/src/main/assets/brouter/scooter-tw.brf"
LOOKUPS = REPO / "app/src/main/assets/brouter/lookups.dat"
SEED_DB = PIPELINE / "build/scooter_seed.db"

# 台灣本島與離島用得到的兩塊。建圖會順手產出 E110_N10 之類的小塊
# （南沙、東沙落在裡面），那些不出貨 —— 一塊圖磚的存在會讓 App 以為那裡有路網。
SHIP_TILES = ["E120_N20.rd5", "E120_N25.rd5"]

# 驗收用的起訖點。挑的是跨越 N25 分界、市區、以及長程三種，
# 因為它們壞掉的方式不一樣：分界壞掉是「南下算不出路線」，
# 市區壞掉是「繞遠」，長程壞掉是「上了國道」。
OD_PAIRS = [
    ("taipei-station-to-city-hall", 25.0478, 121.5170, 25.0410, 121.5670),
    ("xinyi-to-dazhi", 25.0330, 121.5654, 25.0631, 121.5320),
    ("taipei-to-taichung", 25.0478, 121.5170, 24.1477, 120.6736),
    ("taipei-to-banqiao", 25.0478, 121.5170, 25.0143, 121.4677),
]

# TurnRule 的序數值，與 core-rules/TurnRule.kt 對齊。改那邊要改這邊。
TURN_RULE_HOOK = 1
TURN_RULE_NO_LEFT_TURN = 5

# RuleStatus。只有這兩種進圖磚 —— PENDING 與 DISPUTED 不進。
# 一筆錯誤的禁止左轉被烘進圖磚之後，修正要等下一次重建（ADR-0017），
# 所以注入的門檻必須比播報高。
STATUS_OFFICIAL = 1
STATUS_VERIFIED = 2


def log(msg):
    print(f"[tiles] {msg}", flush=True)


def fetch(url, target: Path):
    if target.exists() and target.stat().st_size > 0:
        log(f"已存在，跳過下載：{target.name}")
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".part")
    log(f"下載 {url}")
    t0 = time.time()
    req = urllib.request.Request(url, headers={"User-Agent": "scooter-tiles/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    tmp.replace(target)
    log(f"  {target.name} {target.stat().st_size:,} bytes，{time.time() - t0:.1f} 秒")
    return target


def ensure_toolchain(work: Path):
    """取得建圖工具與建圖用的三份 profile。

    建圖工具在 -all.jar 裡（2.3 MB，含 mapcreator），App 用的是 -ro.jar（346 KB）。
    兩個都沒有 Maven 座標，只在 release zip 裡，所以這裡自己抓自己解。

    all.brf / trekking.brf / softaccess.brf 不在 release zip 裡，它們只存在於
    原始碼的 misc/profiles2/ —— 少了它們建圖的第一步就跑不起來，而錯誤訊息
    只會說找不到檔案。
    """
    tools = work / "tools"
    tools.mkdir(parents=True, exist_ok=True)
    all_jar = tools / f"brouter-{BROUTER_VERSION}-all.jar"
    if not all_jar.exists():
        z = fetch(BROUTER_ZIP, tools / f"brouter-{BROUTER_VERSION}.zip")
        with zipfile.ZipFile(z) as zf:
            for name in zf.namelist():
                if name.endswith(f"brouter-{BROUTER_VERSION}-all.jar"):
                    with zf.open(name) as src, open(all_jar, "wb") as dst:
                        shutil.copyfileobj(src, dst)
                    break
        if not all_jar.exists():
            sys.exit(f"release zip 裡找不到 brouter-{BROUTER_VERSION}-all.jar")
    for name in ("all.brf", "trekking.brf", "softaccess.brf"):
        fetch(BROUTER_RAW + name, tools / name)
    return tools, all_jar


def export_rules(target: Path, hook_turns: bool):
    """把種子庫裡該進圖磚的規則寫成 OsmInject 吃的 TSV。

    只取 OFFICIAL 與 VERIFIED。回報一進資料庫就是 PENDING，那些不進圖磚 ——
    一筆錯誤的禁止左轉會讓所有人在那個路口繞遠，**而且不會有人抱怨**，
    因為繞遠的路線看起來仍然合法（TurnRule.NO_LEFT_TURN 的註解講的就是這件事）。
    """
    if not SEED_DB.exists():
        log(f"找不到種子庫 {SEED_DB}，這一輪不注入任何規則")
        target.write_text("", encoding="utf-8")
        return 0

    kinds = [(TURN_RULE_NO_LEFT_TURN, "NO_LEFT_TURN")]
    if hook_turns:
        kinds.append((TURN_RULE_HOOK, "HOOK"))

    lines = []
    with sqlite3.connect(SEED_DB) as db:
        for turn_rule, kind in kinds:
            rows = db.execute(
                "SELECT id, lat, lon, approach_bearing, exit_bearing FROM rules "
                "WHERE turn_rule = ? AND status IN (?, ?) AND downgrade_reason IS NULL",
                (turn_rule, STATUS_OFFICIAL, STATUS_VERIFIED),
            ).fetchall()
            for rid, lat, lon, approach, exit_b in rows:
                exit_s = "" if exit_b is None else f"{exit_b:.1f}"
                lines.append(f"{kind}\t{rid}\t{lat:.6f}\t{lon:.6f}\t{approach:.1f}\t{exit_s}")
    target.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    log(f"匯出 {len(lines)} 條規則 -> {target.name}")
    return len(lines)


def javac(sources, classpath, out: Path):
    out.mkdir(parents=True, exist_ok=True)
    run(["javac", "-encoding", "UTF-8", "-nowarn", "-cp", classpath, "-d", str(out), *map(str, sources)])


def run(cmd, cwd=None):
    proc = subprocess.run(cmd, cwd=cwd)
    if proc.returncode != 0:
        sys.exit(f"失敗（exit {proc.returncode}）：{' '.join(map(str, cmd))}")


def java(classpath, main, args, cwd=None, xmx="4g", props=()):
    cmd = ["java", f"-Xmx{xmx}", *[f"-D{p}" for p in props], "-cp", classpath, main, *map(str, args)]
    run(cmd, cwd=cwd)


def build(work: Path, tools: Path, all_jar: Path, pbf: Path):
    """跑 BRouter 官方的建圖三步，一行都沒改過它的程式。

    刻意不用自己包裝的版本：BRouter 每隔一陣子會發新版，而「我們改過它的內部
    類別」這種相依性會在升級那天才爆炸。注入已經在上一步做完了，這裡吃的是
    一份普通的 .osm.pbf。
    """
    tmp = work / "mapcreate"
    if tmp.exists():
        shutil.rmtree(tmp)
    for d in ("nodetiles", "waytiles", "nodes55", "waytiles55", "unodes55", "segments"):
        (tmp / d).mkdir(parents=True)

    cp = str(all_jar)
    props = ("deletetmpfiles=true", "useDenseMaps=true")

    log("1/3 OsmFastCutter")
    java(cp, "btools.mapcreator.OsmFastCutter", [
        LOOKUPS, "nodetiles", "waytiles", "nodes55", "waytiles55",
        "bordernids.dat", "relations.dat", "restrictions.dat",
        tools / "all.brf", tools / "trekking.brf", tools / "softaccess.brf", pbf,
    ], cwd=tmp, props=props)

    # 高程資料刻意不給。scooter-tw.brf 一個 uphillcost/downhillcost 都沒有指定，
    # 高程只會改變回報的時間與能量，不會改變路線 —— 實測自建（無高程）與官方
    # （有高程）圖磚跑同一組起訖，距離一模一樣。
    log("2/3 PosUnifier（不帶高程）")
    java(cp, "btools.mapcreator.PosUnifier",
         ["nodes55", "unodes55", "bordernids.dat", "bordernodes.dat", "srtm_absent"],
         cwd=tmp, props=props)

    log("3/3 WayLinker")
    java(cp, "btools.mapcreator.WayLinker", [
        "unodes55", "waytiles55", "bordernodes.dat", "restrictions.dat",
        LOOKUPS, tools / "all.brf", "segments", "rd5",
    ], cwd=tmp, props=("useDenseMaps=true", "skipEncodingCheck=true"))

    out = work / "segments4"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    for name in SHIP_TILES:
        src = tmp / "segments" / name
        if not src.exists():
            sys.exit(f"建圖沒有產出 {name}")
        shutil.copy2(src, out / name)
        log(f"  {name} {src.stat().st_size:,} bytes")
    return out


def verify(work: Path, segments: Path, ro_jar: Path):
    """在自建圖磚上把幾組起訖跑一遍。

    這裡只保證「算得出來」與「數字看得到」；跟官方圖磚的對照要人看，
    因為差異的合理與否取決於資料日期與注入了什麼，機器判不了。
    """
    classes = work / "classes"
    javac([HERE / "RouteCheck.java"], str(ro_jar), classes)

    # profile 與 lookups.dat 必須同一個目錄 —— BRouter 是從 profile 的所在目錄
    # 去找 lookups 的，不是從 classpath。
    pdir = work / "profile"
    pdir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(PROFILE, pdir / PROFILE.name)
    shutil.copy2(LOOKUPS, pdir / LOOKUPS.name)

    od = work / "od.tsv"
    od.write_text("".join(
        f"{n}\t{a}\t{b}\t{c}\t{d}\n" for n, a, b, c, d in OD_PAIRS), encoding="utf-8")

    log("驗收：")
    java(f"{ro_jar}{os.pathsep}{classes}", "RouteCheck",
         [segments, pdir / PROFILE.name, od, work / "routes"], xmx="2g")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--work", default=str(PIPELINE / "build/tiles"))
    ap.add_argument("--skip-fetch", action="store_true", help="不重新下載 OSM 原始資料")
    ap.add_argument("--hook-turns", action="store_true",
                    help="連待轉一起注入。目前的成本模型讀不到它，見模組說明")
    ap.add_argument("--rules", help="改用指定的 rules.tsv，不從種子庫匯出。"
                                    "selftest_rules.tsv 是拿來證明注入真的會改變路線的那一組")
    args = ap.parse_args()

    work = Path(args.work).resolve()
    work.mkdir(parents=True, exist_ok=True)

    tools, all_jar = ensure_toolchain(work)
    ro_jar = REPO / f"app/libs/brouter-{BROUTER_VERSION}-ro.jar"
    if not ro_jar.exists():
        sys.exit(f"找不到 {ro_jar}")

    pbf = work / "taiwan-latest.osm.pbf"
    if args.skip_fetch and not pbf.exists():
        sys.exit(f"--skip-fetch 但 {pbf} 不存在")
    if not args.skip_fetch:
        fetch(OSM_PBF, pbf)

    if args.rules:
        rules = Path(args.rules).resolve()
        count = sum(1 for line in rules.read_text(encoding="utf-8").splitlines()
                    if line.strip() and not line.startswith("#"))
        log(f"改用 {rules}（{count} 條）")
    else:
        rules = work / "rules.tsv"
        count = export_rules(rules, args.hook_turns)

    injected = pbf
    if count:
        classes = work / "classes"
        javac([HERE / "OsmInject.java"], str(all_jar), classes)
        injected = work / "taiwan-scooter.osm.pbf"
        log("注入規則")
        java(f"{all_jar}{os.pathsep}{classes}", "OsmInject",
             [pbf, rules, injected, work / "inject_report.tsv"], xmx="3g")
        log(f"注入報告：{work / 'inject_report.tsv'}")
    else:
        log("沒有規則可注入，直接用原始資料建圖")

    segments = build(work, tools, all_jar, injected)
    verify(work, segments, ro_jar)
    log(f"完成。圖磚在 {segments}")


if __name__ == "__main__":
    main()
