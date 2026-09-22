package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 利用者定義書式ファイルの読み出しと、書式の選び方。
 *
 * <ul>
 *   <li>壊れた 1 件でファイル全体を捨てないこと（残りの書式は使えること）</li>
 *   <li>組み込み書式を横取りする id を拒むこと</li>
 *   <li>自動判定で、組み込みと利用者定義の両方が候補になること</li>
 *   <li>組み込み書式のフィンガープリントが従来のままであること（既存索引を壊さない）</li>
 * </ul>
 */
class LogFormatStoreTest {

    /** 画面からもファイルからも、この形のまま書く。 */
    private static final String PATTERN =
            "^(?<ts>\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) "
                    + "(?<level>\\w+) \\((?<thread>[^)]*)\\) (?<logger>\\S+) : (?<message>.*)$";

    private static Path write(Path dir, String text) throws IOException {
        Path file = dir.resolve(LogFormatStore.FILE_NAME);
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String oneFormat(String id) {
        return "[" + id + "]\n"
                + "name = 自社\n"
                + "pattern = " + PATTERN + "\n"
                + "timestamp = yyyy/MM/dd HH:mm:ss.SSS\n";
    }

    /**
     * README が案内する設定例が、実際に読めて実際に解析できること。
     * 例が動かないと、書式を書き始める取っかかりが無くなる。
     */
    @Test
    void exampleFileInDocsWorks() throws IOException {
        Path example = Paths.get("..", "docs", "aplv-log-formats.example.txt");
        assumeTrue(Files.isRegularFile(example), "リポジトリ内で実行したときだけ確かめる");
        List<CustomLogFormat> formats = new LogFormatStore(example).load();
        assertEquals(2, formats.size());
        assertNotNull(parse(formats.get(0),
                "2026/06/15 00:19:11.705 INFO (main) com.example.Hoge : 開始しました"));
        assertNotNull(parse(formats.get(1),
                "15/Jun/2026:00:19:11 [warn] ディスク残量が少なくなっています"));
    }

    private static LogParser.ParsedLine parse(CustomLogFormat f, String line) {
        byte[] b = line.getBytes(StandardCharsets.UTF_8);
        return f.parse(b, b.length);
    }

    /** 画面から登録した書式が、そのまま読み戻せること。 */
    @Test
    void upsertAddsFormat(@TempDir Path tmp) throws IOException {
        LogFormatStore store = new LogFormatStore(tmp.resolve(LogFormatStore.FILE_NAME));
        store.upsert("my-app", "自社", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS");
        List<CustomLogFormat> formats = store.load();
        assertEquals(1, formats.size());
        assertEquals("自社", formats.get(0).displayName());
        assertEquals(PATTERN, formats.get(0).patternText());
        assertNotNull(parse(formats.get(0),
                "2026/06/15 00:19:11.705 INFO (main) com.example.Hoge : 開始"));
    }

    /**
     * 画面に入れた正規表現が、ファイルの上にも<strong>同じ文字のまま</strong>並ぶこと。
     * ここが崩れると「画面ではこう書く、ファイルではこう書く」という説明が要る形式に戻る。
     */
    @Test
    void writesPatternVerbatim(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve(LogFormatStore.FILE_NAME);
        new LogFormatStore(file).upsert("my-app", "自社", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS");
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(text.contains("[my-app]\n"), text);
        assertTrue(text.contains("pattern = " + PATTERN + "\n"), text);
        assertTrue(text.contains("timestamp = yyyy/MM/dd HH:mm:ss.SSS\n"), text);
    }

    /**
     * 手で書いたコメントが、画面から 1 件登録しても消えないこと。
     *
     * <p>このファイルは手編集も想定していて、雛形にも節ごとの説明を書いてある。
     * 画面で 1 件足しただけで説明が消えると、書式は残っても読み方が分からなくなる。
     */
    @Test
    void keepsCommentsWrittenByHand(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, "# このファイルの説明\n"
                + "\n"
                + "[my-app]\n"
                + "name = 自社\n"
                + "pattern = " + PATTERN + "\n"
                + "timestamp = yyyy/MM/dd HH:mm:ss.SSS\n"
                + "\n"
                + "# バッチのログ用\n"
                + "[batch]\n"
                + "name = バッチ\n"
                + "pattern = " + PATTERN + "\n"
                + "timestamp = yyyy/MM/dd HH:mm:ss.SSS\n");
        LogFormatStore store = new LogFormatStore(file);
        store.upsert("added", "足した", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS");

        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(text.contains("# このファイルの説明"), "ファイルの見出しが残る: " + text);
        assertTrue(text.contains("# バッチのログ用"), "節に付けたコメントが残る: " + text);
        assertTrue(text.indexOf("# バッチのログ用") < text.indexOf("[batch]"),
                "コメントは元の節の直前に戻る: " + text);
        assertTrue(text.indexOf("# このファイルの説明") < text.indexOf("[my-app]"),
                "見出しは先頭のまま: " + text);
        assertEquals(3, store.load().size());
    }

    /** 書き戻しを繰り返しても、見出しやコメントが増えていかないこと。 */
    @Test
    void writingTwiceDoesNotDuplicateComments(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve(LogFormatStore.FILE_NAME);
        LogFormatStore store = new LogFormatStore(file);
        store.upsert("a", "A", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS");
        String once = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        store.upsert("b", "B", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS");
        store.delete("b");
        String twice = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertEquals(once, twice, "書き戻しを重ねても同じ内容になる");
    }

    /**
     * 画面から渡された値も、ファイルと同じように前後の空白を取ること。
     *
     * <p>揃えないと、試し打ちでは末尾の空白まで一致条件になるのに、登録して読み直すと
     * 空白が取れた別の正規表現になる。同じ入力で 2 つの経路が違う書式を見てしまう。
     */
    @Test
    void trimsValuesFromScreenLikeTheFileDoes(@TempDir Path tmp) throws IOException {
        LogFormatStore store = new LogFormatStore(tmp.resolve(LogFormatStore.FILE_NAME));
        // 戻り値はファイルを経由しないので、読み出し側の trim に隠れない
        CustomLogFormat added = store.upsert("  my-app  ", "  自社  ", "  " + PATTERN + "  ",
                "  yyyy/MM/dd HH:mm:ss.SSS  ");
        assertEquals(PATTERN, added.patternText(), "画面から渡した値もここで揃える");
        assertEquals("yyyy/MM/dd HH:mm:ss.SSS", added.timestampPattern());
        CustomLogFormat f = store.load().get(0);
        assertEquals("my-app", f.id());
        assertEquals("自社", f.displayName());
        assertEquals(PATTERN, f.patternText());
        assertEquals("yyyy/MM/dd HH:mm:ss.SSS", f.timestampPattern());
    }

    /** 同じ id で登録し直すと置き換わること（増えない）。 */
    @Test
    void upsertReplacesSameId(@TempDir Path tmp) throws IOException {
        LogFormatStore store = new LogFormatStore(tmp.resolve(LogFormatStore.FILE_NAME));
        store.upsert("my-app", "旧", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS");
        store.upsert("my-app", "新", PATTERN, "yyyy/MM/dd HH:mm:ss");
        List<CustomLogFormat> formats = store.load();
        assertEquals(1, formats.size());
        assertEquals("新", formats.get(0).displayName());
        assertEquals("yyyy/MM/dd HH:mm:ss", formats.get(0).timestampPattern());
    }

    /**
     * 書き込んだ直後は、mtime とサイズが前回と同じでも読み直すこと。
     *
     * <p>キャッシュは mtime とサイズで判断している。名前の長さが同じ書式へ同じミリ秒の
     * うちに書き換えると、どちらも変わらないため、書き込み後に捨てないと古い内容を
     * 返し続ける。その状況を、書き込み後に mtime を戻して作る。
     */
    @Test
    void dropsCacheAfterWriting(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve(LogFormatStore.FILE_NAME);
        LogFormatStore store = new LogFormatStore(file);
        store.upsert("my-app", "AB", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS");
        assertEquals("AB", store.load().get(0).displayName());
        FileTime before = Files.getLastModifiedTime(file);
        long sizeBefore = Files.size(file);

        store.upsert("my-app", "CD", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS");
        Files.setLastModifiedTime(file, before);
        assertEquals(sizeBefore, Files.size(file), "名前の長さが同じならファイルの大きさも同じ");

        assertEquals("CD", store.load().get(0).displayName(),
                "書き込み後はキャッシュを捨てて読み直す");
    }

    /** 削除できること。無い id は false。 */
    @Test
    void deletesFormat(@TempDir Path tmp) throws IOException {
        LogFormatStore store = new LogFormatStore(tmp.resolve(LogFormatStore.FILE_NAME));
        store.upsert("my-app", "自社", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS");
        assertFalse(store.delete("unknown"));
        assertTrue(store.delete("my-app"));
        assertTrue(store.load().isEmpty());
    }

    /**
     * 壊れた書式は<strong>保存する前に</strong>弾くこと。
     * 読むときだけ弾くと、画面では登録できたのに一覧に出てこない状態になる。
     */
    @Test
    void upsertRejectsBrokenFormat(@TempDir Path tmp) throws IOException {
        LogFormatStore store = new LogFormatStore(tmp.resolve(LogFormatStore.FILE_NAME));
        // ts グループが無い
        assertThrows(IllegalArgumentException.class,
                () -> store.upsert("x", "x", "^(?<level>\\w+)$", "yyyy"));
        // 正規表現が壊れている
        assertThrows(IllegalArgumentException.class,
                () -> store.upsert("x", "x", "^(?<ts>\\d{4}", "yyyy"));
        // 日時書式が壊れている
        assertThrows(IllegalArgumentException.class,
                () -> store.upsert("x", "x", "^(?<ts>.*)$", "yyyy/QQQQQQQ"));
        // 組み込みと同じ id
        assertThrows(IllegalArgumentException.class,
                () -> store.upsert("logback", "x", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS"));
        assertTrue(store.load().isEmpty(), "弾いた書式はファイルに残らない");
    }

    /**
     * 読み飛ばしたものがあるファイルには書き戻さないこと。
     *
     * <p>書き戻せるのは読めた書式だけなので、壊れた節は行番号ごと消える。消えるのは
     * 「直さなければならないもの」そのもので、直す材料が先に無くなってしまう。
     */
    @Test
    void refusesToWriteBackWhenSomethingWasSkipped(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, oneFormat("ok")
                + "\n[broken]\n"
                + "pattern = ^(?<ts>\\d{4}\n"
                + "timestamp = yyyy\n");
        byte[] before = Files.readAllBytes(file);
        LogFormatStore store = new LogFormatStore(file);
        assertEquals(1, store.load().size(), "読める書式は読める");

        assertThrows(IllegalArgumentException.class,
                () -> store.upsert("added", "足す", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS"));
        assertThrows(IllegalArgumentException.class, () -> store.delete("ok"));
        assertArrayEquals(before, Files.readAllBytes(file), "壊れた節を消さない");
    }

    /** 行だけの読み飛ばしでも書き戻さないこと（知らないキーもファイルから消えるため）。 */
    @Test
    void refusesToWriteBackWhenALineWasSkipped(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, oneFormat("ok") + "unknown-key = 消したくない\n");
        byte[] before = Files.readAllBytes(file);
        LogFormatStore store = new LogFormatStore(file);
        assertThrows(IllegalArgumentException.class,
                () -> store.upsert("added", "足す", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS"));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    /** 上限を超えて登録できないこと。 */
    @Test
    void upsertStopsAtMaxFormats(@TempDir Path tmp) throws IOException {
        LogFormatStore store = new LogFormatStore(tmp.resolve(LogFormatStore.FILE_NAME));
        for (int i = 0; i < LogFormatStore.MAX_FORMATS; i++) {
            store.upsert("f" + i, "f" + i, PATTERN, "yyyy/MM/dd HH:mm:ss.SSS");
        }
        assertThrows(IllegalArgumentException.class,
                () -> store.upsert("over", "over", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS"));
        // 上限ちょうどなら、既存の置き換えはできる
        store.upsert("f0", "置き換え", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS");
        assertEquals(LogFormatStore.MAX_FORMATS, store.load().size());
    }

    /**
     * 上限を超えたファイルには書き戻さないこと。
     * 書き戻すと、読み込めていない書式が黙って消える（保存条件ファイルと同じ守り）。
     */
    @Test
    void refusesToWriteBackWhenOverMax(@TempDir Path tmp) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i <= LogFormatStore.MAX_FORMATS; i++) {
            text.append(oneFormat("f" + i)).append('\n');
        }
        Path file = write(tmp, text.toString());
        byte[] before = Files.readAllBytes(file);
        LogFormatStore store = new LogFormatStore(file);
        assertEquals(LogFormatStore.MAX_FORMATS, store.load().size());
        assertThrows(IllegalArgumentException.class,
                () -> store.upsert("f0", "置き換え", PATTERN, "yyyy/MM/dd HH:mm:ss.SSS"));
        assertThrows(IllegalArgumentException.class, () -> store.delete("f0"));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    /** ファイルが無ければ空。書式ファイルは必須ではない。 */
    @Test
    void returnsEmptyWhenFileMissing(@TempDir Path tmp) throws IOException {
        assertTrue(new LogFormatStore(tmp.resolve(LogFormatStore.FILE_NAME)).load().isEmpty());
    }

    /** 書いた書式を読めること。 */
    @Test
    void loadsFormat(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, oneFormat("my-app"));
        List<CustomLogFormat> formats = new LogFormatStore(file).load();
        assertEquals(1, formats.size());
        assertEquals("my-app", formats.get(0).id());
        assertEquals("自社", formats.get(0).displayName());
    }

    /** 壊れた 1 件は飛ばし、読める書式はそのまま使えること。 */
    @Test
    void skipsBrokenEntry(@TempDir Path tmp) throws IOException {
        // 1 件目は正規表現の括弧が閉じていない
        Path file = write(tmp, "[broken]\n"
                + "pattern = ^(?<ts>\\d{4}\n"
                + "timestamp = yyyy\n"
                + "\n"
                + "[ok]\n"
                + "name = 読める\n"
                + "pattern = " + PATTERN + "\n"
                + "timestamp = yyyy/MM/dd HH:mm:ss.SSS\n");
        List<CustomLogFormat> formats = new LogFormatStore(file).load();
        assertEquals(1, formats.size());
        assertEquals("ok", formats.get(0).id());
    }

    /** 組み込み書式と同じ id は拒むこと（既存の選択肢を黙って置き換えない）。 */
    @Test
    void rejectsIdOfBuiltinFormat(@TempDir Path tmp) throws IOException {
        assertTrue(new LogFormatStore(write(tmp, oneFormat("logback"))).load().isEmpty());
        assertTrue(new LogFormatStore(write(tmp, oneFormat("auto"))).load().isEmpty());
    }

    /** id の重複は後の 1 件を捨てること（どちらが効くか分からない状態にしない）。 */
    @Test
    void rejectsDuplicateId(@TempDir Path tmp) throws IOException {
        String text = "[dup]\n"
                + "name = 1\n"
                + "pattern = " + PATTERN + "\n"
                + "timestamp = yyyy/MM/dd HH:mm:ss.SSS\n"
                + "\n"
                + "[dup]\n"
                + "name = 2\n"
                + "pattern = " + PATTERN + "\n"
                + "timestamp = yyyy/MM/dd HH:mm:ss.SSS\n";
        List<CustomLogFormat> formats = new LogFormatStore(write(tmp, text)).load();
        assertEquals(1, formats.size());
        assertEquals("1", formats.get(0).displayName());
    }

    /** 扱いにくい id は拒むこと（索引の meta やフィンガープリントに載るため）。 */
    @Test
    void rejectsUnsafeId(@TempDir Path tmp) throws IOException {
        for (String id : new String[] {"My-App", "-app", "app id", "app/../x"}) {
            assertTrue(new LogFormatStore(write(tmp, oneFormat(id))).load().isEmpty(), id);
        }
    }

    /**
     * 中身が変わっていなければ読み直さないこと。
     *
     * <p>ここは取り込みだけでなく {@code /api/meta} からも呼ばれ、取り込み中は画面が
     * 1 秒ごとに問い合わせる。毎回読むと、いちばん忙しい時間帯に JSON 解析と
     * 正規表現のコンパイルが走る。
     */
    @Test
    void reusesResultWhileFileIsUnchanged(@TempDir Path tmp) throws IOException {
        LogFormatStore store = new LogFormatStore(write(tmp, oneFormat("my-app")));
        List<CustomLogFormat> first = store.load();
        assertSame(first, store.load(), "同じファイルなら読み直さない");
    }

    /** ファイルを直したら、読み直して新しい定義を返すこと（再起動を要らなくする）。 */
    @Test
    void rereadsAfterFileChanged(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, oneFormat("my-app"));
        LogFormatStore store = new LogFormatStore(file);
        assertEquals("my-app", store.load().get(0).id());

        write(tmp, oneFormat("other-app"));
        // mtime はミリ秒までしか見ないので、確実に differ させる
        Files.setLastModifiedTime(file,
                FileTime.fromMillis(System.currentTimeMillis() + 2000));
        assertEquals("other-app", store.load().get(0).id());
    }

    /**
     * 書き間違いが<strong>その 1 件・その 1 行</strong>に留まること。
     * JSON では 1 か所の構文エラーでファイル全体が読めなくなっていた。
     */
    @Test
    void oneBadLineDoesNotBreakTheRest(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, "イコールも角括弧も無いただの行\n"
                + "これは = があるが [id] より前\n"
                + "pattern = [id] より前に書かれた行\n"
                + oneFormat("my-app")
                + "\n[later]\n"
                + "unknown-key = 知らないキー\n"
                + "pattern = " + PATTERN + "\n"
                + "timestamp = yyyy/MM/dd HH:mm:ss.SSS\n");
        LogFormatStore.Loaded loaded = new LogFormatStore(file).loadDetailed();
        assertEquals(2, loaded.items.size(), "読める書式は読む");
        assertEquals("my-app", loaded.items.get(0).id());
        assertEquals("later", loaded.items.get(1).id());
        assertEquals(0, loaded.skippedFormats, "書式そのものは 2 件とも読めている");
        assertEquals(4, loaded.skippedLines,
                "読み飛ばしたのは行のほう（= が無い行・[id] より前の 2 行・知らないキー）");
    }

    /** コメントと空行は無視し、値の途中の # はただの文字として扱うこと。 */
    @Test
    void ignoresCommentsButKeepsHashInValues(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, "# これはコメント\n"
                + "\n"
                + "[hash]\n"
                + "   # 行頭の空白の後ろの # もコメント\n"
                + "name = 井げた入り\n"
                + "pattern = ^(?<ts>\\d{4}/\\d{2}/\\d{2}) \\#(?<message>.*)$\n"
                + "timestamp = yyyy/MM/dd\n");
        LogFormatStore.Loaded loaded = new LogFormatStore(file).loadDetailed();
        List<CustomLogFormat> formats = loaded.items;
        assertEquals(1, formats.size());
        assertEquals(0, loaded.skippedFormats, "コメントと空行は読み飛ばしに数えない");
        assertEquals(0, loaded.skippedLines, "コメントと空行は読み飛ばしに数えない");
        assertEquals("井げた入り", formats.get(0).displayName());
        assertNotNull(parse(formats.get(0), "2026/06/15 #なにか"),
                "値の途中の # はコメントではない");
    }

    /** 値に {@code =} が入っていても、最初の {@code =} だけで区切ること。 */
    @Test
    void splitsOnFirstEqualsOnly(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, "[eq]\n"
                + "pattern = ^(?<ts>\\d{4}/\\d{2}/\\d{2}) a=(?<message>.*)$\n"
                + "timestamp = yyyy/MM/dd\n");
        List<CustomLogFormat> formats = new LogFormatStore(file).load();
        assertEquals(1, formats.size());
        assertNotNull(parse(formats.get(0), "2026/06/15 a=なにか"));
    }

    /** id で引けること。組み込みが優先される。 */
    @Test
    void resolvesById(@TempDir Path tmp) throws IOException {
        List<CustomLogFormat> customs = new LogFormatStore(write(tmp, oneFormat("my-app"))).load();
        assertTrue(LogFormatSpec.byId("logback", customs).isCustom() == false);
        assertTrue(LogFormatSpec.byId("my-app", customs).isCustom());
        assertNull(LogFormatSpec.byId("unknown", customs));
    }

    /**
     * 組み込み書式のフィンガープリントが id のままであること。
     * ここが変わると、利用者の手元にある既存の索引がすべて作り直しになる。
     */
    @Test
    void builtinFingerprintIsJustId() {
        for (LogFormat f : LogFormat.values()) {
            assertEquals(f.id(), LogFormatSpec.of(f).fingerprint());
        }
    }

    /** 自動判定が利用者定義の書式も候補にすること。 */
    @Test
    void detectPicksCustomFormat(@TempDir Path tmp) throws IOException {
        List<CustomLogFormat> customs = new LogFormatStore(write(tmp, oneFormat("my-app"))).load();
        Path log = tmp.resolve("app.log");
        Files.write(log, ("2026/06/15 00:19:11.705 INFO (main) com.example.Hoge : 開始\n"
                + "2026/06/15 00:19:11.706 INFO (main) com.example.Hoge : 終了\n")
                .getBytes(StandardCharsets.UTF_8));
        LogFormatSpec spec = LogFormatSpec.detect(Collections.singletonList(log), customs);
        assertTrue(spec.isCustom());
        assertEquals("my-app", spec.id());
    }

    /** 組み込み書式のログでは、利用者定義があっても組み込みが選ばれること。 */
    @Test
    void detectPrefersBuiltinForBuiltinLog(@TempDir Path tmp) throws IOException {
        List<CustomLogFormat> customs = new LogFormatStore(write(tmp, oneFormat("my-app"))).load();
        Path log = tmp.resolve("app.log");
        Files.write(log, "2026-06-15 00:19:11.705[main][INFO][com.example.Hoge] - 開始\n"
                .getBytes(StandardCharsets.UTF_8));
        LogFormatSpec spec = LogFormatSpec.detect(Collections.singletonList(log), customs);
        assertEquals("default", spec.id());
    }

    /**
     * 暴走する書式があっても、自動判定そのものは返ってくること。
     * 判定で巻き添えにすると、書式ファイルを直すための画面すら開けなくなる。
     */
    @Test
    void detectSurvivesCatastrophicPattern(@TempDir Path tmp) throws IOException {
        String text = "[bad]\n"
                + "name = 暴走\n"
                + "pattern = ^(?<ts>(a+)+b)$\n"
                + "timestamp = yyyy\n";
        List<CustomLogFormat> customs = new LogFormatStore(write(tmp, text)).load();
        assertEquals(1, customs.size());
        Path log = tmp.resolve("app.log");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            line.append('a');
        }
        line.append("!\n");
        Files.write(log, line.toString().getBytes(StandardCharsets.UTF_8));
        LogFormatSpec spec = LogFormatSpec.detect(Collections.singletonList(log), customs);
        assertNotNull(spec);
        assertEquals("default", spec.id());
    }
}
