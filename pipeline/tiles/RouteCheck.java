import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 在一組圖磚上算路線，把距離與成本印出來。自建圖磚的驗收工具。
 *
 * <p>它回答兩個問題，而兩個都不是「路線好不好看」：
 *
 * <ol>
 *   <li><b>自建圖磚跟官方圖磚算出來的一不一樣。</b> 一樣代表建圖管線沒有做錯事；
 *       不一樣要能講得出為什麼（資料日期、注入的規則），講不出來就是壞了。
 *   <li><b>注入的規則有沒有改變路線。</b> 這是 ADR-0017 存在的唯一理由 ——
 *       回報如果改變不了路線，整條管線就白做了。
 * </ol>
 *
 * <p>用法：
 *
 * <pre>
 *   java -cp brouter-ro.jar;. RouteCheck &lt;segmentDir&gt; &lt;profile.brf&gt; &lt;od.tsv&gt; [dumpDir]
 * </pre>
 *
 * od.tsv 每列：名稱、lat1、lon1、lat2、lon2，以 tab 分隔。
 *
 * <p><b>profile 旁邊必須有 lookups.dat。</b> BRouter 是從 profile 的所在目錄去找它的，
 * 不是從 classpath —— 少了它的錯誤訊息是 FileNotFoundException: lookups.dat，
 * 看起來像路徑打錯，其實是放錯地方。
 */
public final class RouteCheck {

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println("usage: RouteCheck <segmentDir> <profile.brf> <od.tsv> [dumpDir]");
      System.exit(1);
    }
    File segmentDir = new File(args[0]);
    String profile = args[1];
    File odFile = new File(args[2]);
    File dumpDir = args.length > 3 ? new File(args[3]) : null;
    if (dumpDir != null) {
      dumpDir.mkdirs();
    }

    int failures = 0;
    for (String line : Files.readAllLines(odFile.toPath(), StandardCharsets.UTF_8)) {
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] p = line.split("\t");
      String name = p[0];
      List<OsmNodeNamed> waypoints = new ArrayList<>();
      waypoints.add(waypoint("from", Double.parseDouble(p[1]), Double.parseDouble(p[2])));
      waypoints.add(waypoint("to", Double.parseDouble(p[3]), Double.parseDouble(p[4])));

      RoutingContext rc = new RoutingContext();
      rc.localFunction = profile;
      rc.turnInstructionMode = 1;
      rc.considerTurnRestrictions = true;

      long t0 = System.currentTimeMillis();
      RoutingEngine engine = new RoutingEngine(null, null, segmentDir, waypoints, rc);
      engine.quite = true;
      engine.doRun(0);
      long ms = System.currentTimeMillis() - t0;

      if (engine.getErrorMessage() != null) {
        System.out.printf("%-24s FAILED: %s%n", name, engine.getErrorMessage());
        failures++;
        continue;
      }
      OsmTrack track = engine.getFoundTrack();
      System.out.printf("%-24s distance=%7d m  cost=%8d  nodes=%5d  (%d ms)%n",
          name, track.distance, track.cost, track.nodes.size(), ms);

      if (dumpDir != null) {
        StringBuilder sb = new StringBuilder();
        for (btools.router.OsmPathElement e : track.nodes) {
          sb.append(String.format("%.6f,%.6f%n",
              e.getILat() / 1000000.0 - 90.0, e.getILon() / 1000000.0 - 180.0));
        }
        Files.writeString(Path.of(dumpDir.getPath(), name + ".txt"), sb.toString());
      }
    }
    if (failures > 0) {
      System.err.println(failures + " route(s) failed");
      System.exit(2);
    }
  }

  /** BRouter 的座標是整數微度加偏移，不是浮點經緯度。 */
  private static OsmNodeNamed waypoint(String name, double lat, double lon) {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = name;
    n.ilon = (int) ((lon + 180.0) * 1000000.0 + 0.5);
    n.ilat = (int) ((lat + 90.0) * 1000000.0 + 0.5);
    return n;
  }
}
