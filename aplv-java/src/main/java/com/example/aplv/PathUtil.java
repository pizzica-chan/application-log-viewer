package com.example.aplv;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * パス表示の正規化ユーティリティ。
 *
 * <p>Windows では絶対パス取得時に {@code \\?\}（extended-length path）プレフィックスが
 * 付くことがある。UI 表示・SQLite 保存時には通常表記へ戻す。
 */
public final class PathUtil {

    private PathUtil() {
    }

    /** 文字列パスから {@code \\?\} / {@code \\?\UNC\} プレフィックスを除去する。 */
    public static String normalizePathStr(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("\\\\?\\")) {
            String rest = path.substring(4);
            if (rest.startsWith("UNC\\")) {
                return "\\\\" + rest.substring(4);
            }
            return rest;
        }
        return path;
    }

    /** {@link Path} を絶対パスに解決し、表示用文字列へ変換する。 */
    public static String normalizePath(Path path) {
        Path resolved = resolve(path);
        return normalizePathStr(resolved.toString());
    }

    /** 絶対・実体パスへ解決する（シンボリックリンク含む。失敗時は絶対パス）。 */
    public static Path resolve(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** 文字列から解決済み {@link Path} を得る。 */
    public static Path resolve(String path) {
        return resolve(Paths.get(path));
    }
}
