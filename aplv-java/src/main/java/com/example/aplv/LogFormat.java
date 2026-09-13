package com.example.aplv;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 取り込むログ行の書式。
 *
 * <p>同時に取り込むファイルはすべて同じ書式である前提とし、取り込み開始時に 1 つへ確定させる。
 * こうすることで、書式を増やしても <strong>1 行あたりの判定は 1 書式分だけ</strong>で済み、
 * 継続行（スタックトレース等）を UTF-8 デコードせずに捨てる既存の設計を崩さない。
 *
 * <p>4 書式とも先頭 23 文字のタイムスタンプは数字の位置が同一で、区切り文字だけが違う。
 * {@link TimeUtil#parseLogTimestamp} は区切り文字を見ずに固定位置の数字だけを読むため、
 * 解析処理自体は全書式で共通に使える。
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
    ISO8601("iso8601", "ISO8601（T 区切り）");

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
     * 先頭ファイルの冒頭を読み、最もよく一致する書式を返す。
     *
     * <p>どの書式でも 1 行も解析できなかった場合は {@link #DEFAULT} を返す。誤判定しても
     * 「認識できなかった行」として画面に出るうえ、UI から明示的に切り替えられるため、
     * ここでは黙って最善手を選ぶ方針とする。
     *
     * <p>先頭にコメントや回転ヘッダが入るログがあるため、先頭行が一致することは求めず、
     * {@link #DETECT_SAMPLE_LINES} 行のうち解析できた行数で比較する。
     *
     * @param paths 取り込む対象。先頭の 1 つだけを見る（同時取り込みは同一書式の前提）
     */
    public static LogFormat detect(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return DEFAULT;
        }
        int[] hits = new int[values().length];
        try (InputStream raw = Files.newInputStream(paths.get(0));
             InputStream in = new BufferedInputStream(raw, 1 << 16);
             ByteLineReader reader = new ByteLineReader(in)) {
            int seen = 0;
            while (seen < DETECT_SAMPLE_LINES && reader.next()) {
                seen++;
                if (reader.isBlankLine()) {
                    continue;
                }
                for (LogFormat f : values()) {
                    if (LogParser.parse(f, reader.lineBuf, reader.lineLen) != null) {
                        hits[f.ordinal()]++;
                    }
                }
            }
        } catch (IOException e) {
            return DEFAULT;
        }
        LogFormat best = DEFAULT;
        int bestHits = 0;
        // 同数のときは宣言順（DEFAULT が先頭）を優先する。
        for (LogFormat f : values()) {
            if (hits[f.ordinal()] > bestHits) {
                best = f;
                bestHits = hits[f.ordinal()];
            }
        }
        return best;
    }
}
