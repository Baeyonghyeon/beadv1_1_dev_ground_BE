#!/bin/bash
# 스파이크 강도 사다리 — "동기는 타임아웃, Kafka 는 무결" 인 경계 구간을 찾는다
#
# 사용법: ./bench-scripts/run-k6-spike-ladder.sh [강도목록] [스파이크길이초]
#   예:   ./bench-scripts/run-k6-spike-ladder.sh "100,130,160,200" 10
set -u
cd "$(dirname "$0")/.." || exit 1
source bench-scripts/lib.sh
bench_precheck

LEVELS=${1:-"100,130,160,200"}
T_SPIKE=${2:-10}
T_REST=${T_REST:-40}
REQ_TIMEOUT=${REQ_TIMEOUT:-5s}
PEAK=$(echo "$LEVELS" | tr ',' '\n' | sort -n | tail -1)
NEED=$(python3 -c "print(int($PEAK * ${REQ_TIMEOUT%s}))")
MAX_VUS=${MAX_VUS:-$(python3 -c "print(int($NEED*1.5))")}
[ "$NEED" -gt "$MAX_VUS" ] && echo "⚠️ 필요 VU $NEED > maxVUs $MAX_VUS" >&2
MEM=$(docker run --rm alpine sh -c "free -m | awk '/Mem:/{print \$7}'" 2>/dev/null || echo 0)
echo "강도=[$LEVELS] 스파이크=${T_SPIKE}s 휴식=${T_REST}s 타임아웃=$REQ_TIMEOUT"
echo "VU ${MAX_VUS}개 ≈ $((MAX_VUS*2))MB / 여유 ${MEM}MB"
echo

RES=k6-scripts/results; mkdir -p $RES

run_one () {
  local POST=$1
  bench_arm "$POST"
  bench_wait_lag 180 >/dev/null 2>&1; bench_reset
  bench_warmup 300 10 >/dev/null 2>&1
  bench_wait_lag 180 >/dev/null 2>&1; bench_reset

  local OUT
  OUT=$($COMPOSE run --rm -T \
        -e LEVELS="$LEVELS" -e T_SPIKE="$T_SPIKE" -e T_REST="$T_REST" \
        -e MAX_VUS="$MAX_VUS" -e REQ_TIMEOUT="$REQ_TIMEOUT" \
        k6 run /scripts/order-spike-ladder.js 2>&1)
  echo "$OUT" > "$RES/ladder-spike-$POST.log"
  bench_wait_lag 300 >/dev/null 2>&1
  local DB=$(bench_dbstat)

  echo "═══ Arm = $POST ═══"
  echo "$OUT" | grep '^K6_SPIKE ' | sed 's/^K6_SPIKE //' | \
    awk -F'|' 'BEGIN{printf "  %-10s %8s %11s %8s %8s %8s\n","스파이크","요청수","타임아웃%","p50","p95","max"}
               {printf "  %-10s %8s %11s %8s %8s %8s\n",$1" TPS",$2,$3,$4,$5,$6}'
  echo "$OUT" | grep '^K6_REST ' | sed 's/^K6_REST //' | awk -F'|' '{printf "  %-10s %8s %11s\n","휴식구간",$2,$3}'
  echo "$OUT" | grep '^K6_RESULT ' | sed 's/^K6_RESULT //' | \
    awk -F'|' '{printf "  전체 %s건 / 실패 %s / 5xx %s / 연결실패 %s / dropped %s\n",$1,$2,$3,$4,$5}'
  echo "  drain후(PAID|PENDING|완결avg ms)=$DB"
  echo
}

echo "════════ 스파이크 강도 사다리: 경계 탐색 ════════"
echo
run_one sync
run_one kafka
echo "원본: $RES/ladder-spike-sync.log, $RES/ladder-spike-kafka.log"
