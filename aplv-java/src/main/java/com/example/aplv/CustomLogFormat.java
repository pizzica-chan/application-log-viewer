package com.example.aplv;

import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 利用者が {@code aplv-log-formats.txt} に定義したログ書式（{@link LogFormatStore}）。
 * 1 行を 1 本の正規表現で解析する。
 *
 * <p>組み込み書式（{@link LogFormat}）はバイト列のまま数バイトを見て弾けるが、この書式は
 * 行を UTF-8 デコードしてから正規表現にかけるため重い。100 万行あたりの解析時間は
 * 継続行なしで 343ms → 817ms、継続行あり（半分がスタックトレース）で 181ms → 469ms
 * （Windows 11 / JDK 8 / -Xmx2g、7 回の中央値。{@code docs/performance-report.md} 11 章）。
 * <strong>この重さを払うのは、この書式を選んだ取り込みだけ</strong>で、組み込み書式の
 * 経路は 1 命令も増えない。
 *
 * <p>取り出すのは名前付きグループで、{@code ts} だけが必須。
 * {@code level} / {@code thread} / {@code logger} / {@code message} は任意で、
 * なければ空文字になる。レベルは組み込み書式と違って語の検査をせず、取り出した値を
 * そのまま採る（{@code NOTICE} でも {@code 警告} でも通る）。ただし絞り込みの比較を
 * 揃えるため、組み込み書式と同じく ASCII の範囲で大文字に直す（{@code info} → {@code INFO}）。
 *
 * <h2>暴走する正規表現への備え</h2>
 * <p>利用者が書いた正規表現は、入れ子の量指定子などで後戻りが爆発しうる。Java の
 * {@link Matcher} は外から止められないため、<strong>行の長さに比例した回数だけ文字を
 * 読ませる</strong>入力を渡し、超えたら {@link BudgetExceededException} で取り込みごと
 * 止める。黙って固まるより、直すべき書式が分かる形で失敗させる。
 */
public final class CustomLogFormat {

    /** 1 行あたりに正規表現へ読ませる文字数の上限（行長に比例）。 */
    static final int BUDGET_PER_CHAR = 64;

    /** 短い行でも最低限これだけは許す。 */
    static final int BUDGET_MIN = 4096;

    /** タイムスタンプの必須グループ名。 */
    static final String GROUP_TS = "ts";

    /** 名前付きグループの開始。名前は英字始まりの英数字（Java の正規表現の規則）。 */
    private static final String GROUP_START = "(?<";

    private final String id;
    private final String name;
    private final String patternText;
    private final String timestampPattern;
    private final Pattern pattern;
    private final DateTimeFormatter timestampFormatter;
    private final boolean hasLevel;
    private final boolean hasThread;
    private final boolean hasLogger;
    private final boolean hasMessage;

