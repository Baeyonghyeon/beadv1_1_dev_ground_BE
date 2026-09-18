#!/bin/bash
# 순간 스파이크 흡수 비교 — 동기 vs Kafka
#
# 사용법: ./bench-scripts/run-k6-spike.sh [스파이크TPS] [스파이크길이]
#   예:   ./bench-scripts/run-k6-spike.sh 500 8s
#
# 지속 부하 테스트(run-k6.sh)와 다른 점:
#   스파이크 뒤에 한산한 구간을 두어 "밀린 일을 나중에 처리할 여유" 를 만든다.
#   지속 부하에서는 그 여유가 없어 큐의 이점이 드러나지 않는다.
set -u
cd "$(dirname "$0")/.." || exit 1
source bench-scripts/lib.sh
bench_precheck

SPIKE=${1:-500}
T_SPIKE=${2:-8s}
REQ_TIMEOUT=${REQ_TIMEOUT:-2s}
NEED=$(python3 -c "print(int($SPIKE * ${REQ_TIMEOUT%s}))")
MAX_VUS=${MAX_VUS:-$NEED}
[ "$NEED" -gt "$MAX_VUS" ] && echo "⚠️  필요 VU ${NEED} > maxVUs ${MAX_VUS} — dropped 발생 확정" >&2
MEM=$(docker run --rm alpine sh -c "free -m | awk '/Mem:/{print \$7}'" 2>/dev/null || echo 0)
echo "VU ${MAX_VUS}개 ≈ $((MAX_VUS*2))MB 예상 / VM 여유 ${MEM}MB"

RES=k6-scripts/results; mkdir -p $RES

run_one () {
  local POST=$1
  bench_arm "$POST"
  echo "  초기화..." >&2
  bench_wait_lag 180 >/dev/null 2>&1; bench_reset
  echo "  워밍업 300건..." >&2
  bench_warmup 300 10 >/dev/null 2>&1
  bench_wait_lag 180 >/dev/null 2>&1; bench_reset

  echo "  스파이크 실행: 평상 20 → ${SPIKE} TPS (${T_SPIKE}) → 평상 20 ..." >&2
  local OUT
  OUT=$($COMPOSE run --rm -T \
        -e SPIKE="$SPIKE" -e T_SPIKE="$T_SPIKE" \
        -e MAX_VUS="$MAX_VUS" -e REQ_TIMEOUT="$REQ_TIMEOUT" \
        k6 run /scripts/order-spike.js 2>&1)
  echo "$OUT" > "$RES/spike-raw-$POST.log"

  # 응답 종료 직후 스냅샷 (아직 큐가 남아 있는 시점)
  local IMM=$($MYSQL_N -e "SELECT COUNT(*) FROM Orders WHERE orderStatus='PAID';" 2>/dev/null)
  local PEND=$($MYSQL_N -e "SELECT COUNT(*) FROM Orders WHERE orderStatus='PENDING';" 2>/dev/null)
  echo "  drain 대기..." >&2
  bench_wait_lag 300 >/dev/null 2>&1
  local DB=$(bench_dbstat)

  echo "═══ Arm = $POST ═══"
  echo "$OUT" | grep '^K6_PHASE ' | sed 's/^K6_PHASE //' | \
    awk -F'|' 'BEGIN{printf "  %-9s %8s %9s %8s %8s %8s\n","구간","요청수","거절률%","p50","p95","max"}
               {printf "  %-9s %8s %9s %8s %8s %8s\n",$1,$2,$3,$4,$5,$6}'
  echo "$OUT" | grep '^K6_RESULT ' | sed 's/^K6_RESULT //' | \
    awk -F'|' '{printf "  전체: %s건, 거절 %s건(%s%%), 5xx %s, 연결실패 %s, dropped %s\n",$2,$3,$4,$5,$6,$7}'
  echo "  응답종료직후 PAID=$IMM PENDING=$PEND  |  drain후(PAID|PENDING|완결avg ms)=$DB"
  echo
}

echo "════════ 순간 스파이크 흡수 비교: 20 → ${SPIKE} TPS (${T_SPIKE}) → 20 ════════"
echo
run_one sync
run_one kafka
echo "원본: $RES/spike-raw-sync.log, $RES/spike-raw-kafka.log"
