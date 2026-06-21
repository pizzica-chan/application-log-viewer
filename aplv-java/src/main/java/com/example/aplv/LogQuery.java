package com.example.aplv;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
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
 */
public final class LogQuery {

    /** 正規表現メタ文字。grep がこれらを含まない（=プレーンなリテラル）場合のみ FTS を使う。 */
    private static final String REGEX_META = ".^$*+?()[]{}|\\";
    /** trigram は 3 文字以上でないと部分一致検索できない。 */
    private static final int FTS_MIN_LEN = 3;

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
        StringBuilder sql = new StringBuilder(LogIndex.selectBase());
        sql.append("WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (filter.levels != null && !filter.levels.isEmpty()) {
            sql.append(" AND e.level IN (");
            boolean first = true;
            for (String level : filter.levels) {
                sql.append(first ? "?" : ", ?");
                params.add(level);
                first = false;
            }
            sql.append(")");
        }
        if (filter.sinceMillis != null) {
            sql.append(" AND e.ts_millis >= ?");
            params.add(filter.sinceMillis);
        }
        if (filter.untilMillis != null) {
            sql.append(" AND e.ts_millis <= ?");
            params.add(filter.untilMillis);
        }

        // grep がプレーンなリテラルかつ FTS5 が使えるなら、まず FTS で候補 id を絞り込む。
        // （最終判定は下の正規表現検証で確定するので結果は同一。）
        if (filter.grepRe != null && filter.grepText != null
                && isPlainLiteral(filter.grepText) && LogIndex.ftsAvailable(conn)) {
            sql.append(" AND e.id IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH ?)");
            params.add(ftsMatchExpr(filter.grepText));
        }

        sql.append(" ORDER BY e.ts_millis, e.file_id, e.line_no");

        boolean needsRaw = filter.needsRaw();
        Map<String, RandomAccessFile> handles = needsRaw ? new HashMap<>() : null;

        long total = 0;
        List<EntryRow> page = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EntryRow e = LogIndex.rowFrom(rs);
                    if (!matchesIndexColumns(e, filter)) {
                        continue;
                    }
                    if (needsRaw) {
                        String raw = readRawCached(handles, e);
                        if (!matchesGrep(filter, raw)) {
                            continue;
                        }
                    }
                    if (total >= offset && page.size() < limit) {
                        page.add(e);
                    }
                    total++;
                }
            }
        } finally {
            if (handles != null) {
                for (RandomAccessFile f : handles.values()) {
                    try {
                        f.close();
                    } catch (IOException ignored) {
                        // クローズ失敗は無視
                    }
                }
            }
        }
        return new Result(total, page);
    }

    /** DB 列のみで判定（grep 前。ディスク読み不要）。 */
    private static boolean matchesIndexColumns(EntryRow e, QueryFilter f) {
        if (f.levels != null && !f.levels.contains(e.level.toUpperCase())) {
            return false;
        }
        if (f.sinceMillis != null && e.tsMillis < f.sinceMillis) {
            return false;
        }
        if (f.untilMillis != null && e.tsMillis > f.untilMillis) {
            return false;
        }
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

    private static boolean matchesGrep(QueryFilter f, String raw) {
        if (f.grepRe == null) {
            return true;
        }
        return f.grepRe.matcher(raw).find();
    }

    private static String readRawCached(Map<String, RandomAccessFile> handles, EntryRow e) {
        try {
            RandomAccessFile file = handles.get(e.source);
            if (file == null) {
                file = new RandomAccessFile(e.source, "r");
                handles.put(e.source, file);
            }
            file.seek(e.byteOffset);
            long size = e.endByteOffset > e.byteOffset ? e.endByteOffset - e.byteOffset : 0;
            if (size <= 0) {
                return "";
            }
            byte[] buf = new byte[(int) Math.min(size, Integer.MAX_VALUE)];
            file.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
