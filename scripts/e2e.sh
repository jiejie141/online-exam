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
# 判据是「能通过 TCP 真的查到东西」，不是「mysqladmin ping 通」。
#
# ★ 这是我在 CI 上实际踩到的坑：
#   官方 mysql 镜像首次启动时，entrypoint 会先起一个**临时实例**做初始化
#   （日志里是 'Starting temporary server'），初始化完再关掉它、起正式实例。
#   而临时实例**只监听 unix socket、不监听 TCP**（日志里 port: 0）。
#
#   原来的写法是 docker exec mysqladmin ping —— 它走 socket，
#   在临时实例上就会返回成功。于是脚本以为 MySQL 好了，开开心心去建表，
#   CREATE DATABASE 也确实返回 0 —— 但那是写进临时实例的，
#   临时实例一关，库就没了。紧接着应用启动，Flyway 报
#   "Unknown database 'online_exam'"，看起来完全像是 SQL 脚本或应用的问题。
#
#   本地一直没暴露：数据卷里的库早就建好了，容器不会重新初始化，
#   所以永远走不到那条路径上。
#
# 用 -h 127.0.0.1 强制走 TCP：临时实例没有 TCP，连不上；
# 能连上就说明正式实例已经在 3306 上服务了 —— 正是应用需要的那件事。
MYSQL_READY=0
for i in $(seq 1 40); do
  if docker exec exam-mysql mysqladmin -h 127.0.0.1 -uroot -proot ping --silent >/dev/null 2>&1; then
    echo "MySQL ready after ${i} checks (TCP 可查)"
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
# 每一步都检查退出码。
# 原来这两行不检查 —— 而『建表悄悄失败、脚本继续往下走』正是
# 上面那个 CI 问题难查的原因：日志里每一步都是『正常』，
# 直到十几秒后应用启动时报一句风马牛不相及的 Unknown database。
docker exec -i exam-mysql mysql -uroot -proot --default-character-set=utf8mb4 < src/main/resources/db/schema.sql \
  || { echo "!! schema.sql 执行失败"; exit 1; }
docker exec -i exam-mysql mysql -uroot -proot --default-character-set=utf8mb4 < src/main/resources/db/seed.sql \
  || { echo "!! seed.sql 执行失败"; exit 1; }
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
  # grep -a：应用日志里有 ANSI 颜色码等控制字符，grep 会把它当二进制，
  # 直接报 "Binary file matches" 而**不输出行号** —— 于是 n 是空的，
  # sed 截不到任何东西，annotation 里就只剩一个标题。
  # 这个坑让我多花了两轮：明明写好的截取逻辑，看起来像没执行。
  if grep -aq 'BUILD FAILURE\|ERROR' "$LOG" 2>/dev/null; then
    echo "!! 日志里第一处 ERROR 的上下文："
    n=$(grep -an 'ERROR' "$LOG" | head -1 | cut -d: -f1)
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
