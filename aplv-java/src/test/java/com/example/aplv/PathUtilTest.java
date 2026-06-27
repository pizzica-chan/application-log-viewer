package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PathUtil} の単体試験。
 *
 * <p>試験内容:
 * <ul>
 *   <li>Windows extended-length path プレフィックス（{@code \\?\}）の除去</li>
 *   <li>UNC パス（{@code \\?\UNC\...}）の通常表記への変換</li>
 *   <li>{@link Path} の絶対パス解決と正規化</li>
 * </ul>
 *
 * <p>担保すること:
 * <ul>
 *   <li>SQLite インデックスと UI 表示で同一のパス文字列が使われ、再インデックスや検索の不一致が起きない</li>
 *   <li>Windows 特有のパス表記がユーザーに見える形式へ統一される</li>
 * </ul>
 */
class PathUtilTest {

    /** null 入力は null を返すこと。 */
    @Test
    void normalizePathStrNull() {
        assertNull(PathUtil.normalizePathStr(null));
    }

    /** 通常パスはそのまま保持されること。 */
    @Test
    void normalizePathStrPlain() {
        assertEquals("C:\\logs\\app.log", PathUtil.normalizePathStr("C:\\logs\\app.log"));
    }

    /** {@code \\?\} プレフィックスが除去されること。 */
    @Test
    void normalizePathStrExtendedLength() {
        assertEquals("C:\\logs\\app.log", PathUtil.normalizePathStr("\\\\?\\C:\\logs\\app.log"));
    }

    /** extended-length UNC が {@code \\server\share\...} 形式へ変換されること。 */
    @Test
    void normalizePathStrUnc() {
        assertEquals("\\\\server\\share\\app.log",
                PathUtil.normalizePathStr("\\\\?\\UNC\\server\\share\\app.log"));
    }

    /** {@link PathUtil#normalizePath(Path)} が絶対パス解決後の文字列と一致すること。 */
    @Test
    void normalizePathResolves(@TempDir Path tmp) {
        Path file = tmp.resolve("test.log");
        String normalized = PathUtil.normalizePath(file);
        assertEquals(PathUtil.normalizePathStr(file.toAbsolutePath().normalize().toString()), normalized);
    }
}
