import com.google.protobuf.ByteString;
import org.openstreetmap.osmosis.osmbinary.Fileformat;
import org.openstreetmap.osmosis.osmbinary.Osmformat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 把我們自己的路口規則注入 OSM 原始資料，產出一份給 BRouter 建圖用的 .osm.pbf。
 *
 * <p>ADR-0017：回報如果改變不了路線，「越來越聰明」就只是一句話。BRouter 的
 * profile 讀的是 OSM 標籤、讀不到我們的資料庫，所以唯一能讓回報進入路線計算的
 * 位置，是建圖之前的 OSM 資料本身。
 *
 * <p>刻意不改 BRouter 的任何一行。注入完之後跑的是官方原封不動的
 * OsmFastCutter / PosUnifier / WayLinker —— 因為 BRouter 每隔一陣子會發新版，
 * 而「我們改過它的內部類別」這種相依性，會在升級那天才爆炸。
 *
 * <h2>兩種注入，機制完全不同</h2>
 *
 * <p>禁止左轉合成一條 type=restriction 關聯（from way / via node / to way）。
 * 這是 OSM 自己的表達方式，BRouter 1.4.8 起原生支援，我們只是多寫了幾筆進去。
 * 乾淨、沒有副作用。
 *
 * <p>待轉沒有這麼漂亮的位置。BRouter 的轉向成本 turncost 是 way 層的變數
 * （進入那條 link 時求值，再乘上轉角），不是 (從哪來, 往哪去) 的函數 ——
 * 換句話說 stock BRouter 表達不出「這個路口的這個左轉比較貴」。我們能做到
 * 最接近的，是把左轉的離開臂第一段切成一條獨立的 way 並掛上
 * scooter:hook_turn=yes，profile 對這個標籤給高 turncost。
 *
 * <p>它的精度上限寫在這裡，不要以為是 bug：同一段離開臂，對向來車的右轉也會
 * 吃到同一筆成本，因為兩者進的是同一條 link、轉角一樣大。直行不受影響
 * （轉角為零，turncost 乘以 (1-cos) 之後是零），而直行正是大路口最常見的動作。
 * 節點層的 initialcost 會連直行一起罰，那才是真的錯。
 *
 * <h2>切 way 的那條安全規則</h2>
 *
 * <p>被既有 type=restriction 關聯引用到的 way，一律不切。切開之後那條關聯會指到
 * 半條路，BRouter 可能解成反方向的轉向限制 —— 拿一個軟成本去換一條真的禁止
 * 左轉出錯，不划算。臺北市有 1,204 條這種關聯，正好都集中在我們最想標待轉的
 * 大路口。
 *
 * <p>用法：
 *
 * <pre>
 *   java -cp brouter-all.jar;. OsmInject &lt;in.pbf&gt; &lt;rules.tsv&gt; &lt;out.pbf&gt; &lt;report.tsv&gt;
 * </pre>
 *
 * rules.tsv 每列：kind、ruleId、lat、lon、approachBearing、exitBearing，以 tab 分隔
 * （kind 為 NO_LEFT_TURN 或 HOOK；exitBearing 可留空）。
 */
public final class OsmInject {

  /** 收集節點座標的範圍。夠走完一條臂算出穩定方位角，又不會把整個城市讀進記憶體。 */
  private static final double COLLECT_CELL_DEG = 0.0025;

  /**
   * 路口節點與規則座標的最大距離。規則存的是路口中心（OSM 節點群聚的形心，
   * 見 CONTEXT.md），不是停止線，所以這個容忍量對的是「形心 vs 實際節點」的差距。
   */
  private static final double VIA_MATCH_M = 45.0;

  /** 進入方位角的容忍量。分隔式大路的兩條臂夾角可能有二十幾度。 */
  private static final double APPROACH_TOL_DEG = 40.0;

  /** 離開方位角的容忍量。比進入寬，因為左轉不一定是漂亮的 90 度。 */
  private static final double EXIT_TOL_DEG = 55.0;

