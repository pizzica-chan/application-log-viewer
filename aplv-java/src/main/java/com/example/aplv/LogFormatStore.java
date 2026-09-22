package com.example.aplv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 利用者が定義したログ書式を、ツールホーム直下のテキストファイルで読み書きする。
 * 場所は {@link IndexStore#repoRoot()}（{@code APLV_HOME}。未設定時はリポジトリ直下）。
 *
 * <p>画面の「ログ書式の管理」から登録・削除できるほか、<strong>ファイルを直接編集しても
 * よい</strong>。どちらの経路も {@link #create} で同じ検査を通す。
 *
 * <h2>なぜ JSON ではないのか</h2>
 * <p>ここに入るのは<strong>正規表現</strong>で、{@code \} を大量に含む。JSON だと
 * {@code \d} を {@code "\\d"} と書かねばならず、画面に入れた文字列とファイル上の文字列が
 * 食い違う。「画面では {@code \d}、ファイルでは {@code \\d}」という説明を利用者に
 * 強いることになるため、<strong>エスケープの無い行指向の形式</strong>にしている。
 * 値は書いたとおりに読む。
 *
 * <p>ファイルの形:
 * <pre>
 * # 行頭の # はコメント。空行は無視する。
 * [my-app]
 * name = 自社アプリ形式
 * pattern = ^(?&lt;ts&gt;\d{4}/\d{2}/\d{2}) (?&lt;level&gt;\w+) : (?&lt;message&gt;.*)$
 * timestamp = yyyy/MM/dd
 * </pre>
 *
 * <ul>
 *   <li>{@code [id]} で 1 つの書式が始まる</li>
 *   <li>{@code キー = 値} は最初の {@code =} だけで区切る。値に {@code =} があってもよい</li>
 *   <li>値の前後の空白は取り除く（見えない文字が意味を持つのを避けるため）。
 *       正規表現の末尾で空白に当てたいときは、空白 1 文字を表す {@code [ ]} を書けばよい
 *       ―― これはこの形式の決まりではなく、正規表現としてそう解釈される</li>
 *   <li>コメントは<strong>行頭の {@code #} だけ</strong>。値の途中の {@code #} はただの文字</li>
 *   <li>必須は {@code pattern} と {@code timestamp}。{@code name} を省くと id を使う</li>
 * </ul>
 *
 * <p>壊れた書式が 1 件あってもファイル全体は捨てず、読める書式はそのまま使う
 * （捨てた件は標準エラーに行番号つきで理由を出す）。JSON と違って、1 か所の書き間違いが
 * ファイル全体に波及しない。
 */
public final class LogFormatStore {

    public static final String FILE_NAME = "aplv-log-formats.txt";

    static final int MAX_FORMATS = 20;
    static final int MAX_ID_CHARS = 40;
    static final int MAX_NAME_CHARS = 100;
    static final int MAX_PATTERN_CHARS = 4000;
    static final int MAX_TIMESTAMP_CHARS = 100;
    static final int MAX_FILE_BYTES = 1_000_000;

    private static final String KEY_NAME = "name";
    private static final String KEY_PATTERN = "pattern";
    private static final String KEY_TIMESTAMP = "timestamp";

    private final Path file;
    private final Object lock = new Object();
    /** 前回読んだときのファイルの状態。同じなら読み直さない。 */
    private long cachedMtime = -1;
    private long cachedSize = -1;
    private Loaded cached;

    /**
     * 読み出した書式と、読み飛ばしたもの。
     *
     * <p><strong>書式の件数と行の件数は分ける。</strong>画面は「読めなかった書式が N 件」と
     * 出すので、キーの書き間違いのような行単位の読み飛ばしを混ぜると数が合わなくなる。
     */
    public static final class Loaded {
        static final Loaded EMPTY = new Loaded(Collections.<CustomLogFormat>emptyList(),
                0, 0, false, Collections.<String>emptyList(),
                Collections.<String, List<String>>emptyMap());

        public final List<CustomLogFormat> items;
        /** 読めなかった書式の件数（[id] の節ごと）。 */
        public final int skippedFormats;
        /** 書式に属さない・解釈できなかった行の件数。 */
        public final int skippedLines;
        /** 上限を超えていて、読み込めていない書式があるか。 */
        final boolean overflow;
        /** 最初の [id] より前に書かれていたコメント行（ファイルの見出し）。 */
        final List<String> header;
        /** 各 [id] の直前に書かれていたコメント行。書き戻すときに添え直す。 */
        final Map<String, List<String>> comments;

        Loaded(List<CustomLogFormat> items, int skippedFormats, int skippedLines,
                boolean overflow, List<String> header, Map<String, List<String>> comments) {
            this.items = items;
            this.skippedFormats = skippedFormats;
            this.skippedLines = skippedLines;
            this.overflow = overflow;
            this.header = header;
            this.comments = comments;
        }

        /** 何か読み飛ばしたか（書き戻してよいかの判断に使う）。 */
        boolean hasSkipped() {
            return skippedFormats > 0 || skippedLines > 0;
        }
    }

    public LogFormatStore(Path file) {
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

    /**
     * ファイルを読んで書式の一覧を返す。ファイルが無ければ空。
     *
     * <p>ファイルを直してから画面で読み込み直せば、サーバを起動し直さずに新しい書式を
     * 試せる。そのために毎回ファイルの状態を見るが、<strong>mtime とサイズが前回と
     * 同じなら読み直さない</strong>。ここは取り込みだけでなく {@code /api/meta} からも
     * 呼ばれ、取り込み中は画面が 1 秒ごとに問い合わせる。毎回読むと、いちばん忙しい
     * 時間帯に解析と最大 {@link #MAX_FORMATS} 件の正規表現コンパイルが走ってしまう。
     *
     * @throws IOException ファイルが大きすぎる、通常ファイルでない、読めない場合
     */
    public List<CustomLogFormat> load() throws IOException {
        return loadDetailed().items;
    }

    /** 読み飛ばした件数も要るとき（画面に出す）。 */
    public Loaded loadDetailed() throws IOException {
        synchronized (lock) {
            return loadLocked();
        }
    }

    private Loaded loadLocked() throws IOException {
        if (!Files.exists(file)) {
            return remember(-1, -1, Loaded.EMPTY);
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("書式ファイルが通常ファイルではありません: " + file);
        }
        long size = Files.size(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        if (cached != null && size == cachedSize && mtime == cachedMtime) {
            return cached;
        }
        if (size > MAX_FILE_BYTES) {
            throw new IOException("書式ファイルが大きすぎます（" + MAX_FILE_BYTES + " バイトまで）");
        }
        if (size == 0) {
            return remember(mtime, size, Loaded.EMPTY);
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return remember(mtime, size, parse(text));
    }

    /**
     * 本文を解析する。1 件が壊れていても他は読む。
     *
     * <p>読み飛ばす理由には<strong>行番号</strong>を添える。直す場所がファイルの中の
     * どこかを言えないと、利用者は自分の書いたものを見比べるしかなくなる。
     */
    private Loaded parse(String text) {
        List<CustomLogFormat> formats = new ArrayList<CustomLogFormat>();
        Set<String> ids = new LinkedHashSet<String>();
        int skippedFormats = 0;
        int skippedLines = 0;
        boolean overflow = false;

        String id = null;
        int idLine = 0;
        String name = null;
        String pattern = null;
        String timestamp = null;
        // 直前に並んでいたコメント行。次の [id] に属するものとして預かる。
        // ただし最初の [id] より前の塊は、ファイル全体の見出しとして別に持つ
        // （そうしないと、書き戻すたびに見出しが 1 つ目の節の直前へ動いてしまう）。
        List<String> pendingComments = new ArrayList<String>();
        List<String> header = Collections.emptyList();
        boolean firstSectionSeen = false;
        List<String> sectionComments = Collections.emptyList();
        Map<String, List<String>> comments = new LinkedHashMap<String, List<String>>();

        String[] lines = text.split("\r\n|\n|\r", -1);
        // 最後に 1 回余分に回して、集めかけの 1 件を確定させる
        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : null;
            String trimmed = line != null ? line.trim() : "";
            boolean sectionStart =
                    line != null && trimmed.length() >= 2
                            && trimmed.charAt(0) == '[' && trimmed.endsWith("]");

            if (line == null || sectionStart) {
                if (id != null) {
                    if (formats.size() >= MAX_FORMATS) {
                        skippedFormats += 1;
                        overflow = true;
                        warn(idLine, "登録できる書式は " + MAX_FORMATS + " 件までです");
                    } else {
                        try {
                            CustomLogFormat f = create(id, name, pattern, timestamp);
                            if (!ids.add(f.id())) {
                                throw new IllegalArgumentException("id が重複しています: " + f.id());
                            }
                            formats.add(f);
                            if (!sectionComments.isEmpty()) {
                                comments.put(f.id(), sectionComments);
                            }
                        } catch (IllegalArgumentException e) {
                            skippedFormats += 1;
                            warn(idLine, e.getMessage());
                        }
                    }
                }
                id = null;
                name = null;
                pattern = null;
                timestamp = null;
                if (line == null) {
                    break;
                }
                id = trimmed.substring(1, trimmed.length() - 1).trim();
                idLine = i + 1;
                if (firstSectionSeen) {
                    sectionComments = pendingComments;
                } else {
                    header = pendingComments;
                    sectionComments = Collections.emptyList();
                    firstSectionSeen = true;
                }
                pendingComments = new ArrayList<String>();
                continue;
            }

            if (!trimmed.isEmpty() && trimmed.charAt(0) == '#') {
                // いったん預かる。次に来るのが [id] ならその節のもの、キーなら
                // 節の中のコメント（どのキーに付くか決められないので捨てる）。
                pendingComments.add(trimmed);
                continue;
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                skippedLines += 1;
                warn(i + 1, "「キー = 値」の形ではありません: " + trimmed);
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (id == null) {
                skippedLines += 1;
                warn(i + 1, "[id] より前に書かれています: " + key);
                continue;
            }
            // ここまで預かっていたコメントは節の中のものなので捨てる
            pendingComments.clear();
            if (KEY_NAME.equals(key)) {
                name = value;
            } else if (KEY_PATTERN.equals(key)) {
                pattern = value;
            } else if (KEY_TIMESTAMP.equals(key)) {
                timestamp = value;
            } else {
                skippedLines += 1;
                warn(i + 1, "知らないキーです: " + key
                        + "（使えるのは name / pattern / timestamp）");
            }
        }
        if (skippedFormats > 0 || skippedLines > 0) {
            System.err.println("書式ファイルで読み飛ばしました: 書式 " + skippedFormats
                    + " 件 / 行 " + skippedLines + " 件（" + file + "）");
        }
        return new Loaded(Collections.unmodifiableList(formats), skippedFormats, skippedLines,
                overflow, header, comments);
    }

    private void warn(int lineNo, String message) {
        System.err.println("書式ファイル " + lineNo + " 行目を読み飛ばしました: " + message);
    }

    /**
     * 読めた結果を覚える。次に mtime とサイズが同じなら、これをそのまま返す。
     * 読めなかったときは呼ばない（例外で抜けるので、次回はもう一度読みにいく）。
     */
    private Loaded remember(long mtime, long size, Loaded loaded) {
        cachedMtime = mtime;
        cachedSize = size;
        cached = loaded;
        return loaded;
    }

    /**
     * 値を検査して 1 件作る。手で編集したファイルからも、画面からの登録からも
     * <strong>ここを通す</strong>。別々に検査すると、画面では通るのにファイルからは
     * 読めない（またはその逆の）書式ができてしまう。
     *
     * @throws IllegalArgumentException 値が規則に合わない場合
     */
    public static CustomLogFormat create(String id, String name, String pattern,
            String timestamp) {
        // 画面からの登録とファイルからの読み込みで、同じ値が同じ書式になるようにする。
        // ファイル側は「キー = 値」の前後の空白を取るので、画面側もここで揃える
        // （取らないと、試し打ちと取り込みで別の正規表現を見ることになる）。
        id = trimOrNull(id);
        pattern = trimOrNull(pattern);
        timestamp = trimOrNull(timestamp);
        requireValue("id", id, MAX_ID_CHARS);
        if (!isSafeId(id)) {
            throw new IllegalArgumentException(
                    "id は英小文字・数字・ハイフンで、英小文字か数字で始めてください: " + id);
        }
        if (LogFormat.byId(id) != null || "auto".equals(id)) {
            throw new IllegalArgumentException("id が組み込み書式と重なっています: " + id);
        }
        String label = name == null || name.trim().isEmpty() ? id : name.trim();
        requireValue(KEY_NAME, label, MAX_NAME_CHARS);
        requireValue(KEY_PATTERN, pattern, MAX_PATTERN_CHARS);
        requireValue(KEY_TIMESTAMP, timestamp, MAX_TIMESTAMP_CHARS);
        return new CustomLogFormat(id, label, pattern, timestamp);
    }

    private static String trimOrNull(String v) {
        return v == null ? null : v.trim();
    }

    private static void requireValue(String key, String v, int maxChars) {
        String label = label(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException(label + "を入れてください");
        }
        if (v.length() > maxChars) {
            throw new IllegalArgumentException(label + "が長すぎます（" + maxChars + " 文字まで）");
        }
        if (hasControlChar(v)) {
            throw new IllegalArgumentException(label + "に制御文字は使えません");
        }
    }

    /**
     * 画面の項目名とファイルのキー名を両方入れた呼び方。
     * 同じ文言を画面でもファイルの読み飛ばし理由でも使うため、どちらの読み手にも届くようにする。
     */
    private static String label(String key) {
        if (KEY_NAME.equals(key)) {
            return "名前（name）";
        }
        if (KEY_PATTERN.equals(key)) {
            return "正規表現（pattern）";
        }
        if (KEY_TIMESTAMP.equals(key)) {
            return "日時書式（timestamp）";
        }
        return key + " ";
    }

    /**
     * 同じ id があれば置き換え、無ければ追加する。
     *
     * <p>値の検査は {@link #create} が行う。壊れた書式は<strong>保存する前に</strong>弾く
     * （読むときだけ弾くと、画面では登録できたのに一覧に出てこない状態になる）。
     *
     * @throws IllegalArgumentException 値が規則に合わない、または上限に達している場合
     */
    public CustomLogFormat upsert(String id, String name, String pattern, String timestamp)
            throws IOException {
        CustomLogFormat added = create(id, name, pattern, timestamp);
        synchronized (lock) {
            Loaded loaded = loadLocked();
            requireWritable(loaded);
            List<CustomLogFormat> next = new ArrayList<CustomLogFormat>();
            boolean replaced = false;
            for (CustomLogFormat f : loaded.items) {
                if (f.id().equals(added.id())) {
                    next.add(added);
                    replaced = true;
                } else {
                    next.add(f);
                }
            }
            if (!replaced) {
                if (next.size() >= MAX_FORMATS) {
                    throw new IllegalArgumentException(
                            "登録できる書式は " + MAX_FORMATS + " 件までです");
                }
                next.add(added);
            }
            writeAll(next, loaded.header, loaded.comments);
            return added;
        }
    }

    /** 指定 id を消す。無ければ {@code false}。 */
    public boolean delete(String id) throws IOException {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id を指定してください");
        }
        synchronized (lock) {
            Loaded loaded = loadLocked();
            requireWritable(loaded);
            List<CustomLogFormat> next = new ArrayList<CustomLogFormat>(loaded.items.size());
            boolean removed = false;
            for (CustomLogFormat f : loaded.items) {
                if (f.id().equals(id)) {
                    removed = true;
                } else {
                    next.add(f);
                }
            }
            if (removed) {
                writeAll(next, loaded.header, loaded.comments);
            }
            return removed;
        }
    }

    /**
     * <strong>読み飛ばしたものがあるファイルへは書き戻さない。</strong>
     *
     * <p>書き戻せるのは読めた書式だけなので、読み飛ばした節や行はファイルから消える。
     * 消えるのは「直さなければならないもの」そのもので、行番号も一緒に失われる。
     * 上限超過も同じ理由で断る。読み出し（一覧・取り込み）は今までどおりできる。
     */
    private void requireWritable(Loaded loaded) {
        if (loaded.overflow) {
            throw new IllegalArgumentException("書式ファイルの件数が上限（" + MAX_FORMATS
                    + "）を超えています。このまま保存すると読み込めていない書式が消えるため、"
                    + "登録と削除はできません。" + file + " を直接編集して " + MAX_FORMATS
                    + " 件以下にしてください");
        }
        if (loaded.hasSkipped()) {
            throw new IllegalArgumentException("書式ファイルに読めない部分があります"
                    + "（書式 " + loaded.skippedFormats + " 件 / 行 " + loaded.skippedLines
                    + " 件）。このまま保存するとその部分が消えるため、登録と削除はできません。"
                    + file + " を直接編集して直すか、その部分を消してください"
                    + "（理由は起動したターミナルに行番号つきで出ています）");
        }
    }

    /**
     * ファイルを書き換える。
     *
     * <p>各 {@code [id]} の直前に書かれていたコメントは添え直す。手で書いた説明が、
     * 画面から 1 件登録しただけで消えてしまうのを避けるため（このファイルは手編集も
     * 想定している）。節の中のコメントは、どのキーに付くか決められないので残らない。
     *
     * <p>置き換えに失敗する環境（Docker で単一ファイルを bind mount している等）でも
     * 保存できるよう、保存条件ファイルと同じ手順で退避する。
     */
    private void writeAll(List<CustomLogFormat> formats, List<String> header,
            Map<String, List<String>> comments) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (header.isEmpty()) {
            sb.append("# aplv の利用者定義ログ書式\n");
            sb.append("# 画面の「書式の管理」からも編集できます。\n");
            sb.append("# 行頭の # だけがコメントです。\n");
        } else {
            // 元の見出しをそのまま戻す（こちらの見出しは足さない）
            for (String line : header) {
                sb.append(line).append('\n');
            }
        }
        for (CustomLogFormat f : formats) {
            sb.append('\n');
            for (String comment : comments.containsKey(f.id())
                    ? comments.get(f.id()) : Collections.<String>emptyList()) {
                sb.append(comment).append('\n');
            }
            sb.append('[').append(f.id()).append("]\n");
            sb.append(KEY_NAME).append(" = ").append(f.displayName()).append('\n');
            sb.append(KEY_PATTERN).append(" = ").append(f.patternText()).append('\n');
            sb.append(KEY_TIMESTAMP).append(" = ").append(f.timestampPattern()).append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("書式の内容が大きすぎます（" + MAX_FILE_BYTES + " バイトまで）");
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
            // Docker で書式ファイルだけを bind mount していると rename(2) が EBUSY、
            // Windows で対象が開かれていればアクセス拒否になる。いずれもここで拾う。
        } finally {
            // 書いた内容は次の読み出しで拾い直す（mtime が同じ粒度に収まっても取り違えない）
            cached = null;
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException e) {
            // ふつうの移動も置き換えなので同じ理由で失敗する。最後は元のファイルへ直接書く。
            try {
                Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException inPlace) {
                inPlace.addSuppressed(e);
                throw inPlace;
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 残っても次の保存で上書きされる
                }
            }
        }
    }

    /** 索引の meta・フィンガープリント・URL に載るので、扱いやすい文字に限る。 */
    static boolean isSafeId(String id) {
        if (id.isEmpty() || id.length() > MAX_ID_CHARS) {
            return false;
        }
        char first = id.charAt(0);
        if (!isLowerAlnum(first)) {
            return false;
        }
        for (int i = 1; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!isLowerAlnum(c) && c != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowerAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean hasControlChar(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
