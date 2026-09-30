#!/usr/bin/env python3
"""取卷接口吞吐实测。

用法：
  python scripts/bench_paper.py --base http://127.0.0.1:8099 --exam-id 1 \
      --concurrency 100 --duration 10

为什么测"取卷"而不是别的接口：开考瞬间几百人在同一分钟里拉同一份卷子，
这是整个系统唯一会出现真实尖峰的地方，也是缓存唯一有意义的地方。

配合 scripts/bench.sh 做 A/B：
  Redis 开着   -> 缓存命中，走 Redis
  Redis 停掉   -> 缓存降级，全部落到 MySQL

【关于数字可信度 —— 这段是踩过坑才写的】
第一版每个请求都新建一条 TCP 连接（urllib 默认行为），结果：
  - 同一份代码连跑两轮，缓存组 QPS 一次 671 一次 417，差 60%；
  - 缓存命中的 p50 居然要 243ms —— 一次 Redis GET 不可能这么慢。
原因都在客户端：每请求一次三次握手 + TIME_WAIT 堆积 + Python GIL 抢不过来，
测出来的其实是"我的压测脚本有多快"，不是"服务端有多快"。
这一版改成每线程一条长连接复用（keep-alive），同一轮内数字才稳定下来。
"""
import argparse
import http.client
import json
import statistics
import threading
import time
import urllib.parse
import urllib.request

LAT = []
LAT_LOCK = threading.Lock()
OK = [0]
ERR = [0]
ERR_KINDS = {}


def login(base, username, password):
    req = urllib.request.Request(base + "/api/auth/login", method="POST")
    req.add_header("Content-Type", "application/json")
    req.data = json.dumps({"username": username, "password": password}).encode()
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())["data"]["token"]


class Client:
    """每个线程一条长连接。断了自己重连，不让单次错误终止整个线程。"""

    def __init__(self, host, path, token):
        self.host = host
        self.path = path
        self.headers = {"Authorization": "Bearer " + token}
        self.conn = self._new()

    def _new(self):
        return http.client.HTTPConnection(self.host, timeout=30)

    def one(self):
        t0 = time.time()
        try:
            self.conn.request("GET", self.path, headers=self.headers)
            resp = self.conn.getresponse()
            resp.read()
            status = resp.status
        except Exception as e:
            # 连接被服务端关掉（keep-alive 超时等）就重来一条，不计为失败
            try:
                self.conn.close()
            except Exception:
                pass
            self.conn = self._new()
            raise e
        cost = int((time.time() - t0) * 1000)
        return status, cost


def worker(client, deadline):
    while time.time() < deadline:
        try:
            status, cost = client.one()
        except Exception as e:
            kind = f"{type(e).__name__}: {getattr(e, 'reason', e)}"
            with LAT_LOCK:
                ERR[0] += 1
                ERR_KINDS[kind] = ERR_KINDS.get(kind, 0) + 1
            continue
        with LAT_LOCK:
            if status == 200:
                LAT.append(cost)
                OK[0] += 1
            else:
                ERR[0] += 1
                ERR_KINDS[f"HTTP {status}"] = ERR_KINDS.get(f"HTTP {status}", 0) + 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8099")
    ap.add_argument("--exam-id", type=int, default=1)
    ap.add_argument("--concurrency", type=int, default=100)
    ap.add_argument("--duration", type=int, default=10)
    ap.add_argument("--label", default="")
    args = ap.parse_args()

    parsed = urllib.parse.urlparse(args.base)
    host = parsed.netloc
    base = args.base.rstrip("/")
    path = f"/api/exams/{args.exam_id}/paper"
    token = login(base, "s001", "student123")

    # 预热：填缓存 + 让 JIT 把热点路径编译掉。
    # 不预热测出来的数字，面试现场复现不出来 —— 这个亏吃过一次。
    warm = Client(host, path, token)
    for _ in range(300):
        warm.one()
    warm.conn.close()

    deadline = time.time() + args.duration
    clients = [Client(host, path, token) for _ in range(args.concurrency)]
    threads = [threading.Thread(target=worker, args=(c, deadline)) for c in clients]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.time() - t0
    for c in clients:
        c.conn.close()

    lat = sorted(LAT)
    p = lambda q: lat[min(len(lat) - 1, int(len(lat) * q))] if lat else 0
    qps = OK[0] / elapsed if elapsed > 0 else 0
    label = f"[{args.label}] " if args.label else ""
    print(f"{label}并发 {args.concurrency}  时长 {elapsed:.1f}s  "
          f"成功 {OK[0]}  失败 {ERR[0]}")
    print(f"{label}QPS {qps:.0f}   延迟 avg {int(statistics.mean(lat)) if lat else 0} ms  "
          f"p50 {p(0.5)} ms  p95 {p(0.95)} ms  p99 {p(0.99)} ms  max {lat[-1] if lat else 0} ms")
    if ERR_KINDS:
        print(f"{label}失败原因：{ERR_KINDS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
