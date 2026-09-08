package com.example.aplv;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自前 HTTP サーバ（JDK 内蔵 {@link com.sun.net.httpserver.HttpServer} を使用）。
 *
 * <p>アプリケーションサーバ（Tomcat 等）に依存せず単体で Web UI を提供する。
 * REST API と Web UI（クラスパス同梱の静的ファイル）を提供する。
 *
 * <ul>
 *   <li>{@code GET /}              — index.html</li>
 *   <li>{@code GET /static/*}      — 静的ファイル（クラスパス同梱）</li>
 *   <li>{@code GET /api/meta}      — 読み込み状態</li>
 *   <li>{@code GET /api/browse}    — ディレクトリ一覧</li>
 *   <li>{@code POST /api/load}     — ディレクトリ指定・インデックス構築開始</li>
 *   <li>{@code GET /api/logs}      — フィルタ付き一覧</li>
 *   <li>{@code GET /api/logs/detail} — スタックトレース含む生ログ</li>
 * </ul>
 */
public final class LogServer {

    private static final int MAX_LIMIT = 5000;
    private static final int DEFAULT_LIMIT = 500;
    /**
     * 直前の読み込みワーカーの終了を待つ上限。
     *
     * <p>中断されたワーカーはパーススレッドの回収（最大
     * {@link LogIndex#POOL_DRAIN_TIMEOUT_MS}）を終えてから部分索引を破棄し、接続を閉じる。
     * それより短く待つと DB を掴んだままのワーカーを追い越すことになり、待ち合わせの意味が
     * なくなる。必ず回収の上限を上回る値にすること。
     */
    private static final long PREVIOUS_LOAD_JOIN_MS = LogIndex.POOL_DRAIN_TIMEOUT_MS + 30_000L;
    /**
     * 一覧 1 行あたりに返す生テキストの上限（文字）。
     *
     * <p>一覧の {@code raw} はクライアント側のハイライト判定にしか使わないため全文は要らない。
     * 上限を設けないと、長大なスタックトレース × 表示件数（最大 {@link #MAX_LIMIT}）で
     * レスポンスが数百 MB になり得る。切り詰めてもハイライトは残りの列にフォールバックする。
     */
    private static final int MAX_ROW_RAW_CHARS = 4096;

    private volatile Path logRoot;
    private volatile List<Path> logPaths = Collections.emptyList();
    /** 全文検索 FTS5 を構築するか（--fts 指定時のみ true）。 */
    private final boolean enableFts;

    private volatile String loadStatus = "idle"; // idle / loading / ready / error
    private volatile String loadError;
    private final AtomicLong loadProgress = new AtomicLong();
    /** 読み込みワーカーの世代。新しい load で増加し、古いワーカーは結果を破棄する。 */
    private final AtomicLong loadGeneration = new AtomicLong(0);
    private volatile Thread loadWorker;

    /** ロード完了時に確定する meta 情報。長い検索が dbLock を握っていても参照できる。 */
    private static final class MetaSnapshot {
        static final MetaSnapshot EMPTY = new MetaSnapshot(0, null, null, 0,
                Collections.<SkippedSample>emptyList());

        final long total;
        final String first;
        final String last;
        final int skippedLines;
        final List<SkippedSample> skippedSamples;

        MetaSnapshot(long total, String first, String last, int skippedLines,
                List<SkippedSample> skippedSamples) {
            this.total = total;
            this.first = first;
            this.last = last;
            this.skippedLines = skippedLines;
            this.skippedSamples = skippedSamples;
        }
    }

    /** 認識できなかった行のサンプル（file_id をログファイルパスに解決済み）。 */
    private static final class SkippedSample {
        final String source;
        final long lineNo;
        final String preview;

        SkippedSample(String source, long lineNo, String preview) {
            this.source = source;
            this.lineNo = lineNo;
            this.preview = preview;
        }
    }

    private volatile MetaSnapshot metaSnapshot = MetaSnapshot.EMPTY;

    private final Object loadLock = new Object();
    private final Object dbLock = new Object();
    private Connection conn; // dbLock で保護

    private final Map<String, byte[]> staticCache = new HashMap<>();

    public LogServer(Path logRoot, List<Path> logPaths) {
        this(logRoot, logPaths, false);
    }

    public LogServer(Path logRoot, List<Path> logPaths, boolean enableFts) {
        this.logRoot = logRoot;
        this.logPaths = logPaths != null ? logPaths : Collections.<Path>emptyList();
        this.enableFts = enableFts;
    }

    /** サーバを起動して待ち受ける（戻らない）。 */
    public void start(String host, int port) throws IOException {
        synchronized (dbLock) {
            try {
                conn = LogIndex.openMemory();
            } catch (Exception e) {
                throw new IOException("SQLite を初期化できません: " + e.getMessage(), e);
            }
        }
        if (!logPaths.isEmpty()) {
            startLoad();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.createContext("/", new RootHandler());

        System.out.println("Application Log Viewer (Java): http://" + host + ":" + port);
        System.out.println("インデックス: " + IndexStore.tmpIndexDir() + " (APLV_HOME で repo 変更可)");
        System.out.println("全文検索 FTS5: " + (enableFts ? "有効" : "無効（--fts で有効化）"));
        if (logRoot != null) {
            System.out.println("ログディレクトリ: " + PathUtil.normalizePath(logRoot));
        }
        System.out.println("読み込みファイル (" + logPaths.size() + "):");
        for (Path p : logPaths) {
            System.out.println("  - " + PathUtil.normalizePath(p));
        }
        if (logPaths.isEmpty()) {
            System.out.println("  (未読み込み — ブラウザからディレクトリを選択してください)");
        }
        server.start();
    }

    // ---- ルーティング -----------------------------------------------------

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                if ("/".equals(path)) {
                    serveStatic(ex, "index.html");
                } else if (path.startsWith("/static/")) {
                    serveStatic(ex, path.substring("/static/".length()));
                } else if ("/api/meta".equals(path)) {
                    sendJson(ex, 200, metaPayload());
                } else if ("/api/browse".equals(path)) {
                    handleBrowse(ex);
                } else if ("/api/load".equals(path) && "POST".equalsIgnoreCase(method)) {
                    handleLoad(ex);
                } else if ("/api/logs".equals(path)) {
                    handleLogs(ex);
                } else if ("/api/logs/detail".equals(path)) {
                    handleDetail(ex);
                } else {
                    sendError(ex, 404, "not found");
                }
            } catch (Exception e) {
                try {
                    sendError(ex, 500, e.getMessage() != null ? e.getMessage() : e.toString());
                } catch (IOException ignored) {
                    // レスポンス送信失敗は無視
                }
            } finally {
                ex.close();
            }
        }
    }

    // ---- 読み込み（インデックス構築）--------------------------------------

    private void startLoad() {
        final long gen = loadGeneration.incrementAndGet();
        final Thread previous;
        synchronized (loadLock) {
            previous = loadWorker;
            loadStatus = "loading";
            loadError = null;
            loadProgress.set(0);
        }
        if (previous != null) {
            previous.interrupt();
        }
        final Path root = logRoot;
        final List<Path> paths = new ArrayList<>(logPaths);

        Thread worker = new Thread(() -> {
            // 直前のワーカーは中断されると部分索引を破棄する。その後始末より先に同じ DB を
            // 開くと、Windows では使用中ファイルの削除が失敗して新旧が同一 DB を書き合い、
            // 完成した索引が空にされることがある。必ず終了を待ってから始める。
            if (previous != null) {
                try {
                    previous.join(PREVIOUS_LOAD_JOIN_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            Connection newConn = null;
            boolean adopted = false;
            try {
                long total;
                if (root == null) {
                    if (isStale(gen)) {
                        return;
                    }
                    newConn = LogIndex.openMemory();
                    LogIndex.clearIndex(newConn);
                    total = 0;
                } else {
                    if (isStale(gen)) {
                        return;
                    }
                    IndexStore.ensureTmpDirFor(root);
                    newConn = LogIndex.openOrCreate(root);
                    if (paths.isEmpty()) {
                        if (isStale(gen)) {
                            return;
                        }
                        closeQuietly(newConn);
                        IndexStore.deleteIndexFiles(root);
                        if (isStale(gen)) {
                            return;
                        }
                        newConn = LogIndex.openOrCreate(root);
                        LogIndex.clearIndex(newConn);
                        total = 0;
                    } else if (LogIndex.needsRebuild(newConn, paths, enableFts)) {
                        if (isStale(gen)) {
                            return;
                        }
                        closeQuietly(newConn);
                        IndexStore.deleteIndexFiles(root);
                        if (isStale(gen)) {
                            return;
                        }
                        newConn = LogIndex.openOrCreate(root);
                        LogIndex.BuildResult built =
                                LogIndex.buildIndex(newConn, paths, loadProgress::set, enableFts);
                        total = built.entryCount;
                    } else {
                        total = LogIndex.entryCount(newConn);
                        // 索引と統計は取込時にしか作らないため、再利用時はここで補う
                        // （旧バージョンが作った DB には新しい索引・統計が無い）。
                        LogIndex.ensureIndexes(newConn);
                        LogIndex.updateStatistics(newConn);
                        loadProgress.set(total);
                    }
                }
                if (isStale(gen)) {
                    return;
                }
                MetaSnapshot snapshot = snapshotMeta(newConn, total);
                synchronized (loadLock) {
                    if (isStale(gen)) {
                        return;
                    }
                    replaceConn(newConn);
                    adopted = true;
                    metaSnapshot = snapshot;
                    loadProgress.set(total);
                    loadStatus = "ready";
                }
            } catch (Throwable t) {
                synchronized (loadLock) {
                    if (isStale(gen)) {
                        return;
                    }
                    loadStatus = "error";
                    loadError = t.getMessage() != null ? t.getMessage() : t.toString();
                }
            } finally {
                if (newConn != null && !adopted) {
                    closeQuietly(newConn);
                }
            }
        }, "aplv-loader");
        worker.setDaemon(true);
        synchronized (loadLock) {
            // 起動まで含めてロック内で行う。未起動のスレッドへの join は即座に返るため、
            // ここに隙間があると次のワーカーが待ち合わせを空振りする。
            loadWorker = worker;
            worker.start();
        }
    }

    private boolean isStale(long gen) {
        return gen != loadGeneration.get();
    }

    private static void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // クローズ失敗は無視
        }
    }

    private void replaceConn(Connection newConn) {
        synchronized (dbLock) {
            if (conn != null && conn != newConn) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // クローズ失敗は無視
                }
            }
            conn = newConn;
        }
    }

    private void ensureLoadStarted() {
        if (!logPaths.isEmpty() && "idle".equals(loadStatus)) {
            startLoad();
        }
    }

    // ---- API: meta --------------------------------------------------------

    /** ロード完了直後に meta 情報を確定させる（以後 /api/meta は DB を触らない）。 */
    private static MetaSnapshot snapshotMeta(Connection source, long total) throws SQLException {
        String[] bounds = LogIndex.timestampBounds(source);
        int skipped = LogIndex.getSkippedLineCount(source);
        List<SkippedSample> samples = new ArrayList<>();
        if (skipped > 0) {
            for (SkippedLine line : LogIndex.getSkippedLineSamples(source)) {
                String path = LogIndex.filePath(source, line.fileId);
                samples.add(new SkippedSample(path != null ? path : "", line.lineNo, line.preview));
            }
        }
        return new MetaSnapshot(total, bounds[0], bounds[1], skipped, samples);
    }

    /**
     * 読み込み状態の応答。
     *
     * <p>件数・時刻範囲はロード完了時に確定するため DB を引かない。長時間の全文検索が
     * {@code dbLock} を握っていても、進捗ポーリングが止まらないようにするため。
     */
    private JsonObject metaPayload() {
        ensureLoadStarted();
        // 状態は 1 回だけ読む（複数回読むと loading と ready の判定がずれ得る）。
        // metaSnapshot は loadStatus より先に書かれるため、ready を見たなら最新が見える。
        String status = loadStatus;
        boolean loading = "loading".equals(status);
        boolean ready = "ready".equals(status);
        long progress = loadProgress.get();
        MetaSnapshot snapshot = metaSnapshot;

        long total;
        String first = null;
        String last = null;
        if (loading) {
            total = progress;
        } else if (ready) {
            total = snapshot.total;
            first = snapshot.first;
            last = snapshot.last;
        } else {
            total = 0;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("directory", logRoot != null ? PathUtil.normalizePath(logRoot) : null);
        payload.add("files", sourceNames());
        payload.addProperty("loading", loading);
        payload.addProperty("load_status", loadStatus);
        payload.addProperty("load_progress", progress);
        payload.addProperty("total", total);
        payload.addProperty("first", first);
        payload.addProperty("last", last);
        if (ready && snapshot.skippedLines > 0) {
            payload.addProperty("skipped_lines", snapshot.skippedLines);
            JsonArray samples = new JsonArray();
            for (SkippedSample sample : snapshot.skippedSamples) {
                JsonObject o = new JsonObject();
                o.addProperty("source", sample.source);
                o.addProperty("line_no", sample.lineNo);
                o.addProperty("preview", sample.preview);
                samples.add(o);
            }
            payload.add("skipped_samples", samples);
        }
        if (loadError != null) {
            payload.addProperty("load_error", loadError);
        }
        return payload;
    }

    private JsonArray sourceNames() {
        JsonArray arr = new JsonArray();
        for (Path p : logPaths) {
            arr.add(PathUtil.normalizePath(p));
        }
        return arr;
    }

    // ---- API: browse ------------------------------------------------------

    private void handleBrowse(HttpExchange ex) throws IOException {
        Map<String, String> params = queryParams(ex);
        String rawPath = params.getOrDefault("path", "");
        Path current;
        if (!rawPath.isEmpty()) {
            current = PathUtil.resolve(rawPath);
        } else if (logRoot != null) {
            current = logRoot;
        } else {
            current = Paths.get("").toAbsolutePath();
        }

        if (!Files.isDirectory(current)) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "ディレクトリが見つかりません: " + PathUtil.normalizePath(current));
            sendJson(ex, 400, err);
            return;
        }

        Path parent = current.getParent();
        List<String> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            for (Path entry : stream) {
                Path name = entry.getFileName();
                if (name != null && Files.isDirectory(entry) && !name.toString().startsWith(".")) {
                    dirs.add(PathUtil.normalizePath(entry));
                }
            }
        } catch (IOException e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "ディレクトリを読み取れません: " + e.getMessage());
            sendJson(ex, 400, err);
            return;
        }
        dirs.sort((a, b) -> a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT)));

        JsonObject payload = new JsonObject();
        payload.addProperty("current", PathUtil.normalizePath(current));
        payload.addProperty("parent",
                (parent != null && !parent.equals(current)) ? PathUtil.normalizePath(parent) : null);
        JsonArray arr = new JsonArray();
        for (String d : dirs) {
            arr.add(d);
        }
        payload.add("directories", arr);
        sendJson(ex, 200, payload);
    }

    // ---- API: load --------------------------------------------------------

    private void handleLoad(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String directory = "";
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("directory") && !obj.get("directory").isJsonNull()) {
                directory = obj.get("directory").getAsString();
            }
        } catch (RuntimeException e) {
            sendErrorJson(ex, 400, "JSON を解釈できません");
            return;
        }
        if (directory.isEmpty()) {
            sendErrorJson(ex, 400, "directory を指定してください");
            return;
        }
        Path root = PathUtil.resolve(directory);
        if (!Files.isDirectory(root)) {
            sendErrorJson(ex, 400, "ディレクトリが見つかりません: " + PathUtil.normalizePath(root));
            return;
        }

        List<Path> paths;
        try {
            paths = Discovery.findLogFiles(root);
        } catch (IOException e) {
            sendErrorJson(ex, 400, e.getMessage());
            return;
        }
        synchronized (loadLock) {
            this.logRoot = root;
            this.logPaths = paths;
            this.loadError = null;
        }
        startLoad();
        try {
            sendJson(ex, 200, metaPayload());
        } catch (Exception e) {
            sendErrorJson(ex, 500, e.getMessage());
        }
    }

    // ---- API: logs --------------------------------------------------------

    private void handleLogs(HttpExchange ex) throws IOException {
        if ("loading".equals(loadStatus)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("loading", true);
            payload.addProperty("load_progress", loadProgress.get());
            payload.addProperty("total", 0);
            payload.addProperty("offset", 0);
            payload.addProperty("limit", 0);
            payload.add("items", new JsonArray());
            sendJson(ex, 200, payload);
            return;
        }
        if ("error".equals(loadStatus)) {
            sendErrorJson(ex, 500, loadError != null ? loadError : "読み込みに失敗しました");
            return;
        }

        Map<String, String> p = queryParams(ex);
        QueryFilter filter = new QueryFilter();
        try {
            filter.levels = QueryFilter.parseLevelFilter(p.get("level"));
            filter.loggerRe = QueryFilter.compileRegex(p.get("logger"));
            filter.threadRe = QueryFilter.compileRegex(p.get("thread"));
            filter.messageRe = QueryFilter.compileRegex(p.get("message"));
            filter.sourceRe = QueryFilter.compileRegex(p.get("source"));
            filter.grepRe = QueryFilter.compileRegex(p.get("grep"));
            String grep = p.get("grep");
            filter.grepText = (grep != null && !grep.isEmpty()) ? grep : null;
        } catch (RuntimeException e) {
            sendErrorJson(ex, 400, "正規表現が不正です: " + e.getMessage());
            return;
        }
        try {
            String since = p.get("since");
            String until = p.get("until");
            filter.sinceMillis = (since != null && !since.isEmpty()) ? TimeUtil.parseUiDatetime(since) : null;
            filter.untilMillis = (until != null && !until.isEmpty()) ? TimeUtil.parseUiDatetime(until) : null;
        } catch (IllegalArgumentException e) {
            sendErrorJson(ex, 400, e.getMessage());
            return;
        }

        long limit;
        long offset;
        try {
            limit = Math.max(0, Math.min(parseLong(p.get("limit"), DEFAULT_LIMIT), MAX_LIMIT));
            offset = Math.max(parseLong(p.get("offset"), 0), 0);
        } catch (NumberFormatException e) {
            sendErrorJson(ex, 400, "limit/offset は整数で指定してください");
            return;
        }

        LogQuery.Result result;
        try {
            synchronized (dbLock) {
                result = LogQuery.queryLogs(conn, filter, offset, limit);
            }
        } catch (Exception e) {
            sendErrorJson(ex, 500, e.getMessage());
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("total", result.total);
        payload.addProperty("offset", offset);
        payload.addProperty("limit", limit);
        JsonArray items = new JsonArray();
        Map<String, RandomAccessFile> rawHandles = new HashMap<>();
        try {
            for (LogIndex.EntryRow e : result.page) {
                // grep 検索では LogQuery が既に読み出しているので再読み込みしない。
                String raw = e.raw != null ? e.raw : LogIndex.readEntryRawCached(rawHandles, e);
                items.add(rowJson(e, truncateRaw(raw)));
            }
        } finally {
            LogIndex.closeHandles(rawHandles);
        }
        payload.add("items", items);
        sendJson(ex, 200, payload);
    }

    /** 一覧の生テキストを上限まで切り詰める（サロゲートペアの分断は避ける）。 */
    private static String truncateRaw(String raw) {
        if (raw == null || raw.length() <= MAX_ROW_RAW_CHARS) {
            return raw;
        }
        int end = MAX_ROW_RAW_CHARS;
        if (Character.isHighSurrogate(raw.charAt(end - 1))) {
            end--;
        }
        return raw.substring(0, end);
    }

    private JsonObject rowJson(LogIndex.EntryRow e, String raw) {
        JsonObject o = new JsonObject();
        o.addProperty("timestamp", TimeUtil.formatIso(e.tsMillis));
        o.addProperty("level", e.level);
        o.addProperty("logger", e.logger);
        o.addProperty("thread", e.thread);
        o.addProperty("message", e.message);
        o.addProperty("source", e.source);
        o.addProperty("line_no", e.lineNo);
        o.addProperty("raw", raw);
        return o;
    }

    // ---- API: logs/detail -------------------------------------------------

    private void handleDetail(HttpExchange ex) throws IOException {
        if ("loading".equals(loadStatus)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("loading", true);
            payload.addProperty("load_progress", loadProgress.get());
            sendJson(ex, 200, payload);
            return;
        }
        if ("error".equals(loadStatus)) {
            sendErrorJson(ex, 500, loadError != null ? loadError : "読み込みに失敗しました");
            return;
        }

        Map<String, String> p = queryParams(ex);
        String source = p.getOrDefault("source", "");
        if (source.isEmpty()) {
            sendErrorJson(ex, 400, "ログファイルを指定してください");
            return;
        }
        long lineNo;
        try {
            lineNo = Long.parseLong(p.getOrDefault("line_no", "0"));
        } catch (NumberFormatException e) {
            sendErrorJson(ex, 400, "line_no は整数で指定してください");
            return;
        }

        LogIndex.EntryRow entry;
        String timestamp = p.get("timestamp");
        try {
            synchronized (dbLock) {
                entry = LogIndex.findEntry(conn, source, lineNo, timestamp);
            }
        } catch (IllegalArgumentException e) {
            sendErrorJson(ex, 400, e.getMessage());
            return;
        } catch (Exception e) {
            sendErrorJson(ex, 500, e.getMessage());
            return;
        }
        if (entry == null) {
            sendErrorJson(ex, 404, "該当行が見つかりません");
            return;
        }

        String raw;
        try {
            raw = LogIndex.readEntryRaw(Paths.get(entry.source), entry.byteOffset, entry.endByteOffset);
        } catch (IOException e) {
            sendErrorJson(ex, 500, e.getMessage());
            return;
        }

        JsonObject o = new JsonObject();
        o.addProperty("source", entry.source);
        o.addProperty("line_no", entry.lineNo);
        o.addProperty("timestamp", TimeUtil.formatIso(entry.tsMillis));
        o.addProperty("level", entry.level);
        o.addProperty("logger", entry.logger);
        o.addProperty("thread", entry.thread);
        o.addProperty("raw", raw);
        sendJson(ex, 200, o);
    }

    // ---- 静的ファイル -----------------------------------------------------

    private void serveStatic(HttpExchange ex, String name) throws IOException {
        byte[] content = loadStatic(name);
        if (content == null) {
            sendError(ex, 404, "not found");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", contentType(name));
        ex.sendResponseHeaders(200, content.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(content);
        }
    }

    private byte[] loadStatic(String name) {
        // ディレクトリトラバーサル防止
        if (name.contains("..")) {
            return null;
        }
        synchronized (staticCache) {
            if (staticCache.containsKey(name)) {
                return staticCache.get(name);
            }
        }
        byte[] data = null;
        try (InputStream in = LogServer.class.getResourceAsStream("/static/" + name)) {
            if (in != null) {
                data = readAll(in);
            }
        } catch (IOException ignored) {
            data = null;
        }
        if (data != null) {
            // 存在しないリソース名はキャッシュしない（リクエスト由来のキーでマップが無限に育つため）
            synchronized (staticCache) {
                staticCache.put(name, data);
            }
        }
        return data;
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    // ---- HTTP ユーティリティ ----------------------------------------------

    private Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> map = new TreeMap<>();
        String query = ex.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key;
            String value;
            if (eq >= 0) {
                key = decode(pair.substring(0, eq));
                value = decode(pair.substring(eq + 1));
            } else {
                key = decode(pair);
                value = "";
            }
            if (!map.containsKey(key)) {
                map.put(key, value);
            }
        }
        return map;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static long parseLong(String s, long defaultValue) {
        if (s == null || s.isEmpty()) {
            return defaultValue;
        }
        return Long.parseLong(s.trim());
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private void sendJson(HttpExchange ex, int status, JsonObject obj) throws IOException {
        byte[] body = obj.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendErrorJson(HttpExchange ex, int status, String message) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message != null ? message : "error");
        sendJson(ex, status, obj);
    }

    private void sendError(HttpExchange ex, int status, String message) throws IOException {
        byte[] body = (message != null ? message : "error").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
