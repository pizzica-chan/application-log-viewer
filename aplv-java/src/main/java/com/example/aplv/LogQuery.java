package com.example.aplv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.example.aplv.LogIndex.EntryRow;

/**
 * SQLite インデックスに対するフィルタリング・ページング。
 *
 * <p>レベル・日時は SQL（インデックス利用）で絞り込む。source（ファイルのパス）の正規表現も
 * files テーブルで判定して SQL の条件に置き換える。logger / thread / message の正規表現は
 * DB 列だけで先に評価し、grep 指定時のみ通過行の生ログを byte 範囲から読み出す
 * （不要なディスク I/O を省略）。
 *
 * <p>正規表現・grep がいずれも未指定なら Java 側で判定するものがないため、件数と 1 ページ分を
 * まるごと SQL（{@code COUNT(*)} と {@code LIMIT/OFFSET}）に任せ、全ヒットを {@link EntryRow}
 * に起こして数え上げる処理を省く。日時のみで絞り込む場合は {@code idx_entries_ts} を
 * 並び順どおりに辿れるため、ページ送りは表示件数に比例した時間で返る。
 *
 * <p>レベル絞り込みも、単一レベルなら {@code idx_entries_level_ts} を並び順どおりに辿れる。
 * 複数レベルを {@code IN} で並べた場合だけは、レベルごとに索引の別の範囲を読むことになり
 * 全体の時刻順を保てないため並べ直しが入る（ヒット件数に比例。実測では 150 万件・
 * 2 レベルで 9ms、3 レベルで 34ms）。どう並べ直すかは統計に基づく SQLite の判断で、
 * {@code idx_entries_level_ts} を引いてソートすることも、{@code idx_entries_ts} を
 * 時刻順に走査してレベルを都度判定することもある。
 */
public final class LogQuery {

    /** 正規表現メタ文字。grep がこれらを含まない（=プレーンなリテラル）場合のみ FTS を使う。 */
    private static final String REGEX_META = ".^$*+?()[]{}|\\";
    /** trigram は 3 文字以上でないと部分一致検索できない。 */
    private static final int FTS_MIN_LEN = 3;

    /** 結果の並び順。{@code idx_entries_ts} と同じ並びなので索引を順に辿れる。 */
    private static final String ORDER_BY = " ORDER BY e.ts_millis, e.file_id, e.line_no";

    private LogQuery() {
    }

    /** grep 文字列が FTS で扱えるプレーンなリテラルか。 */
    private static boolean isPlainLiteral(String text) {
        return text.length() >= FTS_MIN_LEN && hasNoRegexMeta(text);
    }

