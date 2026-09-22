package com.example.aplv;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.aplv.LogIndex.EntryRow;

/**
 * セッション追跡。セッション ID などの識別子が現れたエントリを起点に、
 * 同じリクエストのエントリをまとめて返す。
 *
 * <p>「同じリクエスト」は <strong>同一ログファイル・同一スレッド</strong>の範囲で判定する。
 * 同期型のサーブレットでは 1 リクエストを 1 スレッドが最後まで処理し、同じスレッドの
 * リクエストは順番に処理されることを前提にしている。スレッドはプールで使い回されるため、
 * スレッドだけでは区切れず、どこで区切るかを {@link Mode} で選ぶ。
 *
 * <ul>
 *   <li>{@link Mode#BOUNDARY} — はじまり・おわりの語で区切る（既定。下の「範囲の決め方」）</li>
 *   <li>{@link Mode#WINDOW} — 起点の前後一定時間で区切る（{@link #byTimeWindow}）。
 *       語を決めなくてよい代わりに、別のリクエストの行を取り込むことがある</li>
 * </ul>
 *
 * <p>範囲の決め方（{@link Mode#BOUNDARY} の場合。起点ごと）:
 * <ol>
 *   <li>起点から同じスレッドを遡り、最も近い「はじまり」を探す。途中で別の「おわり」に
 *       一致したら、起点はそのリクエストの後ろにあるので探索をやめる</li>
 *   <li>起点から進み、「おわり」で閉じる。おわりより先に次の「はじまり」が来たら、その直前で閉じる</li>
 *   <li>はじまりが見つからなくても、次のはじまりより先におわりが来れば、はじまりの行が
 *       欠けた（ローテートで前のファイルに残った等）リクエストとして扱う</li>
 *   <li>はじまりもおわりも起点を囲まない場合は、どのリクエストにも属さない行として単独で返す</li>
 * </ol>
 * いずれの方向も {@link #maxDurationMillis} を超えたところで打ち切る。おわりが出ないまま
 * 例外で抜けたリクエストが、後続の無関係なログまで取り込むのを防ぐため。
 *
 * <p>判定できないもの（画面にも制限事項として表示している）:
 * 非同期処理で別スレッドに出たログ、1 スレッドで複数リクエストを交互に処理する方式
 * （WebFlux / Netty）、リクエストの途中でファイルが切り替わった場合の前後のファイル。
 *
 * <p>はじまり・おわりの照合は検索時に行い、取り込み時には行わない。文言を変えても
 * 索引を作り直さずに済ませるため。
 *
 * <p>範囲の探索は {@code idx_entries_ts} を起点から時刻順に辿り、境界が見つかったところで
 * 打ち切る。専用の索引（file_id, thread, …）は足していない。
 * 以下の実測はすべて {@link Mode#BOUNDARY} のもので、{@link Mode#WINDOW} は測っていない
 * （窓の秒数とログの密度で読む行数が変わるため、同じ数値の目安にはならない）。
 * 実測（100 万行・1 ファイル・50 スレッド・108 MB、Windows 11 / JDK 8、各 5 回の中央値）:
 * <table summary="セッション追跡の実測">
 *   <tr><th>起点</th><th>--fts あり</th><th>--fts なし</th></tr>
 *   <tr><td>26 件（26 リクエスト）</td><td>11ms</td><td>577ms</td></tr>
 *   <tr><td>299 件（範囲を求めるのは上限の 200 リクエスト）</td><td>58ms</td><td>587ms</td></tr>
 * </table>
 * --fts なしの値は、起点探しをまとめ読み + バイト列照合 + 文字列列の遅延取り出しに変える前は
 * 5,221ms / 5,270ms だった（{@link LogIndex.SequentialRawReader}）。--fts ありは 11ms / 52ms で、
 * 変更後の 11ms / 58ms との差は測定のばらつきの範囲。
 * リクエスト単位の絞り込み（{@link #withRequestFilter}）の追加後に、299 件の条件で測り直した
 * （同じ条件・5 回の中央値）: 絞り込みなし 51ms、含む・全件一致 53ms、除く・299 件すべてを
 * 落とす 70ms。絞り込みなしが上表の 52ms と 1ms 違うのは測定のばらつきの範囲で、差はないとみる。
 * 絞り込みは結論が出た時点で読むのをやめるので、上の値にほとんど上乗せされない。
 * --fts なしでは起点の数によらずほぼ一定で、起点探し（全件のバイト範囲を読む）が大半を占める。
 * ありふれた文字列（{@code sessionId=}、起点 133,347 件）を指定しても、起点を溜めずに流すので
 * --fts ありで 630ms、--fts なしで 860ms、ヒープ 256MB で完走した（高速化前は --fts ありで
 * 1,008ms、除外を付けて {@link #MAX_EXAMINED_REQUESTS} の上限に達した場合で 1,360ms）。
 * 範囲探索のクエリで files と JOIN していたときは、並べ替えのために時間窓の全行を集めていたため、
 * 上の 2 行が --fts ありでも 1,326ms / 11,743ms かかっていた（{@link LogIndex#selectEntriesOnly}）。
 */
