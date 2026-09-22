package com.example.aplv;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 1 回の取り込みで使う書式。組み込み書式（{@link LogFormat}）か、利用者が定義した
 * 書式（{@link CustomLogFormat}）のどちらか一方を指す。
 *
 * <p>取り込み開始時に 1 つへ確定させる設計は変えていない。1 行あたりに走る判定は
 * 確定した 1 書式ぶんだけで、書式を増やしても 1 行あたりの処理は増えない。
 * 利用者定義の書式を足しても、<strong>組み込み書式の解析経路は変わらない</strong>。
 */
public final class LogFormatSpec {

    /** 既定書式。書式を指定しないときの出発点。 */
    public static final LogFormatSpec DEFAULT = new LogFormatSpec(LogFormat.DEFAULT, null);

    private final LogFormat builtin;
    private final CustomLogFormat custom;

    private LogFormatSpec(LogFormat builtin, CustomLogFormat custom) {
        this.builtin = builtin;
        this.custom = custom;
    }

    public static LogFormatSpec of(LogFormat builtin) {
        if (builtin == null) {
            throw new IllegalArgumentException("builtin");
        }
        return new LogFormatSpec(builtin, null);
    }

    public static LogFormatSpec of(CustomLogFormat custom) {
        if (custom == null) {
            throw new IllegalArgumentException("custom");
        }
        return new LogFormatSpec(null, custom);
    }

    /** 組み込みなら {@link LogFormat}、利用者定義なら {@code null}。 */
    LogFormat builtin() {
        return builtin;
    }

    /** 利用者定義なら {@link CustomLogFormat}、組み込みなら {@code null}。 */
    CustomLogFormat custom() {
        return custom;
    }

    public boolean isCustom() {
        return custom != null;
    }

    public String id() {
        return builtin != null ? builtin.id() : custom.id();
    }

    public String displayName() {
        return builtin != null ? builtin.displayName() : custom.displayName();
    }

    /**
     * 索引のフィンガープリントに入れる文字列。
     *
     * <p>組み込み書式では id だけを返す。<strong>この文字列は変えないこと</strong>
     * ―― 変えると、既存の索引がすべて作り直しになる。利用者定義の書式では
     * パターンと日時書式も含め、定義を直したら作り直されるようにする。
     */
    String fingerprint() {
        return builtin != null ? builtin.id() : custom.fingerprint();
    }

    /**
     * id から引く。組み込みを先に探し、なければ利用者定義から探す。
     * 未知の id は {@code null}。
     */
    public static LogFormatSpec byId(String id, List<CustomLogFormat> customs) {
        if (id == null) {
            return null;
        }
        LogFormat builtin = LogFormat.byId(id);
        if (builtin != null) {
            return of(builtin);
        }
        for (CustomLogFormat c : customs != null ? customs : Collections.<CustomLogFormat>emptyList()) {
            if (c.id().equals(id)) {
                return of(c);
            }
        }
        return null;
    }

    /**
     * 先頭ファイルの冒頭を読み、最もよく一致する書式を返す。組み込みと利用者定義の
     * 両方を候補にする。同数のときは組み込みを優先する（利用者定義が既定書式の
     * ログを横取りしないように）。
     *
     * <p>見るのは {@link LogFormat#DETECT_SAMPLE_LINES} 行までで、書式の数に比例して
     * 増えるのはこの判定だけ。取り込み本体は確定した 1 書式ぶんしか走らない。
     *
     * @param paths   取り込む対象。先頭の 1 つだけを見る（同時取り込みは同一書式の前提）
     * @param customs 利用者定義の書式。{@code null} なら組み込みだけで判定する
     */
    public static LogFormatSpec detect(List<Path> paths, List<CustomLogFormat> customs) {
        if (paths == null || paths.isEmpty()) {
            return DEFAULT;
        }
        List<LogFormatSpec> candidates = new ArrayList<LogFormatSpec>();
        for (LogFormat f : LogFormat.values()) {
            candidates.add(of(f));
        }
        if (customs != null) {
            for (CustomLogFormat c : customs) {
                candidates.add(of(c));
            }
        }
        int[] hits = new int[candidates.size()];
        boolean[] broken = new boolean[candidates.size()];
        try (InputStream raw = Files.newInputStream(paths.get(0));
             InputStream in = new BufferedInputStream(raw, 1 << 16);
             ByteLineReader reader = new ByteLineReader(in)) {
            int seen = 0;
            while (seen < LogFormat.DETECT_SAMPLE_LINES && reader.next()) {
                seen++;
                if (reader.isBlankLine()) {
                    continue;
                }
                for (int i = 0; i < candidates.size(); i++) {
                    if (broken[i]) {
                        continue;
                    }
                    LogFormatSpec spec = candidates.get(i);
                    if (spec.custom == null) {
                        if (LogParser.parse(spec.builtin, reader.lineBuf, reader.lineLen) != null) {
                            hits[i]++;
                        }
                        continue;
                    }
                    try {
                        if (spec.custom.parse(reader.lineBuf, reader.lineLen) != null) {
                            hits[i]++;
                        }
                    } catch (CustomLogFormat.FormatFailure e) {
                        // 壊れた書式や暴走する正規表現で自動判定まで巻き添えにしない。
                        // ここで落ちると、書式ファイルを直すための画面すら開けなくなる。
                        // 明示的に選ばれたときは取り込みがはっきり失敗する。
                        broken[i] = true;
                        System.err.println("書式 " + spec.id() + " は自動判定から外しました: "
                                + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            return DEFAULT;
        }
        LogFormatSpec best = DEFAULT;
        int bestHits = 0;
        // 同数のときは候補の並び順（組み込みが先）を優先する。
        for (int i = 0; i < candidates.size(); i++) {
            if (hits[i] > bestHits) {
                best = candidates.get(i);
                bestHits = hits[i];
            }
        }
        return best;
    }
}