    /**
     * 正規表現のメタ文字を含まないか。含まなければ、その文字列は「部分一致」そのものなので、
     * 正規表現エンジンを通さずバイト列のまま探せる（長さの制限は FTS 側の都合なのでここでは見ない）。
     */
    static boolean hasNoRegexMeta(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (REGEX_META.indexOf(text.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** リテラルを FTS5 のフレーズ（部分一致）クエリ文字列に変換する。 */
    private static String ftsMatchExpr(String literal) {
        return "\"" + literal.replace("\"", "\"\"") + "\"";
    }

    /** クエリ結果（総ヒット数 + 現ページ）。 */
    public static final class Result {
        public final long total;
        public final List<EntryRow> page;

        Result(long total, List<EntryRow> page) {
            this.total = total;
            this.page = page;
        }
    }

    public static Result queryLogs(Connection conn, QueryFilter filter, long offset, long limit)
            throws SQLException {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (filter.levels != null && !filter.levels.isEmpty()) {
            where.append(" AND e.level IN (");
            boolean first = true;
            for (String level : filter.levels) {
                where.append(first ? "?" : ", ?");
                params.add(level);
                first = false;
            }
            where.append(")");
        }
        if (filter.sinceMillis != null) {
            where.append(" AND e.ts_millis >= ?");
            params.add(filter.sinceMillis);
        }
        if (filter.untilMillis != null) {
            where.append(" AND e.ts_millis <= ?");
            params.add(filter.untilMillis);
        }
        if (filter.sourceRe != null) {
            where.append(sourceCondition(conn, filter.sourceRe));
        }

        // grep がプレーンなリテラルかつ FTS5 が使えるなら、まず FTS で候補 id を絞り込む。
        // （最終判定は下の正規表現検証で確定するので結果は同一。）
        if (filter.grepRe != null && filter.grepText != null
                && isPlainLiteral(filter.grepText) && LogIndex.ftsAvailable(conn)) {
            where.append(" AND e.id IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH ?)");
            params.add(ftsMatchExpr(filter.grepText));
        }

        String whereSql = where.toString();
        if (!needsJavaFilter(filter)) {
            return new Result(countMatches(conn, whereSql, params),
                    fetchPage(conn, whereSql, params, offset, limit));
        }
        return scanAndFilter(conn, whereSql, params, filter, offset, limit);
    }

    /**
     * source（ログファイルのパス）の正規表現を、files テーブルだけで判定して
     * {@code e.file_id IN (...)} の条件（先頭に {@code " AND "} 付き。絞り込まないなら空文字）にする。
     *
     * <p>パスはファイルごとに 1 つなので、エントリごとに照合しなくても結果は同じになる。
     * SQL 側で確定するので、source だけの絞り込みは {@code COUNT(*)} と {@code LIMIT} で返せる。
     * id は DB から取り出した整数なので、バインド変数の上限を気にせず SQL に直接埋め込む。
     *
     * <p>列に単項の {@code +} を付け、この条件を結合順の選択に使わせない。付けないと
     * SQLite が files を外側に回し、並べ直し（{@code USE TEMP B-TREE FOR ... ORDER BY}）の入る
     * 計画を選ぶことがある（3 ファイル・71.8 万件の索引で確認。1 ページ目の時間は 17ms で、
     * 付けた場合と差はなかった）。速さのためではなく、実行計画を source を指定しない場合と
     * 同じ形に保つために付けている。付けておけば、辿った行をこの条件でふるうだけになる。
     *
     * <p>実測（100 万行・106 MB を 30 ファイルに分けた 71.8 万件、Windows 11 / JDK 11、
     * 5 回の中央値を 3 ラウンド取った中央値）: 全ファイルに一致 1,509ms → 7ms、
     * 5 ファイルに一致 2,025ms → 30ms、それに logger の正規表現を併用 2,024ms → 138ms。
     */
    static String sourceCondition(Connection conn, Pattern sourceRe) throws SQLException {
        List<Long> ids = new ArrayList<>();
        int fileCount = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, path FROM files");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                fileCount++;
                if (sourceRe.matcher(rs.getString(2)).find()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        if (ids.size() == fileCount) {
            // すべてのファイルが一致するなら絞り込むものはない
            return "";
        }
        if (ids.isEmpty()) {
            return " AND 0";
        }
        StringBuilder sql = new StringBuilder(" AND +e.file_id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(ids.get(i).longValue());
        }
        return sql.append(")").toString();
    }

    /**
     * SQL の絞り込みだけでは確定できず、行ごとの判定が要るか。
     * source は {@link #sourceCondition} で SQL 側に押し下げ済みなので含めない。
     */
    private static boolean needsJavaFilter(QueryFilter f) {
        return f.loggerRe != null || f.threadRe != null || f.messageRe != null
                || f.grepRe != null;
    }

    /**
     * 条件に一致する件数。
     *
     * <p>WHERE 句は {@code e.}（entries）の列しか参照しないため files との JOIN を省ける。
     * SQL 側へ押し下げる条件を増やすときは、この前提が崩れていないか確認すること。
     */
    private static long countMatches(Connection conn, String where, List<Object> params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM entries e " + where)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** 1 ページ分だけを SQL で取り出す。 */
    private static List<EntryRow> fetchPage(Connection conn, String where, List<Object> params,
            long offset, long limit) throws SQLException {
        List<EntryRow> page = new ArrayList<>();
        if (limit <= 0) {
            return page;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                LogIndex.selectBase() + where + ORDER_BY + " LIMIT ? OFFSET ?")) {
            int i = bind(ps, params);
            ps.setLong(i++, limit);
            ps.setLong(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    page.add(LogIndex.rowFrom(rs));
                }
            }
        }
        return page;
    }

    /**
     * 全ヒットを走査し、行ごとに正規表現・grep を評価しながら件数とページを組み立てる。
     *
     * <p>速度のために 3 つの手を使う。いずれも結果は変えない。
     * <ul>
     *   <li>元ファイルは {@link LogIndex.SequentialRawReader} で前方向にまとめ読みする
     *       （エントリごとの seek を避ける）</li>
     *   <li>grep がメタ文字を含まないリテラルなら、UTF-8 デコードせずバイト列のまま探す
     *       （既存の grep は ASCII だけ大文字小文字を無視するので、同じ畳み方で比べる）</li>
     *   <li>logger などの文字列は、ページに載る行でだけ作る。列の絞り込みがあるときも、
     *       照合に要る列だけを {@code getString} する（{@code SELECT} する列は変えていない）</li>
     * </ul>
     * 実測（100 万行・108 MB・{@code --fts} なし、Windows 11 / JDK 8、5 回の中央値）:
     * リテラル 5,793ms → 941ms、正規表現 5,678ms → 1,005ms、
     * ありふれたリテラル（13 万件ヒット）5,709ms → 663ms。
     *
     * <p>列の絞り込みで照合に要る列だけを文字列にしたときの実測（100 万行・106 MB を
     * 30 ファイルに分けた 71.8 万件、Windows 11 / JDK 11、5 回の中央値を 3 ラウンド取った中央値）:
     * logger 1,537ms → 588ms、message 1,554ms → 657ms、thread 1,508ms → 596ms、
     * logger + grep 1,591ms → 705ms。以前は 5 つの文字列列をすべて作ってから照合していた。
     */
    private static Result scanAndFilter(Connection conn, String where, List<Object> params,
            QueryFilter filter, long offset, long limit) throws SQLException {
        boolean needsRaw = filter.needsRaw();
        boolean needsColumns = needsRegexColumns(filter);
        // メタ文字がなければ「部分一致」なので、正規表現を通さずバイト列で探せる
        byte[] literal = needsRaw && filter.grepText != null && hasNoRegexMeta(filter.grepText)
                ? LogIndex.toLowerAscii(filter.grepText.getBytes(StandardCharsets.UTF_8))
                : null;
        Map<String, LogIndex.SequentialRawReader> readers = needsRaw ? new HashMap<>() : null;
        Map<Long, String> paths = needsRaw ? new HashMap<>() : null;
        byte[] buffer = new byte[8192];

        long total = 0;
        List<EntryRow> page = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                LogIndex.selectBase() + where + ORDER_BY)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (needsColumns && !matchesRegexColumns(rs, filter)) {
                        continue;
                    }
                    String raw = null;
                    if (needsRaw) {
                        long start = rs.getLong(4);
                        long end = rs.getLong(5);
                        int len = (int) Math.max(0, Math.min(end - start, Integer.MAX_VALUE));
                        if (len > buffer.length) {
                            buffer = new byte[len];
                        }
                        int read = len > 0 ? readRaw(rs, readers, paths, start, len, buffer) : 0;
                        if (literal != null) {
                            if (!LogIndex.containsBytesIgnoreAsciiCase(buffer, read, literal)) {
                                continue;
                            }
                        } else {
                            raw = new String(buffer, 0, read, StandardCharsets.UTF_8);
                            if (!filter.grepRe.matcher(raw).find()) {
                                continue;
                            }
                        }
                        if (total >= offset && page.size() < limit && raw == null) {
                            // ページに載る行だけデコードする（一覧 API がそのまま使う）
                            raw = new String(buffer, 0, read, StandardCharsets.UTF_8);
                        }
                    }
                    if (total >= offset && page.size() < limit) {
                        EntryRow e = LogIndex.rowFrom(rs);
                        e.raw = raw;
                        page.add(e);
                    }
                    total++;
                }
            }
        } catch (IOException ex) {
            throw new SQLException("ログファイルを読み出せません: " + ex.getMessage(), ex);
        } finally {
            LogIndex.closeReaders(readers);
        }
        return new Result(total, page);
    }

    /** いまの行の byte 範囲を読み出す。ファイルのパスは file_id ごとに 1 回だけ取り出す。 */
    private static int readRaw(ResultSet rs, Map<String, LogIndex.SequentialRawReader> readers,
            Map<Long, String> paths, long start, int len, byte[] into)
            throws SQLException, IOException {
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
        return reader.read(start, len, into);
    }

    /** 列（logger / thread / message）の絞り込みがあるか。source は SQL 側で絞り込み済み。 */
    private static boolean needsRegexColumns(QueryFilter f) {
        return f.loggerRe != null || f.threadRe != null || f.messageRe != null;
    }

    private static int bind(PreparedStatement ps, List<Object> params) throws SQLException {
        int i = 1;
        for (Object param : params) {
            ps.setObject(i++, param);
        }
        return i;
    }

    /**
     * DB 列のみで判定（grep 前。ディスク読み不要）。
     *
     * <p>レベル・日時・source は SQL 側で絞り込み済みのため、ここでは正規表現だけを評価する。
     * 列の文字列は、絞り込みに使う列だけを取り出す（一致しない行のために使わない文字列を作らない）。
     * 列番号は {@link LogIndex#rowFrom} と同じ。
     */
    private static boolean matchesRegexColumns(ResultSet rs, QueryFilter f) throws SQLException {
        if (f.loggerRe != null && !f.loggerRe.matcher(rs.getString(7)).find()) {
            return false;
        }
        if (f.threadRe != null && !f.threadRe.matcher(rs.getString(9)).find()) {
            return false;
        }
        if (f.messageRe != null && !f.messageRe.matcher(rs.getString(10)).find()) {
            return false;
        }
        return true;
    }
}
