package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.aplv.LogIndex.EntryRow;
import com.example.aplv.SessionTrace.EndReason;
import com.example.aplv.SessionTrace.Request;

/**
 * {@link SessionTrace} の試験（一時ディレクトリ + SQLite）。
 *
 * <p>担保すること:
 * <ul>
 *   <li>同一ファイル・同一スレッドの「はじまり〜おわり」を 1 リクエストとしてまとめ、
 *       他スレッド・他セッションのログを混ぜない</li>
 *   <li>おわりが無いときは次のはじまりの直前、または最大所要時間で打ち切る</li>
 *   <li>はじまりが無くてもおわりで閉じていればリクエストとし、どちらも囲まない行は単独にする</li>
 *   <li>識別子は正規表現ではなく文字列として、大文字小文字を区別して照合する</li>
 *   <li>FTS5 の有無で結果が変わらない</li>
 *   <li>同一ミリ秒の行が並んでも、ファイル内の行順で範囲を決める</li>
 *   <li>件数の上限で打ち切っても、起点の行は必ず結果に残る</li>
 * </ul>
 */
class SessionTraceTest {

    private static final String START = "^リクエスト開始";
    private static final String END = "^リクエスト終了";
    private static final String SID = "8F3A2C91D4E6B7A0.node1";

    private static Path writeLog(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return PathUtil.resolve(path);
    }

    private static String line(String time, String thread, String message) {
        return "2026-06-16 " + time + "[" + thread + "][INFO][com.example.X] - " + message + "\n";
    }

    private static SessionTrace trace(String id, int maxMinutes) {
        return new SessionTrace(id, QueryFilter.compileRegex(START), QueryFilter.compileRegex(END),
                maxMinutes);
    }

    private static SessionTrace.Result run(Path root, List<Path> logs, boolean fts, String id,
            int maxMinutes) throws Exception {
        Files.createDirectories(root);
        try (Connection conn = LogIndex.openOrCreate(root)) {
            LogIndex.buildIndex(conn, logs, null, fts, LogFormat.DEFAULT);
            return trace(id, maxMinutes).run(conn);
        }
    }

    private static SessionTrace.Result run(Path root, String content, String id, int maxMinutes)
            throws Exception {
        Path log = writeLog(root, "app.log", content);
        return run(root, Collections.singletonList(log), false, id, maxMinutes);
    }

    private static List<String> messages(Request r) {
        List<String> out = new ArrayList<>();
        for (EntryRow e : r.entries) {
            out.add(e.message);
        }
        return out;
    }

    private static List<Path> sampleLogs() throws IOException {
        return Discovery.findLogFiles(Paths.get("..", "samples", "session"));
    }

    /** 冗長構成（3 インスタンス）のサンプル。1 セッションがファイルをまたぐ。 */
    private static List<Path> clusterLogs() throws IOException {
        return Discovery.findLogFiles(Paths.get("..", "samples", "session-cluster"));
    }

    /**
     * リポジトリ同梱のサンプル（samples/session）で、想定した 7 リクエストが得られること。
     * サンプルを書き換えたらこの試験も合わせる（README の説明とも対応している）。
     */
    @Test
    void sampleScenarios(@TempDir Path tmp) throws Exception {
        List<Path> logs = sampleLogs();
        assertEquals(2, logs.size());
        SessionTrace.Result r = run(tmp, logs, false, SID, SessionTrace.DEFAULT_MAX_MINUTES);

        assertEquals(8, r.anchorTotal);
        assertFalse(r.truncated);
        assertEquals(7, r.requests.size());

        // 1. ローテート前のファイル: はじまりはあるが、おわりは次のファイルにある
        Request rotatedBefore = r.requests.get(0);
        assertTrue(rotatedBefore.source.endsWith("webapp.2026-06-16.0.log"));
        assertTrue(rotatedBefore.startFound);
        assertEquals(EndReason.NOT_FOUND, rotatedBefore.endReason);
        assertEquals(3, rotatedBefore.entries.size());

        // 2. ローテート後のファイル: はじまり不明のまま、おわりで閉じる
        Request rotatedAfter = r.requests.get(1);
        assertTrue(rotatedAfter.source.endsWith("webapp.log"));
        assertFalse(rotatedAfter.startFound);
        assertEquals(EndReason.END, rotatedAfter.endReason);
        assertEquals(2, rotatedAfter.entries.size());

        // 3. 通常のリクエスト。識別子を含まない行（在庫確認）も含み、別スレッドの行は含まない。
        //    起点が 2 つあっても 1 リクエストにまとまる。
        Request cart = r.requests.get(2);
        assertEquals("http-nio-8080-exec-1", cart.thread);
        assertTrue(cart.startFound);
        assertEquals(EndReason.END, cart.endReason);
        assertEquals(5, cart.entries.size());
        assertEquals(2, cart.anchorIds.size());
        assertTrue(messages(cart).get(2).startsWith("在庫を確認"));
        for (EntryRow e : cart.entries) {
            assertEquals("http-nio-8080-exec-1", e.thread);
        }

        // 4. 識別子がスタックトレースにしか無いリクエスト。非同期スレッド（task-1）は含まない。
        Request order = r.requests.get(3);
        assertEquals("http-nio-8080-exec-2", order.thread);
        assertEquals(EndReason.END, order.endReason);
        assertEquals(5, order.entries.size());
        assertEquals("注文処理に失敗しました", order.entries.get(3).message);
        assertEquals(1, order.anchorIds.size());
        assertTrue(order.anchorIds.contains(order.entries.get(3).id));

        // 5. おわりが出ないまま抜け、同じスレッドの次のはじまり（別セッション）の直前で閉じる
        Request mypage = r.requests.get(4);
        assertEquals("http-nio-8080-exec-3", mypage.thread);
        assertEquals(EndReason.NEXT_START, mypage.endReason);
        assertEquals(3, mypage.entries.size());

        // 6. どのリクエストにも属さない行
        Request listener = r.requests.get(5);
        assertEquals("Catalina-utility-1", listener.thread);
        assertEquals(EndReason.STANDALONE, listener.endReason);
        assertEquals(1, listener.entries.size());

        // 7. ファイル末尾までおわりが無い
        Request login = r.requests.get(6);
        assertEquals("http-nio-8080-exec-5", login.thread);
        assertEquals(EndReason.NOT_FOUND, login.endReason);
        assertEquals(3, login.entries.size());

        // 別セッションの識別子だけを含む行はどこにも出てこない
        for (Request req : r.requests) {
            for (String m : messages(req)) {
                assertFalse(m.contains("C07E55A1B9D24F68"), m);
            }
        }
    }

