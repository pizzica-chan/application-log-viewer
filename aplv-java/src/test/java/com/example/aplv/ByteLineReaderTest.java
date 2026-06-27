package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@link ByteLineReader} の単体試験。
 *
 * <p>試験内容:
 * <ul>
 *   <li>改行区切りでの行読み出し（末尾改行あり/なし）</li>
 *   <li>各行の開始バイトオフセット（{@link ByteLineReader#lineStart}）と読み位置（{@link ByteLineReader#position()}）</li>
 *   <li>空白のみの行判定（{@link ByteLineReader#isBlankLine()}）</li>
 *   <li>256 バイト超の長行に対するバッファ自動拡張</li>
 * </ul>
 *
 * <p>担保すること:
 * <ul>
 *   <li>インデックス構築時に各エントリの byte 範囲（offset/end）が正確に記録できる</li>
 *   <li>大容量ログでも行単位のストリーム読み出しが途切れない</li>
 *   <li>空白行をスキップする等の前処理判断が可能</li>
 * </ul>
 */
class ByteLineReaderTest {

    private static String readAll(String content) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (ByteLineReader r = new ByteLineReader(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))) {
            while (r.next()) {
                sb.append(new String(r.lineBuf, 0, r.lineLen, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    /** 複数行（改行含む）が欠損なく読み出されること。 */
    @Test
    void readsLinesWithNewlines() throws IOException {
        assertEquals("a\nb\nc\n", readAll("a\nb\nc\n"));
    }

    /** ファイル末尾に改行が無くても最終行が返されること。 */
    @Test
    void readsFinalLineWithoutTrailingNewline() throws IOException {
        assertEquals("only", readAll("only"));
    }

    /** lineStart / position がファイル内のバイト位置と一致すること（インデックス記録の前提）。 */
    @Test
    void tracksLineStartAndPosition() throws IOException {
        try (ByteLineReader r = new ByteLineReader(new ByteArrayInputStream("ab\ncd".getBytes(StandardCharsets.UTF_8)))) {
            assertTrue(r.next());
            assertEquals(0, r.lineStart);
            assertEquals(3, r.position());
            assertTrue(r.next());
            assertEquals(3, r.lineStart);
            assertEquals(5, r.position());
            assertFalse(r.next());
        }
    }

    /** 空白・タブのみの行を blank と判定できること。 */
    @Test
    void isBlankLine() throws IOException {
        try (ByteLineReader r = new ByteLineReader(new ByteArrayInputStream("  \nok\n".getBytes(StandardCharsets.UTF_8)))) {
            assertTrue(r.next());
            assertTrue(r.isBlankLine());
            assertTrue(r.next());
            assertFalse(r.isBlankLine());
        }
    }

    /** 初期 256 バイトを超える行でもバッファが拡張され全文保持されること。 */
    @Test
    void growsBufferForLongLine() throws IOException {
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longLine.append('x');
        }
        longLine.append('\n');
        try (ByteLineReader r = new ByteLineReader(new ByteArrayInputStream(longLine.toString().getBytes(StandardCharsets.UTF_8)))) {
            assertTrue(r.next());
            assertEquals(501, r.lineLen);
            assertTrue(r.lineBuf.length >= 501);
        }
    }

    /** 空ストリームでは行が返らないこと。 */
    @Test
    void emptyStream() throws IOException {
        try (ByteLineReader r = new ByteLineReader(new ByteArrayInputStream(new byte[0]))) {
            assertFalse(r.next());
        }
    }
}
