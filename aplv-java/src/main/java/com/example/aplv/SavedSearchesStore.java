package com.example.aplv;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 保存した検索・追跡条件を、ツールホーム直下の JSON ファイルへ読み書きする。
 * 場所は {@link IndexStore#repoRoot()}（{@code APLV_HOME}。未設定時はリポジトリ直下）。
 *
 * <p>条件欄には正規表現が入る。このクラスは値を<strong>不透明な文字列</strong>としてだけ扱い、
 * {@link java.util.regex.Pattern} へのコンパイルや置換・パス解決には使わない。
 * 読み込み時に正規表現エンジンを動かさないことで、手編集された破滅的なパターンで
 * 起動や一覧取得が止まらないようにする。実際のマッチは検索・追跡の実行時だけ行う。
 * 項目が 1 件だけ壊れていても、読める件はそのまま返す。200 件を超えた余りも読み飛ばすが、
 * そのファイルへの保存・削除は断る（書き戻すと読み飛ばした件が消えるため）。
 * ファイル全体の JSON が壊れているときは失敗する。
 *
 * <p>JSON を使う理由は、{@code \} や {@code "} を含む正規表現をエスケープして往復できるため。
 * 手編集するときは {@code \d} を {@code \\d} と書く（JSON の文字列規則）。YAML は
 * 非引用の {@code *} や真偽値の解釈があるので使わない。
 *
 * <p>ファイル形式（{@code version: 1}）:
 * <pre>
 * {
 *   "version": 1,
 *   "items": [
 *     {
 *       "id": "…",
 *       "name": "エラーだけ",
 *       "savedAt": "2026-09-20T00:00:00Z",
 *       "mode": "search",
 *       "fields": { "logger": "Hoge\\d+", "grep": "Exception" }
 *     }
 *   ]
 * }
 * </pre>
 */
public final class SavedSearchesStore {

    public static final String FILE_NAME = "aplv-saved-searches.json";

    static final int MAX_ITEMS = 200;
    static final int MAX_NAME_CHARS = 100;
    static final int MAX_FIELD_CHARS = 4000;
    static final int MAX_FILE_BYTES = 1_000_000;

    private static final int FILE_VERSION = 1;

    /** 検索タブの input/select の id。これ以外のキーは保存も読み込みもしない。 */
    private static final Set<String> SEARCH_FIELD_IDS = unmodifiableSet(
            "level", "logger", "thread", "message", "grep", "source", "page-limit",
            "since-date", "since-time", "until-date", "until-time");

    /** リクエスト追跡タブの input の id。 */
    private static final Set<String> TRACE_FIELD_IDS = unmodifiableSet(
            "trace-mode", "trace-id", "trace-start", "trace-end", "trace-max-minutes",
            "trace-window-secs", "trace-contains", "trace-excludes");

    /**
     * HTML エスケープしない。{@code <(ERROR|WARN)>} のような正規表現を
     * {@code \u003c} にせず、ファイル上でも読めるようにする。
     */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path file;
    private final Object lock = new Object();

    public SavedSearchesStore(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("file");
        }
        this.file = file;
    }

    /** {@link IndexStore#repoRoot()} 直下の {@link #FILE_NAME}。 */
    public static Path defaultFile() {
        return IndexStore.repoRoot().resolve(FILE_NAME);
    }

    public Path file() {
        return file;
    }

    /** JS の Date が確実に読めるよう、小数はミリ秒までに揃える。 */
    static String nowIso() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    public List<SavedSearch> list() throws IOException {
        return load().items;
    }

    public LoadResult load() throws IOException {
        synchronized (lock) {
            LoadResult loaded = readAll();
            return new LoadResult(
                    Collections.unmodifiableList(new ArrayList<SavedSearch>(loaded.items)),
                    loaded.skipped, loaded.overflow);
        }
    }

    /**
     * 同じ {@code name} + {@code mode} があれば上書きし、なければ追加する。
     * 正規表現の妥当性は見ない（文字列として保存するだけ）。
     */
    public SavedSearch upsert(String name, String mode, Map<String, String> fields)
            throws IOException {
        String trimmedName = requireName(name);
        String normalizedMode = normalizeMode(mode);
        Map<String, String> sanitized = sanitizeFields(normalizedMode, fields);
        synchronized (lock) {
            LoadResult loaded = readAll();
            requireWritable(loaded);
            List<SavedSearch> items = new ArrayList<SavedSearch>(loaded.items);
            SavedSearch existing = findByNameAndMode(items, trimmedName, normalizedMode);
            SavedSearch saved;
            if (existing != null) {
                saved = new SavedSearch(existing.id, trimmedName, nowIso(),
                        normalizedMode, sanitized);
                int index = items.indexOf(existing);
                items.set(index, saved);
            } else {
                if (items.size() >= MAX_ITEMS) {
                    throw new IllegalArgumentException(
                            "保存できる条件は " + MAX_ITEMS + " 件までです");
                }
                saved = new SavedSearch(UUID.randomUUID().toString(), trimmedName,
                        nowIso(), normalizedMode, sanitized);
                items.add(saved);
            }
            writeAll(items);
            return saved;
        }
    }

    /** 指定 id を削除する。無ければ {@code false}。 */
    public boolean delete(String id) throws IOException {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id を指定してください");
        }
        if (!isSafeId(id)) {
            throw new IllegalArgumentException("id の形式が不正です");
        }
        synchronized (lock) {
            LoadResult loaded = readAll();
            requireWritable(loaded);
            List<SavedSearch> items = loaded.items;
            boolean removed = false;
            List<SavedSearch> next = new ArrayList<SavedSearch>(items.size());
            for (SavedSearch item : items) {
                if (id.equals(item.id)) {
                    removed = true;
                } else {
                    next.add(item);
                }
            }
            if (removed) {
                writeAll(next);
            }
            return removed;
        }
    }

    /**
     * 上限を超えて読み飛ばした項目があるファイルへは書き戻さない。
     * 書き戻すと、読めていない 201 件目以降が黙って消えてしまうため。
     * 読み出し（一覧）は今までどおりできる。
     */
    private void requireWritable(LoadResult loaded) {
        if (loaded.overflow) {
            throw new IllegalArgumentException("保存ファイルの件数が上限（" + MAX_ITEMS
                    + "）を超えています。このまま保存すると読み込めていない条件が消えるため、"
                    + "保存と削除はできません。" + file + " を直接編集して " + MAX_ITEMS
                    + " 件以下にしてください");
        }
    }

    private LoadResult readAll() throws IOException {
        if (!Files.exists(file)) {
            return LoadResult.EMPTY;
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("保存ファイルが通常ファイルではありません: " + file);
        }
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("保存ファイルが大きすぎます（" + MAX_FILE_BYTES + " バイトまで）");
        }
        if (size == 0) {
            return LoadResult.EMPTY;
        }
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = text.trim();
        if (text.isEmpty()) {
            return LoadResult.EMPTY;
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                throw new IOException("保存ファイルの JSON がオブジェクトではありません");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new IOException("保存ファイルの JSON を解釈できません。"
                    + "正規表現の \\ は JSON では \\\\ と書いてください", e);
        }
        if (root.has("version") && root.get("version").isJsonPrimitive()) {
            int version;
            try {
                version = root.get("version").getAsInt();
            } catch (RuntimeException e) {
                throw new IOException("保存ファイルの version が不正です", e);
            }
            if (version != FILE_VERSION) {
                throw new IOException("未対応の保存ファイル形式です（version " + version + "）");
            }
        }
        if (!root.has("items") || root.get("items").isJsonNull()) {
            return LoadResult.EMPTY;
        }
        if (!root.get("items").isJsonArray()) {
            throw new IOException("保存ファイルの items が配列ではありません");
        }
        JsonArray array = root.get("items").getAsJsonArray();
        List<SavedSearch> items = new ArrayList<SavedSearch>();
        int skipped = 0;
        boolean overflow = false;
        for (JsonElement element : array) {
            if (items.size() >= MAX_ITEMS) {
                skipped += 1;
                overflow = true;
                continue;
            }
            // 壊れた 1 件でファイル全体を捨てない。JSON 自体が壊れているときは
            // ここに来る前に失敗する。次に保存すると、読めなかった件はファイルから消える。
            if (element == null || !element.isJsonObject()) {
                skipped += 1;
                System.err.println("保存ファイルの項目をスキップしました: オブジェクトではありません");
                continue;
            }
            try {
                items.add(parseItem(element.getAsJsonObject()));
            } catch (IOException e) {
                skipped += 1;
                System.err.println("保存ファイルの項目をスキップしました: " + e.getMessage());
            }
        }
        if (overflow) {
            System.err.println("保存ファイルの件数が上限（" + MAX_ITEMS + "）を超えたため、余りをスキップしました");
        }
        return new LoadResult(items, skipped, overflow);
    }

    private SavedSearch parseItem(JsonObject obj) throws IOException {
        try {
            String id = stringField(obj, "id", true);
            if (!isSafeId(id)) {
                throw new IOException("保存ファイルの id が不正です");
            }
            String name = requireName(stringField(obj, "name", true));
            String savedAt = stringField(obj, "savedAt", false);
            if (savedAt == null) {
                savedAt = "";
            }
            String mode = normalizeMode(stringField(obj, "mode", false));
            Map<String, String> fields = Collections.emptyMap();
            if (obj.has("fields") && !obj.get("fields").isJsonNull()) {
                if (!obj.get("fields").isJsonObject()) {
                    throw new IOException("保存ファイルの fields がオブジェクトではありません");
                }
                fields = sanitizeJsonFields(mode, obj.get("fields").getAsJsonObject());
            }
            return new SavedSearch(id, name, savedAt, mode, fields);
        } catch (IllegalArgumentException e) {
            throw new IOException("保存ファイルの項目が不正です: " + e.getMessage(), e);
        }
    }

    private void writeAll(List<SavedSearch> items) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        JsonArray array = new JsonArray();
        for (SavedSearch item : items) {
            array.add(item.toJson());
        }
        root.add("items", array);
        byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("保存内容が大きすぎます（" + MAX_FILE_BYTES + " バイトまで）");
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException e) {
            // 置き換えができない環境。原因は AtomicMoveNotSupportedException とは限らず、
            // Docker で保存ファイルだけを bind mount していると rename(2) が EBUSY、
            // Windows で対象が開かれていればアクセス拒否になる。いずれもここで拾う。
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException e) {
            // ふつうの移動も置き換えなので同じ理由で失敗する。最後は元のファイルへ直接書く
            // （途中で落ちると壊れうるが、ここまで来たら他に手が無い）。
            writeInPlace(bytes, tmp, e);
        }
    }

    /** 置き換えができない環境向けの最後の手段。既存ファイルを truncate して書く。 */
    void writeInPlace(byte[] bytes, Path tmp, IOException moveFailure) throws IOException {
        try {
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.addSuppressed(moveFailure);
            throw e;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 残っても次の保存で上書きされる
            }
        }
    }

    private static SavedSearch findByNameAndMode(List<SavedSearch> items, String name,
            String mode) {
        for (SavedSearch item : items) {
            if (name.equals(item.name) && mode.equals(item.mode)) {
                return item;
            }
        }
        return null;
    }

    static String normalizeMode(String mode) {
        if (mode == null || mode.isEmpty() || "search".equals(mode)) {
            return "search";
        }
        if ("trace".equals(mode)) {
            return "trace";
        }
        throw new IllegalArgumentException("mode は search または trace を指定してください");
    }

    static String requireName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("名前を入力してください");
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("名前を入力してください");
        }
        if (trimmed.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("名前は " + MAX_NAME_CHARS + " 文字までです");
        }
        if (containsUnsafeChars(trimmed)) {
            throw new IllegalArgumentException("名前に使用できない文字が含まれています");
        }
        return trimmed;
    }

    static Map<String, String> sanitizeFields(String mode, Map<String, String> fields) {
        Set<String> allowed = allowedFieldIds(mode);
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (fields == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String key = entry.getKey();
            if (!allowed.contains(key)) {
                continue;
            }
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            checkFieldValue(key, value);
            out.put(key, value);
        }
        return out;
    }

    private static Map<String, String> sanitizeJsonFields(String mode, JsonObject fields)
            throws IOException {
        Set<String> allowed = allowedFieldIds(mode);
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, JsonElement> entry : fields.entrySet()) {
            String key = entry.getKey();
            if (!allowed.contains(key)) {
                continue;
            }
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            // オブジェクトや配列は受け取らない（構造を条件値として解釈しない）。
            // 数値も文字列へは落とさない。UI は常に文字列を送る。
            if (!value.isJsonPrimitive()) {
                throw new IOException("保存ファイルの fields." + key + " が文字列ではありません");
            }
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (!primitive.isString()) {
                throw new IOException("保存ファイルの fields." + key + " が文字列ではありません");
            }
            String text = primitive.getAsString();
            checkFieldValue(key, text);
            out.put(key, text);
        }
        return out;
    }

    static Set<String> allowedFieldIds(String mode) {
        return "trace".equals(mode) ? TRACE_FIELD_IDS : SEARCH_FIELD_IDS;
    }

    private static void checkFieldValue(String key, String value) {
        if (value.length() > MAX_FIELD_CHARS) {
            throw new IllegalArgumentException(
                    key + " は " + MAX_FIELD_CHARS + " 文字までです");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(key + " に使用できない文字が含まれています");
        }
        // 正規表現として正しいかは見ない。コンパイルしない。
    }

    private static String stringField(JsonObject obj, String key, boolean required)
            throws IOException {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            if (required) {
                throw new IOException("保存ファイルの " + key + " がありません");
            }
            return null;
        }
        JsonElement value = obj.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("保存ファイルの " + key + " が文字列ではありません");
        }
        return value.getAsJsonPrimitive().getAsString();
    }

    /**
     * サーバが採番する UUID（ハイフン付き）だけを id として認める。
     * ファイル名やパスに使わないが、想定外の文字列を API に載せるのも避ける。
     */
    static boolean isSafeId(String id) {
        if (id == null || id.length() != 36) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean hyphen = (i == 8 || i == 13 || i == 18 || i == 23);
            if (hyphen) {
                if (c != '-') {
                    return false;
                }
            } else if (!isHexChar(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 制御文字（改行含む）は名前に使わせない。表示や確認ダイアログの崩れを防ぐ。 */
    static boolean containsUnsafeChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> unmodifiableSet(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }

    /** 読み出した一覧と、壊れている・上限超過で飛ばした件数。 */
    public static final class LoadResult {
        static final LoadResult EMPTY =
                new LoadResult(Collections.<SavedSearch>emptyList(), 0, false);

        public final List<SavedSearch> items;
        public final int skipped;
        /** 件数の上限を超えていて、読み込めていない項目があるか。 */
        public final boolean overflow;

        LoadResult(List<SavedSearch> items, int skipped, boolean overflow) {
            this.items = items;
            this.skipped = skipped;
            this.overflow = overflow;
        }
    }

    /** 1 件の保存条件。{@code fields} の値は正規表現エンジンへ渡す前の生文字列。 */
    public static final class SavedSearch {
        public final String id;
        public final String name;
        public final String savedAt;
        public final String mode;
        public final Map<String, String> fields;

        SavedSearch(String id, String name, String savedAt, String mode,
                Map<String, String> fields) {
            this.id = id;
            this.name = name;
            this.savedAt = savedAt;
            this.mode = mode;
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<String, String>(fields));
        }

        JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id);
            obj.addProperty("name", name);
            obj.addProperty("savedAt", savedAt);
            obj.addProperty("mode", mode);
            JsonObject fieldsObj = new JsonObject();
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                fieldsObj.addProperty(entry.getKey(), entry.getValue());
            }
            obj.add("fields", fieldsObj);
            return obj;
        }
    }
}