public final class SessionTrace {

    /** 最大所要時間の既定値（分）。 */
    public static final int DEFAULT_MAX_MINUTES = 10;
    /** 最大所要時間の上限（分）。 */
    public static final int MAX_MAX_MINUTES = 24 * 60;
    /** {@link Mode#WINDOW} の前後秒数の既定値。 */
    public static final int DEFAULT_WINDOW_SECONDS = 10;
    /** {@link Mode#WINDOW} の前後秒数の上限。 */
    public static final int MAX_WINDOW_SECONDS = 3600;
    /** 返すリクエスト数の上限。超えた分は数えるだけで範囲を求めない。 */
    static final int MAX_REQUESTS = 200;
    /**
     * 範囲を求めるリクエスト数の上限。絞り込みで落ちたものも数える。
     * 除外ばかりが続くときに、いつまでも走査し続けないための歯止め。
     * 実測（100 万行・--fts あり・全件除外）でここまで調べて 1,360ms。
     */
    static final int MAX_EXAMINED_REQUESTS = 2000;
    /**
     * 1 リクエストあたりに返すエントリ数の上限（はじまり側・おわり側の合計）。
     * はじまり側は半分までにして、おわり側を探す余地を必ず残す。
     */
    static final int MAX_ROWS_PER_REQUEST = 1000;

    /**
     * 起点から遡る走査。{@code idx_entries_ts} を逆順に辿り、はじまりかおわりが見つかったら
     * 読むのをやめる。行を全部集めてから並べ替える実行計画になると打ち切りが効かなくなるため、
     * files との JOIN は入れない（試験で実行計画を確認している）。
     */
    static final String BACKWARD_SQL = LogIndex.selectEntriesOnly()
            + "WHERE e.file_id = ? AND e.thread = ? AND e.ts_millis BETWEEN ? AND ? "
            + "ORDER BY e.ts_millis DESC, e.file_id DESC, e.line_no DESC";
    /** 起点から進む走査。{@link #BACKWARD_SQL} と同じ理由で JOIN を入れない。 */
    static final String FORWARD_SQL = LogIndex.selectEntriesOnly()
            + "WHERE e.file_id = ? AND e.thread = ? AND e.ts_millis BETWEEN ? AND ? "
            + "ORDER BY e.ts_millis, e.file_id, e.line_no";

    /** trigram は 3 文字以上でないと部分一致検索できない。 */
    private static final int FTS_MIN_LEN = 3;

    /** 同じリクエストとみなす範囲の決め方。 */
    public enum Mode {
        /** はじまり・おわりの語で区切る。 */
        BOUNDARY,
        /** セッション ID のある行の前後一定時間を同じリクエストとみなす。 */
        WINDOW
    }

    private final String sessionId;
    private final Mode mode;
    private final Pattern startRe;
    private final Pattern endRe;
    private final long maxDurationMillis;
    /** {@link Mode#WINDOW} で前後に見る時間（ミリ秒）。 */
    private final long windowMillis;
    /** これに一致する行を含むリクエストだけを残す（null なら絞り込まない）。 */
    private Pattern containsRe;
    /** これに一致する行を含むリクエストを除く（null なら除かない）。 */
    private Pattern excludesRe;
    /** 起点探しで使う識別子のバイト列と読み出しバッファ（使い回す）。 */
    private byte[] needle;
    private byte[] buffer = new byte[8192];

