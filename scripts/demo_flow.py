#!/usr/bin/env python3
"""一条命令走完学生端完整流程：登录 -> 进考试 -> 按标准答案作答 -> 交卷 -> 等出分。

用法：
    python scripts/demo_flow.py               # 用 s002 跑一遍（全对应得 100 分）
    python scripts/demo_flow.py --user s003   # 换个学生
    python scripts/demo_flow.py --base http://127.0.0.1:8099   # 指定服务地址

说明：
- 标准答案取自 seed.sql 的题库，换了题目要同步改 ANSWER_KEY。
- 接口响应按字节读入后交给 json 解析（自动识别 UTF-8），不经过终端本地编码，
  所以 GBK 控制台下也能正常跑；中文输出统一按 UTF-8 写出，避免本地编码报错。
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

# 按 seq 顺序的标准答案（来源：seed.sql）
ANSWER_KEY = ["B", "C", "B", "B", "C", "C", "ABCD", "ABC", "T", "F"]


def call(base, method, path, token=None, body=None):
    req = urllib.request.Request(base + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(req, data, timeout=30) as resp:
        return json.loads(resp.read())


def wait_score(base, token, record_id, timeout_sec=15):
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            body = call(base, "GET", f"/api/records/{record_id}/result", token=token)
            if body.get("code") == 0:
                return body["data"]["score"]
        except urllib.error.HTTPError:
            pass  # 还没判完，继续等
        time.sleep(0.3)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--user", default="s002")
    ap.add_argument("--base", default="http://127.0.0.1:8081")
    args = ap.parse_args()

    # 输出统一 UTF-8：GBK 控制台下中文显示可能乱码，但不会崩
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    try:
        token = call(args.base, "POST", "/api/auth/login",
                     body={"username": args.user, "password": "student123"})["data"]["token"]
    except urllib.error.URLError:
        print("!! 连不上服务，先把它跑起来：  mvn spring-boot:run")
        return 1
    print(f"[1/5] 登录成功（{args.user}）")

    exams = call(args.base, "GET", "/api/exams/available", token=token)["data"]
    if not exams:
        print("!! 没有已发布的考试，先灌种子数据（见 README 第三节）")
        return 1
    exam_id = exams[0]["id"]
    print(f"[2/5] 考试 #{exam_id}：{exams[0]['title']}")

    started = call(args.base, "POST", f"/api/exams/{exam_id}/start", token=token)["data"]
    record_id = started["recordId"]
    paper = started["paper"]
    print(f"[3/5] 答卷 #{record_id}，共 {len(paper)} 题，逐题作答：")
    for item in paper:
        ans = ANSWER_KEY[item["seq"] - 1]
        try:
            call(args.base, "POST", f"/api/records/{record_id}/answers", token=token,
                 body={"questionId": item["id"], "answer": ans})
            print(f"      第{item['seq']}题 -> {ans}")
        except urllib.error.HTTPError as e:
            print(f"      第{item['seq']}题保存失败（http {e.code}，多半是这份答卷已交过）")

    call(args.base, "POST", f"/api/records/{record_id}/submit", token=token)
    print("[4/5] 已交卷，判分走 RabbitMQ 异步，等出分 ...")

    score = wait_score(args.base, token, record_id)
    if score is None:
        print("!! 15 秒内没等到判分结果，稍后手动查："
              f"GET {args.base}/api/records/{record_id}/result")
        return 1
    print(f"[5/5] 成绩 = {score} / 100")
    return 0


if __name__ == "__main__":
    sys.exit(main())