    private static SessionTrace.Result runFiltered(Path root, List<Path> logs, String contains,
            String excludes) throws Exception {
        return runFiltered(root, logs, SID, contains, excludes);
    }

    private static SessionTrace.Result runFiltered(Path root, List<Path> logs, String id,
            String contains, String excludes) throws Exception {
        Files.createDirectories(root);
        try (Connection conn = LogIndex.openOrCreate(root)) {
            LogIndex.buildIndex(conn, logs, null, false, LogFormat.DEFAULT);
            return trace(id, 10)
                    .withRequestFilter(QueryFilter.compileRegex(contains),
                            QueryFilter.compileRegex(excludes))
                    .run(conn);
        }
    }

    /**
     * リクエスト単位の絞り込み。スタックトレースの中にしか無い語でも絞れること、
     * 除外が優先されること、落とした件数を数えること。
     */
    @Test
    void filtersRequestsByContainsAndExcludes(@TempDir Path tmp) throws Exception {
        List<Path> logs = sampleLogs();
        // スタックトレースにしか無い例外クラス名で 1 リクエストに絞れる（全 7 件のうち 1 件）
        SessionTrace.Result only = runFiltered(tmp.resolve("a"), logs, "PaymentException", null);
        assertEquals(1, only.requests.size());
        assertEquals(6, only.filteredOut);
        assertEquals(8, only.anchorTotal); // 起点の数は絞り込みで変わらない
        assertEquals("http-nio-8080-exec-2", only.requests.get(0).thread);

        // 除外すると、そのリクエストだけが落ちる
        SessionTrace.Result without = runFiltered(tmp.resolve("b"), logs, null, "PaymentException");
        assertEquals(6, without.requests.size());
        assertEquals(1, without.filteredOut);
        for (Request r : without.requests) {
            assertFalse("http-nio-8080-exec-2".equals(r.thread) && r.entries.size() == 5);
        }

        // 両方指定すると、含む条件を満たしても除外に当たれば落ちる
        SessionTrace.Result both =
                runFiltered(tmp.resolve("c"), logs, "リクエスト開始", "PaymentException");
        // はじまりの行を持つのは 5 件（「はじまり不明」と「単独の行」には無い）。そこから 1 件除く
        assertEquals(4, both.requests.size());
        for (Request r : both.requests) {
            assertTrue(messages(r).get(0).startsWith("リクエスト開始"));
        }
    }

