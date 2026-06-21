package com.example.aplv;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code /api/logs} のフィルタ条件。
 *
 * <p>レベル・日時は SQL で事前絞り込み、logger / thread / message / source / grep は
 * Java 側で正規表現マッチ（Python / Rust 版と同じ挙動。正規表現は大文字小文字を無視）。
 */
public final class QueryFilter {

    public Set<String> levels;       // null = 全レベル
    public Pattern loggerRe;
    public Pattern threadRe;
    public Pattern messageRe;
    public Pattern sourceRe;
    public Pattern grepRe;
    public String grepText;          // grep の元文字列（FTS 候補絞り込み判定・MATCH 生成用）
    public Long sinceMillis;         // null = 下限なし
    public Long untilMillis;         // null = 上限なし

    /** grep（全文検索）が指定されているか。指定時のみ生ログを読み出す。 */
    public boolean needsRaw() {
        return grepRe != null;
    }

    /** {@code ERROR} / {@code WARN,ERROR} 形式を解釈する。空なら null。 */
    public static Set<String> parseLevelFilter(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Set<String> result = new HashSet<>();
        for (String part : value.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                result.add(p.toUpperCase());
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** 大文字小文字を無視する正規表現をコンパイルする（空なら null）。 */
    public static Pattern compileRegex(String pat) {
        if (pat == null || pat.isEmpty()) {
            return null;
        }
        return Pattern.compile(pat, Pattern.CASE_INSENSITIVE);
    }
}
