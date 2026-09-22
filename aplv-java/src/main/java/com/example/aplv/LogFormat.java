package com.example.aplv;

import java.nio.file.Path;
import java.util.List;

/**
 * 取り込むログ行の書式。
 *
 * <p>同時に取り込むファイルはすべて同じ書式である前提とし、取り込み開始時に 1 つへ確定させる。
 * こうすることで、書式を増やしても <strong>1 行あたりの判定は 1 書式分だけ</strong>で済み、
 * 継続行（スタックトレース等）を UTF-8 デコードせずに捨てる既存の設計を崩さない。
 *
 * <p>{@link #TOMCAT_JULI} を除く 4 書式は、先頭 23 文字のタイムスタンプの数字の位置が
 * 同一で、区切り文字だけが違う。{@link TimeUtil#parseLogTimestamp} は区切り文字を見ずに
 * 固定位置の数字だけを読むため、この 4 書式では解析処理を共通に使える。
 *
 * <pre>
 * 位置:  0123456789012345678901234
 *        2026-06-15 00:19:11.705[...]     DEFAULT
 *        2026-06-15 00:19:11.705  INFO …  SPRING_BOOT
 *        2026-06-15 00:19:11,705 INFO  …  LOGBACK（ミリ秒は . でも , でも可）
 *        2026-06-15T00:19:11.705 INFO  …  ISO8601
 *                  ^          ^  ^
 *                  10         19 23
 * </pre>
 *
 * <p>{@link #TOMCAT_JULI} だけは日付の並びが違い（{@code dd-MMM-yyyy}）、
 * タイムスタンプが 24 文字になるため専用の解析を持つ。月名が入るので位置 4 が
 * 数字の区切りにならず、他の 4 書式と取り違えることはない。</p>
 */
public enum LogFormat {

    /** {@code 2026-06-15 00:19:11.705[Thread][LEVEL][Logger] - Message}（従来からの既定）。 */
    DEFAULT("default", "既定 [Thread][LEVEL][Logger]"),

    /**
     * Spring Boot の既定レイアウト。
     * {@code 2026-06-15 00:19:11.705  INFO 12345 --- [nio-8080-exec-1] c.e.Hoge : Message}
     */
    SPRING_BOOT("spring-boot", "Spring Boot 既定"),

    /**
     * logback / log4j の標準的なレイアウト。ミリ秒の区切りは {@code .} でも {@code ,} でも可。
     * {@code 2026-06-15 00:19:11,705 INFO  [main] com.example.Hoge - Message}
     */
    LOGBACK("logback", "logback / log4j 標準"),

    /**
     * 日付と時刻を {@code T} で区切る形。本文の並びは {@link #LOGBACK} と同じ。
     * {@code 2026-06-15T00:19:11.705 INFO [main] com.example.Hoge - Message}
     */
    ISO8601("iso8601", "ISO8601（T 区切り）"),

    /**
     * Tomcat の {@code catalina.out}（JULI の OneLineFormatter）。
     * 日付が {@code dd-MMM-yyyy} で、ロガーとメッセージの区切りが空白 1 つになる。
     * {@code 15-Jun-2026 00:19:11.705 INFO [main] org.apache.catalina.startup.Catalina.start Message}
     */
    TOMCAT_JULI("tomcat-juli", "Tomcat catalina.out（JULI）");

    /** 自動判定でサンプリングする行数。空行・継続行も含めて数える。 */
    static final int DETECT_SAMPLE_LINES = 500;

    private final String id;
    private final String displayName;

    LogFormat(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** API・索引フィンガープリントで使う識別子。 */
    public String id() {
        return id;
    }

    /** 画面表示用の名前。 */
    public String displayName() {
        return displayName;
    }

    /** {@link #id()} から引く。未知の値や {@code null} は {@code null}。 */
    public static LogFormat byId(String id) {
        if (id == null) {
            return null;
        }
        for (LogFormat f : values()) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        return null;
    }

    /**
     * 先頭ファイルの冒頭を読み、最もよく一致する<strong>組み込み書式</strong>を返す。
     *
     * <p>判定そのものは {@link LogFormatSpec#detect} が行う。利用者定義の書式も候補に
     * したい本番経路はそちらを直接呼ぶこと。ここは組み込み書式だけを相手にする
     * 呼び出し口で、判定の規則（サンプル行数・同数のときの優先順）を二重に持たない
     * ようにするため委譲している。
     *
     * @param paths 取り込む対象。先頭の 1 つだけを見る（同時取り込みは同一書式の前提）
     */
    public static LogFormat detect(List<Path> paths) {
        LogFormat builtin = LogFormatSpec.detect(paths, null).builtin();
        return builtin != null ? builtin : DEFAULT;
    }
}
