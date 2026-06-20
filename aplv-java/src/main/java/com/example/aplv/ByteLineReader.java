package com.example.aplv;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * バイトオフセットを保持しながら 1 行ずつ読み出す高速リーダー。
 *
 * <p>大容量ログのインデックス構築用。大きな読み込みバッファでまとめて読み、
 * 改行を走査して行を切り出す。各行の開始バイトオフセットを保持するため、
 * エントリの byte 範囲記録（オンデマンドな生ログ読み出し）に利用できる。
 *
 * <p>行バイト列は内部の再利用バッファ {@link #lineBuf} に格納し、有効長は {@link #lineLen}。
 * 改行（{@code \n}）は行に含まれる。
 */
public final class ByteLineReader implements Closeable {

    private static final int READ_BUF_SIZE = 1 << 20; // 1 MiB

    private final InputStream in;
    private final byte[] buf = new byte[READ_BUF_SIZE];
    private int bufLen;
    private int bufPos;
    private long absPos; // buf[bufPos] の絶対バイトオフセット

    /** 直近の行バイト列（再利用バッファ）。 */
    public byte[] lineBuf = new byte[256];
    /** {@link #lineBuf} の有効長。 */
    public int lineLen;
    /** 直近の行の開始バイトオフセット。 */
    public long lineStart;

    public ByteLineReader(InputStream in) {
        this.in = in;
    }

    /**
     * 次の行を読み出す。
     *
     * @return EOF に達して読む行が無ければ {@code false}
     */
    public boolean next() throws IOException {
        if (bufPos >= bufLen && !fill()) {
            return false;
        }
        lineStart = absPos;
        lineLen = 0;
        while (true) {
            if (bufPos >= bufLen && !fill()) {
                // EOF。これまでに集めたバイトがあれば最終行として返す。
                return lineLen > 0;
            }
            int nl = indexOfNewline(buf, bufPos, bufLen);
            if (nl >= 0) {
                int chunk = nl - bufPos + 1; // 改行を含める
                append(buf, bufPos, chunk);
                bufPos += chunk;
                absPos += chunk;
                return true;
            }
            int chunk = bufLen - bufPos;
            append(buf, bufPos, chunk);
            bufPos += chunk;
            absPos += chunk;
        }
    }

    /** 現在の絶対バイトオフセット（次に読む位置 = 直近行の終端）。 */
    public long position() {
        return absPos;
    }

    private boolean fill() throws IOException {
        int n = in.read(buf, 0, buf.length);
        if (n <= 0) {
            bufLen = 0;
            bufPos = 0;
            return false;
        }
        bufLen = n;
        bufPos = 0;
        return true;
    }

    private void append(byte[] src, int off, int len) {
        int need = lineLen + len;
        if (need > lineBuf.length) {
            int cap = lineBuf.length;
            while (cap < need) {
                cap <<= 1;
            }
            lineBuf = Arrays.copyOf(lineBuf, cap);
        }
        System.arraycopy(src, off, lineBuf, lineLen, len);
        lineLen = need;
    }

    private static int indexOfNewline(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** 行バイト列が空白のみ（改行・空白文字だけ）かどうか。 */
    public boolean isBlankLine() {
        for (int i = 0; i < lineLen; i++) {
            byte c = lineBuf[i];
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
