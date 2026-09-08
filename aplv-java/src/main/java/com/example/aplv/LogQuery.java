package com.example.aplv;

import java.io.RandomAccessFile;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.aplv.LogIndex.EntryRow;

/**
 * SQLite インデックスに対するフィルタリング・ページング。
 *
 * <p>レベル・日時は SQL（インデックス利用）で絞り込み、正規表現系（logger / thread /
 * message / source）は DB 列だけで先に評価し、grep 指定時のみ通過行の
 * 生ログを byte 範囲から読み出す（不要なディスク I/O を省略）。
 *
 * <p>正規表現・grep がいずれも未指定なら Java 側で判定するものが無いため、件数と 1 ページ分を
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
        if (text.length() < FTS_MIN_LEN) {
            return false;
        }
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

    /** SQL の絞り込みだけでは確定できず、行ごとの判定が要るか。 */
    private static boolean needsJavaFilter(QueryFilter f) {
        return f.loggerRe != null || f.threadRe != null || f.messageRe != null
                || f.sourceRe != null || f.grepRe != null;
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

    /** 全ヒットを走査し、行ごとに正規表現・grep を評価しながら件数とページを組み立てる。 */
    private static Result scanAndFilter(Connection conn, String where, List<Object> params,
            QueryFilter filter, long offset, long limit) throws SQLException {
        boolean needsRaw = filter.needsRaw();
        Map<String, RandomAccessFile> handles = needsRaw ? new HashMap<>() : null;

        long total = 0;
        List<EntryRow> page = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                LogIndex.selectBase() + where + ORDER_BY)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EntryRow e = LogIndex.rowFrom(rs);
                    if (!matchesRegexColumns(e, filter)) {
                        continue;
                    }
                    String raw = null;
                    if (needsRaw) {
                        raw = LogIndex.readEntryRawCached(handles, e);
                        if (!filter.grepRe.matcher(raw).find()) {
                            continue;
                        }
                    }
                    if (total >= offset && page.size() < limit) {
                        // grep 判定で読んだ生テキストは一覧 API がそのまま使うので持たせる。
                        e.raw = raw;
                        page.add(e);
                    }
                    total++;
                }
            }
        } finally {
            LogIndex.closeHandles(handles);
        }
        return new Result(total, page);
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
     * <p>レベル・日時は SQL 側で絞り込み済みのため、ここでは正規表現だけを評価する。
     */
    private static boolean matchesRegexColumns(EntryRow e, QueryFilter f) {
        if (f.sourceRe != null && !f.sourceRe.matcher(e.source).find()) {
            return false;
        }
        if (f.loggerRe != null && !f.loggerRe.matcher(e.logger).find()) {
            return false;
        }
        if (f.threadRe != null && !f.threadRe.matcher(e.thread).find()) {
            return false;
        }
        if (f.messageRe != null && !f.messageRe.matcher(e.message).find()) {
            return false;
        }
        return true;
    }
}
