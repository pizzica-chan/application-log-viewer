package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Discovery} の単体試験。
 *
 * <p>試験内容:
 * <ul>
 *   <li>ログファイル名パターン（{@code application*.log}, {@code *.out} 等）の一致判定</li>
 *   <li>圧縮ファイル（{@code .gz} 等）の除外</li>
 *   <li>glob マッチ（{@code *}, {@code ?}）の動作</li>
 *   <li>ディレクトリ再帰探索と除外ディレクトリ（{@code .git}, {@code .aplv}, {@code node_modules} 等）のスキップ</li>
 * </ul>
 *
 * <p>担保すること:
 * <ul>
 *   <li>起動時に一般的な Java アプリログだけを自動検出できる</li>
 *   <li>リポジトリ内の VCS・ビルド成果物・インデックス DB を誤ってログとして読まない</li>
 *   <li>ファイルをルートに指定した場合は空リストを返し、例外で落ちない</li>
 * </ul>
 */
class DiscoveryTest {

    /** 想定ログ名は受理し、圧縮ファイル・非ログ拡張子は除外されること。 */
    @Test
    void logFilePatterns() {
        assertTrue(Discovery.isLogFile("application.log"));
        assertTrue(Discovery.isLogFile("application.log.1"));
        assertTrue(Discovery.isLogFile("server.log"));
        assertTrue(Discovery.isLogFile("spring.log"));
        assertTrue(Discovery.isLogFile("catalina.2026-06-15.log"));
        assertTrue(Discovery.isLogFile("localhost.log"));
        assertTrue(Discovery.isLogFile("catalina.out"));
        assertTrue(Discovery.isLogFile("app-debug.log"));
        assertFalse(Discovery.isLogFile("application.log.gz"));
        assertFalse(Discovery.isLogFile("application.log.bz2"));
        assertFalse(Discovery.isLogFile("readme.txt"));
    }

    /** {@code *} / {@code ?} を含む glob が意図どおり一致・不一致となること。 */
    @Test
    void globWildcards() {
        assertTrue(Discovery.globMatch("*.log", "application.log"));
        assertTrue(Discovery.globMatch("application*.log*", "application.log.2"));
        assertTrue(Discovery.globMatch("server?.log", "server1.log"));
        assertFalse(Discovery.globMatch("*.out", "application.log"));
        assertFalse(Discovery.globMatch("app?.log", "application.log"));
    }

    /** .git / .aplv 配下の .log 相当ファイルを探索対象に含めないこと。 */
    @Test
    void findSkipsGitAndAplv(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve(".git").resolve("objects"));
        Files.createDirectories(tmp.resolve(".aplv"));
        Files.createDirectories(tmp.resolve("logs"));
        Files.write(tmp.resolve(".git/objects/fake.log"), "x".getBytes());
        Files.write(tmp.resolve(".aplv/index.db"), "x".getBytes());
        Files.write(tmp.resolve("logs/app.log"), "log".getBytes());

        List<Path> found = Discovery.findLogFiles(tmp);
        assertEquals(1, found.size());
        assertTrue(found.get(0).toString().endsWith("app.log"));
    }

    /** node_modules 配下をスキップし、ルート直下のログだけ返すこと。 */
    @Test
    void findSkipsNodeModules(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("node_modules").resolve("pkg"));
        Files.write(tmp.resolve("node_modules/pkg/debug.log"), "x".getBytes());
        Files.write(tmp.resolve("real.log"), "log".getBytes());

        List<Path> found = Discovery.findLogFiles(tmp);
        assertEquals(1, found.size());
        assertTrue(found.get(0).toString().endsWith("real.log"));
    }

    /** ディレクトリでないパスを渡した場合は空リストを返すこと。 */
    @Test
    void findReturnsEmptyForFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("notdir.log");
        Files.write(file, "x".getBytes());
        assertTrue(Discovery.findLogFiles(file).isEmpty());
    }
}
