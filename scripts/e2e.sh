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

# 校验工具先行探测（fail-fast）：
# Windows 上 `python` 可能命中 Microsoft Store 的「应用执行别名」存根 ——
# 它退出码非 0 且一行输出都没有。实际踩到：e2e 一路绿灯走到第 5 步报
# "校验失败"，e2e-check.log 却是空的，排查方向被带偏到接口和应用上，
# 实际是校验脚本从未执行。存根机器上通常还有 py 启动器，依次回退。
PYTHON_BIN=""
if python -c "pass" >/dev/null 2>&1; then
  PYTHON_BIN="python"
elif py -3 -c "pass" >/dev/null 2>&1; then
  PYTHON_BIN="py -3"
  echo "（python 不可用，改用 py 启动器）"
else
  echo "!! 找不到可用的 Python，接口校验将无法执行，在这里停下。"
  echo "   Windows 常见原因：系统开了 python.exe 应用执行别名（商店存根），去"
  echo "   设置 > 应用 > 高级应用设置 > 应用执行别名 里关掉，或安装 Python 后重试。"
  exit 1
fi

# JWT 密钥要在 compose up **之前**导出：compose 会校验所有 service 的变量插值，
# 哪怕引用它的 app service 属于未启用的 profile —— 放在后面的话，每次起依赖
# 都会打一条 "The EXAM_JWT_SECRET variable is not set" 的警告。
# 用固定值而不是随机值，是为了让"失败重跑"的结果可比较；
# 它只在本地 8099 端口上活几分钟，不承担任何真实安全性。
export EXAM_JWT_SECRET="${EXAM_JWT_SECRET:-e2e-only-local-secret-not-for-production-0123456789abcdef}"

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
# JWT 密钥已在脚本开头（compose up 之前）导出，应用不设密钥起不来（见 JwtUtil）。
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
PYTHONIOENCODING=utf-8 $PYTHON_BIN scripts/e2e_check.py --base "$BASE" 2>&1 | tee target/e2e-check.log
# 上面是管道，$? 会变成 tee 的退出码 —— 用 PIPESTATUS 拿 python 的。
# 这一点和 ci.yml 里那个 docker run 是同一个坑，别改成 $?。
CHECK_RC=${PIPESTATUS[0]}

echo "== 6/6 收尾 =="
if [ "$CHECK_RC" -ne 0 ]; then
  # 先分清「校验失败了」和「校验根本没跑成」：日志为空说明 python 进程
  # 没有产出任何东西（工具缺失/被存根截胡/当场崩溃），这时去翻应用日志
  # 只会白费功夫 —— 两者要用不同的方式修。
  if [ ! -s target/e2e-check.log ]; then
    echo "!! 校验日志为空：校验脚本没有真正执行（不是接口校验失败）。"
    echo "   检查 PYTHON_BIN=$PYTHON_BIN 是否指向真实解释器。"
  else
    echo "!! 校验失败，服务日志尾部："
    tail -40 "$LOG"
  fi
fi
exit $CHECK_RC
