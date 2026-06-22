package com.example.aplv;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ログファイルの再帰探索。
 *
 * <p>一般的な Java アプリログのファイル名パターンに一致するファイルを再帰探索する。
 */
public final class Discovery {

    private Discovery() {
    }

    /** 探索対象のファイル名 glob（小文字比較）。 */
    private static final String[] LOG_FILE_PATTERNS = {
            "application*.log*",
            "server*.log*",
            "spring*.log*",
            "catalina*.log*",
            "localhost*.log*",
            "app*.log*",
            "*.log",
            "*.out",
    };

    /** 走査をスキップするディレクトリ名。 */
    private static final Set<String> SKIP_DIR_NAMES = new HashSet<>(Arrays.asList(
            ".git", "__pycache__", "node_modules", ".venv", "venv",
            ".tox", ".mypy_cache", ".pytest_cache", ".aplv"));

    /** ファイル名がログファイルパターンに一致するか（圧縮ファイルは除外）。 */
    public static boolean isLogFile(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".gz") || lower.endsWith(".bz2") || lower.endsWith(".xz")) {
            return false;
        }
        for (String pat : LOG_FILE_PATTERNS) {
            if (globMatch(pat, lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 指定ディレクトリ配下を再帰探索し、ログファイルのパス一覧（昇順）を返す。
     */
    public static List<Path> findLogFiles(Path root) throws IOException {
        Path base = PathUtil.resolve(root);
        if (!Files.isDirectory(base)) {
            return Collections.emptyList();
        }
        final List<Path> found = new ArrayList<>();
        Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                if (name != null && SKIP_DIR_NAMES.contains(name.toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path name = file.getFileName();
                if (name != null && isLogFile(name.toString())) {
                    found.add(PathUtil.resolve(file));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(found);
        return found;
    }

    /** 簡易 glob マッチ（{@code *} と {@code ?} のみ。{@code fnmatch} 相当）。 */
    static boolean globMatch(String pattern, String name) {
        return globMatch(pattern, 0, name, 0);
    }

    private static boolean globMatch(String pat, int pi, String name, int ni) {
        int plen = pat.length();
        int nlen = name.length();
        while (pi < plen) {
            char pc = pat.charAt(pi);
            if (pc == '*') {
                if (pi + 1 == plen) {
                    return true;
                }
                for (int i = ni; i <= nlen; i++) {
                    if (globMatch(pat, pi + 1, name, i)) {
                        return true;
                    }
                }
                return false;
            }
            if (ni >= nlen) {
                return false;
            }
            if (pc != '?' && pc != name.charAt(ni)) {
                return false;
            }
            pi++;
            ni++;
        }
        return ni == nlen;
    }
}