  /** 算臂的方位角時往外走多遠。太短會被路口內幾公尺的節點雜訊帶歪。 */
  private static final double ARM_WALK_M = 25.0;

  /** 合成物件的編號起點。OSM 現有編號遠小於此，撞不到。 */
  private static final long SYNTHETIC_ID_BASE = 1_000_000_000_000L;

  /** 待轉的標籤。值只有 yes —— 成本大小寫在 profile 裡，不寫在資料裡。 */
  private static final String HOOK_TURN_KEY = "scooter:hook_turn";

  // ---------------------------------------------------------------- 資料結構

  private static final class Rule {
    String kind;
    String id;
    double lat;
    double lon;
    double approachBearing;
    Double exitBearing;
  }

  private static final class Way {
    long id;
    long[] refs;
    String[] keys;
    String[] vals;
    int blobIndex;
  }

  /** 從 via 出發的一條臂：往外的第一個節點，以及往外走 25 公尺之後的方位角。 */
  private static final class Arm {
    long wayId;
    long firstNode;
    double bearingOut;
  }

  /** 一筆要切開的 way：把 refs[cut] 到 refs[cut+1] 那一段獨立出來。 */
  private static final class Split {
    long wayId;
    int cut;
    String ruleId;
  }

  private static final class Restriction {
    long fromWay;
    long viaNode;
    long toWay;
    String ruleId;
  }

