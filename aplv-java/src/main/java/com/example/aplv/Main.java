package com.example.aplv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * CLI エントリポイント。
 *
 * <pre>
 *   java -jar aplv-java.jar [--dir &lt;ログディレクトリ&gt;] [--host &lt;host&gt;] [--port &lt;port&gt;]
 * </pre>
 *
 * デフォルト: http://127.0.0.1:8768（Python 8766 / Rust 8767 と競合しない）
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 8768;
        String dir = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--dir":
                    dir = requireValue(args, ++i, "--dir");
                    break;
                case "--host":
                    host = requireValue(args, ++i, "--host");
                    break;
                case "--port":
                    port = Integer.parseInt(requireValue(args, ++i, "--port"));
                    break;
                case "-h":
                case "--help":
                    printUsage();
                    return;
                default:
                    System.err.println("不明な引数: " + arg);
                    printUsage();
                    System.exit(2);
            }
        }

        Path logRoot = null;
        List<Path> paths = Collections.emptyList();
        if (dir != null) {
            logRoot = PathUtil.resolve(dir);
            if (!Files.isDirectory(logRoot)) {
                System.err.println("ディレクトリが見つかりません: " + logRoot);
                System.exit(1);
            }
            try {
                paths = Discovery.findLogFiles(logRoot);
            } catch (IOException e) {
                System.err.println("ログ探索に失敗しました: " + e.getMessage());
                System.exit(1);
            }
        }

        LogServer server = new LogServer(logRoot, paths);
        try {
            server.start(host, port);
        } catch (IOException e) {
            System.err.println("サーバ起動に失敗しました: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String requireValue(String[] args, int index, String name) {
        if (index >= args.length) {
            System.err.println(name + " の値を指定してください");
            System.exit(2);
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("Application Log Viewer (Java 8)");
        System.out.println("使い方: java -jar aplv-java.jar [--dir <dir>] [--host <host>] [--port <port>]");
        System.out.println("  --dir   起動時に読み込むログディレクトリ（省略時は UI から選択）");
        System.out.println("  --host  待ち受けアドレス（デフォルト 127.0.0.1）");
        System.out.println("  --port  待ち受けポート（デフォルト 8768）");
    }
}
