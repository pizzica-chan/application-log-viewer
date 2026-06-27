package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link QueryFilter} の単体試験。
 *
 * <p>試験内容:
 * <ul>
 *   <li>レベルフィルタ文字列（{@code ERROR} / {@code WARN,ERROR}）の解析</li>
 *   <li>正規表現コンパイル（大文字小文字無視、空入力）</li>
 *   <li>grep 指定時の生ログ読み出し要否（{@link QueryFilter#needsRaw()}）</li>
 * </ul>
 *
 * <p>担保すること:
 * <ul>
 *   <li>UI/API から渡されたフィルタ条件が {@link LogQuery} で正しく解釈される</li>
 *   <li>未指定フィルタは {@code null} として全件対象となり、不要な I/O を避けられる</li>
 *   <li>grep 未指定時は byte 範囲読み出しをスキップできる</li>
 * </ul>
 */
class QueryFilterTest {

    /** null・空・空白のみは「フィルタなし（全レベル）」として null を返すこと。 */
    @Test
    void parseLevelFilterNullOrEmpty() {
        assertNull(QueryFilter.parseLevelFilter(null));
        assertNull(QueryFilter.parseLevelFilter(""));
        assertNull(QueryFilter.parseLevelFilter("  ,  "));
    }

    /** 単一・複数レベルが大文字 Set に正規化されること。 */
    @Test
    void parseLevelFilterSingleAndMultiple() {
        Set<String> one = QueryFilter.parseLevelFilter("error");
        assertNotNull(one);
        assertEquals(1, one.size());
        assertTrue(one.contains("ERROR"));

        Set<String> multi = QueryFilter.parseLevelFilter(" warn , ERROR , info ");
        assertNotNull(multi);
        assertEquals(3, multi.size());
        assertTrue(multi.contains("WARN"));
        assertTrue(multi.contains("ERROR"));
        assertTrue(multi.contains("INFO"));
    }

    /** null・空文字列は正規表現未指定（null）として扱われること。 */
    @Test
    void compileRegexNullOrEmpty() {
        assertNull(QueryFilter.compileRegex(null));
        assertNull(QueryFilter.compileRegex(""));
    }

    /** コンパイル済み正規表現が大文字小文字を無視してマッチすること。 */
    @Test
    void compileRegexCaseInsensitive() {
        assertNotNull(QueryFilter.compileRegex("foo"));
        assertTrue(QueryFilter.compileRegex("Foo").matcher("bar FOO baz").find());
        assertFalse(QueryFilter.compileRegex("Foo").matcher("bar baz").find());
    }

    /** grep 正規表現が設定された場合のみ生ログ全文の読み出しが必要となること。 */
    @Test
    void needsRawWhenGrepSet() {
        QueryFilter withGrep = new QueryFilter();
        withGrep.grepRe = QueryFilter.compileRegex("x");
        assertTrue(withGrep.needsRaw());

        QueryFilter withoutGrep = new QueryFilter();
        assertFalse(withoutGrep.needsRaw());
    }
}
