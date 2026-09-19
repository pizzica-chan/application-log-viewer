package com.example.aplv;

import java.io.RandomAccessFile;
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
 * <p>「同じリクエスト」は <strong>同一ログファイル・同一スレッドで、はじまり〜おわりの範囲</strong>
 * と判定する。同期型のサーブレットでは 1 リクエストを 1 スレッドが最後まで処理し、同じスレッドの
 * リクエストは順番に処理されることを前提にしている。スレッドはプールで使い回されるため、
 * スレッドだけでは区切れず、はじまり・おわりのメッセージで区切る。
 *
 * <p>範囲の決め方（起点ごと）:
 * <ol>
 *   <li>起点から同じスレッドを遡り、最も近い「はじまり」を探す。途中で別の「おわり」に
 *       当たったら、起点はそのリクエストの後ろにあるので探索をやめる</li>
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
 * 実測（100 万行・1 ファイル・50 スレッド・108 MB、Windows 11 / JDK 8、各 5 回の中央値）:
 * <table summary="セッション追跡の実測">
 *   <tr><th>起点</th><th>--fts あり</th><th>--fts なし</th></tr>
 *   <tr><td>26 件（26 リクエスト）</td><td>11ms</td><td>5,221ms</td></tr>
 *   <tr><td>299 件（範囲を求めるのは上限の 200 リクエスト）</td><td>52ms</td><td>5,270ms</td></tr>
 * </table>
 * --fts なしでは起点の数によらずほぼ一定で、起点探しの全件走査（一覧の全文検索と同じ処理）が
 * 大半を占める。ありふれた文字列（{@code sessionId=}、起点 133,347 件）を指定しても、
 * 起点を溜めずに流すので --fts ありで 1,008ms、ヒープ 256MB で完走した。
 * 範囲探索のクエリで files と JOIN していたときは、並べ替えのために時間窓の全行を集めていたため、
 * 上の 2 行が --fts ありでも 1,326ms / 11,743ms かかっていた（{@link LogIndex#selectEntriesOnly}）。
 */
public final class SessionTrace {

    /** 最大所要時間の既定値（分）。 */
    public static final int DEFAULT_MAX_MINUTES = 10;
    /** 最大所要時間の上限（分）。 */
    public static final int MAX_MAX_MINUTES = 24 * 60;
    /** 返すリクエスト数の上限。超えた分は数えるだけで範囲を求めない。 */
    static final int MAX_REQUESTS = 200;
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

    private final String sessionId;
    private final Pattern startRe;
    private final Pattern endRe;
    private final long maxDurationMillis;

    /**
     * @param sessionId 識別子。正規表現ではなく文字列としてそのまま照合する（大文字小文字を区別）
     * @param startRe   はじまりのメッセージ（1 行目）に一致する正規表現
     * @param endRe     おわりのメッセージ（1 行目）に一致する正規表現
     * @param maxMinutes 1 リクエストの最大所要時間（分）
     */
    public SessionTrace(String sessionId, Pattern startRe, Pattern endRe, int maxMinutes) {
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
        this.startRe = startRe;
        this.endRe = endRe;
        this.maxDurationMillis = maxMinutes * 60_000L;
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
        STANDALONE
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
        /** {@link #MAX_REQUESTS} を超えて範囲を求めなかった起点があるか。 */
        public boolean truncated;
        /** 先頭エントリの時刻順。 */
        public final List<Request> requests = new ArrayList<>();
    }

    public Result run(Connection conn) throws SQLException {
        Result result = new Result();
        // (file_id + "\0" + thread) → そのスレッドで求めたリクエスト。同じリクエストに
        // 起点が複数あるとき、範囲を求め直さずに既存のリクエストへ寄せるために使う。
        Map<String, List<Request>> byContext = new HashMap<>();
        Map<String, RandomAccessFile> handles = new HashMap<>();
        // 起点は溜めずに 1 件ずつ処理する。ありふれた文字列を指定すると全行が起点になりうるため。
        // 起点のカーソルを開いたまま範囲探索のクエリを流す（SQLite は同じ接続で併用できる）。
        try (PreparedStatement ps = prepareAnchorQuery(conn);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                EntryRow anchor = LogIndex.rowFrom(rs);
                if (!LogIndex.readEntryRawCached(handles, anchor).contains(sessionId)) {
                    continue;
                }
                result.anchorTotal++;
                String key = anchor.fileId + "\0" + anchor.thread;
                Request existing = findContaining(byContext.get(key), anchor.lineNo);
                if (existing != null) {
                    existing.anchorIds.add(anchor.id);
                    continue;
                }
                if (result.requests.size() >= MAX_REQUESTS) {
                    result.truncated = true;
                    continue;
                }
                Request req = expand(conn, anchor);
                req.anchorIds.add(anchor.id);
                result.requests.add(req);
                byContext.computeIfAbsent(key, k -> new ArrayList<>()).add(req);
            }
        } finally {
            LogIndex.closeHandles(handles);
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
     * 識別子を含みうるエントリを時刻順に返すクエリ。含むかどうかの最終判定は呼び出し側で行う。
     *
     * <p>照合はスタックトレースを含むエントリ全体に対して行う（一覧の全文検索と同じ範囲）。
     * FTS5 があれば trigram で候補を絞ってから確かめる。trigram は大文字小文字を区別しないので
     * 候補は取りこぼさず、最終判定の {@link String#contains} で区別する。
     * 識別子を正規表現として扱わないのは、jvmRoute 付きの JSESSIONID（{@code ABC.node1}）の
     * {@code .} などをメタ文字にしないため。
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

    /** {@link LogIndex#selectEntriesOnly} の行を読む。ファイルは起点と同じなのでパスを引き継ぐ。 */
    private static EntryRow rowInSameFile(ResultSet rs, EntryRow anchor) throws SQLException {
        EntryRow e = LogIndex.rowFrom(rs);
        e.source = anchor.source;
        return e;
    }

    /** 起点を含むリクエストの範囲を求める。 */
    private Request expand(Connection conn, EntryRow anchor) throws SQLException {
        Request req = new Request(anchor.source, anchor.thread);
        // スレッド名が取れない行は、どの行と同じスレッドか判断できない。
        if (anchor.thread == null || anchor.thread.isEmpty()) {
            req.entries.add(anchor);
            req.endReason = EndReason.STANDALONE;
            return req;
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
        req.entries.addAll(before);
        req.entries.addAll(after);
        return req;
    }
}