    /**
     * @param sessionId 識別子。正規表現ではなく文字列としてそのまま照合する（大文字小文字を区別）
     * @param startRe   はじまりのメッセージ（1 行目）に一致する正規表現
     * @param endRe     おわりのメッセージ（1 行目）に一致する正規表現
     * @param maxMinutes 1 リクエストの最大所要時間（分）
     */
    public SessionTrace(String sessionId, Pattern startRe, Pattern endRe, long maxMinutes) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("セッション ID を指定してください");
        }
        if (startRe == null || endRe == null) {
            throw new IllegalArgumentException("はじまりとおわりの両方を指定してください");
        }
        if (maxMinutes < 1 || maxMinutes > MAX_MAX_MINUTES) {
            throw new IllegalArgumentException(
                    "最大所要時間は 1〜" + MAX_MAX_MINUTES + " 分で指定してください");
        }
        this.sessionId = sessionId;
        this.mode = Mode.BOUNDARY;
        this.startRe = startRe;
        this.endRe = endRe;
        this.maxDurationMillis = maxMinutes * 60_000L;
        this.windowMillis = 0;
    }

    private SessionTrace(String sessionId, long windowSeconds) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("セッション ID を指定してください");
        }
        if (windowSeconds < 1 || windowSeconds > MAX_WINDOW_SECONDS) {
            throw new IllegalArgumentException(
                    "前後の秒数は 1〜" + MAX_WINDOW_SECONDS + " 秒で指定してください");
        }
        this.sessionId = sessionId;
        this.mode = Mode.WINDOW;
        this.startRe = null;
        this.endRe = null;
        this.maxDurationMillis = 0;
        this.windowMillis = windowSeconds * 1000L;
    }

    /**
     * はじまり・おわりの語を使わず、<strong>セッション ID のある行の前後
     * {@code windowSeconds} 秒</strong>にある同じファイル・同じスレッドの行を、
     * ひとまとまりのリクエストとみなす。
     *
     * <p>語を決めなくても使える代わりに、正しさは落ちる。スレッドはプールで使い回されるので、
     * 時間だけで区切ると直前・直後に同じスレッドが処理した<strong>別のリクエスト</strong>の行まで
     * 取り込むことがある（逆に、秒数が短ければ同じリクエストの行を取りこぼす）。
     * はじまり・おわりの語が分かっているなら {@link Mode#BOUNDARY} の方が正確。
     *
     * <p>同じスレッドで、前の起点から {@code windowSeconds} 秒以内に次の起点が出たときは、
     * 範囲を継ぎ足して 1 つのリクエストにまとめる（行が二重に出ないようにするため）。
     */
    public static SessionTrace byTimeWindow(String sessionId, long windowSeconds) {
        return new SessionTrace(sessionId, windowSeconds);
    }

    /**
     * リクエスト単位の絞り込みを設定する。
     *
     * <p>判定はリクエストの塊ごとに行う（行単位で消すと処理の流れが読めなくなるため）。
     * 照合はスタックトレースを含むエントリ全体に対して行い、一致した時点で読むのをやめる。
     *
     * @param containsRe 一致する行を含むリクエストだけを残す正規表現（null なら絞り込まない）
     * @param excludesRe 一致する行を含むリクエストを除く正規表現（null なら除かない）
     */
    public SessionTrace withRequestFilter(Pattern containsRe, Pattern excludesRe) {
        this.containsRe = containsRe;
        this.excludesRe = excludesRe;
        return this;
    }

    /** おわり側の閉じ方。 */
    public enum EndReason {
        /** おわりのメッセージで閉じた。 */
        END,
        /** おわりより先に同じスレッドの次のはじまりが来たので、その直前で閉じた。 */
        NEXT_START,
        /** 最大所要時間・ファイル末尾・件数上限のいずれかまでに閉じなかった。 */
        NOT_FOUND,
        /** どのリクエストにも属さない単独の行。 */
        STANDALONE,
        /** {@link Mode#WINDOW} で、前後の時間だけで区切った。 */
        WINDOW
    }

    /** 1 リクエスト分の結果。 */
    public static final class Request {
        public final String source;
        public final String thread;
        /** はじまりのメッセージを見つけたか。 */
        public boolean startFound;
        public EndReason endReason;
        /** 件数上限で打ち切ったか。 */
        public boolean truncated;
        /** おわり側を件数上限で打ち切ったか。後ろの行はまだ同じリクエストに属しうる。 */
        boolean cutAtTail;
        /** {@link Mode#WINDOW}: このリクエストに寄せた最後の起点の時刻。継ぎ足しの判定に使う。 */
        long lastAnchorTs;
        /** {@link Mode#WINDOW}: 取り込み済みの時間範囲の右端。 */
        long rangeEndTs;
        /** 絞り込みを通っているか（継ぎ足しで変わりうる）。 */
        boolean kept;
        /** 直前の継ぎ足しで行が増えたか。増えたときだけ絞り込みを判定し直す。 */
        boolean rowsAppended;
        /** 絞り込みの判定を済ませた行数。ここから先だけを読み直す。 */
        int filterCheckedRows;
        /** 「含む」に一致した行があったか。 */
        boolean containsHit;
        /** 「除く」に一致した行があったか（該当したら以後は覆らない）。 */
        boolean excludeHit;
        /** 時系列（ファイル内の行順）に並んだエントリ。 */
        public final List<EntryRow> entries = new ArrayList<>();
        /** 起点になった（識別子を含む）エントリの id。行ごとに引くので Set で持つ。 */
        public final Set<Long> anchorIds = new HashSet<>();

        Request(String source, String thread) {
            this.source = source;
            this.thread = thread;
        }

        long firstLine() {
            return entries.get(0).lineNo;
        }

        long lastLine() {
            return entries.get(entries.size() - 1).lineNo;
        }
    }

    /** 追跡結果。 */
    public static final class Result {
        /** 識別子を含むエントリの総数。 */
        public long anchorTotal;
        /** 上限に達して範囲を求めなかった起点があるか（{@link #MAX_REQUESTS} 等）。 */
        public boolean truncated;
        /** 絞り込みで落としたリクエスト数。 */
        public long filteredOut;
        /** 先頭エントリの時刻順。 */
        public final List<Request> requests = new ArrayList<>();
    }

    public Result run(Connection conn) throws SQLException {
        Result result = new Result();
        // (file_id + "\0" + thread) → そのスレッドで求めたリクエスト。同じリクエストに
        // 起点が複数あるとき、範囲を求め直さずに既存のリクエストへ寄せるために使う。
        Map<String, List<Request>> byContext = new HashMap<>();
        Map<String, RandomAccessFile> handles = new HashMap<>();
        // 起点探し用。エントリごとに seek するのではなく、ファイルを前方向にまとめ読みする
        Map<String, LogIndex.SequentialRawReader> readers = new HashMap<>();
        Map<Long, String> paths = new HashMap<>();
        int examined = 0;
        // 起点は溜めずに 1 件ずつ処理する。ありふれた文字列を指定すると全行が起点になりうるため。
        // 起点のカーソルを開いたまま範囲探索のクエリを流す（SQLite は同じ接続で併用できる）。
        try (PreparedStatement ps = prepareAnchorQuery(conn);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (!anchorMatches(rs, readers, paths)) {
                    continue;
                }
                // 一致した行だけ、文字列の列（logger / thread / message / パス）を取り出す
                EntryRow anchor = LogIndex.rowFrom(rs);
                result.anchorTotal++;
                String key = anchor.fileId + "\0" + anchor.thread;
                List<Request> sameContext = byContext.get(key);
                // 起点は時刻順に来るので、同じスレッドで直前に求めたリクエストは末尾にある
                Request last = sameContext == null ? null
                        : sameContext.get(sameContext.size() - 1);
                // 時間窓モード: 直前のリクエストが取り込み済みの行か、最後の起点から窓の時間内なら
                // 同じリクエストとして扱い、この起点のぶんだけ窓を右へ伸ばす。
                // （ID が続けて出るあいだは 1 つのリクエストにまとめる）
                if (mode == Mode.WINDOW && last != null
                        && (anchor.lineNo <= last.lastLine()
                            || anchor.tsMillis - last.lastAnchorTs <= windowMillis)) {
                    last.anchorIds.add(anchor.id);
                    if (anchor.tsMillis > last.lastAnchorTs) {
                        last.lastAnchorTs = anchor.tsMillis;
                    }
                    appendWindowRows(conn, last, anchor, anchor.tsMillis + windowMillis);
                    reconcileFilter(result, last, handles);
                    continue;
                }
                Request existing = findContaining(sameContext, anchor.lineNo);
                if (existing != null) {
                    existing.anchorIds.add(anchor.id);
                    continue;
                }
                if (result.requests.size() >= MAX_REQUESTS || examined >= MAX_EXAMINED_REQUESTS) {
                    result.truncated = true;
                    continue;
                }
                Request previous = last;
                Request req = expand(conn, anchor, previous);
                if (req == previous) {
                    // 上限で切った後ろにあった起点、または時間窓を継ぎ足した起点
                    previous.anchorIds.add(anchor.id);
                    reconcileFilter(result, previous, handles);
                    continue;
                }
                examined++;
                req.anchorIds.add(anchor.id);
                // 落としたリクエストも byContext には残す。同じリクエストの別の起点で
                // 範囲を求め直さないため（落とした分がまた出てくることもない）。
                byContext.computeIfAbsent(key, k -> new ArrayList<>()).add(req);
                req.kept = keepRequest(req, handles);
                if (req.kept) {
                    result.requests.add(req);
                } else {
                    // 落としたリクエストは結果に載せない（byContext には残してあるので、
                    // 同じリクエストの別の起点で求め直すことも、数え直すこともない）。
                    result.filteredOut++;
                }
            }
        } catch (IOException e) {
            throw new SQLException("ログファイルを読み出せません: " + e.getMessage(), e);
        } finally {
            LogIndex.closeHandles(handles);
            LogIndex.closeReaders(readers);
        }
        // 起点は時刻順に拾うが、はじまりまで遡るとリクエストの先頭の順序は入れ替わりうる。
        Collections.sort(result.requests, (a, b) -> {
            EntryRow x = a.entries.get(0);
            EntryRow y = b.entries.get(0);
            if (x.tsMillis != y.tsMillis) {
                return Long.compare(x.tsMillis, y.tsMillis);
            }
            if (x.fileId != y.fileId) {
                return Long.compare(x.fileId, y.fileId);
            }
            return Long.compare(x.lineNo, y.lineNo);
        });
        return result;
    }

    /**
     * 絞り込みを通すか。{@code contains} はどれか 1 行でも一致すれば通し、
     * {@code excludes} はどれか 1 行でも一致したら落とす。
     *
     * <p>スタックトレースを含む本文は元ファイルから読むので、結論が出た時点で読むのをやめる。
     * {@code excludes} が無ければ最初の一致で、{@code contains} が無ければ全行を見ずに済む。
     *
     * <p>見るのは {@link #MAX_ROWS_PER_REQUEST} で切ったあとのエントリだけ。切り落とした
     * 後ろにしか語が無いリクエストは、{@code contains} では残らず、{@code excludes} では
     * 落ちない。1 リクエストが上限を超えたときだけの話なので、判定を合わせるために
     * 上限の先まで読み直すことはしない（画面の制限事項に明記している）。
     */
    private boolean keepRequest(Request req, Map<String, RandomAccessFile> handles)
            throws IOException {
        if (containsRe == null && excludesRe == null) {
            return true;
        }
        if (req.excludeHit) {
            return false; // 一度でも除外条件に該当したら、行が増えても覆らない
        }
        if (excludesRe == null && req.containsHit) {
            return true; // 除外を見る必要が無く、既に含む条件を満たしている
        }
        // 前に見たところから先だけを読む。時間窓モードでは継ぎ足しのたびにここへ来るので、
        // 毎回すべての行を読み直すと、1 リクエストが育つほど読み出しが増えてしまう。
        for (int i = req.filterCheckedRows; i < req.entries.size(); i++) {
            // 読めなければエラーにする（黙って一致しなかったことにすると、
            // 絞り込みの結果が静かにずれる）
            String raw = LogIndex.readEntryRaw(handles, req.entries.get(i));
            if (excludesRe != null && excludesRe.matcher(raw).find()) {
                req.excludeHit = true;
                req.filterCheckedRows = i + 1;
                return false;
            }
            if (!req.containsHit && containsRe != null && containsRe.matcher(raw).find()) {
                req.containsHit = true;
                if (excludesRe == null) {
                    req.filterCheckedRows = i + 1;
                    return true;
                }
            }
        }
        req.filterCheckedRows = req.entries.size();
        return containsRe == null || req.containsHit;
    }

    /**
     * 時間窓の継ぎ足しで行が増えたリクエストについて、絞り込みの判定をやり直す。
     * 増えた行に「含む」の語が出れば拾い直し、「除く」の語が出れば落とす。
     */
    private void reconcileFilter(Result result, Request req,
            Map<String, RandomAccessFile> handles) throws IOException {
        if (!req.rowsAppended || (containsRe == null && excludesRe == null)) {
            req.rowsAppended = false;
            return;
        }
        req.rowsAppended = false;
        boolean keep = keepRequest(req, handles);
        if (keep == req.kept) {
            return;
        }
        req.kept = keep;
        if (keep) {
            if (result.requests.size() >= MAX_REQUESTS) {
                req.kept = false;
                result.truncated = true;
                return;
            }
            result.requests.add(req);
            result.filteredOut--;
        } else {
            result.requests.remove(req);
            result.filteredOut++;
        }
    }

    private static Request findContaining(List<Request> requests, long lineNo) {
        if (requests == null) {
            return null;
        }
        for (Request r : requests) {
            if (r.firstLine() <= lineNo && lineNo <= r.lastLine()) {
                return r;
            }
        }
        return null;
    }

    /**
     * いまの行が識別子を含むか。文字列の列は取り出さず、byte 範囲を読んでバイト列のまま照合する。
     *
     * <p>照合はスタックトレースを含むエントリ全体に対して行う（一覧の全文検索と同じ範囲）。
     * 大文字小文字は区別する（{@link LogIndex#containsBytes} はバイト列をそのまま比べる）。
     * 識別子を正規表現として扱わないのは、jvmRoute 付きの JSESSIONID（{@code ABC.node1}）の
     * {@code .} などをメタ文字にしないため。
     *
     * <p>一致しない行が大半なので、ここで {@code logger} などの文字列を取り出すと、
     * 使われない文字列の生成に時間を取られる（実測は {@link LogIndex.SequentialRawReader}）。
     * ファイルのパスも、行ごとではなく file_id ごとに 1 回だけ取り出す。
     */
    private boolean anchorMatches(ResultSet rs, Map<String, LogIndex.SequentialRawReader> readers,
            Map<Long, String> paths) throws SQLException, IOException {
        long start = rs.getLong(4);
        long end = rs.getLong(5);
        long size = end > start ? end - start : 0;
        if (size <= 0) {
            return false;
        }
        long fileId = rs.getLong(2);
        String path = paths.get(fileId);
        if (path == null) {
            path = rs.getString(11);
            paths.put(fileId, path);
        }
        LogIndex.SequentialRawReader reader = readers.get(path);
        if (reader == null) {
            reader = new LogIndex.SequentialRawReader(path);
            readers.put(path, reader);
        }
        int len = (int) Math.min(size, Integer.MAX_VALUE);
        if (buffer.length < len) {
            buffer = new byte[len];
        }
        int read = reader.read(start, len, buffer);
        return LogIndex.containsBytes(buffer, read, needleBytes());
    }

    /** 識別子の UTF-8 バイト列（1 回だけ作る）。 */
    private byte[] needleBytes() {
        if (needle == null) {
            needle = sessionId.getBytes(StandardCharsets.UTF_8);
        }
        return needle;
    }

    /**
     * 識別子を含みうるエントリを時刻順に返すクエリ。含むかどうかの最終判定は
     * {@link #anchorMatches} で行う。
     *
     * <p>FTS5 があれば trigram で候補を絞る。trigram は大文字小文字を区別しないので
     * 候補は取りこぼさず、最終判定で区別する。
     */
    private PreparedStatement prepareAnchorQuery(Connection conn) throws SQLException {
        StringBuilder sql = new StringBuilder(LogIndex.selectBase());
        boolean useFts = sessionId.length() >= FTS_MIN_LEN && LogIndex.ftsAvailable(conn);
        if (useFts) {
            sql.append("WHERE e.id IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH ?)");
        }
        sql.append(" ORDER BY e.ts_millis, e.file_id, e.line_no");
        PreparedStatement ps = conn.prepareStatement(sql.toString());
        try {
            if (useFts) {
                ps.setString(1, "\"" + sessionId.replace("\"", "\"\"") + "\"");
            }
        } catch (SQLException e) {
            ps.close();
            throw e;
        }
        return ps;
    }

    private boolean isStart(EntryRow e) {
        return startRe.matcher(e.message).find();
    }

    private boolean isEnd(EntryRow e) {
        return endRe.matcher(e.message).find();
    }

    /**
     * 前後の時間だけで範囲を決める（{@link Mode#WINDOW}）。
     *
     * <p>最後の起点から窓の時間内に次の起点が来たときは、新しく作らず右へ継ぎ足す
     * （その判定は {@link #run} が行う）。窓どうしが重なっても同じ行を 2 度出さないよう、
     * 取り込み済みの最後の行より後ろだけを足す。
     */
    private Request expandByWindow(Connection conn, EntryRow anchor, Request previous,
            Request req) throws SQLException {
        req.endReason = EndReason.WINDOW;
        req.startFound = false;
        req.lastAnchorTs = anchor.tsMillis;
        // 直前のリクエストと行が重ならないようにする（窓が重なっても二重に出さない）
        long floorLine = previous != null && previous.source.equals(anchor.source)
                && previous.thread.equals(anchor.thread) ? previous.lastLine() : Long.MIN_VALUE;

        // 起点より前を先に集める。窓の左端から順に入れると、前が混んでいるときに件数上限へ
        // 達して起点そのものが落ちるため、前側は上限の半分までにして残りを起点側へ空けておく
        // （はじまり・おわりで区切るときと同じ考え方）。
        List<EntryRow> before = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(BACKWARD_SQL)) {
            ps.setLong(1, anchor.fileId);
            ps.setString(2, anchor.thread);
            ps.setLong(3, anchor.tsMillis - windowMillis);
            ps.setLong(4, anchor.tsMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EntryRow e = rowInSameFile(rs, anchor);
                    if (e.lineNo >= anchor.lineNo) {
                        continue; // 起点と、それ以降は後ろ側で入れる
                    }
                    if (e.lineNo <= floorLine) {
                        break; // 直前のリクエストに入っている行
                    }
                    if (before.size() >= MAX_ROWS_PER_REQUEST / 2) {
                        req.truncated = true;
                        break;
                    }
                    before.add(e);
                }
            }
        }
        Collections.reverse(before);
        req.entries.addAll(before);

        // 起点から後ろ（起点を含む）
        fetchWindowRows(conn, req, anchor, anchor.tsMillis, anchor.tsMillis + windowMillis,
                Math.max(floorLine, anchor.lineNo - 1));
        if (req.entries.isEmpty()) {
            req.entries.add(anchor); // 取れないことは無いはずだが、起点だけは必ず残す
        }
        return req;
    }

    /** 継ぎ足し。取り込み済みの右端より後ろだけを足す。 */
    private void appendWindowRows(Connection conn, Request req, EntryRow anchor, long untilTs)
            throws SQLException {
        int before = req.entries.size();
        fetchWindowRows(conn, req, anchor, req.rangeEndTs + 1, untilTs, req.lastLine());
        if (req.entries.size() > before) {
            req.rowsAppended = true;
        }
    }

    /**
     * 同じファイル・同じスレッドの行を、時刻の範囲で取り込む。
     * {@code floorLine} 以下の行は既に別のリクエストへ入っているので飛ばす。
     */
    private void fetchWindowRows(Connection conn, Request req, EntryRow anchor, long fromTs,
            long untilTs, long floorLine) throws SQLException {
        if (untilTs < fromTs) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(FORWARD_SQL)) {
            ps.setLong(1, anchor.fileId);
            ps.setString(2, anchor.thread);
            ps.setLong(3, fromTs);
            ps.setLong(4, untilTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EntryRow e = rowInSameFile(rs, anchor);
                    if (e.lineNo <= floorLine) {
                        continue;
                    }
                    if (req.entries.size() >= MAX_ROWS_PER_REQUEST) {
                        req.truncated = true;
                        req.cutAtTail = true;
                        break;
                    }
                    req.entries.add(e);
                }
            }
        }
        if (untilTs > req.rangeEndTs) {
            req.rangeEndTs = untilTs;
        }
    }

    /** {@link LogIndex#selectEntriesOnly} の行を読む。ファイルは起点と同じなのでパスを引き継ぐ。 */
    private static EntryRow rowInSameFile(ResultSet rs, EntryRow anchor) throws SQLException {
        EntryRow e = LogIndex.rowFrom(rs);
        e.source = anchor.source;
        return e;
    }

    /**
     * 起点を含むリクエストの範囲を求める。
     *
     * <p>遡る途中で {@code previous}（同じファイル・スレッドで直前に求めたリクエスト）の範囲に
     * 入ったら、そこで止めて行を重複させない。{@code previous} がおわり側を件数上限で切っていた
     * 場合は、間にはじまりもおわりも無かったので同じリクエストの続きであり、{@code previous}
     * をそのまま返す（呼び出し側で起点を寄せる）。
     */
    private Request expand(Connection conn, EntryRow anchor, Request previous)
            throws SQLException {
        Request req = new Request(anchor.source, anchor.thread);
        // スレッド名が取れない行は、どの行と同じスレッドか判断できない。
        if (anchor.thread == null || anchor.thread.isEmpty()) {
            req.entries.add(anchor);
            req.endReason = EndReason.STANDALONE;
            return req;
        }
        if (mode == Mode.WINDOW) {
            return expandByWindow(conn, anchor, previous, req);
        }

        // ---- はじまり側: 起点から遡る（起点自身は含めない）
        List<EntryRow> before = new ArrayList<>();
        boolean startFound = isStart(anchor);
        boolean backwardTruncated = false;
        if (!startFound) {
            try (PreparedStatement ps = conn.prepareStatement(BACKWARD_SQL)) {
                ps.setLong(1, anchor.fileId);
                ps.setString(2, anchor.thread);
                ps.setLong(3, anchor.tsMillis - maxDurationMillis);
                ps.setLong(4, anchor.tsMillis);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        EntryRow e = rowInSameFile(rs, anchor);
                        if (e.tsMillis == anchor.tsMillis && e.lineNo >= anchor.lineNo) {
                            continue; // 同じ時刻で起点以降の行
                        }
                        if (previous != null && e.lineNo <= previous.lastLine()) {
                            if (previous.cutAtTail) {
                                return previous;
                            }
                            break; // 直前のリクエスト（単独の行など）の範囲。重ねない
                        }
                        // おわりを先に見る。両方に一致する行は 1 行で完結した前のリクエストで、
                        // 起点のはじまりにはなりえない。
                        if (isEnd(e)) {
                            break; // 前のリクエストのおわり。起点はその後ろにある
                        }
                        if (isStart(e)) {
                            before.add(e);
                            startFound = true;
                            break;
                        }
                        if (before.size() >= MAX_ROWS_PER_REQUEST / 2) {
                            backwardTruncated = true;
                            break;
                        }
                        before.add(e);
                    }
                }
            }
        }
        Collections.reverse(before);

        // ---- おわり側: 起点から進む（起点自身を含む）
        long rangeBeginTs = before.isEmpty() ? anchor.tsMillis : before.get(0).tsMillis;
        List<EntryRow> after = new ArrayList<>();
        EndReason reason = EndReason.NOT_FOUND;
        boolean forwardTruncated = false;
        int remaining = MAX_ROWS_PER_REQUEST - before.size();
        try (PreparedStatement ps = conn.prepareStatement(FORWARD_SQL)) {
            ps.setLong(1, anchor.fileId);
            ps.setString(2, anchor.thread);
            ps.setLong(3, anchor.tsMillis);
            ps.setLong(4, rangeBeginTs + maxDurationMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EntryRow e = rowInSameFile(rs, anchor);
                    if (e.tsMillis == anchor.tsMillis && e.lineNo < anchor.lineNo) {
                        continue; // 同じ時刻で起点より前の行
                    }
                    boolean isAnchor = e.id == anchor.id;
                    if (!isAnchor && isStart(e)) {
                        reason = EndReason.NEXT_START;
                        break;
                    }
                    // はじまり側は上限の半分までなので、起点の 1 行は必ずここに収まる
                    if (after.size() >= remaining) {
                        forwardTruncated = true;
                        break;
                    }
                    after.add(e);
                    if (isEnd(e)) {
                        reason = EndReason.END;
                        break;
                    }
                }
            }
        }

        // はじまりもおわりも起点を囲んでいなければ、どのリクエストにも属さない。
        // 件数の上限で探索を打ち切った場合は囲んでいないとは言い切れないので、単独にはしない。
        if (!startFound && reason != EndReason.END && !backwardTruncated && !forwardTruncated) {
            req.entries.add(anchor);
            req.endReason = EndReason.STANDALONE;
            return req;
        }
        req.startFound = startFound;
        req.endReason = reason;
        req.truncated = backwardTruncated || forwardTruncated;
        req.cutAtTail = forwardTruncated;
        req.entries.addAll(before);
        req.entries.addAll(after);
        return req;
    }
}
