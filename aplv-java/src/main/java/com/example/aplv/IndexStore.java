package com.example.aplv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SQLite インデックスファイルの保存場所とクリーンアップ。
 *
 * <p>DB は {@code {repo}/tmp/aplv/{sha256(log_root)}.db} に保存する。
 * 環境変数 {@code APLV_HOME} でリポジトリルートを上書き可能。
 */
public final class IndexStore {

    private static final long DEFAULT_MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final int DEFAULT_MAX_COUNT = 20;

    private static final AtomicBoolean CLEANUP_DONE = new AtomicBoolean(false);

    private IndexStore() {
    }

    /** リポジトリルート。 */
    public static Path repoRoot() {
        String home = System.getenv("APLV_HOME");
        if (home != null && !home.isEmpty()) {
            return Paths.get(home).toAbsolutePath().normalize();
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("aplv-java/pom.xml"))) {
            return cwd;
        }
        if ("aplv-java".equals(String.valueOf(cwd.getFileName()))
                && Files.exists(cwd.resolve("pom.xml"))
                && cwd.getParent() != null) {
            return cwd.getParent();
        }
        return cwd;
    }

    /** {@code {repo}/tmp/aplv} */
    public static Path tmpIndexDir() {
        return repoRoot().resolve("tmp").resolve("aplv");
    }

    public static String logRootKey(Path logRoot) {
        String normalized = PathUtil.normalizePath(PathUtil.resolve(logRoot));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static Path indexDbPath(Path logRoot) {
        return tmpIndexDir().resolve(logRootKey(logRoot) + ".db");
    }

    /** 指定ログディレクトリの DB ファイル群を削除する。 */
    public static void deleteIndexFiles(Path logRoot) {
        Path base = indexDbPath(logRoot);
        deleteSidecars(base);
    }

    private static void deleteSidecars(Path base) {
        for (String suffix : new String[] {"", "-wal", "-shm", "-journal"}) {
            Path p = suffix.isEmpty() ? base : Paths.get(base.toString() + suffix);
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // 削除失敗は無視
            }
        }
    }

    /** tmp/aplv 内の古い・過剰な DB を削除する。 */
    public static void cleanupStaleIndexes(Path activeLogRoot) {
        Path indexDir = tmpIndexDir();
        if (!Files.isDirectory(indexDir)) {
            return;
        }
        String activeKey = activeLogRoot != null ? logRootKey(activeLogRoot) : null;
        long now = System.currentTimeMillis();

        List<Path> dbFiles = listDbFiles(indexDir);

        for (Path db : dbFiles) {
            if (activeKey != null && activeKey.equals(dbFileStem(db))) {
                continue;
            }
            try {
                long age = now - Files.getLastModifiedTime(db).toMillis();
                if (age > DEFAULT_MAX_AGE_MS) {
                    deleteSidecars(db);
                }
            } catch (IOException ignored) {
                // スキップ
            }
        }

        dbFiles = listDbFiles(indexDir);
        List<Path> others = new ArrayList<>();
        Path activeDb = null;
        for (Path db : dbFiles) {
            if (activeKey != null && activeKey.equals(dbFileStem(db))) {
                activeDb = db;
            } else {
                others.add(db);
            }
        }
        others.sort(Comparator.comparingLong(IndexStore::lastModifiedSafe).reversed());

        int limit = activeDb != null ? DEFAULT_MAX_COUNT - 1 : DEFAULT_MAX_COUNT;
        for (int i = limit; i < others.size(); i++) {
            deleteSidecars(others.get(i));
        }
    }

    private static List<Path> listDbFiles(Path indexDir) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(indexDir, "*.db")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    result.add(p);
                }
            }
        } catch (IOException ignored) {
            // 空リスト
        }
        return result;
    }

    private static String dbFileStem(Path db) {
        String name = db.getFileName().toString();
        return name.endsWith(".db") ? name.substring(0, name.length() - 3) : name;
    }

    private static long lastModifiedSafe(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** tmp/aplv を作成し、stale クリーンアップを実行する。 */
    public static void ensureTmpDirFor(Path logRoot) throws IOException {
        Files.createDirectories(tmpIndexDir());
        if (CLEANUP_DONE.compareAndSet(false, true)) {
            cleanupStaleIndexes(logRoot);
        } else {
            cleanupStaleIndexes(logRoot);
        }
    }
}
