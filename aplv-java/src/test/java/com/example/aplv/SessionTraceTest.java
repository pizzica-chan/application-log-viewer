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
        assertThrows(IllegalArgumentException.class,
                () -> new SessionTrace(SID, null, QueryFilter.compileRegex(END), 10));
    }
}
