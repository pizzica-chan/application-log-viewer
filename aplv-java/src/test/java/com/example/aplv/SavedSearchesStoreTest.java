package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.aplv.SavedSearchesStore.SavedSearch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SavedSearchesStore} の単体試験。
 *
 * <p>試験内容:
 * <ul>
 *   <li>正規表現を含む条件の JSON 往復（エスケープ、未完結パターンでも読み出せること）</li>
 *   <li>未知のフィールド・他タブのキーを捨てること</li>
 *   <li>同名・同モードの上書き、削除、件数上限</li>
 *   <li>壊れた JSON や JSON として不正な {@code \d} を読んだときの失敗</li>
 *   <li>項目が 1 件だけ壊れても、読める件はそのまま返すこと</li>
 *   <li>201 件あるファイルは 200 件まで返し、余りはスキップすること</li>
 * </ul>
 *
 * <p>担保すること:
 * <ul>
 *   <li>保存・読み込みで {@link Pattern} をコンパイルせず、破滅的な正規表現でも一覧が取れる</li>
 *   <li>条件値をパスや置換パターンとして解釈しない</li>
 *   <li>既定の保存先が {@link IndexStore#repoRoot()} 直下の {@link SavedSearchesStore#FILE_NAME} であること</li>
 * </ul>
 */
class SavedSearchesStoreTest {

    @TempDir
    Path tmp;

    private SavedSearchesStore store() {
        return new SavedSearchesStore(tmp.resolve(SavedSearchesStore.FILE_NAME));
    }

    /** バックスラッシュや引用符を含む正規表現が、ファイルを経由しても同じ文字列で戻ること。 */
    @Test
    void roundtripRegexLiterals() throws IOException {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("logger", "Hoge\\d+");
        fields.put("grep", "foo\"bar\\n");
        fields.put("message", "<(ERROR|WARN)>");
        SavedSearch saved = store().upsert("正規表現", "search", fields);

        SavedSearchesStore reopened = store();
        List<SavedSearch> items = reopened.list();
        assertEquals(1, items.size());
        SavedSearch loaded = items.get(0);
        assertEquals(saved.id, loaded.id);
        assertEquals("正規表現", loaded.name);
        assertEquals("search", loaded.mode);
        assertTrue(loaded.savedAt.contains("T") && loaded.savedAt.endsWith("Z"), loaded.savedAt);
        assertEquals("Hoge\\d+", loaded.fields.get("logger"));
        assertEquals("foo\"bar\\n", loaded.fields.get("grep"));
        assertEquals("<(ERROR|WARN)>", loaded.fields.get("message"));

        String json = new String(Files.readAllBytes(reopened.file()), StandardCharsets.UTF_8);
        assertTrue(json.contains("Hoge\\\\d+"), json);
        assertTrue(json.contains("<(ERROR|WARN)>"), json);
        assertFalse(json.contains("\\u003c"), json);
    }

    /**
     * 未完結の正規表現も文字列として保存・読み出せること。
     * ここで {@link Pattern#compile(String)} すると失敗するが、ストアはコンパイルしない。
     */
    @Test
    void invalidRegexIsStoredAsPlainString() throws IOException {
        Map<String, String> fields = Collections.singletonMap("logger", "[unterminated");
        store().upsert("途中", "search", fields);
        assertThrows(java.util.regex.PatternSyntaxException.class,
                () -> Pattern.compile("[unterminated"));
        assertEquals("[unterminated", store().list().get(0).fields.get("logger"));
    }

    /** 追跡条件の正規表現も検索と同様に文字列のまま往復すること。 */
    @Test
    void roundtripTraceRegex() throws IOException {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("trace-id", "abc.def");
        fields.put("trace-start", "^リクエスト開始");
        fields.put("trace-end", "^リクエスト終了");
        fields.put("trace-contains", "PaymentException|Timeout");
        store().upsert("決済", "trace", fields);
        SavedSearch loaded = store().list().get(0);
        assertEquals("trace", loaded.mode);
        assertEquals("^リクエスト開始", loaded.fields.get("trace-start"));
        assertEquals("PaymentException|Timeout", loaded.fields.get("trace-contains"));
        assertFalse(loaded.fields.containsKey("logger"));
    }

    /** 検索のキーを追跡へ、追跡のキーを検索へ混ぜても捨てること。未知キーも捨てる。 */
    @Test
    void dropsUnknownAndOtherTabFields() throws IOException {
        Map<String, String> fields = new HashMap<String, String>();
        fields.put("logger", "Hoge");
        fields.put("trace-id", "should-drop");
        fields.put("log-dir", "C:\\\\logs");
        fields.put("__proto__", "x");
        SavedSearch saved = store().upsert("混在", "search", fields);
        assertEquals("Hoge", saved.fields.get("logger"));
        assertFalse(saved.fields.containsKey("trace-id"));
        assertFalse(saved.fields.containsKey("log-dir"));
        assertFalse(saved.fields.containsKey("__proto__"));
    }

    /** 同じ名前でも検索と追跡は別件として保存すること。 */
    @Test
    void sameNameDifferentModeAreDistinct() throws IOException {
        store().upsert("共通", "search", Collections.singletonMap("grep", "a"));
        store().upsert("共通", "trace", Collections.singletonMap("trace-start", "b"));
        assertEquals(2, store().list().size());
    }

    /** 同名・同モードは上書きし、id は維持すること。 */
    @Test
    void upsertOverwritesSameNameAndMode() throws IOException {
        SavedSearch first = store().upsert("上書き", "search",
                Collections.singletonMap("grep", "old"));
        SavedSearch second = store().upsert("上書き", "search",
                Collections.singletonMap("grep", "new"));
        assertEquals(first.id, second.id);
        assertEquals(1, store().list().size());
        assertEquals("new", store().list().get(0).fields.get("grep"));
    }

    /** 削除した id が一覧から消えること。 */
    @Test
    void deleteRemovesItem() throws IOException {
        SavedSearch saved = store().upsert("消す", "search",
                Collections.singletonMap("level", "ERROR"));
        assertTrue(store().delete(saved.id));
        assertTrue(store().list().isEmpty());
        assertFalse(store().delete(saved.id));
    }

    /** ファイルが無いときは空一覧で、例外にしないこと。 */
    @Test
    void missingFileIsEmpty() throws IOException {
        assertTrue(store().list().isEmpty());
    }

    /** JSON として不正な {@code \d}（手編集でありがちなミス）は読み込み失敗になること。 */
    @Test
    void unescapedRegexBackslashIsRejected() throws IOException {
        Path file = tmp.resolve(SavedSearchesStore.FILE_NAME);
        String json = "{\n"
                + "  \"version\": 1,\n"
                + "  \"items\": [\n"
                + "    {\n"
                + "      \"id\": \"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\",\n"
                + "      \"name\": \"手編集\",\n"
                + "      \"savedAt\": \"\",\n"
                + "      \"mode\": \"search\",\n"
                + "      \"fields\": { \"grep\": \"\\d+\" }\n"
                + "    }\n"
                + "  ]\n"
                + "}\n";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        IOException ex = assertThrows(IOException.class, () -> store().list());
        assertTrue(ex.getMessage().contains("JSON"), ex.getMessage());
    }

    /** fields にオブジェクトがある項目は捨て、隣の読める項目はそのまま返すこと。 */
    @Test
    void skipsBrokenItemAndKeepsNeighbors() throws IOException {
        Path file = tmp.resolve(SavedSearchesStore.FILE_NAME);
        String json = "{"
                + "\"version\":1,"
                + "\"items\":["
                + "{\"id\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\","
                + "\"name\":\"残す\",\"savedAt\":\"\",\"mode\":\"search\","
                + "\"fields\":{\"grep\":\"Exception\"}},"
                + "{\"id\":\"bbbbbbbb-cccc-dddd-eeee-ffffffffffff\","
                + "\"name\":\"壊れた\",\"savedAt\":\"\",\"mode\":\"search\","
                + "\"fields\":{\"grep\":{\"pattern\":\".*\"}}},"
                + "\"not-an-object\""
                + "]}";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        List<SavedSearch> items = store().list();
        assertEquals(1, items.size());
        assertEquals("残す", items.get(0).name);
        assertEquals("Exception", items.get(0).fields.get("grep"));
        assertEquals(2, store().load().skipped);
    }

    /** 201 件あるファイルは先頭 200 件だけ返し、余りはスキップすること。 */
    @Test
    void skipsItemsOverMax() throws IOException {
        writeItemsOverMax();
        SavedSearchesStore.LoadResult loaded = store().load();
        assertEquals(SavedSearchesStore.MAX_ITEMS, loaded.items.size());
        assertEquals(1, loaded.skipped);
    }

    /**
     * 上限を超えたファイルには書き戻さないこと。書き戻すと、読み込めていない
     * 201 件目以降が黙って消えてしまう。一覧の読み出しだけは今までどおりできる。
     */
    @Test
    void refusesToWriteBackWhenOverMax() throws IOException {
        Path file = writeItemsOverMax();
        byte[] before = Files.readAllBytes(file);
        SavedSearchesStore s = store();
        assertThrows(IllegalArgumentException.class,
                () -> s.upsert("n0", "search", Collections.<String, String>emptyMap()));
        assertThrows(IllegalArgumentException.class,
                () -> s.delete("aaaaaaaa-bbbb-cccc-dddd-000000000000"));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(SavedSearchesStore.MAX_ITEMS, s.list().size());
    }

    /** 上限を 1 件超える保存ファイルを作る。 */
    private Path writeItemsOverMax() throws IOException {
        Path file = tmp.resolve(SavedSearchesStore.FILE_NAME);
        StringBuilder json = new StringBuilder();
        json.append("{\"version\":1,\"items\":[");
        for (int i = 0; i < SavedSearchesStore.MAX_ITEMS + 1; i++) {
            if (i > 0) {
                json.append(',');
            }
            String id = String.format("aaaaaaaa-bbbb-cccc-dddd-%012x", i);
            json.append("{\"id\":\"").append(id)
                    .append("\",\"name\":\"n").append(i)
                    .append("\",\"savedAt\":\"\",\"mode\":\"search\",\"fields\":{}}");
        }
        json.append("]}");
        Files.write(file, json.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** 空の名前や制御文字を含む名前は保存できないこと。 */
    @Test
    void rejectsUnsafeNames() {
        SavedSearchesStore s = store();
        assertThrows(IllegalArgumentException.class,
                () -> s.upsert("  ", "search", Collections.<String, String>emptyMap()));
        assertThrows(IllegalArgumentException.class,
                () -> s.upsert("a\nb", "search", Collections.<String, String>emptyMap()));
        assertThrows(IllegalArgumentException.class,
                () -> s.upsert("x", "other", Collections.<String, String>emptyMap()));
    }

    /** パス風の id では削除できず、保存ファイルの場所も変わらないこと。 */
    @Test
    void deleteRejectsPathLikeId() throws IOException {
        SavedSearchesStore s = store();
        s.upsert("残す", "search", Collections.singletonMap("grep", "a"));
        assertThrows(IllegalArgumentException.class, () -> s.delete("../etc/passwd"));
        assertTrue(Files.exists(s.file()));
        assertEquals(1, s.list().size());
    }

    /** 既定の保存先がホーム直下の固定名であること。 */
    @Test
    void defaultFileName() {
        Path path = SavedSearchesStore.defaultFile();
        assertEquals(IndexStore.repoRoot().resolve(SavedSearchesStore.FILE_NAME), path);
    }

    /**
     * 置き換え（rename）ができない環境向けの書き込み。Docker で保存ファイルだけを
     * bind mount すると、置き換え先がマウントポイントになって rename が失敗するため、
     * 元のファイルへ直接書くフォールバックを持っている。
     */
    @Test
    void writeInPlaceReplacesContentAndRemovesTemp(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("aplv-saved-searches.json");
        Files.write(file, "{\"version\":1,\"items\":[]}".getBytes(StandardCharsets.UTF_8));
        Path temp = tmp.resolve("aplv-saved-searches.json.tmp");
        Files.write(temp, "捨てられる一時ファイル".getBytes(StandardCharsets.UTF_8));

        SavedSearchesStore store = new SavedSearchesStore(file);
        byte[] bytes = "{\"version\":1,\"items\":[{\"name\":\"書き直した\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        store.writeInPlace(bytes, temp, new IOException("rename できない環境"));

        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertFalse(Files.exists(temp), "一時ファイルは片付ける");
    }
}