  // ------------------------------------------------------------------- 進入點

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      System.err.println("usage: OsmInject <in.pbf> <rules.tsv> <out.pbf> <report.tsv>");
      System.exit(1);
    }
    File in = new File(args[0]);
    File rulesFile = new File(args[1]);
    File out = new File(args[2]);
    File report = new File(args[3]);

    List<Rule> rules = readRules(rulesFile);
    System.out.println("rules: " + rules.size());

    Scan scan = scan(in, rules);
    System.out.println("nodes near rules: " + scan.nodePos.size()
        + ", candidate ways: " + scan.ways.size()
        + ", ways used by existing restrictions: " + scan.restrictionWays.size());

    Resolution res = resolve(rules, scan);
    writeReport(report, res);
    System.out.println("resolved: " + res.restrictions.size() + " restrictions, "
        + res.splits.size() + " hook-turn splits, " + res.failures.size() + " unresolved");

    write(in, out, res, scan);
    System.out.println("wrote " + out + " (" + out.length() + " bytes)");
  }

  // -------------------------------------------------------------------- 讀規則

  private static List<Rule> readRules(File f) throws Exception {
    List<Rule> rules = new ArrayList<>();
    if (!f.exists()) {
      return rules;
    }
    try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        String[] p = line.split("\t", -1);
        if (p.length < 5) {
          throw new IllegalArgumentException("bad rules line: " + line);
        }
        Rule r = new Rule();
        r.kind = p[0];
        r.id = p[1];
        r.lat = Double.parseDouble(p[2]);
        r.lon = Double.parseDouble(p[3]);
        r.approachBearing = Double.parseDouble(p[4]);
        r.exitBearing = (p.length > 5 && !p[5].isEmpty()) ? Double.valueOf(p[5]) : null;
        if (!"NO_LEFT_TURN".equals(r.kind) && !"HOOK".equals(r.kind)) {
          throw new IllegalArgumentException("unknown kind: " + r.kind);
        }
        rules.add(r);
      }
    }
    return rules;
  }

  // ---------------------------------------------------------- 第一趟：掃描 PBF

  private static final class Scan {
    Map<Long, double[]> nodePos = new HashMap<>();
    List<Way> ways = new ArrayList<>();
    Set<Long> restrictionWays = new HashSet<>();
    /** wayId 對到它落在第幾個 blob，第二趟只需要重編這幾個 blob。 */
    Map<Long, Integer> wayBlob = new HashMap<>();
  }

  /** 用 0.0025 度（約 250 到 280 公尺）的格子當粗篩，避免每個節點跟每條規則比一次。 */
  private static long cellKey(double lat, double lon) {
    long a = (long) Math.floor(lat / COLLECT_CELL_DEG);
    long b = (long) Math.floor(lon / COLLECT_CELL_DEG);
    return (a << 32) ^ (b & 0xffffffffL);
  }

  private static Scan scan(File in, List<Rule> rules) throws Exception {
    Scan s = new Scan();
    Set<Long> cells = new HashSet<>();
    for (Rule r : rules) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
          cells.add(cellKey(r.lat + dy * COLLECT_CELL_DEG, r.lon + dx * COLLECT_CELL_DEG));
        }
      }
    }

    int blobIndex = -1;
    try (DataInputStream din = open(in)) {
      byte[][] blob;
      while ((blob = readBlob(din)) != null) {
        blobIndex++;
        Fileformat.BlobHeader bh = Fileformat.BlobHeader.parseFrom(blob[0]);
        if (!"OSMData".equals(bh.getType())) {
          continue;
        }
        Osmformat.PrimitiveBlock pb = Osmformat.PrimitiveBlock.parseFrom(inflate(blob[1]));
        List<String> st = stringTable(pb);
        double granularity = pb.getGranularity();
        double latOffset = pb.getLatOffset();
        double lonOffset = pb.getLonOffset();

        for (Osmformat.PrimitiveGroup g : pb.getPrimitivegroupList()) {
          if (g.hasDense()) {
            Osmformat.DenseNodes dn = g.getDense();
            long id = 0;
            long lat = 0;
            long lon = 0;
            for (int i = 0; i < dn.getIdCount(); i++) {
              id += dn.getId(i);
              lat += dn.getLat(i);
              lon += dn.getLon(i);
              double dlat = (latOffset + granularity * lat) * 1e-9;
              double dlon = (lonOffset + granularity * lon) * 1e-9;
              if (cells.contains(cellKey(dlat, dlon))) {
                s.nodePos.put(id, new double[] {dlat, dlon});
              }
            }
          }
          for (Osmformat.Node n : g.getNodesList()) {
            double dlat = (latOffset + granularity * n.getLat()) * 1e-9;
            double dlon = (lonOffset + granularity * n.getLon()) * 1e-9;
            if (cells.contains(cellKey(dlat, dlon))) {
              s.nodePos.put(n.getId(), new double[] {dlat, dlon});
            }
          }
          for (Osmformat.Way w : g.getWaysList()) {
            boolean isHighway = false;
            for (int i = 0; i < w.getKeysCount(); i++) {
              if ("highway".equals(st.get(w.getKeys(i)))) {
                isHighway = true;
                break;
              }
            }
            if (!isHighway) {
              continue;
            }
            long[] refs = new long[w.getRefsCount()];
            long ref = 0;
            boolean near = false;
            for (int i = 0; i < w.getRefsCount(); i++) {
              ref += w.getRefs(i);
              refs[i] = ref;
              if (!near && s.nodePos.containsKey(ref)) {
                near = true;
              }
            }
            if (!near) {
              continue;
            }
            Way way = new Way();
            way.id = w.getId();
            way.refs = refs;
            way.keys = new String[w.getKeysCount()];
            way.vals = new String[w.getKeysCount()];
            for (int i = 0; i < w.getKeysCount(); i++) {
              way.keys[i] = st.get(w.getKeys(i));
              way.vals[i] = st.get(w.getVals(i));
            }
            way.blobIndex = blobIndex;
            s.ways.add(way);
            s.wayBlob.put(way.id, blobIndex);
          }
          for (Osmformat.Relation r : g.getRelationsList()) {
            boolean isRestriction = false;
            for (int i = 0; i < r.getKeysCount(); i++) {
              if ("type".equals(st.get(r.getKeys(i)))
                  && "restriction".equals(st.get(r.getVals(i)))) {
                isRestriction = true;
                break;
              }
            }
            if (!isRestriction) {
              continue;
            }
            long mid = 0;
            for (int i = 0; i < r.getMemidsCount(); i++) {
              mid += r.getMemids(i);
              if (r.getTypes(i) == Osmformat.Relation.MemberType.WAY) {
                s.restrictionWays.add(mid);
              }
            }
          }
        }
      }
    }
    return s;
  }

  // ------------------------------------------------------------------ 解析規則

  private static final class Resolution {
    List<Restriction> restrictions = new ArrayList<>();
    List<Split> splits = new ArrayList<>();
    List<String[]> failures = new ArrayList<>();
    List<String[]> successes = new ArrayList<>();
  }

  private static Resolution resolve(List<Rule> rules, Scan s) {
    // 只用候選 way 建一張小圖：節點對到鄰居，以及節點被哪幾條 way 用到。
    Map<Long, List<int[]>> incident = new HashMap<>();
    Map<Long, Set<Long>> neighbours = new HashMap<>();
    for (int wi = 0; wi < s.ways.size(); wi++) {
      Way w = s.ways.get(wi);
      for (int i = 0; i < w.refs.length; i++) {
        long n = w.refs[i];
        if (!s.nodePos.containsKey(n)) {
          continue;
        }
        incident.computeIfAbsent(n, k -> new ArrayList<>()).add(new int[] {wi, i});
        Set<Long> nb = neighbours.computeIfAbsent(n, k -> new HashSet<>());
        if (i > 0) {
          nb.add(w.refs[i - 1]);
        }
        if (i + 1 < w.refs.length) {
          nb.add(w.refs[i + 1]);
        }
      }
    }

    Resolution res = new Resolution();
    Set<Long> splitOnce = new HashSet<>();

    for (Rule r : rules) {
      long via = -1;
      double best = VIA_MATCH_M;
      for (Map.Entry<Long, Set<Long>> e : neighbours.entrySet()) {
        if (e.getValue().size() < 3) {
          continue; // 不是路口，是路上的一點
        }
        double[] p = s.nodePos.get(e.getKey());
        double d = distance(r.lat, r.lon, p[0], p[1]);
        if (d < best) {
          best = d;
          via = e.getKey();
        }
      }
      if (via < 0) {
        res.failures.add(new String[] {r.id, r.kind, "no-junction-node",
            "半徑 " + (int) VIA_MATCH_M + " 公尺內找不到三叉以上的路口節點"});
        continue;
      }

      List<Arm> arms = arms(via, incident, s);
      if (arms.size() < 3) {
        res.failures.add(new String[] {r.id, r.kind, "too-few-arms",
            "路口只有 " + arms.size() + " 條臂"});
        continue;
      }

      Arm from = pick(arms, norm(r.approachBearing + 180.0), APPROACH_TOL_DEG, null);
      if (from == null) {
        res.failures.add(new String[] {r.id, r.kind, "no-approach-arm",
            "找不到進入方位角 " + fmt(r.approachBearing) + " 對得上的臂"});
        continue;
      }
      double wantExit = r.exitBearing != null ? r.exitBearing : norm(r.approachBearing - 90.0);
      Arm to = pick(arms, wantExit, EXIT_TOL_DEG, from);
      if (to == null) {
        res.failures.add(new String[] {r.id, r.kind, "no-exit-arm",
            "找不到離開方位角 " + fmt(wantExit) + " 對得上的臂"});
        continue;
      }

      if ("NO_LEFT_TURN".equals(r.kind)) {
        Restriction x = new Restriction();
        x.fromWay = from.wayId;
        x.viaNode = via;
        x.toWay = to.wayId;
        x.ruleId = r.id;
        res.restrictions.add(x);
        res.successes.add(new String[] {r.id, r.kind, "ok",
            "from=" + from.wayId + " via=" + via + " to=" + to.wayId});
      } else {
        if (s.restrictionWays.contains(to.wayId)) {
          res.failures.add(new String[] {r.id, r.kind, "way-in-restriction",
              "離開路 " + to.wayId + " 被既有轉向限制關聯引用，不切"});
          continue;
        }
        if (!splitOnce.add(to.wayId)) {
          res.failures.add(new String[] {r.id, r.kind, "way-already-split",
              "離開路 " + to.wayId + " 已被另一條規則切過"});
          continue;
        }
        Way w = wayById(s, to.wayId);
        int idx = indexOf(w.refs, via);
        int nidx = indexOf(w.refs, to.firstNode);
        if (idx < 0 || nidx < 0 || Math.abs(idx - nidx) != 1) {
          res.failures.add(new String[] {r.id, r.kind, "bad-split-index", "way " + to.wayId});
          continue;
        }
        Split sp = new Split();
        sp.wayId = to.wayId;
        sp.cut = Math.min(idx, nidx);
        sp.ruleId = r.id;
        res.splits.add(sp);
        res.successes.add(new String[] {r.id, r.kind, "ok",
            "way=" + to.wayId + " via=" + via + " cut=" + sp.cut});
      }
    }
    return res;
  }

  private static Way wayById(Scan s, long id) {
    for (Way w : s.ways) {
      if (w.id == id) {
        return w;
      }
    }
    throw new IllegalStateException("way not found: " + id);
  }

  private static int indexOf(long[] a, long v) {
    for (int i = 0; i < a.length; i++) {
      if (a[i] == v) {
        return i;
      }
    }
    return -1;
  }

  /**
   * 列出從 via 出發的每一條臂。沿著 way 往外走到累積 25 公尺為止再取方位角 ——
   * 路口內常有間隔幾公尺的節點，只看第一段會量到雜訊。
   */
  private static List<Arm> arms(long via, Map<Long, List<int[]>> incident, Scan s) {
    List<Arm> out = new ArrayList<>();
    double[] c = s.nodePos.get(via);
    for (int[] inc : incident.getOrDefault(via, new ArrayList<>())) {
      Way w = s.ways.get(inc[0]);
      int i = inc[1];
      for (int dir = -1; dir <= 1; dir += 2) {
        int j = i + dir;
        if (j < 0 || j >= w.refs.length) {
          continue;
        }
        if (!s.nodePos.containsKey(w.refs[j])) {
          continue;
        }
        double walked = 0;
        double[] prev = c;
        double[] tip = s.nodePos.get(w.refs[j]);
        int k = j;
        while (k >= 0 && k < w.refs.length) {
          double[] p = s.nodePos.get(w.refs[k]);
          if (p == null) {
            break;
          }
          walked += distance(prev[0], prev[1], p[0], p[1]);
          tip = p;
          if (walked >= ARM_WALK_M) {
            break;
          }
          prev = p;
          k += dir;
        }
        Arm a = new Arm();
        a.wayId = w.id;
        a.firstNode = w.refs[j];
        a.bearingOut = bearing(c[0], c[1], tip[0], tip[1]);
        out.add(a);
      }
    }
    return out;
  }

  private static Arm pick(List<Arm> arms, double want, double tol, Arm exclude) {
    Arm best = null;
    double bestDiff = tol;
    for (Arm a : arms) {
      if (exclude != null && a.wayId == exclude.wayId && a.firstNode == exclude.firstNode) {
        continue;
      }
      double d = angleDiff(a.bearingOut, want);
      if (d < bestDiff) {
        bestDiff = d;
        best = a;
      }
    }
    return best;
  }

  // -------------------------------------------------------------- 第二趟：寫檔

  private static void write(File in, File out, Resolution res, Scan scan) throws Exception {
    Set<Integer> blobsToRewrite = new HashSet<>();
    Map<Long, Split> splitByWay = new HashMap<>();
    for (Split sp : res.splits) {
      splitByWay.put(sp.wayId, sp);
      Integer bi = scan.wayBlob.get(sp.wayId);
      if (bi == null) {
        throw new IllegalStateException("no blob for way " + sp.wayId);
      }
      blobsToRewrite.add(bi);
    }

    long nextId = SYNTHETIC_ID_BASE;
    int blobIndex = -1;
    try (DataInputStream din = open(in);
        DataOutputStream dout = new DataOutputStream(
            new BufferedOutputStream(new FileOutputStream(out), 1 << 20))) {
      byte[][] blob;
      while ((blob = readBlob(din)) != null) {
        blobIndex++;
        if (!blobsToRewrite.contains(blobIndex)) {
          dout.writeInt(blob[0].length);
          dout.write(blob[0]);
          dout.write(blob[1]);
          continue;
        }
        Osmformat.PrimitiveBlock pb = Osmformat.PrimitiveBlock.parseFrom(inflate(blob[1]));
        for (Osmformat.PrimitiveGroup g : pb.getPrimitivegroupList()) {
          if (g.hasDense() || g.getNodesCount() > 0) {
            throw new IllegalStateException("blob " + blobIndex
                + " 同時裝了節點與要切的 way，這個寫入器只重編純 way 的 blob");
          }
        }
        nextId = rewriteWayBlob(dout, pb, splitByWay, nextId);
      }
      if (!res.restrictions.isEmpty()) {
        writeRestrictionBlob(dout, res.restrictions, nextId);
      }
    }
  }

  /** 重編一個裝 way 的 blob：把要切的 way 換成 head、stub、tail 三段。 */
  private static long rewriteWayBlob(DataOutputStream dout, Osmformat.PrimitiveBlock src,
      Map<Long, Split> splitByWay, long nextId) throws Exception {
    List<String> st = stringTable(src);
    StringTableBuilder stb = new StringTableBuilder();
    Osmformat.PrimitiveGroup.Builder pg = Osmformat.PrimitiveGroup.newBuilder();

    for (Osmformat.PrimitiveGroup g : src.getPrimitivegroupList()) {
      for (Osmformat.Way w : g.getWaysList()) {
        long[] refs = new long[w.getRefsCount()];
        long ref = 0;
        for (int i = 0; i < refs.length; i++) {
          ref += w.getRefs(i);
          refs[i] = ref;
        }
        String[] keys = new String[w.getKeysCount()];
        String[] vals = new String[w.getKeysCount()];
        for (int i = 0; i < keys.length; i++) {
          keys[i] = st.get(w.getKeys(i));
          vals[i] = st.get(w.getVals(i));
        }
        Split sp = splitByWay.get(w.getId());
        if (sp == null) {
          pg.addWays(buildWay(stb, w.getId(), refs, keys, vals, false));
          continue;
        }
        // 第一段保留原編號 —— 其他關聯（公車路線之類）指到的是原編號，
        // 讓最前面那一段留著原名，斷得最少。
        int cut = sp.cut;
        List<long[]> parts = new ArrayList<>();
        if (cut > 0) {
          parts.add(Arrays.copyOfRange(refs, 0, cut + 1));
        }
        parts.add(Arrays.copyOfRange(refs, cut, cut + 2));
        if (cut + 2 <= refs.length - 1) {
          parts.add(Arrays.copyOfRange(refs, cut + 1, refs.length));
        }
        int stubIdx = (cut > 0) ? 1 : 0;
        for (int i = 0; i < parts.size(); i++) {
          long id = (i == 0) ? w.getId() : nextId++;
          pg.addWays(buildWay(stb, id, parts.get(i), keys, vals, i == stubIdx));
        }
      }
      for (Osmformat.Relation r : g.getRelationsList()) {
        pg.addRelations(rebuildRelation(stb, st, r));
      }
    }

    Osmformat.PrimitiveBlock.Builder pb = Osmformat.PrimitiveBlock.newBuilder()
        .setStringtable(stb.build())
        .addPrimitivegroup(pg)
        .setGranularity(src.getGranularity())
        .setLatOffset(src.getLatOffset())
        .setLonOffset(src.getLonOffset())
        .setDateGranularity(src.getDateGranularity());
    writeBlob(dout, "OSMData", pb.build().toByteArray());
    return nextId;
  }

  private static Osmformat.Way.Builder buildWay(StringTableBuilder stb, long id, long[] refs,
      String[] keys, String[] vals, boolean hookTurn) {
    Osmformat.Way.Builder b = Osmformat.Way.newBuilder().setId(id);
    for (int i = 0; i < keys.length; i++) {
      b.addKeys(stb.get(keys[i]));
      b.addVals(stb.get(vals[i]));
    }
    if (hookTurn) {
      b.addKeys(stb.get(HOOK_TURN_KEY));
      b.addVals(stb.get("yes"));
    }
    long prev = 0;
    for (long r : refs) {
      b.addRefs(r - prev);
      prev = r;
    }
    return b;
  }

  private static Osmformat.Relation.Builder rebuildRelation(StringTableBuilder stb,
      List<String> st, Osmformat.Relation r) {
    Osmformat.Relation.Builder b = Osmformat.Relation.newBuilder().setId(r.getId());
    for (int i = 0; i < r.getKeysCount(); i++) {
      b.addKeys(stb.get(st.get(r.getKeys(i))));
      b.addVals(stb.get(st.get(r.getVals(i))));
    }
    for (int i = 0; i < r.getMemidsCount(); i++) {
      b.addRolesSid(stb.get(st.get(r.getRolesSid(i))));
      b.addMemids(r.getMemids(i));
      b.addTypes(r.getTypes(i));
    }
    return b;
  }

  /** 把合成的轉向限制關聯寫成最後一個 blob。關聯本來就排在最後，順序不會壞掉。 */
  private static void writeRestrictionBlob(DataOutputStream dout, List<Restriction> list,
      long nextId) throws Exception {
    StringTableBuilder stb = new StringTableBuilder();
    Osmformat.PrimitiveGroup.Builder pg = Osmformat.PrimitiveGroup.newBuilder();
    for (Restriction x : list) {
      Osmformat.Relation.Builder b = Osmformat.Relation.newBuilder().setId(nextId++);
      b.addKeys(stb.get("type")).addVals(stb.get("restriction"));
      b.addKeys(stb.get("restriction")).addVals(stb.get("no_left_turn"));
      // 留一個記號，圖磚出問題時查得出是誰放的。BRouter 不看這個 key。
      b.addKeys(stb.get("source")).addVals(stb.get("scooter-report"));
      long[] ids = {x.fromWay, x.viaNode, x.toWay};
      String[] roles = {"from", "via", "to"};
      Osmformat.Relation.MemberType[] types = {
          Osmformat.Relation.MemberType.WAY,
          Osmformat.Relation.MemberType.NODE,
          Osmformat.Relation.MemberType.WAY};
      long prev = 0;
      for (int i = 0; i < 3; i++) {
        b.addRolesSid(stb.get(roles[i]));
        b.addMemids(ids[i] - prev);
        b.addTypes(types[i]);
        prev = ids[i];
      }
      pg.addRelations(b);
    }
    Osmformat.PrimitiveBlock pb = Osmformat.PrimitiveBlock.newBuilder()
        .setStringtable(stb.build())
        .addPrimitivegroup(pg)
        .setGranularity(100)
        .setDateGranularity(1000)
        .build();
    writeBlob(dout, "OSMData", pb.toByteArray());
  }

  private static final class StringTableBuilder {
    private final Map<String, Integer> index = new HashMap<>();
    private final List<String> values = new ArrayList<>();

    StringTableBuilder() {
      values.add("");
      index.put("", 0);
    }

    int get(String s) {
      Integer i = index.get(s);
      if (i != null) {
        return i;
      }
      int n = values.size();
      values.add(s);
      index.put(s, n);
      return n;
    }

    Osmformat.StringTable build() {
      Osmformat.StringTable.Builder b = Osmformat.StringTable.newBuilder();
      for (String s : values) {
        b.addS(ByteString.copyFromUtf8(s));
      }
      return b.build();
    }
  }

  // ------------------------------------------------------------------ PBF 底層

  private static DataInputStream open(File f) throws Exception {
    return new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 20));
  }

  /** 回傳 headerBytes 與 blobBytes 兩個陣列，讀完回傳 null。 */
  private static byte[][] readBlob(DataInputStream in) throws Exception {
    int len;
    try {
      len = in.readInt();
    } catch (EOFException e) {
      return null;
    }
    byte[] header = new byte[len];
    in.readFully(header);
    Fileformat.BlobHeader bh = Fileformat.BlobHeader.parseFrom(header);
    byte[] body = new byte[bh.getDatasize()];
    in.readFully(body);
    return new byte[][] {header, body};
  }

  private static byte[] inflate(byte[] body) throws Exception {
    Fileformat.Blob b = Fileformat.Blob.parseFrom(body);
    if (b.hasRaw()) {
      return b.getRaw().toByteArray();
    }
    if (b.hasZlibData()) {
      byte[] out = new byte[b.getRawSize()];
      Inflater inf = new Inflater();
      inf.setInput(b.getZlibData().toByteArray());
      int n = 0;
      while (n < out.length) {
        int k = inf.inflate(out, n, out.length - n);
        if (k == 0 && (inf.finished() || inf.needsInput())) {
          break;
        }
        n += k;
      }
      inf.end();
      return out;
    }
    throw new IllegalStateException("blob 用了我們沒實作的壓縮方式");
  }

  private static void writeBlob(DataOutputStream dout, String type, byte[] raw) throws Exception {
    Deflater def = new Deflater();
    def.setInput(raw);
    def.finish();
    byte[] buf = new byte[raw.length + 1024];
    int n = def.deflate(buf);
    def.end();
    Fileformat.Blob blob = Fileformat.Blob.newBuilder()
        .setRawSize(raw.length)
        .setZlibData(ByteString.copyFrom(buf, 0, n))
        .build();
    byte[] body = blob.toByteArray();
    byte[] header = Fileformat.BlobHeader.newBuilder()
        .setType(type)
        .setDatasize(body.length)
        .build()
        .toByteArray();
    dout.writeInt(header.length);
    dout.write(header);
    dout.write(body);
  }

  private static List<String> stringTable(Osmformat.PrimitiveBlock pb) {
    List<String> out = new ArrayList<>(pb.getStringtable().getSCount());
    for (ByteString bs : pb.getStringtable().getSList()) {
      out.add(bs.toStringUtf8());
    }
    return out;
  }

  // -------------------------------------------------------------------- 幾何

  private static double distance(double lat1, double lon1, double lat2, double lon2) {
    double mLat = 111132.0;
    double mLon = 111320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
    double dy = (lat2 - lat1) * mLat;
    double dx = (lon2 - lon1) * mLon;
    return Math.sqrt(dx * dx + dy * dy);
  }

  private static double bearing(double lat1, double lon1, double lat2, double lon2) {
    double mLon = Math.cos(Math.toRadians((lat1 + lat2) / 2));
    double dy = lat2 - lat1;
    double dx = (lon2 - lon1) * mLon;
    return norm(Math.toDegrees(Math.atan2(dx, dy)));
  }

  private static double norm(double d) {
    double x = d % 360.0;
    return x < 0 ? x + 360.0 : x;
  }

  private static double angleDiff(double a, double b) {
    double d = Math.abs(norm(a) - norm(b));
    return d > 180 ? 360 - d : d;
  }

  private static String fmt(double d) {
    return String.format("%.1f", d);
  }

  private static void writeReport(File f, Resolution res) throws Exception {
    try (PrintWriter pw = new PrintWriter(f, StandardCharsets.UTF_8)) {
      pw.println("rule_id\tkind\tstatus\tdetail");
      for (String[] r : res.successes) {
        pw.println(String.join("\t", r));
      }
      for (String[] r : res.failures) {
        pw.println(String.join("\t", r));
      }
    }
  }
}
