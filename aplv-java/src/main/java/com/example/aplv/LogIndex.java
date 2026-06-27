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
import java.util.concurrent.atomic.AtomicLong;
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
    /** skipped 行サンプルの上限（UI 表示用）。 */
    private static final int MAX_SKIPPED_SAMPLES = 5;
    private static final int PREVIEW_MAX_LEN = 120;
    private static final String META_SKIPPED_LINES = "skipped_lines";
    private static final String META_SKIPPED_SAMPLES = "skipped_samples";

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

    /** インデックス保存ディレクトリ {@code {repo}/tmp/aplv}。 */
    public static Path indexDir(Path logRoot) {
        return IndexStore.tmpIndexDir();
    }

    /** SQLite DB ファイルパス。 */
    public static Path indexDbPath(Path logRoot) {
        return IndexStore.indexDbPath(logRoot);
    }

    /** DB を開き、スキーマがなければ作成する。 */
    public static Connection openOrCreate(Path logRoot) throws SQLException, IOException {
        IndexStore.ensureTmpDirFor(logRoot);
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

    /**
     * 全文検索用 FTS5 テーブル定義。
     *
     * <p>contentless（content=''）で本文の複製を持たず転置インデックスのみ保持し（省容量）、
     * trigram トークナイザにより 3 文字以上の部分一致検索を高速化する。grep の正規表現リテラルと
     * 同じ「部分文字列・大文字小文字無視」の挙動を再現でき、rowid を entries.id に一致させて
     * 候補 id の絞り込みに使う。
     */
    private static final String FTS_SCHEMA =
            "CREATE VIRTUAL TABLE entries_fts USING fts5(body, content='', tokenize='trigram')";

    /** FTS5 全文検索テーブルが存在するか。 */
    public static boolean ftsAvailable(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='entries_fts'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** FTS5 テーブルを作り直す。FTS5/trigram 非対応なら false（フォールバック）。 */
    private static boolean recreateFts(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS entries_fts");
            st.execute(FTS_SCHEMA);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** FTS5 テーブルを削除する（--fts 無効時に既存索引を残さないため）。 */
    private static void dropFts(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS entries_fts");
        } catch (SQLException e) {
            // 削除失敗は無視（テーブルが無い場合など）
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
    public static boolean needsRebuild(Connection conn, List<Path> paths, boolean enableFts)
            throws SQLException, IOException {
        if (paths.isEmpty()) {
            return false;
        }
        String fp = indexFingerprint(paths, enableFts);
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

    /** ファイル集合 + FTS 設定のフィンガープリント（meta 保存用）。 */
    private static String indexFingerprint(List<Path> paths, boolean enableFts) throws IOException {
        return fileFingerprint(paths) + "\nfts:" + (enableFts ? "1" : "0");
    }

    /** entries / files テーブルを空にする。 */
    public static void clearIndex(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM entries");
            st.execute("DELETE FROM files");
            st.execute("DELETE FROM meta WHERE key IN ('"
                    + META_SKIPPED_LINES + "', '" + META_SKIPPED_SAMPLES + "')");
        }
    }

    /**
     * 構築失敗・中断時に部分索引を破棄し、次回 {@link #needsRebuild} が true になるようにする。
     * 途中 commit 済みの行も含めてクリアする。
     */
    private static void abortIncompleteBuild(Connection conn) throws SQLException {
        clearIndex(conn);
        dropFts(conn);
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM meta WHERE key = 'fingerprint'");
        }
        conn.commit();
    }

    public static long entryCount(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM entries")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** 解析できなかった孤立行の件数（meta 未保存時は 0）。 */
    public static int getSkippedLineCount(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, META_SKIPPED_LINES);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Integer.parseInt(rs.getString(1));
                }
            }
        }
        return 0;
    }

    /** 解析できなかった孤立行のサンプル（最大 {@link #MAX_SKIPPED_SAMPLES} 件）。 */
    public static List<SkippedLine> getSkippedLineSamples(Connection conn) throws SQLException {
        String json = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, META_SKIPPED_SAMPLES);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    json = rs.getString(1);
                }
            }
        }
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        return deserializeSkippedSamples(json);
    }

    /** files テーブルから file_id に対応するパスを返す。 */
    public static String filePath(Connection conn, long fileId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT path FROM files WHERE id = ?")) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
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
        /** ヘッダ行＋継続行（スタックトレース等）の本文。FTS5 索引用（不要時 null）。 */
        StringBuilder bodyBuf;
    }

    private static final List<Row> POISON = Collections.emptyList();

    /**
     * ログファイル群を走査し SQLite にインデックスを構築する。
     *
     * <p>複数ファイルを並列パース → 単一スレッドで一括 INSERT。同一ファイル内で
     * 連続する非ヘッダ行（スタックトレース等）は、次ヘッダ行の開始 offset までを
     * {@code end_byte_offset} として記録する。
     */
    public static final class BuildResult {
        public final long entryCount;
        public final int skippedLines;
        public final List<SkippedLine> skippedSamples;

        BuildResult(long entryCount, int skippedLines, List<SkippedLine> skippedSamples) {
            this.entryCount = entryCount;
            this.skippedLines = skippedLines;
            this.skippedSamples = skippedSamples;
        }
    }

    /**
     * @return 取り込んだエントリ総数と skipped 行の集計
     */
    public static BuildResult buildIndex(Connection conn, List<Path> paths, ProgressCallback progress,
            boolean enableFts) throws SQLException, IOException {
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            return buildIndexTx(conn, paths, progress, enableFts);
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    private static BuildResult buildIndexTx(Connection conn, List<Path> paths, ProgressCallback progress,
            boolean enableFts) throws SQLException, IOException {
        clearIndex(conn);
        boolean hasFts;
        if (enableFts) {
            hasFts = recreateFts(conn);
        } else {
            dropFts(conn);
            hasFts = false;
        }
        // 構築中は fingerprint を消し、並行 load がキャッシュ再利用しないようにする。
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM meta WHERE key = 'fingerprint'");
        }
        conn.commit();
        String fp = indexFingerprint(paths, enableFts);

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
        AtomicLong skippedCounter = new AtomicLong();
        List<SkippedLine> skippedSamples = Collections.synchronizedList(new ArrayList<SkippedLine>());

        for (int i = 0; i < paths.size(); i++) {
            final long fileId = i + 1L;
            final Path path = paths.get(i);
            pool.submit(() -> {
                try {
                    parseFileInto(fileId, path, queue, hasFts, skippedCounter, skippedSamples);
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
        long nextId = 1;
        boolean buildComplete = false;
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO entries (id, file_id, line_no, byte_offset, end_byte_offset, ts_millis, "
                        + "logger, level, thread, message) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement ftsIns = hasFts ? conn.prepareStatement(
                "INSERT INTO entries_fts (rowid, body) VALUES (?, ?)") : null) {
            while (true) {
                List<Row> batch;
                try {
                    batch = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (batch == POISON) {
                    buildComplete = true;
                    break;
                }
                for (Row r : batch) {
                    long id = nextId++;
                    ins.setLong(1, id);
                    ins.setLong(2, r.fileId);
                    ins.setLong(3, r.lineNo);
                    ins.setLong(4, r.byteOffset);
                    ins.setLong(5, r.endByteOffset);
                    ins.setLong(6, r.tsMillis);
                    ins.setString(7, r.logger);
                    ins.setString(8, r.level);
                    ins.setString(9, r.thread);
                    ins.setString(10, r.message);
                    ins.addBatch();
                    if (ftsIns != null) {
                        ftsIns.setLong(1, id);
                        ftsIns.setString(2, r.bodyBuf != null ? r.bodyBuf.toString() : "");
                        ftsIns.addBatch();
                    }
                }
                ins.executeBatch();
                if (ftsIns != null) {
                    ftsIns.executeBatch();
                }
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
        if (t != null || !buildComplete) {
            abortIncompleteBuild(conn);
            if (t != null) {
                throw new IOException("インデックス構築に失敗しました: " + t.getMessage(), t);
            }
            throw new IOException("インデックス構築が中断されました");
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('fingerprint', ?)")) {
            ps.setString(1, fp);
            ps.executeUpdate();
        }
        saveSkippedMeta(conn, (int) skippedCounter.get(), skippedSamples);
        conn.commit();

        if (progress != null) {
            progress.onProgress(total);
        }
        return new BuildResult(total, (int) skippedCounter.get(), new ArrayList<>(skippedSamples));
    }

    private static void saveSkippedMeta(Connection conn, int count, List<SkippedLine> samples)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, META_SKIPPED_LINES);
            ps.setString(2, String.valueOf(count));
            ps.executeUpdate();
            ps.setString(1, META_SKIPPED_SAMPLES);
            ps.setString(2, serializeSkippedSamples(samples));
            ps.executeUpdate();
        }
    }

    private static String serializeSkippedSamples(List<SkippedLine> samples) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SkippedLine s = samples.get(i);
            sb.append("{\"file_id\":").append(s.fileId)
                    .append(",\"line_no\":").append(s.lineNo)
                    .append(",\"preview\":").append(jsonString(s.preview)).append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<SkippedLine> deserializeSkippedSamples(String json) {
        List<SkippedLine> out = new ArrayList<>();
        int i = 0;
        while (i < json.length()) {
            int objStart = json.indexOf('{', i);
            if (objStart < 0) {
                break;
            }
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) {
                break;
            }
            String obj = json.substring(objStart + 1, objEnd);
            long fileId = extractJsonLong(obj, "file_id");
            long lineNo = extractJsonLong(obj, "line_no");
            String preview = extractJsonString(obj, "preview");
            out.add(new SkippedLine(fileId, lineNo, preview));
            i = objEnd + 1;
        }
        return out;
    }

    private static long extractJsonLong(String obj, String key) {
        String needle = "\"" + key + "\":";
        int idx = obj.indexOf(needle);
        if (idx < 0) {
            return 0L;
        }
        int start = idx + needle.length();
        int end = start;
        while (end < obj.length() && Character.isDigit(obj.charAt(end))) {
            end++;
        }
        return Long.parseLong(obj.substring(start, end));
    }

    private static String extractJsonString(String obj, String key) {
        String needle = "\"" + key + "\":\"";
        int idx = obj.indexOf(needle);
        if (idx < 0) {
            return "";
        }
        int start = idx + needle.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (c == '\\' && i + 1 < obj.length()) {
                char next = obj.charAt(i + 1);
                if (next == 'n') {
                    sb.append('\n');
                } else if (next == 'r') {
                    sb.append('\r');
                } else if (next == 't') {
                    sb.append('\t');
                } else if (next == '\\' || next == '"') {
                    sb.append(next);
                } else {
                    sb.append(next);
                }
                i++;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void parseFileInto(long fileId, Path path, BlockingQueue<List<Row>> queue,
            boolean collectBody, AtomicLong skippedCounter, List<SkippedLine> skippedSamples)
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
                boolean header = LogParser.looksLikeHeader(reader.lineBuf, reader.lineLen);
                LogParser.ParsedLine parsed =
                        header ? LogParser.parse(reader.lineBuf, reader.lineLen) : null;
                if (parsed == null) {
                    if (pending == null) {
                        skippedCounter.incrementAndGet();
                        if (skippedSamples.size() < MAX_SKIPPED_SAMPLES) {
                            skippedSamples.add(new SkippedLine(fileId, lineNo,
                                    previewLine(reader.lineBuf, reader.lineLen)));
                        }
                    } else if (collectBody) {
                        // 継続行（スタックトレース等）は直前エントリの本文に蓄積。
                        pending.bodyBuf.append(new String(
                                reader.lineBuf, 0, reader.lineLen, StandardCharsets.UTF_8));
                    }
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
                if (collectBody) {
                    pending.bodyBuf = new StringBuilder();
                    pending.bodyBuf.append(new String(
                            reader.lineBuf, 0, reader.lineLen, StandardCharsets.UTF_8));
                }
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

    private static String previewLine(byte[] buf, int len) {
        int end = len;
        while (end > 0 && (buf[end - 1] == '\n' || buf[end - 1] == '\r')) {
            end--;
        }
        String trimmed = new String(buf, 0, end, StandardCharsets.UTF_8);
        if (trimmed.length() <= PREVIEW_MAX_LEN) {
            return trimmed;
        }
        return trimmed.substring(0, PREVIEW_MAX_LEN - 3) + "...";
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

    /** ソースパス・行番号で 1 件検索（timestamp 指定時は行の特定を補助）。 */
    public static EntryRow findEntry(Connection conn, String source, long lineNo, String timestampIso)
            throws SQLException {
        if (timestampIso != null && !timestampIso.isEmpty()) {
            long tsMillis = TimeUtil.parseUiDatetime(timestampIso);
            try (PreparedStatement ps = conn.prepareStatement(
                    SELECT_BASE + "WHERE f.path = ? AND e.line_no = ? AND e.ts_millis = ? LIMIT 1")) {
                ps.setString(1, source);
                ps.setLong(2, lineNo);
                ps.setLong(3, tsMillis);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rowFrom(rs) : null;
                }
            }
        }
        return findEntry(conn, source, lineNo);
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