    /** 絞り込みで落としたリクエストの別の起点でも、範囲を求め直さず結果にも出ないこと。 */
    @Test
    void filteredOutRequestStaysOut(@TempDir Path tmp) throws Exception {
        String content = line("10:00:00.000", "exec-1", "リクエスト開始 GET /a")
                + line("10:00:00.010", "exec-1", "id=" + SID + " 1 回目")
                + line("10:00:00.020", "exec-1", "除外したい語 NG")
                + line("10:00:00.030", "exec-1", "id=" + SID + " 2 回目")
                + line("10:00:00.040", "exec-1", "リクエスト終了 status=200");
        Files.createDirectories(tmp);
        Path log = writeLog(tmp, "app.log", content);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormat.DEFAULT);
            SessionTrace.Result r = trace(SID, 10)
                    .withRequestFilter(null, QueryFilter.compileRegex("除外したい語"))
                    .run(conn);
            assertEquals(2, r.anchorTotal);
            assertEquals(0, r.requests.size());
            assertEquals(1, r.filteredOut); // 2 つ目の起点で数え直さない
        }
    }

    /** 絞り込みを指定しなければ、これまでどおり全リクエストを返すこと。 */
    @Test
    void noFilterKeepsEveryRequest(@TempDir Path tmp) throws Exception {
        SessionTrace.Result r = runFiltered(tmp, sampleLogs(), null, null);
        assertEquals(7, r.requests.size());
        assertEquals(0, r.filteredOut);
    }

    /** 落としたリクエストも数に入れ、調べる件数の上限で打ち切ること。 */
    @Test
    void stopsAfterExaminedLimit(@TempDir Path tmp) throws Exception {
        StringBuilder sb = new StringBuilder();
        int n = SessionTrace.MAX_EXAMINED_REQUESTS + 5;
        for (int i = 0; i < n; i++) {
            String t = String.format("10:%02d:%02d", i / 60, i % 60);
            sb.append(line(t + ".000", "exec-1", "リクエスト開始 GET /" + i));
            sb.append(line(t + ".001", "exec-1", "id=" + SID));
            sb.append(line(t + ".002", "exec-1", "リクエスト終了 status=200"));
        }
        Path log = writeLog(tmp, "app.log", sb.toString());
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormat.DEFAULT);
            SessionTrace.Result r = trace(SID, 10)
                    .withRequestFilter(null, QueryFilter.compileRegex("リクエスト開始"))
                    .run(conn);
            assertEquals(n, r.anchorTotal);
            assertTrue(r.truncated);
            assertEquals(0, r.requests.size());
            assertEquals(SessionTrace.MAX_EXAMINED_REQUESTS, r.filteredOut);
        }
    }

    /**
     * 冗長構成のサンプル（samples/session-cluster）で、1 セッションのリクエストを
     * ログファイルをまたいで追えること。
     *
     * <p>同じスレッド名が別インスタンスにもあるため、ファイルで分けられていないと
     * 別の台のリクエストが混ざる。ここではそれが起きないことも確かめる。
     * ログは scripts/gen-session-cluster.py で生成している（種を固定した決定的な生成）。
     */
    @Test
    void clusterSampleIsTracedAcrossFiles(@TempDir Path tmp) throws Exception {
        String sid = "D41B8E2F5A7C4903";
        List<Path> logs = clusterLogs();
        assertEquals(3, logs.size());
        SessionTrace.Result r = run(tmp, logs, false, sid, 10);

        // 12 リクエスト + 同時刻の並列 2 リクエスト + セッション破棄の 1 行。
        // 起点は 16（例外の回はスタックトレースにも出るため 1 リクエストに 2 つ）
        assertEquals(16, r.anchorTotal);
        assertEquals(15, r.requests.size());

        java.util.Set<String> sources = new java.util.HashSet<>();
        int standalone = 0;
        int closed = 0;
        int rows = 0;
        for (Request req : r.requests) {
            rows += req.entries.size();
            sources.add(req.source);
            if (req.endReason == EndReason.STANDALONE) {
                standalone++;
                continue;
            }
            closed++;
            assertEquals(EndReason.END, req.endReason);
            assertTrue(req.startFound);
            // 1 リクエストのエントリは同じファイル・同じスレッドに収まる。
            // ファイルは source ではなく file_id で見る（source は起点のものを引き継ぐため、
            // 別ファイルの行が混ざっても source の比較では気付けない）。
            long fileId = req.entries.get(0).fileId;
            for (EntryRow e : req.entries) {
                assertEquals(fileId, e.fileId);
                assertEquals(req.source, e.source);
                assertEquals(req.thread, e.thread);
            }
            // ヘルスチェックや他セッションのリクエストは混ざらない
            for (String m : messages(req)) {
                assertFalse(m.contains("/health"), m);
            }
        }
        assertEquals(1, standalone);
        assertEquals(14, closed);
        // 行数まで固定して、範囲が短く切れたり他の行を巻き込んだりしたら気付けるようにする
        assertEquals(69, rows);

        // 同じセッションの並列リクエストが、別インスタンスの同じ名前のスレッドに同時刻で
        // 乗っている。ファイルで分けていないと、ここで互いのログを巻き込む。
        List<Request> parallel = new ArrayList<>();
        for (Request req : r.requests) {
            if (messages(req).get(0).contains("/api/")) {
                parallel.add(req);
            }
        }
        assertEquals(2, parallel.size());
        assertEquals("http-nio-8080-exec-1", parallel.get(0).thread);
        assertEquals("http-nio-8080-exec-1", parallel.get(1).thread);
        assertFalse(parallel.get(0).source.equals(parallel.get(1).source));
        for (Request req : parallel) {
            assertEquals(4, req.entries.size());
            boolean cart = messages(req).get(0).contains("/api/cart/count");
            for (String m : messages(req)) {
                assertFalse(m.contains(cart ? "通知" : "カート件数"), m);
            }
        }
        // 3 つのインスタンスすべてにまたがっている
        assertEquals(3, sources.size());

        // 他のセッションの ID を含む行は出てこない
        for (Request req : r.requests) {
            for (EntryRow e : req.entries) {
                String raw = LogIndex.readEntryRaw(Paths.get(e.source), e.byteOffset,
                        e.endByteOffset);
                int idAt = raw.indexOf("sessionId=");
                if (idAt >= 0) {
                    assertTrue(raw.startsWith(sid, idAt + "sessionId=".length()), raw);
                }
            }
        }
    }

    /** 冗長構成のサンプルでも、リクエスト単位の絞り込みが効くこと。 */
    @Test
    void clusterSampleFilters(@TempDir Path tmp) throws Exception {
        String sid = "D41B8E2F5A7C4903";
        List<Path> logs = clusterLogs();
        // スタックトレースにしか無い例外クラス名で、失敗した注文の 1 リクエストだけ残る
        SessionTrace.Result only = runFiltered(tmp.resolve("a"), logs, sid, "PaymentException", null);
        assertEquals(1, only.requests.size());
        assertEquals(14, only.filteredOut);
        assertEquals(EndReason.END, only.requests.get(0).endReason);
        assertTrue(messages(only.requests.get(0)).get(0).contains("POST /order"));

        // /order のリクエストを除くと、残りは /order 以外になる
        SessionTrace.Result without = runFiltered(tmp.resolve("b"), logs, sid, null, "POST /order");
        assertTrue(without.filteredOut > 0);
        for (Request req : without.requests) {
            assertFalse(messages(req).get(0).contains("POST /order"));
        }
        assertEquals(15, without.requests.size() + without.filteredOut);
    }

    private static SessionTrace.Result runWindow(Path root, String content, String id,
            int windowSeconds, String contains, String excludes) throws Exception {
        Path log = writeLog(root, "app.log", content);
        try (Connection conn = LogIndex.openOrCreate(root)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormat.DEFAULT);
            return SessionTrace.byTimeWindow(id, windowSeconds)
                    .withRequestFilter(QueryFilter.compileRegex(contains),
                            QueryFilter.compileRegex(excludes))
                    .run(conn);
        }
    }

    /**
     * 時間窓モード: はじまり・おわりの語を使わず、ID のある行の前後 n 秒で区切ること。
     * 窓の外の行と、別スレッドの行は入らない。
     */
    @Test
    void windowModeUsesTimeAroundAnchor(@TempDir Path tmp) throws Exception {
        String content = line("10:00:00.000", "exec-1", "窓の外（前）")
                + line("10:00:07.000", "exec-1", "窓の内（前）")
                + line("10:00:08.000", "exec-2", "別スレッド")
                + line("10:00:10.000", "exec-1", "id=" + SID)
                + line("10:00:12.000", "exec-1", "窓の内（後）")
                + line("10:00:20.000", "exec-1", "窓の外（後）");
        SessionTrace.Result r = runWindow(tmp, content, SID, 5, null, null);
        assertEquals(1, r.requests.size());
        Request req = r.requests.get(0);
        assertEquals(EndReason.WINDOW, req.endReason);
        assertFalse(req.startFound);
        assertEquals(java.util.Arrays.asList("窓の内（前）", "id=" + SID, "窓の内（後）"),
                messages(req));
    }

    /** 窓の秒数を変えれば取り込む範囲も変わること。 */
    @Test
    void windowSecondsChangeTheRange(@TempDir Path tmp) throws Exception {
        String content = line("10:00:00.000", "exec-1", "8 秒前")
                + line("10:00:08.000", "exec-1", "id=" + SID)
                + line("10:00:16.000", "exec-1", "8 秒後");
        assertEquals(1, runWindow(tmp.resolve("a"), content, SID, 5, null, null)
                .requests.get(0).entries.size());
        assertEquals(3, runWindow(tmp.resolve("b"), content, SID, 10, null, null)
                .requests.get(0).entries.size());
    }

    /**
     * 窓どうしが重なるときは 1 つのリクエストに継ぎ足し、同じ行を 2 回出さないこと。
     * 窓より離れた起点は別のリクエストになる。
     */
    @Test
    void windowModeChainsOverlappingAnchors(@TempDir Path tmp) throws Exception {
        String content = line("10:00:10.000", "exec-1", "id=" + SID + " 1 回目")
                + line("10:00:12.000", "exec-1", "間の行")
                + line("10:00:13.000", "exec-1", "id=" + SID + " 2 回目")
                + line("10:00:40.000", "exec-1", "id=" + SID + " 別のリクエスト");
        SessionTrace.Result r = runWindow(tmp, content, SID, 5, null, null);
        assertEquals(3, r.anchorTotal);
        assertEquals(2, r.requests.size());
        assertEquals(3, r.requests.get(0).entries.size());
        assertEquals(2, r.requests.get(0).anchorIds.size());
        assertEquals(1, r.requests.get(1).entries.size());
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Request req : r.requests) {
            for (EntryRow e : req.entries) {
                assertTrue(seen.add(e.id), "重複した行: " + e.message);
            }
        }
    }

    /** 継ぎ足しで増えた行に除外の語が出たら、そのリクエストを落とし直すこと。 */
    @Test
    void windowModeReappliesFilterAfterChaining(@TempDir Path tmp) throws Exception {
        // 1 つ目の窓（5〜15 秒）には除外の語が無く、2 つ目の起点で窓が 19 秒まで伸びてから
        // 17 秒の行が入る。継ぎ足しのあとに判定し直さないと、落とせないまま残る。
        String content = line("10:00:10.000", "exec-1", "id=" + SID + " 1 回目")
                + line("10:00:14.000", "exec-1", "id=" + SID + " 2 回目")
                + line("10:00:17.000", "exec-1", "除外したい語 NG");
        SessionTrace.Result kept = runWindow(tmp.resolve("a"), content, SID, 5, null, null);
        assertEquals(1, kept.requests.size());
        assertEquals(3, kept.requests.get(0).entries.size());

        SessionTrace.Result dropped =
                runWindow(tmp.resolve("b"), content, SID, 5, null, "除外したい語");
        assertEquals(0, dropped.requests.size());
        assertEquals(1, dropped.filteredOut);
    }

    /** 継ぎ足しで増えた行に「含む」の語が出たら、落としたリクエストを拾い直すこと。 */
    @Test
    void windowModeRecoversRequestWhenContainsAppears(@TempDir Path tmp) throws Exception {
        // 1 つ目の窓には無く、継ぎ足しで入ってくる行にだけ「含む」の語がある
        String content = line("10:00:10.000", "exec-1", "id=" + SID + " 1 回目")
                + line("10:00:14.000", "exec-1", "id=" + SID + " 2 回目")
                + line("10:00:17.000", "exec-1", "あとから出る語 OK");
        SessionTrace.Result r = runWindow(tmp, content, SID, 5, "あとから出る語", null);
        assertEquals(1, r.requests.size());
        assertEquals(0, r.filteredOut);
        assertEquals(3, r.requests.get(0).entries.size());
    }

    /** 時間窓モードでも、別ファイル・別スレッドの行は混ざらないこと。 */
    @Test
    void windowModeStaysWithinFileAndThread(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp);
        Path a = writeLog(tmp, "app1.log",
                line("10:00:10.000", "exec-1", "id=" + SID)
                        + line("10:00:11.000", "exec-1", "同じスレッド"));
        Path b = writeLog(tmp, "app2.log",
                line("10:00:10.500", "exec-1", "別インスタンスの同名スレッド"));
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, java.util.Arrays.asList(a, b), null, false,
                    LogFormat.DEFAULT);
            SessionTrace.Result r = SessionTrace.byTimeWindow(SID, 5).run(conn);
            assertEquals(1, r.requests.size());
            assertEquals(2, r.requests.get(0).entries.size());
            for (EntryRow e : r.requests.get(0).entries) {
                assertTrue(e.source.endsWith("app1.log"), e.source);
            }
        }
    }

    /**
     * ID が続けて出るあいだは 1 つのリクエストにまとめ、窓を右へ伸ばすこと。
     * 最後の ID から窓のぶんだけ後ろの行も入る。
     */
    @Test
    void windowModeExtendsWhileAnchorsContinue(@TempDir Path tmp) throws Exception {
        String content = line("10:00:10.000", "exec-1", "id=" + SID + " 1 つ目")
                + line("10:00:14.000", "exec-1", "id=" + SID + " 2 つ目")
                + line("10:00:18.000", "exec-1", "2 つ目から 4 秒後の行")
                + line("10:00:30.000", "exec-1", "ずっと後の行");
        SessionTrace.Result r = runWindow(tmp, content, SID, 5, null, null);
        assertEquals(1, r.requests.size());
        Request req = r.requests.get(0);
        assertEquals(2, req.anchorIds.size());
        // 1 つ目の窓（〜15 秒）だけなら 18 秒の行は入らない。2 つ目の窓（〜19 秒）まで伸ばす
        assertEquals(java.util.Arrays.asList("id=" + SID + " 1 つ目", "id=" + SID + " 2 つ目",
                "2 つ目から 4 秒後の行"), messages(req));
    }

    /** 窓が重なる別のリクエストでも、同じ行を 2 度返さないこと。 */
    @Test
    void windowModeDoesNotRepeatRowsWhenWindowsOverlap(@TempDir Path tmp) throws Exception {
        String content = line("10:00:10.000", "exec-1", "id=" + SID + " 1 回目")
                + line("10:00:14.000", "exec-1", "重なりの中にある行")
                + line("10:00:18.000", "exec-1", "id=" + SID + " 2 回目");
        // 窓 5 秒: 1 回目の窓は 5〜15 秒、2 回目の窓は 13〜23 秒で 13〜15 秒が重なる。
        // 起点どうしは 8 秒離れていてまとまらないが、14 秒の行は 1 回目にだけ入る。
        SessionTrace.Result r = runWindow(tmp, content, SID, 5, null, null);
        assertEquals(2, r.requests.size());
        assertEquals(2, r.requests.get(0).entries.size());
        assertEquals(1, r.requests.get(1).entries.size());
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Request req : r.requests) {
            for (EntryRow e : req.entries) {
                assertTrue(seen.add(e.id), "重複した行: " + e.message);
            }
        }
    }

    /** 時間窓モードでも 1 リクエストあたりの行数の上限で打ち切ること。 */
    @Test
    void windowModeTruncatesAtRowLimit(@TempDir Path tmp) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(line("10:00:00.000", "exec-1", "id=" + SID));
        for (int i = 0; i < SessionTrace.MAX_ROWS_PER_REQUEST + 200; i++) {
            sb.append(line(String.format("10:00:%02d.%03d", i / 1000, i % 1000), "exec-1",
                    "行 " + i));
        }
        Request req = runWindow(tmp, sb.toString(), SID, 60, null, null).requests.get(0);
        assertTrue(req.truncated);
        assertEquals(SessionTrace.MAX_ROWS_PER_REQUEST, req.entries.size());
    }

    /**
     * 行数の上限で切った後ろに ID があっても、窓の時間内なら同じリクエストとして扱うこと
     * （切った行は返さないので、行の範囲では合流を判定できない）。
     */
    @Test
    void windowModeMergesAnchorAfterRowLimit(@TempDir Path tmp) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(line("10:00:00.000", "exec-1", "id=" + SID + " 1 つ目"));
        for (int i = 0; i < SessionTrace.MAX_ROWS_PER_REQUEST + 200; i++) {
            sb.append(line(String.format("10:00:%02d.%03d", 1 + i / 1000, i % 1000), "exec-1",
                    "行 " + i));
        }
        sb.append(line("10:00:05.000", "exec-1", "id=" + SID + " 2 つ目"));
        SessionTrace.Result r = runWindow(tmp, sb.toString(), SID, 60, null, null);
        assertEquals(2, r.anchorTotal);
        assertEquals(1, r.requests.size()); // 切った後ろの起点で新しいリクエストを作らない
        Request req = r.requests.get(0);
        assertTrue(req.truncated);
        assertEquals(2, req.anchorIds.size());
        assertEquals(SessionTrace.MAX_ROWS_PER_REQUEST, req.entries.size());
    }

    /**
     * 起点より前が混んでいて件数上限に達しても、起点の行は必ず残ること。
     * 窓の左端から順に詰めると、起点に届く前に上限へ達して ID の行が消えてしまう。
     */
    @Test
    void windowModeKeepsAnchorWhenEarlierRowsOverflow(@TempDir Path tmp) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SessionTrace.MAX_ROWS_PER_REQUEST + 200; i++) {
            sb.append(line(String.format("10:00:%02d.%03d", i / 1000, i % 1000), "exec-1",
                    "起点より前の行 " + i));
        }
        sb.append(line("10:00:05.000", "exec-1", "id=" + SID));
        Request req = runWindow(tmp, sb.toString(), SID, 60, null, null).requests.get(0);
        assertTrue(req.truncated);
        assertEquals(SessionTrace.MAX_ROWS_PER_REQUEST / 2 + 1, req.entries.size());
        assertEquals("id=" + SID, req.entries.get(req.entries.size() - 1).message);
        assertEquals(1, req.anchorIds.size());
        assertTrue(req.anchorIds.contains(req.entries.get(req.entries.size() - 1).id));
    }

    /** 起点の前後どちらも混んでいるとき、前側は上限の半分までにして後ろ側の余地を残すこと。 */
    @Test
    void windowModeSplitsRowLimitAroundAnchor(@TempDir Path tmp) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 800; i++) {
            sb.append(line(String.format("10:00:%02d.%03d", i / 1000, i % 1000), "exec-1",
                    "前 " + i));
        }
        sb.append(line("10:00:05.000", "exec-1", "id=" + SID));
        for (int i = 0; i < 800; i++) {
            sb.append(line(String.format("10:00:%02d.%03d", 6 + i / 1000, i % 1000), "exec-1",
                    "後 " + i));
        }
        Request req = runWindow(tmp, sb.toString(), SID, 60, null, null).requests.get(0);
        assertEquals(SessionTrace.MAX_ROWS_PER_REQUEST, req.entries.size());
        assertEquals(SessionTrace.MAX_ROWS_PER_REQUEST / 2, indexOfAnchor(req));
        assertTrue(messages(req).get(0).startsWith("前 "));
        assertTrue(messages(req).get(req.entries.size() - 1).startsWith("後 "));
    }

    private static int indexOfAnchor(Request req) {
        for (int i = 0; i < req.entries.size(); i++) {
            if (req.anchorIds.contains(req.entries.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 起点と同じミリ秒に前後の行があっても、前側で入れた行を後ろ側で入れ直さないこと。
     * （前側と後ろ側で 2 回に分けて取り込むため、同じ時刻の行が重なりうる）
     */
    @Test
    void windowModeDoesNotRepeatRowsOnTheSameMillisecond(@TempDir Path tmp) throws Exception {
        String content = line("10:00:10.000", "exec-1", "同じ時刻の前の行")
                + line("10:00:10.000", "exec-1", "id=" + SID)
                + line("10:00:10.000", "exec-1", "同じ時刻の後の行");
        Request req = runWindow(tmp, content, SID, 5, null, null).requests.get(0);
        assertEquals(java.util.Arrays.asList("同じ時刻の前の行", "id=" + SID, "同じ時刻の後の行"),
                messages(req));
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (EntryRow e : req.entries) {
            assertTrue(seen.add(e.id), "重複した行: " + e.message);
        }
    }

    /**
     * 起点の照合はバイト列で行うが、日本語など複数バイトの識別子でも
     * 文字列として照合したときと同じ結果になること。
     */
    @Test
    void findsAnchorWithMultibyteId(@TempDir Path tmp) throws Exception {
        String id = "セッション−あいうえお";
        String content = line("10:00:00.000", "exec-1", "リクエスト開始 GET /a")
                + line("10:00:00.010", "exec-1", "id=" + id + " を処理します")
                + line("10:00:00.020", "exec-1", "id=セッション−かきくけこ は別物")
                + line("10:00:00.030", "exec-1", "リクエスト終了 status=200");
        Path log = writeLog(tmp, "app.log", content);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormat.DEFAULT);
            SessionTrace.Result r = new SessionTrace(id, QueryFilter.compileRegex(START),
                    QueryFilter.compileRegex(END), 10).run(conn);
            assertEquals(1, r.anchorTotal);
            assertEquals(1, r.requests.size());
            assertEquals(4, r.requests.get(0).entries.size());
        }
    }

    /** 読み出しの窓（64 KiB）より大きいエントリでも、識別子を見つけられること。 */
    @Test
    void findsAnchorInEntryLargerThanReadWindow(@TempDir Path tmp) throws Exception {
        StringBuilder stack = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            stack.append("\tat com.example.Deep").append(i).append(".run(Deep.java:").append(i)
                    .append(")\n");
        }
        String content = line("10:00:00.000", "exec-1", "リクエスト開始 GET /a")
                + line("10:00:00.010", "exec-1", "処理に失敗しました")
                + "java.lang.RuntimeException: session=" + SID + "\n" + stack
                + line("10:00:00.020", "exec-1", "リクエスト終了 status=500");
        Path log = writeLog(tmp, "app.log", content);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormat.DEFAULT);
            EntryRow big = LogIndex.findEntry(conn, PathUtil.normalizePath(log), 2);
            assertTrue(big.endByteOffset - big.byteOffset > 65536,
                    "窓より大きいエントリであること");
            SessionTrace.Result r = trace(SID, 10).run(conn);
            assertEquals(1, r.anchorTotal);
            assertEquals(3, r.requests.get(0).entries.size());
        }
    }

    /**
     * ログファイルを読めなくなったら、黙って結果を欠けさせずエラーにすること
     * （起点探しと、絞り込みの判定のどちらも）。
     */
    @Test
    void readFailureIsReportedNotSilentlyIgnored(@TempDir Path tmp) throws Exception {
        String content = line("10:00:00.000", "exec-1", "リクエスト開始 GET /a")
                + line("10:00:00.010", "exec-1", "id=" + SID)
                + line("10:00:00.020", "exec-1", "リクエスト終了 status=200");
        Path log = writeLog(tmp, "app.log", content);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormat.DEFAULT);
            Files.delete(log); // 索引を作ったあとでログが消える
            assertThrows(java.sql.SQLException.class, () -> trace(SID, 10).run(conn),
                    "起点探しで読めなければエラー");
        }
    }

    /** 時間窓モードの引数の検証。 */
    @Test
    void windowModeRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> SessionTrace.byTimeWindow("", 5));
        assertThrows(IllegalArgumentException.class, () -> SessionTrace.byTimeWindow(SID, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SessionTrace.byTimeWindow(SID, SessionTrace.MAX_WINDOW_SECONDS + 1));
    }

    /** FTS5 で候補を絞っても、全件走査と同じ結果になること。 */
    @Test
    void ftsGivesSameResult(@TempDir Path tmp) throws Exception {
        List<Path> logs = sampleLogs();
        SessionTrace.Result scan = run(tmp.resolve("scan"), logs, false, SID, 10);
        SessionTrace.Result fts = run(tmp.resolve("fts"), logs, true, SID, 10);
        assertEquals(scan.anchorTotal, fts.anchorTotal);
        assertEquals(scan.requests.size(), fts.requests.size());
        for (int i = 0; i < scan.requests.size(); i++) {
            assertEquals(messages(scan.requests.get(i)), messages(fts.requests.get(i)));
            assertEquals(scan.requests.get(i).endReason, fts.requests.get(i).endReason);
        }
    }

    /** 識別子の . を任意の 1 文字として扱わず、大文字小文字も区別すること。 */
    @Test
    void idIsLiteralAndCaseSensitive(@TempDir Path tmp) throws Exception {
        String content = line("10:00:00.000", "exec-1", "id=ABC.node1")
                + line("10:00:01.000", "exec-2", "id=ABCXnode1")
                + line("10:00:02.000", "exec-3", "id=abc.node1");
        SessionTrace.Result r = run(tmp, content, "ABC.node1", 10);
        assertEquals(1, r.anchorTotal);
        assertEquals("exec-1", r.requests.get(0).thread);
    }

    /** FTS5 経由でも、大文字小文字の違う行を起点にしないこと（trigram は区別しないため）。 */
    @Test
    void ftsIdIsCaseSensitive(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                line("10:00:00.000", "exec-1", "id=ABC.node1")
                        + line("10:00:02.000", "exec-3", "id=abc.node1"));
        SessionTrace.Result r = run(tmp, Collections.singletonList(log), true, "ABC.node1", 10);
        assertEquals(1, r.anchorTotal);
    }

    /**
     * おわりが無いリクエストは最大所要時間で打ち切り、それより後の行を取り込まないこと。
     * 所要時間は起点ではなく、はじまりの時刻から数える（起点から数えると 10:15 まで取り込む）。
     */
    @Test
    void cutsAtMaxDuration(@TempDir Path tmp) throws Exception {
        String content = line("10:00:00.000", "exec-1", "リクエスト開始 GET /a")
                + line("10:05:00.000", "exec-1", "id=" + SID)
                + line("10:11:00.000", "exec-1", "はじまりから 11 分後の行");
        SessionTrace.Result shortWindow = run(tmp.resolve("a"), content, SID, 10);
        assertEquals(EndReason.NOT_FOUND, shortWindow.requests.get(0).endReason);
        assertEquals(2, shortWindow.requests.get(0).entries.size());

        SessionTrace.Result longWindow = run(tmp.resolve("b"), content, SID, 20);
        assertEquals(3, longWindow.requests.get(0).entries.size());
    }

    /** はじまりまで遡るときも、最大所要時間より前の行は見ないこと。 */
    @Test
    void backwardSearchIsBoundedByMaxDuration(@TempDir Path tmp) throws Exception {
        String content = line("10:00:00.000", "exec-1", "リクエスト開始 GET /a")
                + line("10:11:00.000", "exec-1", "id=" + SID)
                + line("10:11:00.010", "exec-1", "リクエスト終了 status=200");
        Request r = run(tmp, content, SID, 10).requests.get(0);
        assertFalse(r.startFound);
        assertEquals(EndReason.END, r.endReason);
        assertEquals(2, r.entries.size());
    }

    /**
     * 前のリクエストのおわりより前へは遡らないこと。おわりの後・次のはじまりの前にある行は
     * どのリクエストにも属さない。
     */
    @Test
    void stopsAtPreviousEnd(@TempDir Path tmp) throws Exception {
        String betweenRequests = line("10:00:00.000", "exec-1", "リクエスト開始 GET /a")
                + line("10:00:00.010", "exec-1", "リクエスト終了 status=200")
                + line("10:00:00.020", "exec-1", "id=" + SID)
                + line("10:00:00.030", "exec-1", "リクエスト開始 GET /b")
                + line("10:00:00.040", "exec-1", "リクエスト終了 status=200");
        Request standalone = run(tmp.resolve("a"), betweenRequests, SID, 10).requests.get(0);
        assertEquals(EndReason.STANDALONE, standalone.endReason);
        assertEquals(1, standalone.entries.size());

        // おわりで閉じるなら、前のおわりの直後からをはじまり不明のリクエストとする
        String startMissing = line("10:00:00.000", "exec-1", "リクエスト開始 GET /a")
                + line("10:00:00.010", "exec-1", "リクエスト終了 status=200")
                + line("10:00:00.020", "exec-1", "前段の行")
                + line("10:00:00.030", "exec-1", "id=" + SID)
                + line("10:00:00.040", "exec-1", "リクエスト終了 status=200");
        Request missing = run(tmp.resolve("b"), startMissing, SID, 10).requests.get(0);
        assertFalse(missing.startFound);
        assertEquals(EndReason.END, missing.endReason);
        assertEquals(3, missing.entries.size());
        assertEquals("前段の行", missing.entries.get(0).message);
    }

    /**
     * はじまりとおわりの両方に一致する行は 1 行で完結した前のリクエストであり、
     * 遡ったときに起点のはじまりとして扱わないこと。
     */
    @Test
    void lineMatchingBothIsNotStartWhenSearchingBackward(@TempDir Path tmp) throws Exception {
        String content = line("10:00:00.000", "exec-1", "リクエスト開始 GET /a")
                + line("10:00:00.010", "exec-1", "リクエスト終了 リクエスト開始 GET /b")
                + line("10:00:00.020", "exec-1", "id=" + SID);
        SessionTrace t = new SessionTrace(SID, QueryFilter.compileRegex("リクエスト開始"),
                QueryFilter.compileRegex("リクエスト終了"), 10);
        Path log = writeLog(tmp, "app.log", content);
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormat.DEFAULT);
            Request r = t.run(conn).requests.get(0);
            assertEquals(EndReason.STANDALONE, r.endReason);
        }
    }

    /** 識別子がはじまり・おわりの行そのものにあっても、1 リクエストになること。 */
    @Test
    void anchorOnBoundaryLines(@TempDir Path tmp) throws Exception {
        String content = line("10:00:00.000", "exec-1", "リクエスト開始 GET /a id=" + SID)
                + line("10:00:00.010", "exec-1", "処理中")
                + line("10:00:00.020", "exec-1", "リクエスト終了 id=" + SID);
        SessionTrace.Result r = run(tmp, content, SID, 10);
        assertEquals(2, r.anchorTotal);
        assertEquals(1, r.requests.size());
        Request req = r.requests.get(0);
        assertTrue(req.startFound);
        assertEquals(EndReason.END, req.endReason);
        assertEquals(3, req.entries.size());
        assertEquals(2, req.anchorIds.size());
    }

    /** リクエストは起点の順ではなく、先頭（はじまり）の時刻順に並ぶこと。 */
    @Test
    void requestsAreOrderedByFirstEntry(@TempDir Path tmp) throws Exception {
        String content = line("10:00:00.000", "exec-1", "リクエスト開始 GET /slow")
                + line("10:00:01.000", "exec-2", "リクエスト開始 GET /fast")
                + line("10:00:02.000", "exec-2", "id=" + SID)
                + line("10:00:02.010", "exec-2", "リクエスト終了 status=200")
                + line("10:00:03.000", "exec-1", "id=" + SID)
                + line("10:00:03.010", "exec-1", "リクエスト終了 status=200");
        SessionTrace.Result r = run(tmp, content, SID, 10);
        assertEquals(2, r.requests.size());
        assertEquals("exec-1", r.requests.get(0).thread);
        assertEquals("exec-2", r.requests.get(1).thread);
    }

    /** 同じミリ秒に行が並んでも、ファイル内の行順で範囲を決めること。 */
    @Test
    void sameMillisecondUsesLineOrder(@TempDir Path tmp) throws Exception {
        String content = line("10:00:00.000", "exec-1", "リクエスト終了 前のリクエスト")
                + line("10:00:00.000", "exec-1", "リクエスト開始 GET /a")
                + line("10:00:00.000", "exec-1", "id=" + SID)
                + line("10:00:00.000", "exec-1", "リクエスト終了 status=200")
                + line("10:00:00.000", "exec-1", "リクエスト開始 GET /b");
        Request r = run(tmp, content, SID, 10).requests.get(0);
        assertTrue(r.startFound);
        assertEquals(EndReason.END, r.endReason);
        List<String> m = messages(r);
        assertEquals(3, m.size());
        assertEquals("リクエスト開始 GET /a", m.get(0));
        assertEquals("リクエスト終了 status=200", m.get(2));
    }

    /**
     * はじまり側が上限で溢れても、起点の行とおわりは結果に残ること。はじまり側は上限の半分まで
     * しか使わないので、おわり側を探す余地が残る。上限で打ち切ったときは単独の行にしない。
     */
    @Test
    void keepsAnchorWhenBackwardOverflows(@TempDir Path tmp) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SessionTrace.MAX_ROWS_PER_REQUEST + 500; i++) {
            sb.append(line(String.format("10:00:%02d.%03d", i / 1000, i % 1000), "exec-1",
                    "行 " + i));
        }
        sb.append(line("10:00:05.000", "exec-1", "id=" + SID));
        sb.append(line("10:00:05.010", "exec-1", "リクエスト終了 status=200"));
        Request r = run(tmp, sb.toString(), SID, 10).requests.get(0);
        assertTrue(r.truncated);
        assertFalse(r.startFound);
        assertEquals(EndReason.END, r.endReason);
        assertEquals(SessionTrace.MAX_ROWS_PER_REQUEST / 2 + 2, r.entries.size());
        assertEquals("id=" + SID, r.entries.get(r.entries.size() - 2).message);
    }

    /** 上限で打ち切って境界が見つからなかった場合も、単独の行とはしないこと。 */
    @Test
    void truncatedWithoutBoundariesIsNotStandalone(@TempDir Path tmp) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SessionTrace.MAX_ROWS_PER_REQUEST; i++) {
            sb.append(line(String.format("10:00:%02d.%03d", i / 1000, i % 1000), "exec-1",
                    "行 " + i));
        }
        sb.append(line("10:00:05.000", "exec-1", "id=" + SID));
        Request r = run(tmp, sb.toString(), SID, 10).requests.get(0);
        assertTrue(r.truncated);
        assertEquals(EndReason.NOT_FOUND, r.endReason);
        assertEquals(SessionTrace.MAX_ROWS_PER_REQUEST / 2 + 1, r.entries.size());
    }

    /** おわり側が溢れた場合も上限で打ち切ること。 */
    @Test
    void truncatesForward(@TempDir Path tmp) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(line("10:00:00.000", "exec-1", "リクエスト開始 GET /a"));
        sb.append(line("10:00:00.001", "exec-1", "id=" + SID));
        for (int i = 0; i < SessionTrace.MAX_ROWS_PER_REQUEST + 500; i++) {
            sb.append(line(String.format("10:00:%02d.%03d", 1 + i / 1000, i % 1000), "exec-1",
                    "行 " + i));
        }
        Request r = run(tmp, sb.toString(), SID, 10).requests.get(0);
        assertTrue(r.truncated);
        assertEquals(EndReason.NOT_FOUND, r.endReason);
        assertEquals(SessionTrace.MAX_ROWS_PER_REQUEST, r.entries.size());
    }

    /**
     * おわり側を上限で切ったリクエストの、切った後ろにある起点は同じリクエストに寄せ、
     * 別のリクエストとして行を重複させないこと。
     */
    @Test
    void anchorAfterTailCutJoinsSameRequest(@TempDir Path tmp) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(line("10:00:00.000", "exec-1", "リクエスト開始 GET /a"));
        sb.append(line("10:00:00.001", "exec-1", "id=" + SID));
        for (int i = 0; i < SessionTrace.MAX_ROWS_PER_REQUEST + 300; i++) {
            sb.append(line(String.format("10:00:%02d.%03d", 1 + i / 1000, i % 1000), "exec-1",
                    "行 " + i));
        }
        sb.append(line("10:00:05.000", "exec-1", "id=" + SID + " 後半"));
        sb.append(line("10:00:05.010", "exec-1", "リクエスト終了 status=200"));
        SessionTrace.Result r = run(tmp, sb.toString(), SID, 10);
        assertEquals(2, r.anchorTotal);
        assertEquals(1, r.requests.size());
        Request req = r.requests.get(0);
        assertTrue(req.truncated);
        assertEquals(2, req.anchorIds.size());
    }

    /** 単独の行の範囲にも重ねないこと（遡りはその手前で止まる）。 */
    @Test
    void doesNotOverlapPreviousStandalone(@TempDir Path tmp) throws Exception {
        // 1 つ目の起点は、10 分の窓（〜10:10）におわりが無いので単独の行になる。
        // 2 つ目の起点（10:09）は 10:12 のおわりで閉じるが、遡る窓（9:59〜）に入っている
        // 1 つ目の行を取り込まない。
        String content = line("10:00:00.000", "exec-1", "id=" + SID + " 1")
                + line("10:09:00.000", "exec-1", "id=" + SID + " 2")
                + line("10:12:00.000", "exec-1", "リクエスト終了 status=200");
        SessionTrace.Result r = run(tmp, content, SID, 10);
        assertEquals(2, r.requests.size());
        assertEquals(EndReason.STANDALONE, r.requests.get(0).endReason);
        assertEquals(EndReason.END, r.requests.get(1).endReason);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Request req : r.requests) {
            for (EntryRow e : req.entries) {
                assertTrue(seen.add(e.id), "重複した行: " + e.message);
            }
        }
    }

    /** リクエスト数の上限を超えた起点は数えるだけにすること。 */
    @Test
    void capsRequestCount(@TempDir Path tmp) throws Exception {
        StringBuilder sb = new StringBuilder();
        int n = SessionTrace.MAX_REQUESTS + 1;
        for (int i = 0; i < n; i++) {
            String t = String.format("10:%02d:%02d", i / 60, i % 60);
            sb.append(line(t + ".000", "exec-1", "リクエスト開始 GET /" + i));
            sb.append(line(t + ".001", "exec-1", "id=" + SID));
            sb.append(line(t + ".002", "exec-1", "リクエスト終了 status=200"));
        }
        SessionTrace.Result r = run(tmp, sb.toString(), SID, 10);
        assertEquals(n, r.anchorTotal);
        assertTrue(r.truncated);
        assertEquals(SessionTrace.MAX_REQUESTS, r.requests.size());
        for (Request req : r.requests) {
            assertEquals(3, req.entries.size());
        }
    }

    /**
     * 範囲探索のクエリが時刻索引を並び順どおりに辿ること。
     *
     * <p>{@code USE TEMP B-TREE FOR ORDER BY}（全体の並べ替え）になると、条件に合う行を
     * 時間窓ぶんすべて集めてからでないと 1 行目が返らず、境界での打ち切りが効かない
     * （files と JOIN していたときに実際に起きた）。同じ時刻の中だけを並べ替える
     * {@code LAST TERM OF ORDER BY} は、打ち切りを妨げないので許容する。
     */
    @Test
    void rangeQueriesWalkTimeIndexInOrder(@TempDir Path tmp) throws Exception {
        Path log = writeLog(tmp, "app.log",
                line("10:00:00.000", "exec-1", "リクエスト開始 GET /a")
                        + line("10:00:00.010", "exec-1", "id=" + SID));
        try (Connection conn = LogIndex.openOrCreate(tmp)) {
            LogIndex.buildIndex(conn, Collections.singletonList(log), null, false,
                    LogFormat.DEFAULT);
            for (String sql : new String[] {SessionTrace.BACKWARD_SQL, SessionTrace.FORWARD_SQL}) {
                StringBuilder plan = new StringBuilder();
                try (PreparedStatement ps = conn.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
                    ps.setLong(1, 1);
                    ps.setString(2, "exec-1");
                    ps.setLong(3, 0);
                    ps.setLong(4, Long.MAX_VALUE);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            plan.append(rs.getString(4)).append('\n');
                        }
                    }
                }
                String text = plan.toString();
                assertTrue(text.contains("idx_entries_ts"), text);
                assertFalse(text.contains("USE TEMP B-TREE FOR ORDER BY"), text);
            }
        }
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> trace("", 10));
        assertThrows(IllegalArgumentException.class, () -> trace(SID, 0));
        assertThrows(IllegalArgumentException.class,
                () -> trace(SID, SessionTrace.MAX_MAX_MINUTES + 1));
        // int に丸めると 10 になる値。範囲外として拒否すること
        assertThrows(IllegalArgumentException.class, () -> new SessionTrace(SID,
                QueryFilter.compileRegex(START), QueryFilter.compileRegex(END), 4294967306L));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionTrace(SID, null, QueryFilter.compileRegex(END), 10));
    }
}
