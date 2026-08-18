package btools.router;

/**
 * 讀取 BRouter 轉向指示的欄位。
 *
 * <p><b>這個檔案放在 {@code btools.router} 套件裡是刻意的，不是放錯地方。</b>
 * {@link VoiceHint} 的 {@code cmd}、{@code angle}、{@code distanceToNext}、
 * {@code indexInTrack} 全部是 package-private，公開的只有 {@code getTime()}
 * 這幾個 —— BRouter 預期的用法是把整條路線序列化成 GPX 或 GeoJSON 再解析回來。
 * 我們要的是逐向導航，把一份剛算好的物件序列化成文字再解析回來，
 * 只為了讀四個 int，那才是真正難看的做法。
 *
 * <p>用 Java 而不是 Kotlin 寫，是因為 package-private 是 Java 的可見性概念，
 * 由 Java 端存取最不容易在編譯器版本之間出意外。
 *
 * <p><b>升級 BRouter 時第一個要看的就是這個檔案。</b> 它依賴的是內部欄位，
 * 上游沒有義務維持它們 —— 欄位改名會是編譯錯誤（好事），欄位語意改變則不會
 * （壞事，症狀是轉向指示悄悄變得不對）。
 */
public final class VoiceHintAccess {

    private VoiceHintAccess() {}

    /** 轉向動作。數值是 {@link VoiceHint} 的常數，語意見 {@link #isLeftTurn}。 */
    public static int command(VoiceHint hint) {
        return hint.cmd;
    }

    /** 轉向角度，負為左、正為右。 */
    public static float angle(VoiceHint hint) {
        return hint.angle;
    }

    /** 到<b>下一個</b>指示的距離，公尺。不是到這個指示的距離。 */
    public static double distanceToNext(VoiceHint hint) {
        return hint.distanceToNext;
    }

    /** 這個指示落在路線的第幾個節點上。 */
    public static int indexInTrack(VoiceHint hint) {
        return hint.indexInTrack;
    }

    /**
     * 轉入的那條路的標籤，形如 {@code highway=secondary surface=asphalt oneway=yes}。
     *
     * <p><b>裡面沒有路名。</b> rd5 只存路由用得到的標籤，而 {@code name} 不是
     * 其中之一 —— BRouter 是路由引擎，不是圖資供應商。路名要另外來源。
     */
    public static String wayTags(VoiceHint hint) {
        return hint.goodWay == null ? "" : String.valueOf(hint.goodWay.wayKeyValues);
    }

    /**
     * 是不是左轉類的動作（含大左轉與斜左轉）。
     *
     * <p>本專案只關心左轉 —— 待轉規則掛在左轉上，禁止左轉也是。
     * 右轉與直行對機車沒有台灣專屬的規則，那些交給一般的導航播報。
     */
    public static boolean isLeftTurn(VoiceHint hint) {
        return hint.cmd == VoiceHint.TL || hint.cmd == VoiceHint.TSLL || hint.cmd == VoiceHint.TSHL;
    }
}