    /**
     * @throws IllegalArgumentException 正規表現・日時書式が壊れている、
     *                                  または {@code ts} グループがない場合
     */
    CustomLogFormat(String id, String name, String patternText, String timestampPattern) {
        this.id = id;
        this.name = name;
        this.patternText = patternText;
        this.timestampPattern = timestampPattern;
        try {
            this.pattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("pattern の正規表現が不正です: " + e.getMessage(), e);
        }
        try {
            // 月名（MMM）は英語で書かれる前提。ログの暦の値をそのまま使い、
            // タイムゾーン変換はしない（組み込み書式と同じ方針）。
            this.timestampFormatter =
                    DateTimeFormatter.ofPattern(timestampPattern, Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("timestamp の日時書式が不正です: " + e.getMessage(), e);
        }
        Set<String> groups = groupNames(patternText);
        if (!groups.contains(GROUP_TS)) {
            throw new IllegalArgumentException(
                    "pattern に名前付きグループ (?<" + GROUP_TS + ">…) が必要です");
        }
        this.hasLevel = groups.contains("level");
        this.hasThread = groups.contains("thread");
        this.hasLogger = groups.contains("logger");
        this.hasMessage = groups.contains("message");
    }

    /**
     * パターン文字列から、名前付きグループの名前を集める。
     *
     * <p>Java 8 の {@link Matcher} には名前の一覧を得る公開 API がなく、マッチしていない
     * 状態で {@code group(name)} を呼ぶと、名前の有無にかかわらず
     * {@link IllegalStateException} になる（存在確認より先に投げられる）。そのため
     * パターン文字列を自分で走査する。
     *
     * <p>数えてよいのは<strong>文字クラスの外にある、打ち消されても引用されてもいない
     * {@code (?<name>}</strong> だけ。次はいずれもグループではない。
     * <ul>
     *   <li>{@code [(?<level>a-z]} … 文字クラスの中</li>
     *   <li>{@code \(?<level>} … 括弧が打ち消されている</li>
     *   <li>{@code \Q(?<level>\E} … リテラル引用の中</li>
     * </ul>
     * 取り違えると、存在しない名前を取りにいって解析のたびに落ちる。逆に
     * {@code \Q[\E} の {@code [} を文字クラスの開始と数えてしまうと、それ以降の
     * グループを<strong>すべて見失う</strong>（行は一致するのに項目が黙って空になる）。
     * 文字クラスは {@code [a-z&&[^bc]]} のように入れ子になるので深さで数える。
     * 先読み・後読みの {@code (?<=} {@code (?<!} は、名前が英字始まりでないので外れる。
     *
     * <p><strong>取りこぼす書き方が 1 つ残っている。</strong>{@code (?x)} を付けると
     * {@code #} から行末までが正規表現のコメントになり、Java はその中の
     * {@code (?<name>} をグループとして扱わないが、ここでは数えてしまう。
     * ここまで合わせるには正規表現の構文解析をもう 1 つ持つことになるので、
     * 代わりに {@link #parse} がどの書式で失敗したかを示して投げる。
     */
    private static Set<String> groupNames(String pattern) {
        Set<String> names = new LinkedHashSet<String>();
        int classDepth = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == 'Q') {
                    // \Q…\E の中はすべてただの文字。ここを読み飛ばさないと、引用した
                    // [ を文字クラスの開始と数えてしまい、以降のグループを全部見失う
                    // （行は一致するのに level や message が黙って空になる）。
                    int end = pattern.indexOf("\\E", i + 2);
                    i = end < 0 ? pattern.length() : end + 1;
                    continue;
                }
                i++; // 次の 1 文字は打ち消されている
                continue;
            }
            if (c == '[') {
                classDepth++;
                continue;
            }
            if (c == ']') {
                classDepth = Math.max(0, classDepth - 1);
                continue;
            }
            if (classDepth > 0 || !pattern.startsWith(GROUP_START, i)) {
                continue;
            }
            int end = groupNameEnd(pattern, i + GROUP_START.length());
            if (end < 0) {
                continue; // (?<= や (?<! など、名前ではない
            }
            names.add(pattern.substring(i + GROUP_START.length(), end));
            i = end;
        }
        return names;
    }

    /**
     * {@code (?<} の直後から名前の終わり（{@code >} の位置）を返す。
     * 名前として成立しなければ {@code -1}。
     */
    private static int groupNameEnd(String pattern, int start) {
        if (start >= pattern.length() || !isLetter(pattern.charAt(start))) {
            return -1;
        }
        for (int i = start + 1; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '>') {
                return i;
            }
            if (!isLetter(c) && !(c >= '0' && c <= '9')) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    public String id() {
        return id;
    }

    /** 画面表示用の名前。 */
    public String displayName() {
        return name;
    }

    String patternText() {
        return patternText;
    }

    String timestampPattern() {
        return timestampPattern;
    }

    /**
     * 索引のフィンガープリントに入れる文字列。<strong>パターンを変えたら索引を作り直す</strong>
     * ため、id だけでなく中身も含める。含めないと、書式を直したのに古い索引が再利用される。
     */
    String fingerprint() {
        return id + "\npattern:" + patternText + "\nts:" + timestampPattern;
    }

    /**
     * バイト列を 1 ヘッダ行として解析する。ヘッダでなければ {@code null}。
     *
     * @throws BudgetExceededException 正規表現が行長に見合わない量の後戻りをした場合
     */
    public LogParser.ParsedLine parse(byte[] b, int len) {
        return parse(b, len, null);
    }

    /**
     * バイト列を 1 ヘッダ行として解析する。ヘッダでなければ {@code null}。
     *
     * <p><strong>{@code null} には 2 つの意味がある。</strong>正規表現に一致しなかった
     * （＝継続行）のと、一致したが {@code ts} を日時として読めなかったのとでは、
     * 呼び出し側の扱いが変わる。前者は直前のエントリの本文、後者は<strong>直すべき
     * 書式がある読み飛ばし</strong>で、混ぜると日時書式の間違いが画面のどこにも出ない。
     *
     * @param matchedShape {@code null} でなければ、正規表現に一致したかを {@code [0]} に書く。
     *                     取り込みは 1 行ごとにここを通るので、返り値を増やさず使い回しの
     *                     配列へ書いて、1 行あたりの確保を増やさない
     * @throws BudgetExceededException 正規表現が行長に見合わない量の後戻りをした場合
     */
    public LogParser.ParsedLine parse(byte[] b, int len, final boolean[] matchedShape) {
        if (matchedShape != null) {
            matchedShape[0] = false;
        }
        int end = len;
        while (end > 0 && (b[end - 1] == '\n' || b[end - 1] == '\r')) {
            end--;
        }
        if (end == 0) {
            return null;
        }
        final String line = new String(b, 0, end, StandardCharsets.UTF_8);
        return guarded(new Supplier<LogParser.ParsedLine>() {
            @Override
            public LogParser.ParsedLine get() {
                return match(line, matchedShape);
            }
        });
    }

    /**
     * この書式で解析する処理を包み、定義に由来する失敗を {@link FormatFailure} に変える。
     *
     * <p><strong>照合する経路はすべてここを通すこと。</strong>取り込みだけを包んで
     * 試し打ちを素通しにすると、同じ壊れた書式が、取り込みでは「どの書式が原因か」を
     * 示す 400 になり、試し打ちでは原因の分からない 500 になる。<strong>先に触るのは
     * 試し打ちのほう</strong>なので、いちばん親切であるべき経路がいちばん不親切になる。
     */
    private <T> T guarded(Supplier<T> body) {
        try {
            return body.get();
        } catch (FormatFailure e) {
            throw e;
        } catch (RuntimeException e) {
            // グループ名の取り違えなど、この書式の定義に由来する失敗。どの書式かを
            // 添えて投げ直す。素の例外のままだと、取り込みや自動判定が
            // 「原因の分からない失敗」になり、直すべきファイルに辿り着けない。
            throw new FormatFailure("書式 " + id + " で解析に失敗しました: " + e, e);
        } catch (StackOverflowError e) {
            // 入れ子の深い正規表現は照合が再帰でスタックを食い潰す。ここで受け止めないと
            // 取り込みスレッドごと死ぬ。
            throw new FormatFailure("書式 " + id + " の正規表現が深すぎます（入れ子を浅くしてください）", e);
        }
    }

    /** 読ませる文字数に上限を掛けた照合器。 */
    private Matcher matcher(String line) {
        return pattern.matcher(new BoundedCharSequence(id, line, budgetFor(line.length())));
    }

    private LogParser.ParsedLine match(String line, boolean[] matchedShape) {
        Matcher m = matcher(line);
        if (!m.matches()) {
            return null;
        }
        if (matchedShape != null) {
            matchedShape[0] = true;
        }
        String ts = m.group(GROUP_TS);
        if (ts == null) {
            return null;
        }
        long millis = parseTimestamp(ts);
        if (millis == Long.MIN_VALUE) {
            return null;
        }
        return new LogParser.ParsedLine(millis,
                group(m, hasLogger, "logger"),
                group(m, hasLevel, "level").toUpperCase(Locale.ROOT),
                group(m, hasThread, "thread"),
                group(m, hasMessage, "message"));
    }

    /**
     * 正規表現だけを照合して、{@code ts} に取れた文字列を返す。一致しなければ {@code null}。
     *
     * <p>試し打ちで「一致しなかった」のか「一致したが日時を読めなかった」のかを
     * 区別するために使う。直す場所（正規表現か日時書式か）が違うため。
     */
    String matchedTimestamp(final String line) {
        return guarded(new Supplier<String>() {
            @Override
            public String get() {
                Matcher m = matcher(line);
                return m.matches() ? m.group(GROUP_TS) : null;
            }
        });
    }

    /**
     * 正規表現だけを照合して、取り出せた項目を返す。一致しなければ空。
     *
     * <p>日時書式をまだ書いていない段階の試し打ちで使う。日時は文字列のままなので、
     * ここでは解釈しない。
     */
    Map<String, String> matchedGroups(final String line) {
        return guarded(new Supplier<Map<String, String>>() {
            @Override
            public Map<String, String> get() {
                Matcher m = matcher(line);
                if (!m.matches()) {
                    return Collections.emptyMap();
                }
                Map<String, String> values = new LinkedHashMap<String, String>();
                values.put("level", group(m, hasLevel, "level").toUpperCase(Locale.ROOT));
                values.put("thread", group(m, hasThread, "thread"));
                values.put("logger", group(m, hasLogger, "logger"));
                values.put("message", group(m, hasMessage, "message"));
                return values;
            }
        });
    }

    static long budgetFor(int length) {
        return Math.max(BUDGET_MIN, (long) BUDGET_PER_CHAR * length);
    }

    private static String group(Matcher m, boolean present, String name) {
        if (!present) {
            return "";
        }
        String v = m.group(name);
        return v != null ? v : "";
    }

    /**
     * 取り出した文字列を epoch millis へ変換する。組み込み書式と同じく、
     * ログに書かれた暦の値をそのまま使う（タイムゾーン変換をしない）。
     *
     * @return 解析できない場合は {@link Long#MIN_VALUE}
     */
    long parseTimestamp(String text) {
        try {
            TemporalAccessor ta = timestampFormatter.parse(text);
            int year = field(ta, ChronoField.YEAR, Integer.MIN_VALUE);
            int month = field(ta, ChronoField.MONTH_OF_YEAR, Integer.MIN_VALUE);
            int day = field(ta, ChronoField.DAY_OF_MONTH, Integer.MIN_VALUE);
            if (year == Integer.MIN_VALUE || month == Integer.MIN_VALUE
                    || day == Integer.MIN_VALUE) {
                return Long.MIN_VALUE;
            }
            if (wasAdjusted(ta, text)) {
                return Long.MIN_VALUE;
            }
            return TimeUtil.toMillis(year, month, day,
                    field(ta, ChronoField.HOUR_OF_DAY, 0),
                    field(ta, ChronoField.MINUTE_OF_HOUR, 0),
                    field(ta, ChronoField.SECOND_OF_MINUTE, 0),
                    field(ta, ChronoField.MILLI_OF_SECOND, 0));
        } catch (DateTimeException e) {
            return Long.MIN_VALUE;
        } catch (ArithmeticException e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * 解釈の途中で値が寄せられていないか。寄せられていれば<strong>書かれていない日時</strong>に
     * なるので、その行は取り込まない。
     *
     * <p>{@link DateTimeFormatter} の既定（SMART）は、{@code 2026/02/31} を 2 月末へ、
     * {@code 24:00:00} を翌日 0 時へ黙って寄せる。取り込んでしまうと、実際には存在しない
     * 時刻で並び、期間検索の結果がずれる。
     *
     * <p>{@link java.time.format.ResolverStyle#STRICT} に切り替える手もあるが、それだと
     * {@code yyyy}（年号内の年）が使えなくなり（{@code Unsupported field: Year}）、
     * 利用者が自然に書く書式がすべて通らなくなる。
     *
     * <p>そこで<strong>解釈する前の値と、解釈した後の値を比べる</strong>。書き戻した文字列と
     * 比べる方法もあるが、それでは桁の埋め方の違いまで拾ってしまう。{@code yyyy/M/d} は
     * {@code 06} も {@code 6} も読めるのに書き戻しは {@code 6} になるため、ゼロ埋めの
     * ログが 1 行残らず捨てられる。見たいのは暦の値が動いたかどうかだけ。
     */
    private boolean wasAdjusted(TemporalAccessor resolved, String text) {
        TemporalAccessor raw;
        try {
            raw = timestampFormatter.parseUnresolved(text, new ParsePosition(0));
        } catch (DateTimeException e) {
            return false;
        }
        if (raw == null) {
            // 解釈前の値を取れないときは判断しない（解釈そのものは成功している）
            return false;
        }
        for (ChronoField f : ADJUSTABLE_FIELDS) {
            if (raw.isSupported(f) && resolved.isSupported(f)
                    && raw.getLong(f) != resolved.getLong(f)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解釈で寄せられうる項目。年は寄せられないが、{@code yyyy} は解釈前が年号内の年
     * （{@code YEAR_OF_ERA}）なので、そもそも突き合わせの対象にならない。
     *
     * <p>時・分・秒は、いまの Java だと単独では寄せられない（{@code 24:00} は日付も
     * 翌日へ動くので、日の比較で捕まる）。将来の版で挙動が変わっても取りこぼさないよう、
     * 念のため並べてある。
     */
    private static final ChronoField[] ADJUSTABLE_FIELDS = {
        ChronoField.MONTH_OF_YEAR, ChronoField.DAY_OF_MONTH, ChronoField.HOUR_OF_DAY,
        ChronoField.MINUTE_OF_HOUR, ChronoField.SECOND_OF_MINUTE, ChronoField.MILLI_OF_SECOND,
    };

    /**
     * 日時として読めない理由を返す。読めるなら {@code null}。
     * 画面の「この行で試す」で、直す場所を示すために使う。
     */
    String timestampError(String text) {
        TemporalAccessor ta;
        try {
            ta = timestampFormatter.parse(text);
        } catch (DateTimeException e) {
            return "日時書式「" + timestampPattern + "」と形が合っていません";
        }
        if (!ta.isSupported(ChronoField.YEAR) || !ta.isSupported(ChronoField.MONTH_OF_YEAR)
                || !ta.isSupported(ChronoField.DAY_OF_MONTH)) {
            return "日時書式「" + timestampPattern + "」に年月日が揃っていません"
                    + "（日付がないと日をまたいで並べられません）";
        }
        if (wasAdjusted(ta, text)) {
            return "実在しない日時です（" + resolvedText(ta) + "に寄せられます）";
        }
        return null;
    }

    /**
     * 寄せられた先を、その書式が持っている項目だけで言い表す。
     *
     * <p>{@code yyyy/MM/dd} のように時刻を含まない書式では、解釈した結果も時刻を持たない。
     * 決め打ちで時まで読むと、実在しない日を試したときにその場で落ちる。
     *
     * <p>分の確認は、いまの Java では外れない（{@code yyyy/MM/dd HH} のように時だけ
     * 書いても、解釈側が時刻を組み立てるので分・秒まで付いてくる）。時と同じ壊れ方を
     * 繰り返さないよう、念のため残してある。
     */
    private static String resolvedText(TemporalAccessor ta) {
        StringBuilder sb = new StringBuilder();
        sb.append(ta.get(ChronoField.YEAR)).append(" 年 ")
                .append(ta.get(ChronoField.MONTH_OF_YEAR)).append(" 月 ")
                .append(ta.get(ChronoField.DAY_OF_MONTH)).append(" 日");
        if (ta.isSupported(ChronoField.HOUR_OF_DAY)) {
            sb.append(' ').append(ta.get(ChronoField.HOUR_OF_DAY)).append(" 時");
            if (ta.isSupported(ChronoField.MINUTE_OF_HOUR)) {
                sb.append(' ').append(ta.get(ChronoField.MINUTE_OF_HOUR)).append(" 分");
            }
        }
        return sb.toString();
    }

    private static int field(TemporalAccessor ta, ChronoField f, int fallback) {
        return ta.isSupported(f) ? ta.get(f) : fallback;
    }

    /**
     * この書式の定義に由来する失敗。ログの行が一致しないことは失敗ではない（{@code null} を返す）。
     *
     * <p>取り込みと自動判定は<strong>これを捕まえて、どの書式が原因かを示す</strong>。
     * 素の実行時例外が外へ漏れると、利用者は直すべきファイルに辿り着けない。
     */
    public static class FormatFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FormatFailure(String message, Throwable cause) {
            // 発生箇所より「どの書式か」が重要なので、スタックトレースは採らない
            super(message, cause, false, false);
        }
    }

    /** 正規表現に読ませる文字数が上限を超えた。 */
    public static final class BudgetExceededException extends FormatFailure {
        private static final long serialVersionUID = 1L;

        BudgetExceededException(String message) {
            super(message, null);
        }
    }

    /**
     * 正規表現に読ませる文字数を数える入力。上限を超えたら投げる。
     *
     * <p>{@link Matcher} は {@link #charAt} で 1 文字ずつ読むため、後戻りが爆発すると
     * 呼び出し回数が行長に対して不釣り合いに増える。ここで打ち切れば、取り込みが
     * 止まったまま返らない事態を避けられる。{@link #subSequence} は結果の取り出しに
     * 使われるだけなので数えない。
     */
    static final class BoundedCharSequence implements CharSequence {
        private final String formatId;
        private final CharSequence delegate;
        private long remaining;

        BoundedCharSequence(String formatId, CharSequence delegate, long budget) {
            this.formatId = formatId;
            this.delegate = delegate;
            this.remaining = budget;
        }

        @Override
        public char charAt(int index) {
            if (--remaining < 0) {
                // どの書式が原因かを必ず入れる。利用者はこれを手がかりに定義を直す。
                throw new BudgetExceededException("書式 " + formatId + " の正規表現が、長さ "
                        + delegate.length() + " の行に対して打ち切り基準を超えました。"
                        + "後戻りが爆発する書き方になっていないか見直してください");
            }
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
