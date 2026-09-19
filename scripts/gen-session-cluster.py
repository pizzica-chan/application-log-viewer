"""samples/session-cluster/ のサンプルログを生成する。

アプリサーバを 3 台並べた冗長構成を想定し、1 セッションのリクエストが
インスタンス（= ログファイル）をまたいで行き来する様子を作る。
セッション追跡がファイルをまたいでも追えることの確認に使う。

乱数の種を固定してあるので、実行するたびに同じログができる
（samples/session-cluster/ の試験は行数や件数を前提にしている）。

    python scripts/gen-session-cluster.py
"""
import datetime
import os
import random

SEED = 20260620
START = datetime.datetime(2026, 6, 20, 9, 0, 0)
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "samples",
                       "session-cluster")

INSTANCES = ["app1", "app2", "app3"]
THREADS_PER_INSTANCE = 4
# セッション ID。jvmRoute は付けない（振り分けでインスタンスが変わっても ID は変わらない前提）
SESSIONS = [
    "D41B8E2F5A7C4903",
    "7B0E9C6A3F18D25E",
    "C58A14D7E93B6F02",
    "9E26F0B85D4A17C3",
    "F3D7A92C608B5E41",
    "2A6C5B83F71E094D",
    "6F914E0D2B87A3C5",
    "80B5D3A6C49F72E1",
    "4C7E28F1A05D9B36",
    "E19F6C4B37D0582A",
]
REQUESTS_PER_SESSION = 12

ENDPOINTS = [
    ("GET", "/cart", "com.example.web.CartController", [
        ("INFO", "com.example.web.CartController", "カートを表示 sessionId={sid}"),
        ("DEBUG", "com.example.service.CartService", "カートを読み込みます userId={uid} items={n}"),
        ("DEBUG", "com.example.db.CartMapper", "SELECT cart WHERE user_id={uid} 件数={n}"),
    ]),
    ("POST", "/cart/add", "com.example.web.CartController", [
        ("INFO", "com.example.web.CartController", "カートに追加 sessionId={sid} productId=P-{n}0{n}"),
        ("DEBUG", "com.example.service.StockService", "在庫を確認 productId=P-{n}0{n} stock={n}{n}"),
        ("DEBUG", "com.example.service.CartService", "カートを保存 items={n}"),
    ]),
    ("GET", "/products", "com.example.web.ProductController", [
        ("INFO", "com.example.web.ProductController", "商品一覧を表示 sessionId={sid} page={n}"),
        ("DEBUG", "com.example.db.ProductMapper", "商品を検索 category=book hits={n}{n}"),
    ]),
    ("POST", "/order", "com.example.web.OrderController", [
        ("INFO", "com.example.web.OrderController", "注文を受け付けました sessionId={sid} orderId=O-{n}00{n}"),
        ("INFO", "com.example.service.OrderService", "在庫を引き当てます orderId=O-{n}00{n}"),
        ("INFO", "com.example.service.PaymentService", "決済を実行します orderId=O-{n}00{n} amount={n}800"),
        ("DEBUG", "com.example.service.OrderService", "注文を確定しました orderId=O-{n}00{n}"),
    ]),
    ("GET", "/mypage", "com.example.web.MyPageController", [
        ("INFO", "com.example.web.MyPageController", "マイページを表示 sessionId={sid}"),
        ("DEBUG", "com.example.service.HistoryService", "購入履歴を取得 userId={uid} 件数={n}"),
    ]),
]

# 例外で落ちるリクエスト。スタックトレースの中にだけセッション ID が出る
ERROR_BODY = [
    "com.example.order.PaymentException: 決済が拒否されました session={sid} orderId=O-{n}00{n}",
    "\tat com.example.service.PaymentService.charge(PaymentService.java:88)",
    "\tat com.example.service.OrderService.place(OrderService.java:51)",
    "\tat com.example.web.OrderController.post(OrderController.java:37)",
]


def fmt(ts):
    return ts.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]


def line(ts, thread, level, logger, message):
    return "%s[%s][%s][%s] - %s\n" % (fmt(ts), thread, level, logger, message)


