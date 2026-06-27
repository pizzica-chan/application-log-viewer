package com.example.aplv;

/**
 * ログエントリとして認識できなかった行の参照情報（生本文は保持しない）。
 */
public final class SkippedLine {

    public final long fileId;
    public final long lineNo;
    public final String preview;

    public SkippedLine(long fileId, long lineNo, String preview) {
        this.fileId = fileId;
        this.lineNo = lineNo;
        this.preview = preview;
    }
}
