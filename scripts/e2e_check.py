#!/usr/bin/env python3
"""端到端接口校验 + 判分延迟实测。

用法： python scripts/e2e_check.py --base http://127.0.0.1:8099

做两件事：
  1. 校验 —— 用一个"答案已知"的答卷跑完整流程，断言总分必须等于手算出来的 87 分。
             这不是"接口返回 200 就算过"，而是判分结果必须和数字对得上。
  2. 实测 —— 批量造 N 个学生各交一份卷，记录"交卷 -> 出分"的延迟分布。
             这个数字是简历上唯一能写、也唯一必须在现场能复现的那种数字。
"""
import argparse
import json
import statistics
import re
import sys
import time
import urllib.error
import urllib.request

FAILS = []
PASSES = 0


def check(name, ok, detail=""):
    global PASSES
    if ok:
        PASSES += 1
        print(f"  PASS  {name}" + (f"  ({detail})" if detail else ""))
    else:
        FAILS.append(name)
        print(f"  FAIL  {name}  {detail}")


def decode_body(resp):
    """按响应类型解码，不假设一定是 JSON。

    /actuator/prometheus 返回的是**纯文本**指标格式（# HELP / # TYPE / 名字 值）。
    对它无条件 json.loads 会抛 JSONDecodeError —— 而这个异常抛在 check()
    之前，会让整个脚本崩在第 12 步，**前面已经跑过的检查结果也一起丢掉**，
    看起来像"整个 e2e 挂了"，实际只是最后一步的解析方式不对。
    """
    raw = resp.read().decode() or ""
    if "json" in (resp.headers.get("Content-Type") or ""):
        return json.loads(raw or "{}")
    return raw


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, token=None, body=None):
        req = urllib.request.Request(self.base + path, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        data = json.dumps(body).encode() if body is not None else None
        try:
            with urllib.request.urlopen(req, data, timeout=30) as resp:
                return resp.status, decode_body(resp)
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            try:
                return e.code, json.loads(raw or "{}")
            except json.JSONDecodeError:
                return e.code, {"raw": raw}

    def raw(self, path, token=None):
        """拿原始响应文本，用于检查响应里有没有漏出不该出现的字段"""
        req = urllib.request.Request(self.base + path)
        if token:
            req.add_header("Authorization", "Bearer " + token)
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read().decode()


def login(api, username, password):
    st, body = api.call("POST", "/api/auth/login",
                        body={"username": username, "password": password})
    if st != 200:
        raise RuntimeError(f"login failed {username}: {st} {body}")
    return body["data"]["token"]


def submit_and_wait(api, token, record_id, timeout_sec=30):
    t0 = time.time()
    st, body = api.call("POST", f"/api/records/{record_id}/submit", token=token)
    if st != 200:
        return None, None, f"submit http {st}: {body}"
    submit_ms = int((time.time() - t0) * 1000)
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        st2, body2 = api.call("GET", f"/api/records/{record_id}/result", token=token)
        if st2 == 200:
            return body2["data"]["score"], int((time.time() - t0) * 1000), None
        # 轮询间隔直接决定测出来的延迟精度。之前用 50ms，测出来的数被量化成
        # 50/100/150ms 三档，p99 完全失真；降到 10ms 才看得出真实分布。
        time.sleep(0.01)
    return None, None, "grading timeout"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8099")
    ap.add_argument("--students", type=int, default=50, help="批量造多少学生跑延迟实测")
    args = ap.parse_args()

    api = Api(args.base)

    print("\n[1] 登录")
    tt = login(api, "t001", "teacher123")
    check("老师 t001 登录", True)
    st, body = api.call("POST", "/api/auth/login",
                        body={"username": "s001", "password": "wrong-password"})
    # HTTP 状态和 body.code 必须一致：只判断 HTTP 状态的调用方也要能认出这是鉴权失败
    check("错误口令返回 401（HTTP 状态与 body code 一致）",
          st == 401 and body.get("code") == 401, f"http={st} code={body.get('code')}")
    ss = login(api, "s001", "student123")
    check("学生 s001 登录", True)

    print("\n[2] 可参加的考试")
    st, body = api.call("GET", "/api/exams/available", token=ss)
    exams = body.get("data") or []
    check("能看到已发布的考试", len(exams) >= 1, f"{len(exams)} 场")
    exam_id = exams[0]["id"] if exams else 1

    print("\n[3] 进入考试")
    st, body = api.call("POST", f"/api/exams/{exam_id}/start", token=ss)
    check("进入考试", st == 200 and body.get("code") == 0, str(body.get("message", "")))
    record_id = body["data"]["recordId"]
    st, body2 = api.call("POST", f"/api/exams/{exam_id}/start", token=ss)
    check("重复进入返回同一份答卷（不重开）",
          body2["data"]["recordId"] == record_id,
          f"{record_id} vs {body2['data']['recordId']}")

    print("\n[4] 卷面不能泄露答案")
    raw = api.raw(f"/api/exams/{exam_id}/paper", token=ss)
    check("卷面 JSON 里没有 answer 字段", '"answer"' not in raw)
    st, body = api.call("GET", f"/api/exams/{exam_id}/paper", token=ss)
    paper = body["data"]
    check("卷面 10 道题", len(paper) == 10, f"{len(paper)} 题")

    print("\n[5] 按已知答案作答")
    # 卷面按 seq 排，对应 seed.sql 里的 10 道题
    # Q1-Q6 单选各 10 分全对 = 60
    # Q7 多选 ABCD 全对 = 15
    # Q8 多选答案 ABC，故意只答 AB -> 漏选半分 = 7
    # Q9 判断答案 T，答 T = 5
    # Q10 判断答案 F，故意答 T = 0
    # 手算总分 = 60 + 15 + 7 + 5 + 0 = 87
    answers = ["B", "C", "B", "B", "C", "C", "ABCD", "AB", "T", "T"]
    expected = 87

    # 这个脚本**不保证库是干净的**：直接对着一个已经跑过的库再跑一遍，
    # s001 那份答卷已经是 submitted/graded，saveAnswer 会（正确地）
    # 拒绝修改已交卷的答卷，10 个保存全返回 400。
    #
    # 以前这里循环里报 FAIL、循环后却写死 check(..., True) ——
    # 那个断言永远不会失败，是个装饰。结果是"10 条 FAIL + 1 条 PASS"
    # 同时出现，看日志的人得自己猜哪个算数。
    #
    # 现在按答卷状态区分两种情况：还开着就真保存并断言条数，
    # 已经交了就说明"跳过的原因"，不再拿一个不可能失败的断言凑数。
    st, body = api.call("POST", f"/api/exams/{exam_id}/start", token=ss)
    already_done = body["data"].get("status") in ("submitted", "graded")
    if already_done:
        print(f"  SKIP  答卷已是 {body['data']['status']}，跳过作答（重复运行同一套库）")
    else:
        saved = 0
        for item, ans in zip(paper, answers):
            st, _ = api.call("POST", f"/api/records/{record_id}/answers", token=ss,
                             body={"questionId": item["id"], "answer": ans})
            if st == 200:
                saved += 1
            else:
                check(f"保存第 {item['seq']} 题", False, f"http {st}")
        # 断言的是真的条数，不是写死的 True
        check("10 道题作答全部保存成功", saved == len(answers),
              f"成功 {saved}/{len(answers)}")

    print("\n[6] 交卷与判分（异步 MQ）")
    score, latency_ms, err = submit_and_wait(api, ss, record_id)
    check("判分完成", err is None, err or "")
    check(f"总分等于手算值 {expected}", score == expected, f"实际 {score}")
    if latency_ms is not None:
        print(f"      交卷->出分 延迟 {latency_ms} ms")

    # 成绩页要能画出"哪道题、我答了什么、对还是错"。
    # ExamAnswer 里只有 questionId，没有题干 —— 所以 result 接口必须
    # 同时带回不含答案的卷面（PaperItem），前端按 questionId 对齐。
    # 这里顺带钉住两件事：paper 长度对得上、且它**依然不含 answer 字段**
    # （题目答案不能因为多带了一份卷面就漏进学生端）。
    st, body = api.call("GET", f"/api/records/{record_id}/result", token=ss)
    res = body["data"]
    check("成绩单带回本场 examId", res.get("examId") == exam_id, str(res.get("examId")))
    check("成绩单带回 10 题卷面（用于渲染题干）", len(res.get("paper") or []) == 10,
          f"{len(res.get('paper') or [])}")
    leaked = [q for q in (res.get("paper") or []) if "answer" in q or "correctAnswer" in q]
    check("成绩单卷面没有泄露答案字段", not leaked, str(leaked[:1]))

    print("\n[7] 重复交卷幂等")
    st, body = api.call("POST", f"/api/records/{record_id}/submit", token=ss)
    st2, body2 = api.call("GET", f"/api/records/{record_id}/result", token=ss)
    check("重复交卷后分数不变", body2["data"]["score"] == expected,
          f"{body2['data']['score']}")

    print("\n[8] 越权访问")
    s2 = login(api, "s002", "student123")
    st, body = api.call("GET", f"/api/records/{record_id}/result", token=s2)
    check("别人查不到我的答卷（403）", body.get("code") == 403, f"code={body.get('code')}")
    # 卷面比成绩单更敏感：成绩单泄露的是一个人的分数，卷面泄露的是整场考试的题。
    # s002 从没进过这场考试，所以这里必须 403 —— 而不能像以前那样
    # 只要登录着、只要能猜到 examId 就把题拿走。
    st, body = api.call("GET", f"/api/exams/{exam_id}/paper", token=s2)
    check("没进过这场考试就取不到卷面（403）", body.get("code") == 403,
          f"code={body.get('code')}")
    # 反过来要确认这道闸门没有误伤：s001 是真进过场的，[4] 里能取到卷面，
    # 这里再取一次，证明"加校验"没有变成"谁都取不到"。
    st, body = api.call("GET", f"/api/exams/{exam_id}/paper", token=ss)
    check("进过场的学生仍然能取卷面", body.get("code") == 0 and len(body.get("data") or []) == 10,
          f"code={body.get('code')}")

    print("\n[9] 老师端统计")
    st, body = api.call("GET", f"/api/teacher/exams/{exam_id}/stats", token=tt)
    stats = body["data"]
    check("已判分人数 >= 1", stats["total"] >= 1, str(stats))
    st, body = api.call("GET", f"/api/teacher/exams/{exam_id}/distribution", token=tt)
    check("成绩分布非空", len(body["data"]) >= 1, str(body["data"]))
    st, body = api.call("GET", f"/api/teacher/exams/{exam_id}/accuracy", token=tt)
    acc = body["data"]
    check("逐题正确率返回 10 题", len(acc) == 10, f"{len(acc)}")
    # 漏选的那道（卷面第 8 题）给了半分但 correct=false，正确率必须是 0。
    # 用 paper 里实际的 id 而不是写死 8：题号是自增主键，重跑种子数据后不一定从 1 开始。
    missed_id = paper[7]["id"]
    missed = [q for q in acc if q["questionId"] == missed_id]
    check("漏选题的正确率是 0（不算对）",
          bool(missed) and missed[0]["correctCount"] == 0, str(missed))
    # 只断言 correctCount 是不够的：曾经 SQL 把 COUNT(*) 别名写成 attempt_count，
    # 而 DTO 字段叫 attempt，MyBatis 对不上时不报错、只把 attempt 留成 null。
    # 结果接口照样 200、correctCount 也对，只有老师端"作答人数"整列空白。
    # 所以这里必须把 attempt 也钉住 —— "有值"和"是 1"分开断言，
    # 前者能定位"字段根本没映射上"，后者能定位"算错了"。
    check("逐题正确率的 attempt 字段有值（别名映射没断）",
          all(q.get("attempt") is not None for q in acc),
          str([q for q in acc if q.get("attempt") is None][:3]))
    check("作答人数就是已判分的答卷数（1 人）",
          all(q.get("attempt") == 1 for q in acc),
          str({q["questionId"]: q.get("attempt") for q in acc}))

    print("\n[10] 权限边界")
    st, body = api.call("GET", f"/api/teacher/exams/{exam_id}/stats", token=ss)
    check("学生访问老师接口被拒（403）", st == 403, f"http {st}")

    # 老师与老师之间也要隔离。只校验 ROLE_teacher 是不够的 ——
    # 那回答的是"你是不是老师"，而不是"这场考试是不是你的"。
    # 少了这一层，任何老师只要猜到 examId 就能看别人的全班成绩、
    # 甚至把别人还没考完的考试直接关掉。
    api.call("POST", "/api/teacher/users", token=tt,
             body={"username": "t002", "password": "teacher123",
                   "realName": "另一位老师", "role": "teacher"})
    t2 = login(api, "t002", "teacher123")
    st, body = api.call("GET", "/api/teacher/exams", token=t2)
    check("新老师看不到别人建的考试列表",
          all(e["id"] != exam_id for e in (body.get("data") or [])),
          f"{len(body.get('data') or [])} 场")
    st, body = api.call("GET", f"/api/teacher/exams/{exam_id}/stats", token=t2)
    check("新老师查不到别人考试的成绩（403）", body.get("code") == 403,
          f"code={body.get('code')}")
    st, body = api.call("POST", f"/api/teacher/exams/{exam_id}/close", token=t2)
    check("新老师关不掉别人的考试（403）", body.get("code") == 403,
          f"code={body.get('code')}")

    # ---------------- 延迟实测 ----------------
    print(f"\n[11] 判分延迟实测（另造 {args.students} 名学生各交一份卷）")
    latencies = []
    submit_latencies = []
    scores = []
    ok = 0
    for i in range(args.students):
        username = f"bench{i:03d}"
        st, body = api.call("POST", "/api/teacher/users", token=tt,
                            body={"username": username, "password": "student123",
                                  "realName": f"压测{i}", "role": "student"})
        if st != 200 and body.get("code") != 400:
            continue
        tok = login(api, username, "student123")
        st, body = api.call("POST", f"/api/exams/{exam_id}/start", token=tok)
        if st != 200:
            continue
        rid = body["data"]["recordId"]
        for item, ans in zip(paper, answers):
            api.call("POST", f"/api/records/{rid}/answers", token=tok,
                     body={"questionId": item["id"], "answer": ans})
        t0 = time.time()
        st, _ = api.call("POST", f"/api/records/{rid}/submit", token=tok)
        submit_ms = int((time.time() - t0) * 1000)
        score, total_ms, err = submit_and_wait(api, tok, rid)
        if err is None:
            ok += 1
            latencies.append(total_ms)
            submit_latencies.append(submit_ms)
            scores.append(score)

    if latencies:
        latencies.sort()
        submit_latencies.sort()
        p = lambda arr, q: arr[min(len(arr) - 1, int(len(arr) * q))]
        print(f"      成功判分 {ok} 份")
        print(f"      交卷接口耗时   avg {int(statistics.mean(submit_latencies))} ms"
              f"   p99 {p(submit_latencies, 0.99)} ms")
        print(f"      交卷->出分延迟  avg {int(statistics.mean(latencies))} ms"
              f"   p50 {p(latencies, 0.5)} ms   p99 {p(latencies, 0.99)} ms"
              f"   max {latencies[-1]} ms")
        check("批量判分全部完成", ok == len(latencies) and ok >= args.students * 0.9,
              f"{ok}/{args.students}")
        check("批量判分分数一致（都是 87）", set(scores) == {87}, f"出现过的分数 {set(scores)}")
    else:
        check("批量判分", False, "一份都没判成功")

    print("\n[12] 漏判指标（判分失败的发现手段）")
    # 判分走 MQ，消费者有意不把异常抛回 Broker（抛回去会无限重投），
    # 所以"有卷子没判上"这件事必须靠指标发现，不能靠翻日志 ——
    # 日志回答不了"此刻积压了几份"。
    #
    # 这里直接查 Prometheus 端点，验的是**整条链路**：
    # Mapper 的 SQL 能跑 -> 扫描任务把数写进 Gauge -> Actuator 暴露出来。
    # 单测只能验到第二段，SQL 和暴露这两段只有连真库跑才验得到 —— 这正是本脚本存在的理由。
    # 带老师令牌：/actuator/prometheus 不在匿名放行名单里
    # （只有 health 和 info 是），不带令牌会 401/403。
    # 用 raw() 而不是 call()：这个端点的响应体是纯文本指标，
    # call() 的语义是"JSON 接口"。这里要的就是原始文本。
    try:
        raw = api.raw("/actuator/prometheus", token=tt)
        prom_ok = True
    except urllib.error.HTTPError as e:
        raw = ""
        prom_ok = False
        prom_code = e.code
    except Exception as e:  # noqa: BLE001
        raw = ""
        prom_ok = False
        prom_code = type(e).__name__
    check("Prometheus 端点可访问（带令牌）", prom_ok,
          "http 200" if prom_ok else f"取不到：{prom_code}")
    m = re.search(r"^exam_grading_unscored\s+([0-9.]+)$", raw, re.M)
    check("exam_grading_unscored 指标已暴露", m is not None,
          "" if m else "未找到，检查 GradingMetrics 是否被注册、management.endpoints 是否含 prometheus")
    if m:
        # 这一步跑在刚交完卷、判分已完成的干净库上，所以必须是 0。
        # 非 0 意味着有卷子交了但没判上 —— 那正是这个指标要报的警。
        check("刚交完卷的库上漏判数为 0", float(m.group(1)) == 0.0, f"实际 {m.group(1)}")
    check("exam_grading_failed_total 指标已暴露",
          "exam_grading_failed_total" in raw,
          "" if "exam_grading_failed_total" in raw else "未找到，检查 GradingMetrics 的 Counter 是否注册")

    print("\n" + "=" * 58)
    print(f"通过 {PASSES} 项，失败 {len(FAILS)} 项")
    if FAILS:
        for f in FAILS:
            print(f"  - {f}")
    print("=" * 58)
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