def free_slot_at(lines, names, thread, start, window_secs=3):
    """指定のスレッドが前後 window_secs 秒あいだ空いている時刻を、start 以降から探す。

    1 つのスレッドが同時に 2 つのリクエストを処理することはないので、
    並列リクエストを差し込むときに既存の行（ヘルスチェック等）と重ならないようにする。
    """
    busy = []
    for name in names:
        for text in lines[name]:
            if "[%s]" % thread in text.split("] - ")[0]:
                busy.append(datetime.datetime.strptime(text[:23], "%Y-%m-%d %H:%M:%S.%f"))
    busy.sort()
    candidate = start
    for _ in range(600):  # 1 秒ずつ最大 10 分ぶん探す
        window = datetime.timedelta(seconds=window_secs)
        if not any(candidate - window <= b <= candidate + window for b in busy):
            return candidate
        candidate += datetime.timedelta(seconds=1)
    raise RuntimeError("並列リクエストを差し込める空きが見つかりません")


def main():
    rnd = random.Random(SEED)
    lines = {name: [] for name in INSTANCES}
    # インスタンスごとのスレッドプール。次に空く時刻を持つ
    free_at = {name: [START] * THREADS_PER_INSTANCE for name in INSTANCES}

    # ロードバランサのヘルスチェック（セッション ID を含まない。除外の絞り込みで使う）
    for name in INSTANCES:
        ts = START + datetime.timedelta(seconds=rnd.randint(0, 20))
        while ts < START + datetime.timedelta(minutes=40):
            thread = "http-nio-8080-exec-%d" % rnd.randint(1, THREADS_PER_INSTANCE)
            lines[name].append(line(ts, thread, "INFO", "com.example.web.RequestLogFilter",
                                    "リクエスト開始 GET /health"))
            lines[name].append(line(ts + datetime.timedelta(milliseconds=2), thread, "INFO",
                                    "com.example.web.RequestLogFilter",
                                    "リクエスト終了 status=200 elapsed=2ms"))
            ts += datetime.timedelta(seconds=rnd.randint(25, 40))

    # 定期ジョブ（リクエストの外で動くスレッド。どのリクエストにも属さない行になる）
    for name in INSTANCES:
        ts = START + datetime.timedelta(seconds=rnd.randint(30, 90))
        while ts < START + datetime.timedelta(minutes=40):
            lines[name].append(line(ts, "scheduler-1", "INFO", "com.example.batch.CacheRefresher",
                                    "キャッシュを更新しました 件数=%d" % rnd.randint(10, 90)))
            ts += datetime.timedelta(seconds=rnd.randint(180, 300))

    # セッションごとのリクエスト。インスタンスは固定せず、毎回選び直す（振り分けで移動する想定）
    for si, sid in enumerate(SESSIONS):
        uid = "u%d" % (1000 + si)
        ts = START + datetime.timedelta(seconds=rnd.randint(5, 40), milliseconds=rnd.randint(0, 999))
        error_at = rnd.randrange(2, REQUESTS_PER_SESSION)
        previous = None
        for ri in range(REQUESTS_PER_SESSION):
            # 同じインスタンスに続けて当たることもあるが、たいていは別の台へ移る
            others = [n for n in INSTANCES if n != previous]
            name = rnd.choice(others if previous and rnd.random() < 0.8 else INSTANCES)
            previous = name
            slot = min(range(THREADS_PER_INSTANCE), key=lambda i: free_at[name][i])
            begin = max(ts, free_at[name][slot])
            thread = "http-nio-8080-exec-%d" % (slot + 1)
            is_error = ri == error_at
            # 例外で落ちる回は /order にそろえる（スタックトレース付きの見本にする）
            method, path, _, body = ENDPOINTS[3 if is_error else rnd.randrange(len(ENDPOINTS))]

            cur = begin
            out = [line(cur, thread, "INFO", "com.example.web.RequestLogFilter",
                        "リクエスト開始 %s %s" % (method, path))]
            for level, logger, message in body:
                cur += datetime.timedelta(milliseconds=rnd.randint(3, 40))
                out.append(line(cur, thread, level, logger,
                                message.format(sid=sid, uid=uid, n=rnd.randint(1, 9))))
            if is_error:
                cur += datetime.timedelta(milliseconds=rnd.randint(5, 30))
                stack = "".join(s.format(sid=sid, n=rnd.randint(1, 9)) + "\n" for s in ERROR_BODY)
                out.append(line(cur, thread, "ERROR", "com.example.web.OrderController",
                                "注文処理に失敗しました") + stack)
            cur += datetime.timedelta(milliseconds=rnd.randint(3, 25))
            status = 500 if is_error else 200
            elapsed = int((cur - begin).total_seconds() * 1000)
            out.append(line(cur, thread, "INFO", "com.example.web.RequestLogFilter",
                            "リクエスト終了 status=%d elapsed=%dms" % (status, elapsed)))
            lines[name].extend(out)

            free_at[name][slot] = cur + datetime.timedelta(milliseconds=1)
            # 次のリクエストまでの間隔（利用者の操作を想定）
            ts = cur + datetime.timedelta(seconds=rnd.randint(5, 180),
                                          milliseconds=rnd.randint(0, 999))

    # 同じセッションの並列リクエスト（画面から Ajax を 2 本同時に投げた想定）。
    # 別インスタンスの「同じ名前のスレッド」に同時刻で乗る形にしてある。ファイルで分けずに
    # スレッド名だけで束ねると、ここで別の台のログが混ざる。
    parallel_sid = SESSIONS[0]
    parallel_at = free_slot_at(lines, ["app1", "app2"], "http-nio-8080-exec-1",
                               START + datetime.timedelta(minutes=30))
    for offset, (name, path, body) in enumerate([
        ("app1", "/api/cart/count", [
            ("INFO", "com.example.web.CartController",
             "カート件数を返します sessionId=%s items=3" % parallel_sid),
            ("DEBUG", "com.example.db.CartMapper", "SELECT count(*) FROM cart"),
        ]),
        ("app2", "/api/notifications", [
            ("INFO", "com.example.web.NotificationController",
             "通知を取得します sessionId=%s unread=2" % parallel_sid),
            ("DEBUG", "com.example.db.NotificationMapper", "SELECT * FROM notification LIMIT 20"),
        ]),
    ]):
        thread = "http-nio-8080-exec-1"  # わざと同じスレッド名にする
        cur = parallel_at + datetime.timedelta(milliseconds=offset * 3)
        out = [line(cur, thread, "INFO", "com.example.web.RequestLogFilter",
                    "リクエスト開始 GET %s" % path)]
        for level, logger, message in body:
            cur += datetime.timedelta(milliseconds=rnd.randint(20, 60))
            out.append(line(cur, thread, level, logger, message))
        cur += datetime.timedelta(milliseconds=rnd.randint(10, 40))
        out.append(line(cur, thread, "INFO", "com.example.web.RequestLogFilter",
                        "リクエスト終了 status=200 elapsed=%dms"
                        % int((cur - parallel_at).total_seconds() * 1000)))
        lines[name].extend(out)

    # セッション破棄（リクエストの外。どのリクエストにも属さない行になる）
    for si, sid in enumerate(SESSIONS[:2]):
        name = INSTANCES[si % len(INSTANCES)]
        ts = START + datetime.timedelta(minutes=45, seconds=si * 7)
        lines[name].append(line(ts, "Catalina-utility-1", "INFO",
                                "com.example.web.SessionListener",
                                "セッションを破棄しました sessionId=%s reason=timeout" % sid))

    os.makedirs(OUT_DIR, exist_ok=True)
    for name in INSTANCES:
        # ファイル内は時刻順（エントリ単位で並べる。継続行は先頭行にぶら下げてある）
        entries = sorted(lines[name], key=lambda s: s[:23])
        path = os.path.join(OUT_DIR, name + ".log")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.writelines(entries)
        total = sum(s.count("\n") for s in entries)
        print("%s: %d 行 / %d エントリ" % (os.path.normpath(path), total, len(entries)))


if __name__ == "__main__":
    main()
