package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 利用者定義書式（正規表現）の解析。
 *
 * <ul>
 *   <li>名前付きグループの取り出しと、任意グループが無いときの既定値</li>
 *   <li>時刻の解釈が組み込み書式と一致すること（同じログを別書式で読んでもずれない）</li>
 *   <li>暴走する正規表現を打ち切ること（取り込みが返らなくなるのを防ぐ）</li>
 *   <li>定義そのものが壊れているときに、作る時点で弾くこと</li>
 * </ul>
 */
class CustomLogFormatTest {

    private static final String PATTERN =
            "^(?<ts>\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) (?<level>\\w+) "
                    + "\\((?<thread>[^)]*)\\) (?<logger>\\S+) : (?<message>.*)$";
    private static final String TS = "yyyy/MM/dd HH:mm:ss.SSS";

    private static CustomLogFormat format() {
        return new CustomLogFormat("my-app", "自社アプリ形式", PATTERN, TS);
    }

    private static LogParser.ParsedLine parse(CustomLogFormat f, String line) {
        byte[] b = line.getBytes(StandardCharsets.UTF_8);
        return f.parse(b, b.length);
    }

    /** 名前付きグループをそれぞれ取り出せること。 */
    @Test
    void extractsNamedGroups() {
        LogParser.ParsedLine p = parse(format(),
                "2026/06/15 00:19:11.705 INFO (main) com.example.Hoge : 処理を開始します\n");
        assertNotNull(p);
        assertEquals("INFO", p.level);
        assertEquals("main", p.thread);
        assertEquals("com.example.Hoge", p.logger);
        assertEquals("処理を開始します", p.message);
    }

    /** 行末の CR/LF を含んでいても解析できること（読み出しは改行込みで渡ってくる）。 */
    @Test
    void ignoresTrailingNewlines() {
        assertNotNull(parse(format(),
                "2026/06/15 00:19:11.705 INFO (main) com.example.Hoge : x\r\n"));
    }

    /**
     * 時刻の解釈が組み込み書式と一致すること。
     * ずれると、同じログを書式違いで読んだときに期間検索の結果が変わってしまう。
     */
    @Test
    void timestampMatchesBuiltinFormat() {
        LogParser.ParsedLine custom = parse(format(),
                "2026/06/15 00:19:11.705 INFO (main) com.example.Hoge : x");
        byte[] builtinLine =
                "2026-06-15 00:19:11.705[main][INFO][com.example.Hoge] - x"
                        .getBytes(StandardCharsets.UTF_8);
        LogParser.ParsedLine builtin =
                LogParser.parse(LogFormat.DEFAULT, builtinLine, builtinLine.length);
        assertNotNull(custom);
        assertNotNull(builtin);
        assertEquals(builtin.tsMillis, custom.tsMillis);
    }

    /** 任意グループが無い書式でも使えて、欠けた項目は空文字になること。 */
    @Test
    void optionalGroupsDefaultToEmpty() {
        CustomLogFormat f = new CustomLogFormat("ts-only", "時刻だけ",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) .*$", TS);
        LogParser.ParsedLine p = parse(f, "2026/06/15 00:19:11.705 なにか");
        assertNotNull(p);
        assertEquals("", p.level);
        assertEquals("", p.thread);
        assertEquals("", p.logger);
        assertEquals("", p.message);
    }

    /** レベルは組み込み書式と違って語を検査せず、書いてあるものを採ること。 */
    @Test
    void keepsUnknownLevelWords() {
        LogParser.ParsedLine p = parse(format(),
                "2026/06/15 00:19:11.705 NOTICE (main) com.example.Hoge : x");
        assertNotNull(p);
        assertEquals("NOTICE", p.level);
    }

