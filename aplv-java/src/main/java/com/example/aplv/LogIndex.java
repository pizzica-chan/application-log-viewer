package com.example.aplv;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SQLite インデックスの構築・参照。
 *
 * <p>エントリのメタデータ（日時・レベル・logger 等）と元ファイル上の byte 範囲のみを DB に保存し、
 * スタックトレース本文は DB に載せず {@link #readEntryRaw} でオンデマンド読み出しする。
 * これにより数 GB のログでもメモリに全文を抱えず（OOM 回避）、インデックスはディスクに永続化される。
 *
 * <p>インデックス構築は、複数ファイルを並列にパースしつつ、SQLite への書き込みは
 * 単一ライタースレッドに集約する producer/consumer 方式で高速化している。
 */
public final class LogIndex {

    /** INSERT バッチサイズ。 */
    private static final int BATCH_SIZE = 5000;
    /** 進捗通知の間隔（エントリ数）。 */
    private static final long PROGRESS_INTERVAL = 50_000L;
    /** トランザクションを区切るコミット間隔（巨大トランザクションによるメモリ肥大を防ぐ）。 */
    private static final long COMMIT_INTERVAL = 200_000L;

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc が見つかりません", e);
        }
    }

    private LogIndex() {
    }

    /** 進捗コールバック。 */
    public interface ProgressCallback {
        void onProgress(long count);
    }

    /** DB から取得した 1 エントリ（一覧・詳細 API 用）。 */
    public static final class EntryRow {
        public long id;
        public long fileId;
        public long lineNo;
        public long byteOffset;
        public long endByteOffset;
        public long tsMillis;
        public String logger;
        public String level;
        public String thread;
        public String message;
        public String source;
    }

    /** インデックス保存ディレクトリ {@code {log_root}/.aplv}。 */
    public static Path indexDir(Path logRoot) {
        return logRoot.resolve(".aplv");
    }

    /** SQLite DB ファイルパス。 */
    public static Path indexDbPath(Path logRoot) {
        return indexDir(logRoot).resolve("index.db");
    }

    /** DB を開き、スキーマがなければ作成する。 */
    public static Connection openOrCreate(Path logRoot) throws SQLException, IOException {
        Files.createDirectories(indexDir(logRoot));
        String url = "jdbc:sqlite:" + indexDbPath(logRoot).toString();
        Connection conn = DriverManager.getConnection(url);
        initSchema(conn);
        return conn;
    }

    /** メモリ上の DB を開く（ディレクトリ未選択時）。 */
    public static Connection openMemory() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try {
            initSchema(conn);
        } catch (IOException e) {
            throw new SQLException(e);
        }
        return conn;
    }

    private static void initSchema(Connection conn) throws SQLException, IOException {
        try (Statement st = conn.createStatement()) {
            // 構築・参照の双方で十分な速度が出るチューニング。
            st.execute("PRAGMA journal_mode = MEMORY");
            st.execute("PRAGMA synchronous = OFF");
            st.execute("PRAGMA temp_store = MEMORY");
            st.execute("PRAGMA cache_size = -65536"); // 約 64 MiB
            st.execute("CREATE TABLE IF NOT EXISTS meta ("
                    + "key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS files ("
                    + "id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, "
                    + "mtime_secs INTEGER NOT NULL, size INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS entries ("
                    + "id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, line_no INTEGER NOT NULL, "
                    + "byte_offset INTEGER NOT NULL, end_byte_offset INTEGER, ts_millis INTEGER NOT NULL, "
                    + "logger TEXT NOT NULL, level TEXT NOT NULL, thread TEXT NOT NULL, message TEXT NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_ts "
                    + "ON entries(ts_millis, file_id, line_no)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_level ON entries(level)");
        }
    }

    /** 対象ファイル集合のフィンガープリント（パス・mtime・サイズ）。 */
    private static String fileFingerprint(List<Path> paths) throws IOException {
        List<String> parts = new ArrayList<>(paths.size());
        for (Path path : paths) {
            long mtime = Files.getLastModifiedTime(path).toMillis() / 1000L;
            long size = Files.size(path);
            parts.add(PathUtil.normalizePath(path) + ":" + mtime + ":" + size);
        }
        Collections.sort(parts);
        return String.join("\n", parts);
    }

    /** 保存済みフィンガープリントと異なれば true（再インデックスが必要）。 */
    public static boolean needsRebuild(Connection conn, List<Path> paths) throws SQLException, IOException {
        if (paths.isEmpty()) {
            return false;
        }
        String fp = fileFingerprint(paths);
        String stored = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM meta WHERE key = 'fingerprint'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stored = rs.getString(1);
                }
            }
        }
        return !fp.equals(stored);
    }

    /** entries / files テーブルを空にする。 */
    public static void clearIndex(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM entries");
            st.execute("DELETE FROM files");
        }
    }

    public static long entryCount(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM entries")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** (最古, 最新) のタイムスタンプを ISO 文字列で返す（空なら null）。 */
    public static String[] timestampBounds(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MIN(ts_millis), MAX(ts_millis) FROM entries")) {
            if (rs.next()) {
                long min = rs.getLong(1);
                boolean hasMin = !rs.wasNull();
                long max = rs.getLong(2);
                boolean hasMax = !rs.wasNull();
                return new String[] {
                        hasMin ? TimeUtil.formatIso(min) : null,
                        hasMax ? TimeUtil.formatIso(max) : null,
                };
            }
        }
        return new String[] {null, null};
    }

    // ---- インデックス構築 -------------------------------------------------

    /** パース済みエントリ 1 件分（DB 書き込み前）。 */
    static final class Row {
        long fileId;
        long lineNo;
        long byteOffset;
        long endByteOffset;
        long tsMillis;
        String logger;
        String level;
        String thread;
        String message;
    }

    private static final List<Row> POISON = Collections.emptyList();

    /**
     * ログファイル群を走査し SQLite にインデックスを構築する。
     *
     * <p>複数ファイルを並列パース → 単一スレッドで一括 INSERT。同一ファイル内で
     * 連続する非ヘッダ行（スタックトレース等）は、次ヘッダ行の開始 offset までを
     * {@code end_byte_offset} として記録する。
     *
     * @return 取り込んだエントリ総数
     */
    public static long buildIndex(Connection conn, List<Path> paths, ProgressCallback progress)
            throws SQLException, IOException {
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            return buildIndexTx(conn, paths, progress);
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    private static long buildIndexTx(Connection conn, List<Path> paths, ProgressCallback progress)
            throws SQLException, IOException {
        clearIndex(conn);
        String fp = fileFingerprint(paths);

        // files テーブルを先に登録（FK 整合のため）。
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO files (id, path, mtime_secs, size) VALUES (?, ?, ?, ?)")) {
            for (int i = 0; i < paths.size(); i++) {
                Path path = paths.get(i);
                ps.setLong(1, i + 1L);
                ps.setString(2, PathUtil.normalizePath(path));
                ps.setLong(3, Files.getLastModifiedTime(path).toMillis() / 1000L);
                ps.setLong(4, Files.size(path));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();

        int threads = Math.max(1, Math.min(paths.size(), Runtime.getRuntime().availableProcessors()));
        BlockingQueue<List<Row>> queue = new ArrayBlockingQueue<>(Math.max(8, threads * 4));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger remaining = new AtomicInteger(paths.size());

        for (int i = 0; i < paths.size(); i++) {
            final long fileId = i + 1L;
            final Path path = paths.get(i);
            pool.submit(() -> {
                try {
                    parseFileInto(fileId, path, queue);
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        putUninterruptibly(queue, POISON);
                    }
                }
            });
        }
        if (paths.isEmpty()) {
            putUninterruptibly(queue, POISON);
        }

        long total = 0;
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO entries (file_id, line_no, byte_offset, end_byte_offset, ts_millis, "
                        + "logger, level, thread, message) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            while (true) {
                List<Row> batch;
                try {
                    batch = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (batch == POISON) {
                    break;
                }
                for (Row r : batch) {
                    ins.setLong(1, r.fileId);
                    ins.setLong(2, r.lineNo);
                    ins.setLong(3, r.byteOffset);
                    ins.setLong(4, r.endByteOffset);
                    ins.setLong(5, r.tsMillis);
                    ins.setString(6, r.logger);
                    ins.setString(7, r.level);
                    ins.setString(8, r.thread);
                    ins.setString(9, r.message);
                    ins.addBatch();
                }
                ins.executeBatch();
                long before = total;
                total += batch.size();
                if (before / COMMIT_INTERVAL != total / COMMIT_INTERVAL) {
                    conn.commit();
                }
                if (progress != null && before / PROGRESS_INTERVAL != total / PROGRESS_INTERVAL) {
                    progress.onProgress(total);
                }
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Throwable t = error.get();
        if (t != null) {
            conn.rollback();
            throw new IOException("インデックス構築に失敗しました: " + t.getMessage(), t);
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('fingerprint', ?)")) {
            ps.setString(1, fp);
            ps.executeUpdate();
        }
        conn.commit();

        if (progress != null) {
            progress.onProgress(total);
        }
        return total;
    }

    private static void parseFileInto(long fileId, Path path, BlockingQueue<List<Row>> queue)
            throws IOException, InterruptedException {
        try (InputStream raw = Files.newInputStream(path);
             InputStream in = new BufferedInputStream(raw, 1 << 16);
             ByteLineReader reader = new ByteLineReader(in)) {
            long lineNo = 0;
            // pending: end_byte_offset 未確定のエントリ
            Row pending = null;
            List<Row> batch = new ArrayList<>(BATCH_SIZE);

            while (reader.next()) {
                lineNo++;
                if (reader.isBlankLine()) {
                    continue;
                }
                if (!LogParser.looksLikeHeader(reader.lineBuf, reader.lineLen)) {
                    continue;
                }
                LogParser.ParsedLine parsed = LogParser.parse(reader.lineBuf, reader.lineLen);
                if (parsed == null) {
                    continue;
                }
                long offset = reader.lineStart;
                if (pending != null) {
                    pending.endByteOffset = offset;
                    batch.add(pending);
                    if (batch.size() >= BATCH_SIZE) {
                        queue.put(batch);
                        batch = new ArrayList<>(BATCH_SIZE);
                    }
                }
                pending = new Row();
                pending.fileId = fileId;
                pending.lineNo = lineNo;
                pending.byteOffset = offset;
                pending.tsMillis = parsed.tsMillis;
                pending.logger = parsed.logger;
                pending.level = parsed.level;
                pending.thread = parsed.thread;
                pending.message = parsed.message;
            }

            if (pending != null) {
                pending.endByteOffset = reader.position();
                batch.add(pending);
            }
            if (!batch.isEmpty()) {
                queue.put(batch);
            }
        }
    }

    private static <T> void putUninterruptibly(BlockingQueue<T> queue, T item) {
        boolean interrupted = false;
        while (true) {
            try {
                queue.put(item);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 参照 -------------------------------------------------------------

    /** エントリの生テキスト（スタックトレース含む）を byte 範囲から読み出す。 */
    public static String readEntryRaw(Path path, long start, long end) throws IOException {
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(path.toFile(), "r")) {
            file.seek(start);
            if (end <= start) {
                String line = file.readLine();
                return line == null ? "" : stripEol(new String(
                        line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            }
            int size = (int) Math.min(end - start, Integer.MAX_VALUE);
            byte[] buf = new byte[size];
            file.readFully(buf);
            return stripEol(new String(buf, StandardCharsets.UTF_8));
        }
    }

    private static String stripEol(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static final String SELECT_BASE =
            "SELECT e.id, e.file_id, e.line_no, e.byte_offset, e.end_byte_offset, "
                    + "e.ts_millis, e.logger, e.level, e.thread, e.message, f.path "
                    + "FROM entries e JOIN files f ON e.file_id = f.id ";

    static EntryRow rowFrom(ResultSet rs) throws SQLException {
        EntryRow e = new EntryRow();
        e.id = rs.getLong(1);
        e.fileId = rs.getLong(2);
        e.lineNo = rs.getLong(3);
        e.byteOffset = rs.getLong(4);
        e.endByteOffset = rs.getLong(5);
        e.tsMillis = rs.getLong(6);
        e.logger = rs.getString(7);
        e.level = rs.getString(8);
        e.thread = rs.getString(9);
        e.message = rs.getString(10);
        e.source = rs.getString(11);
        return e;
    }

    /** ソースパス・行番号で 1 件検索。 */
    public static EntryRow findEntry(Connection conn, String source, long lineNo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                SELECT_BASE + "WHERE f.path = ? AND e.line_no = ? LIMIT 1")) {
            ps.setString(1, source);
            ps.setLong(2, lineNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowFrom(rs) : null;
            }
        }
    }

    static String selectBase() {
        return SELECT_BASE;
    }
}
