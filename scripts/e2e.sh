#!/usr/bin/env bash
# 端到端冒烟：起依赖 -> 建表 -> 灌演示数据 -> 起服务 -> 跑接口校验 -> 关服务
#
# 一条命令跑完： bash scripts/e2e.sh
# 目的是让"我这边能跑通"这句话可以被别人复现，而不是靠手点一遍。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8099}"
BASE="http://127.0.0.1:${PORT}"
LOG="target/e2e-app.log"

cd "$ROOT"

echo "== 1/6 起依赖 =="
docker compose up -d

echo "== 2/6 等 MySQL 就绪 =="
MYSQL_READY=0
for i in $(seq 1 40); do
  if docker exec exam-mysql mysqladmin ping -uroot -proot --silent 2>/dev/null; then
    echo "MySQL ready after ${i} checks"
    MYSQL_READY=1
    break
  fi
  sleep 2
done

# 等不到就得在这里停下。少了这段，循环只是走完，脚本会继续去建表，
# 然后挂在一条难懂的 "docker exec" 报错上 —— 看起来像 SQL 有问题，
# 实际是 MySQL 根本没起来。下面等应用启动的地方就是这么兜的，这里保持一致。
if [ "$MYSQL_READY" -ne 1 ]; then
  echo "!! MySQL 80 秒内没就绪，容器状态："
  docker compose ps || true
  echo "!! MySQL 日志尾部："
  docker compose logs --tail 40 mysql || true
  exit 1
fi

echo "== 3/6 建表 + 灌数据 =="
docker exec -i exam-mysql mysql -uroot -proot --default-character-set=utf8mb4 < src/main/resources/db/schema.sql
docker exec -i exam-mysql mysql -uroot -proot --default-character-set=utf8mb4 < src/main/resources/db/seed.sql
docker exec exam-mysql mysql -uroot -proot -e \
  "SELECT (SELECT COUNT(*) FROM question) AS questions, \
          (SELECT COUNT(*) FROM exam_question) AS paper_items, \
          (SELECT COUNT(*) FROM exam_user) AS users, \
          (SELECT COUNT(*) FROM exam WHERE status='published') AS published_exams;" online_exam

echo "== 4/6 起服务（端口 ${PORT}）=="
mkdir -p target
# JWT 密钥：应用不再内置默认值，不设就起不来（见 JwtUtil 的启动校验）。
# 这里用固定值而不是随机值，是为了让"失败重跑"的结果可比较；
# 它只在本地 8099 端口上活几分钟，不承担任何真实安全性。
export EXAM_JWT_SECRET="${EXAM_JWT_SECRET:-e2e-only-local-secret-not-for-production-0123456789abcdef}"
# 显式指定端口：某些环境会注入 SERVER__PORT 把 server.port 顶掉
# 去掉 -q：它会把 Maven 的 INFO 一起压掉，而启动失败的原因经常就在
# 被压掉的那几行里（CI 上就是只拿到一段 [ERROR] 套话，看不到真正的原因）。
# 应用自己的日志本来就被下面的 > "$LOG" 收着，这里加上 Maven 的构建输出
# 只会让文件大一点，换来的是失败时能直接看到原因。
mvn -B spring-boot:run -e -Dspring-boot.run.arguments="--server.port=${PORT}" > "$LOG" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null' EXIT

# 等待上限 5 分钟，不是 2 分钟。
#
# 本地 ~/.m2 是满的，应用十几秒就起来，所以 2 分钟看起来绰绰有余。
# 但在一个干净的 CI runner 上，spring-boot:run 可能还要现下插件 ——
# 那时 2 分钟等到的不是"应用坏了"，而是"还没下完"，
# 而这两种情况在日志里长得一模一样（都是健康检查一直失败）。
# 把上限放宽到远大于任何合理的冷启动时间，失败就真的是失败了。
for i in $(seq 1 150); do
  if curl -s -f "${BASE}/actuator/health" > /dev/null 2>&1; then
    echo "app ready after ${i} checks"
    break
  fi
  sleep 2
done

if ! curl -s -f "${BASE}/actuator/health" > /dev/null 2>&1; then
  echo "!! 服务没起来，日志尾部："
  tail -40 "$LOG"
  # Maven 的报错通常在日志中段（后面还接着它自己的一堆 [ERROR] 套话），
  # 只 tail 尾部经常正好错过第一现场。再把第一个 ERROR 的上下文捞一遍。
  if grep -q 'BUILD FAILURE\|ERROR' "$LOG" 2>/dev/null; then
    echo "!! 日志里第一处 ERROR 的上下文："
    n=$(grep -n 'ERROR' "$LOG" | head -1 | cut -d: -f1)
    from=$((n > 12 ? n - 12 : 1))
    sed -n "${from},$((n + 20))p" "$LOG"
  fi
  exit 1
fi

echo "== 5/6 接口校验 =="
# Windows 控制台默认 GBK，不设这个中文题干会打成一串转义符
# tee 一份到文件：失败时 CI 的注释（annotation）要从这里取摘要，
# 匿名看 job 页面是拿不到日志正文的（下载接口要认证）。
PYTHONIOENCODING=utf-8 python scripts/e2e_check.py --base "$BASE" 2>&1 | tee target/e2e-check.log
# 上面是管道，$? 会变成 tee 的退出码 —— 用 PIPESTATUS 拿 python 的。
# 这一点和 ci.yml 里那个 docker run 是同一个坑，别改成 $?。
CHECK_RC=${PIPESTATUS[0]}

echo "== 6/6 收尾 =="
if [ "$CHECK_RC" -ne 0 ]; then
  echo "!! 校验失败，服务日志尾部："
  tail -40 "$LOG"
fi
exit $CHECK_RC