    /**
     * 画面のガイドが配っている「入れ子の角括弧」用の部品が、実際に効くこと。
     *
     * <p>素直な {@code [^\]]*} は最初の {@code ]} で止まるため、
     * {@code [pool-1[worker-3]]} のようなスレッド名には行ごと一致しない。
     * 入れ子が無い行にも同じ部品が使えることまで確かめる。
     */
    @Test
    void nestedBracketPartMatchesBothShapes() {
        String nested = "^(?<ts>\\d{4}/\\d{2}/\\d{2}) "
                + "\\[(?<thread>(?:[^\\[\\]]|\\[[^\\]]*\\])*)\\] (?<message>.*)$";
        CustomLogFormat f = new CustomLogFormat("nested", "入れ子", nested, "yyyy/MM/dd");
        LogParser.ParsedLine deep = parse(f, "2026/06/15 [pool-1[worker-3]] 開始");
        assertNotNull(deep, "入れ子のある行に一致する");
        assertEquals("pool-1[worker-3]", deep.thread);
        LogParser.ParsedLine flat = parse(f, "2026/06/15 [main] 開始");
        assertNotNull(flat, "入れ子の無い行にも同じ部品が使える");
        assertEquals("main", flat.thread);

        // 素直な部品では、入れ子のある行は行ごと一致しない（部品を分けている理由）
        CustomLogFormat simple = new CustomLogFormat("simple", "素直",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2}) \\[(?<thread>[^\\]]*)\\] (?<message>.*)$",
                "yyyy/MM/dd");
        assertNull(parse(simple, "2026/06/15 [pool-1[worker-3]] 開始"));
    }

    /**
     * ガイドが言う「空白 1 個以上」で、桁を揃えたログを両方向とも読めること。
     *
     * <p>レベルの桁を揃えるログには、後ろが空くもの（左詰め。logback の {@code %-5level}）と
     * 前が空くもの（右詰め。Spring Boot の {@code %5p}）がある。空白 1 個で書くと
     * どちらも一致しない ―― これがいちばん多いつまずきなので、両方を押さえる。
     */
    @Test
    void paddedLevelsNeedOneOrMoreSpaces() {
        String ts = "(?<ts>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})";
        String tsPattern = "yyyy-MM-dd HH:mm:ss";
        // 左詰め（後ろが空く）
        String left = "2026-06-15 00:19:11 INFO  [main] com.example.Hoge - 開始";
        assertNull(parse(new CustomLogFormat("l1", "l1",
                "^" + ts + " (?<level>\\w+) \\[(?<thread>[^\\]]*)\\] (?<message>.*)$",
                tsPattern), left), "空白 1 個では一致しない");
        assertNotNull(parse(new CustomLogFormat("l2", "l2",
                "^" + ts + " (?<level>\\w+) +\\[(?<thread>[^\\]]*)\\] (?<message>.*)$",
                tsPattern), left), "空白 1 個以上なら読める");

        // 右詰め（前が空く）
        String right = "2026-06-15 00:19:11  INFO 12345 --- [main] c.e.Hoge : 開始";
        assertNull(parse(new CustomLogFormat("r1", "r1",
                "^" + ts + " (?<level>\\w+) \\d+ --- \\[(?<thread>[^\\]]*)\\] (?<message>.*)$",
                tsPattern), right), "空白 1 個では一致しない");
        assertNotNull(parse(new CustomLogFormat("r2", "r2",
                "^" + ts + " +(?<level>\\w+) \\d+ --- \\[(?<thread>[^\\]]*)\\] (?<message>.*)$",
                tsPattern), right), "空白 1 個以上なら読める");
    }

    /** 一致しない行は継続行として扱えるよう null を返すこと。 */
    @Test
    void returnsNullForNonMatchingLine() {
        assertNull(parse(format(), "\tat com.example.Hoge.run(Hoge.java:12)"));
        assertNull(parse(format(), ""));
    }

    /** 形は合っていても日時として成立しない行は取り込まないこと。 */
    @Test
    void rejectsImpossibleTimestamp() {
        assertNull(parse(format(),
                "2026/13/45 00:19:11.705 INFO (main) com.example.Hoge : x"));
    }

    /**
     * 実在しない日を<strong>月末に寄せて取り込まない</strong>こと。
     *
     * <p>{@code DateTimeFormatter} の既定（SMART）は {@code 2026/02/31} を 2 月末へ、
     * {@code 24:00:00} を翌日 0 時へ黙って寄せる。取り込むと、実際には存在しない時刻で
     * 並んで期間検索の結果がずれる。月が 13 のような範囲外は元から拒否されるので、
     * その試験だけではこの経路に気づけない。
     */
    @Test
    void rejectsDatesThatWereRoundedToTheEndOfMonth() {
        assertNull(parse(format(), "2026/02/31 00:19:11.705 INFO (main) com.example.Hoge : x"),
                "2 月 31 日は 2 月末に寄せずに捨てる");
        assertNull(parse(format(), "2026/04/31 00:19:11.705 INFO (main) com.example.Hoge : x"));
        assertNotNull(parse(format(), "2024/02/29 00:19:11.705 INFO (main) com.example.Hoge : x"),
                "閏年の 2 月 29 日は実在するので取り込む");
        assertNotNull(parse(format(), "2026/02/28 00:19:11.705 INFO (main) com.example.Hoge : x"));
    }

    /**
     * 日時書式の桁幅が 1 文字（{@code M} や {@code H}）でも、ゼロ埋めされたログを
     * 取り込めること。
     *
     * <p>Java は {@code M} で {@code 06} も {@code 6} も読めるが、書き戻すと {@code 6}
     * になる。書き戻した文字列と比べる作りにすると、実在する日付なのに<strong>1 行残らず
     * 捨てる</strong>ことになる。見るのは暦の値が動いたかどうかだけ。
     */
    @Test
    void acceptsZeroPaddedValuesWithSingleWidthPattern() {
        CustomLogFormat f = new CustomLogFormat("narrow", "桁幅 1",
                "^(?<ts>[^ ]+ [^ ]+) (?<message>.*)$", "yyyy/M/d H:m:s");
        assertNotNull(parse(f, "2026/06/15 00:19:11 ゼロ埋め"), "ゼロ埋めでも読む");
        assertNotNull(parse(f, "2026/6/15 0:19:11 ゼロ埋めなし"), "ゼロ埋めなしでも読む");
        // 桁幅が 1 文字でも、実在しない日は捨てる
        assertNull(parse(f, "2026/02/31 00:19:11 実在しない"));
    }

    /** 24 時は翌日へ寄せずに捨てること（同じく書かれていない時刻になるため）。 */
    @Test
    void rejectsHour24() {
        assertNull(parse(format(), "2026/06/15 24:00:00.000 INFO (main) com.example.Hoge : x"));
        assertNotNull(parse(format(), "2026/06/15 23:59:59.999 INFO (main) com.example.Hoge : x"));
    }

    /**
     * 日時書式を書く前でも、正規表現だけで取り出せた項目を見られること。
     *
     * <p>利用者はふつう、ログの行を貼って正規表現を組み立て、当たることを確かめてから
     * 日時書式を書く。試し打ちで日時書式を必須にすると、その最初の一歩が止まる。
     */
    @Test
    void matchedGroupsWorkBeforeTimestampIsWritten() {
        CustomLogFormat f = format();
        String line = "2026/06/15 00:19:11.705 INFO (main) com.example.Hoge : 開始しました";
        assertEquals("2026/06/15 00:19:11.705", f.matchedTimestamp(line));
        Map<String, String> groups = f.matchedGroups(line);
        assertEquals("INFO", groups.get("level"));
        assertEquals("main", groups.get("thread"));
        assertEquals("com.example.Hoge", groups.get("logger"));
        assertEquals("開始しました", groups.get("message"));
        assertTrue(f.matchedGroups("これは一致しない").isEmpty());
    }

    /**
     * 画面のガイドがボタンで配る日時書式が、そこに添えてある例をそのまま読めること。
     *
     * <p>ボタンは「押せば動く」前提で置いてある。書式と例がずれていると、利用者は
     * 自分の書き方を疑って延々と直すことになる。
     */
    @Test
    void timestampPartsFromGuideParseTheirSamples() {
        String[][] cases = {
            {"yyyy-MM-dd HH:mm:ss.SSS", "2026-06-15 00:19:11.705"},
            {"yyyy/MM/dd HH:mm:ss.SSS", "2026/06/15 00:19:11.705"},
            {"yyyy-MM-dd'T'HH:mm:ss.SSS", "2026-06-15T00:19:11.705"},
            {"yyyy-MM-dd HH:mm:ss", "2026-06-15 00:19:11"},
            {"yyyy/MM/dd HH:mm:ss", "2026/06/15 00:19:11"},
            {"dd/MMM/yyyy:HH:mm:ss", "15/Jun/2026:00:19:11"},
            {"yyyyMMdd HHmmss", "20260615 001911"},
            {"yyyy/M/d H:m:s", "2026/6/15 0:19:11"},
        };
        for (String[] c : cases) {
            CustomLogFormat f = new CustomLogFormat("btn", "btn", "^(?<ts>.+)$", c[0]);
            assertNotNull(parse(f, c[1]), c[0] + " で「" + c[1] + "」を読めること");
        }
    }

    /** 試し打ちが、読めない理由を直す場所ごとに言い分けること。 */
    @Test
    void timestampErrorTellsWhatToFix() {
        CustomLogFormat f = format();
        assertNull(f.timestampError("2026/06/15 00:19:11.705"), "読めるときは理由なし");
        assertTrue(f.timestampError("2026-06-15 00:19:11.705").contains("形が合っていません"));
        assertTrue(f.timestampError("2026/02/31 00:19:11.705").contains("実在しない"));

        CustomLogFormat noDate = new CustomLogFormat("time-only", "時刻だけ",
                "^(?<ts>\\d{2}:\\d{2}:\\d{2})$", "HH:mm:ss");
        assertTrue(noDate.timestampError("00:19:11").contains("年月日"));
    }

    /**
     * 時刻を含まない書式でも、寄せ先の説明を出せること。
     *
     * <p>{@code yyyy/MM/dd} で解釈した結果は時刻を持たない。決め打ちで時まで読むと、
     * 実在しない日を試した瞬間にその場で落ちる（取り込み側は正しく捨てるので、
     * 試し打ちだけが壊れる）。
     */
    @Test
    void timestampErrorWorksForDateOnlyPattern() {
        CustomLogFormat dateOnly = new CustomLogFormat("date-only", "日付だけ",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2})$", "yyyy/MM/dd");
        String why = dateOnly.timestampError("2026/02/31");
        assertNotNull(why);
        assertTrue(why.contains("実在しない"), why);
        assertTrue(why.contains("2 月 28 日"), why);
        assertNull(dateOnly.timestampError("2026/02/28"));
        // 取り込み側も日付だけの書式で寄せを捨てる
        assertNull(parse(dateOnly, "2026/02/31"));
        assertNotNull(parse(dateOnly, "2026/02/28"));

        // 時はあるが分が無い書式でも、同じ理由で落ちないこと
        CustomLogFormat hourOnly = new CustomLogFormat("hour-only", "時まで",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2} \\d{2})$", "yyyy/MM/dd HH");
        String hourWhy = hourOnly.timestampError("2026/02/31 05");
        assertNotNull(hourWhy);
        assertTrue(hourWhy.contains("2 月 28 日 5 時"), hourWhy);
        assertNull(hourOnly.timestampError("2026/02/28 05"));
    }

    /**
     * 後戻りが爆発する正規表現を打ち切ること。
     * 打ち切らないと、取り込みが返らないままアプリが無反応になる。
     */
    @Test
    void abortsCatastrophicBacktracking() {
        CustomLogFormat f = new CustomLogFormat("bad", "暴走する書式",
                "^(?<ts>(a+)+b)$", TS);
        // 20 文字でも後戻りは 2^20 通りに広がり、打ち切り基準を十分に超える。
        // これ以上長くすると、打ち切りを外す変異を入れたときに試験が
        // 「赤くなる」ではなく「返ってこない」になり、壊れたことに気づけない。
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            line.append('a');
        }
        line.append('!');
        assertThrows(CustomLogFormat.BudgetExceededException.class,
                () -> parse(f, line.toString()));
    }

    /** ふつうの書式は打ち切り基準に引っかからないこと。 */
    @Test
    void doesNotAbortNormalPattern() {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            message.append('x');
        }
        assertNotNull(parse(format(),
                "2026/06/15 00:19:11.705 INFO (main) com.example.Hoge : " + message));
    }

    /** 打ち切り基準は行の長さに比例すること（長い行を短い行と同じ上限で切らない）。 */
    @Test
    void budgetGrowsWithLineLength() {
        assertEquals(CustomLogFormat.BUDGET_MIN, CustomLogFormat.budgetFor(1));
        assertTrue(CustomLogFormat.budgetFor(10_000) > CustomLogFormat.budgetFor(1_000));
    }

    /**
     * 文字クラスの中にある {@code (?<name>} をグループと取り違えないこと。
     *
     * <p>取り違えると、存在しない名前を取りにいって解析のたびに落ちる。取り込みが
     * 全滅するうえ、自動判定でも落ちるので画面すら開けなくなる。
     */
    @Test
    void ignoresGroupLikeTextInsideCharacterClass() {
        CustomLogFormat f = new CustomLogFormat("cls", "文字クラス入り",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2}) [(?<level>a-z]+ (?<message>.*)$", "yyyy/MM/dd");
        LogParser.ParsedLine p = parse(f, "2026/06/15 info なにか");
        assertNotNull(p);
        assertEquals("", p.level, "文字クラスの中の (?<level> はグループではない");
        assertEquals("なにか", p.message);
    }

    /** 入れ子の文字クラスがあっても、その後ろのグループを見落とさないこと。 */
    @Test
    void findsGroupsAfterNestedCharacterClass() {
        CustomLogFormat f = new CustomLogFormat("nested", "入れ子の文字クラス",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2}) [a-z&&[^0-9]]+ (?<message>.*)$", "yyyy/MM/dd");
        LogParser.ParsedLine p = parse(f, "2026/06/15 info なにか");
        assertNotNull(p);
        assertEquals("なにか", p.message);
    }

    /**
     * {@code \Q…\E}（リテラル引用）の中を、正規表現の記号として数えないこと。
     *
     * <p>引用した {@code [} を文字クラスの開始と数えると、それ以降のグループを
     * <strong>すべて見失う</strong>。行は一致して取り込まれるのに、レベルも
     * メッセージも黙って空になるため、例外も出ず気づけない。
     */
    @Test
    void ignoresLiteralQuotedText() {
        CustomLogFormat f = new CustomLogFormat("quoted", "引用入り",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2}) \\Q[\\E(?<level>[a-z]+) (?<message>.*)$",
                "yyyy/MM/dd");
        LogParser.ParsedLine p = parse(f, "2026/06/15 [info なにか");
        assertNotNull(p);
        assertEquals("INFO", p.level);
        assertEquals("なにか", p.message);
    }

    /** 引用の中にグループらしい並びがあっても、グループとして数えないこと。 */
    @Test
    void ignoresGroupLikeTextInsideLiteralQuote() {
        CustomLogFormat f = new CustomLogFormat("quoted2", "引用の中のグループ風",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2}) \\Q(?<level>\\E (?<message>.*)$", "yyyy/MM/dd");
        LogParser.ParsedLine p = parse(f, "2026/06/15 (?<level> なにか");
        assertNotNull(p);
        assertEquals("", p.level, "引用の中の (?<level> はグループではない");
        assertEquals("なにか", p.message);
    }

    /**
     * 閉じていない {@code \Q} は、そこから後ろが<strong>すべてただの文字</strong>になること。
     *
     * <p>Java は {@code \E} が無ければパターンの終わりまで引用を続ける。途中で打ち切って
     * 走査を再開すると、引用された {@code (?<level>} をグループと数えてしまい、
     * 解析のたびに落ちる。
     */
    @Test
    void handlesUnterminatedLiteralQuote() {
        CustomLogFormat f = new CustomLogFormat("quoted3", "閉じない引用",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2}) \\Q(?<level>\\w+)", "yyyy/MM/dd");
        LogParser.ParsedLine p = parse(f, "2026/06/15 (?<level>\\w+)");
        assertNotNull(p, "引用の中はそのままの文字として一致する");
        assertEquals("", p.level, "引用の中の (?<level> はグループではない");
    }

    /** 打ち消された括弧はグループの開始ではないこと。 */
    @Test
    void ignoresEscapedGroupStart() {
        CustomLogFormat f = new CustomLogFormat("escaped", "打ち消し",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2}) \\(?<level>x (?<message>.*)$", "yyyy/MM/dd");
        LogParser.ParsedLine p = parse(f, "2026/06/15 (<level>x なにか");
        assertNotNull(p);
        assertEquals("", p.level);
        assertEquals("なにか", p.message);
    }

    /**
     * 後読みを名前付きグループと取り違えないこと（{@code (?<=} は名前ではない）。
     */
    @Test
    void ignoresLookbehind() {
        CustomLogFormat f = new CustomLogFormat("lookbehind", "後読み",
                "^(?<ts>\\d{4}/\\d{2}/\\d{2}) (?<=\\d )(?<message>.*)$", "yyyy/MM/dd");
        assertNotNull(parse(f, "2026/06/15 なにか"));
    }

    /**
     * 走査で取りこぼす書き方でも、原因の書式が分かる形で失敗すること。
     *
     * <p>{@code (?x)} を付けると {@code #} から行末までが正規表現のコメントになり、
     * Java はその中の {@code (?<level>} をグループとして扱わない。こちらの走査は
     * コメントを知らないので「ある」と数えてしまう。この取りこぼしまで無くすのは
     * 正規表現の構文解析をもう 1 つ持つことになるので、<strong>取りこぼしても
     * どの書式が原因か分かる形で失敗させる</strong>ほうを選んでいる。
     * 素の {@link IllegalArgumentException} が出ると、取り込みも自動判定も
     * 原因不明で落ち、直すべきファイルに辿り着けない。
     */
    @Test
    void reportsFormatIdWhenGroupLookupFails() {
        CustomLogFormat f = new CustomLogFormat("cmt", "コメント入り",
                "(?x) ^(?<ts>\\d{4}/\\d{2}/\\d{2}) \\  # (?<level>zzz)\n (?<message>.*) $",
                "yyyy/MM/dd");
        CustomLogFormat.FormatFailure e = assertThrows(CustomLogFormat.FormatFailure.class,
                () -> parse(f, "2026/06/15 なにか"));
        assertTrue(e.getMessage().contains("cmt"), e.getMessage());
    }

    /**
     * 日時書式が空のままの試し打ちでも、取り込みと同じように書式 id つきで失敗すること。
     *
     * <p>「この行で試す」は<strong>日時書式を空のまま使うのがふつうの手順</strong>
     * （正規表現を組み立ててから日時書式を書く）。ここが {@link #parse} と別経路だと、
     * 同じ壊れた書式が、取り込みでは原因つきの 400、試し打ちでは原因不明の 500 になる。
     * 利用者がいちばん先に触るほうが、いちばん不親切になってしまう。
     */
    @Test
    void matchOnlyPathsReportFormatIdToo() {
        CustomLogFormat f = new CustomLogFormat("cmt2", "コメント入り",
                "(?x) ^(?<ts>\\d{4}/\\d{2}/\\d{2}) \\  # (?<level>zzz)\n (?<message>.*) $",
                "yyyy/MM/dd");
        CustomLogFormat.FormatFailure viaGroups = assertThrows(
                CustomLogFormat.FormatFailure.class, () -> f.matchedGroups("2026/06/15 なにか"));
        assertTrue(viaGroups.getMessage().contains("cmt2"), viaGroups.getMessage());

        // matchedTimestamp は ts しか触らないので、上の書式では落ちない。
        // こちらは ts そのものをコメントの中に置き、その経路でも包まれることを見る
        CustomLogFormat tsInComment = new CustomLogFormat("cmt3", "ts がコメントの中",
                "(?x) ^\\d{4} # (?<ts>zzz)\n", "yyyy");
        CustomLogFormat.FormatFailure viaTs = assertThrows(
                CustomLogFormat.FormatFailure.class, () -> tsInComment.matchedTimestamp("2026"));
        assertTrue(viaTs.getMessage().contains("cmt3"), viaTs.getMessage());
    }

    /**
     * 後戻りの打ち切りは、日時書式が空の試し打ちでも効くこと。
     * 効かないと、試し打ちを押した利用者の画面が返ってこなくなる。
     */
    @Test
    void matchOnlyPathsAreAlsoBudgeted() {
        CustomLogFormat f = new CustomLogFormat("bad2", "暴走する書式",
                "^(?<ts>(a+)+b)$", TS);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            line.append('a');
        }
        line.append('!');
        assertThrows(CustomLogFormat.BudgetExceededException.class,
                () -> f.matchedTimestamp(line.toString()));
        assertThrows(CustomLogFormat.BudgetExceededException.class,
                () -> f.matchedGroups(line.toString()));
    }

    /** ts グループが無い書式は作れないこと（時刻が無いと索引に入れられない）。 */
    @Test
    void requiresTimestampGroup() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CustomLogFormat("no-ts", "時刻なし", "^(?<level>\\w+) .*$", TS));
        assertTrue(e.getMessage().contains("ts"));
    }

    /** 壊れた正規表現・日時書式は作る時点で弾くこと。 */
    @Test
    void rejectsBrokenDefinition() {
        assertThrows(IllegalArgumentException.class,
                () -> new CustomLogFormat("bad-re", "壊れた正規表現", "^(?<ts>\\d{4}", TS));
        assertThrows(IllegalArgumentException.class,
                () -> new CustomLogFormat("bad-ts", "壊れた日時書式",
                        "^(?<ts>.*)$", "yyyy/QQQQQQQ"));
    }

    /**
     * 定義を変えたらフィンガープリントも変わること。
     * 変わらないと、書式を直したのに古い索引がそのまま使われる。
     */
    @Test
    void fingerprintCoversPatternAndTimestamp() {
        String base = format().fingerprint();
        assertTrue(base.contains("my-app"));
        String otherPattern = new CustomLogFormat("my-app", "自社アプリ形式",
                PATTERN.replace(" : ", " - "), TS).fingerprint();
        String otherTs = new CustomLogFormat("my-app", "自社アプリ形式",
                PATTERN, "yyyy/MM/dd HH:mm:ss").fingerprint();
        assertTrue(!base.equals(otherPattern), "正規表現を変えたら別物になる");
        assertTrue(!base.equals(otherTs), "日時書式を変えたら別物になる");
    }
}
