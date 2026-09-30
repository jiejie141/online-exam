#!/usr/bin/env bash
# 取卷吞吐 A/B：Redis 开着（缓存命中） vs Redis 停掉（缓存降级全走库）
#
#   bash scripts/bench.sh
#
# 服务由本脚本拉起、测完再关掉。中途会 docker stop exam-redis，
# 测完会 docker start 把它恢复回去。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8099}"
BASE="http://127.0.0.1:${PORT}"
CONCURRENCY="${CONCURRENCY:-100}"
DURATION="${DURATION:-10}"
LOG="target/bench-app.log"

cd "$ROOT"

echo "== 起服务（端口 ${PORT}）=="
mkdir -p target
mvn -B -q spring-boot:run -Dspring-boot.run.arguments="--server.port=${PORT}" > "$LOG" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null; docker start exam-redis >/dev/null 2>&1' EXIT

for i in $(seq 1 60); do
  if curl -s -f "${BASE}/actuator/health" > /dev/null 2>&1; then
    echo "app ready after ${i} checks"
    break
  fi
  sleep 2
done

export PYTHONIOENCODING=utf-8
echo
echo "== A 组：缓存开启（Redis 在线）=="
python scripts/bench_paper.py --base "$BASE" --concurrency "$CONCURRENCY" \
  --duration "$DURATION" --label "缓存开启"

echo
echo "== B 组：缓存降级（停掉 Redis，全部落到 MySQL）=="
docker stop exam-redis > /dev/null 2>&1
sleep 2
python scripts/bench_paper.py --base "$BASE" --concurrency "$CONCURRENCY" \
  --duration "$DURATION" --label "缓存降级"

echo
echo "== 恢复 Redis =="
docker start exam-redis > /dev/null 2>&1
sleep 3
docker ps --format "{{.Names}} {{.Status}}" | grep exam-redis
