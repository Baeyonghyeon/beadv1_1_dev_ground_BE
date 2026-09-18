#!/bin/bash
# k6 도착률 고정(open-loop) 버스트 — 거절률 측정
#
# 사용법: ./bench-scripts/run-k6.sh [버스트TPS] [버스트시간]
#   예:   ./bench-scripts/run-k6.sh 300 90s
#
# xargs 방식(run-burst.sh)과 달리 시스템 상태와 무관하게 초당 목표 건수를 밀어넣는다.
# 처리 못 한 요청이 거절(5xx/timeout)로 드러나므로 "거절률" 을 측정할 수 있다.
#
# ⚠️ k6 는 SUT 자원 예산 밖에서 돈다 (compose 에 자원 제한 없음).
set -u
cd "$(dirname "$0")/.." || exit 1
source bench-scripts/lib.sh
bench_precheck

BURST=${1:-300}
T_BURST=${2:-60s}

# ⚠️ VU 프로비저닝 규칙: maxVUs >= 도착률 × 클라이언트 타임아웃 (리틀의 법칙 상한)
#    VU 가 모자라면 k6 가 목표 도착률을 못 만들고 dropped_iterations 가 올라가 회차가 무효가 된다.
#    기본값 300 TPS × 4s = 1,200 VU. 메모리 여유(약 3GB, VU 당 ~2MB)를 넘지 않는 선.
MAX_VUS=${MAX_VUS:-1200}
PRE_VUS=${PRE_VUS:-400}
REQ_TIMEOUT=${REQ_TIMEOUT:-4s}
NEED=$(python3 -c "print(int($BURST * ${REQ_TIMEOUT%s}))")
if [ "$NEED" -gt "$MAX_VUS" ]; then
  echo "⚠️  필요 VU ${NEED} > maxVUs ${MAX_VUS} — dropped 발생이 확정적입니다." >&2
  echo "    BURST 를 낮추거나 REQ_TIMEOUT 을 줄이거나 MAX_VUS 를 올리세요." >&2
fi
RES=k6-scripts/results; mkdir -p $RES

run_one () {
  local POST=$1
  bench_arm "$POST"
  echo "  초기화 (lag drain 대기 후)..." >&2
  bench_wait_lag 180; bench_reset
  echo "  워밍업 100건..." >&2
  bench_warmup 100 10
  bench_wait_lag 180; bench_reset

  echo "  k6 실행: baseline 30 -> burst ${BURST} TPS (${T_BURST}) ..." >&2
  local S=$(date +%s)
  # --rm: 컨테이너 재사용 안 함 / 자원 제한 없음 (SUT 예산 밖)
  local OUT
  OUT=$($COMPOSE run --rm -T \
        -e BURST="$BURST" -e T_BURST="$T_BURST" \
        -e MAX_VUS="$MAX_VUS" -e PRE_VUS="$PRE_VUS" -e REQ_TIMEOUT="$REQ_TIMEOUT" \
        k6 run /scripts/order-burst.js 2>&1)
  local E=$(date +%s)

  echo "$OUT" > "$RES/k6-raw-$POST.log"
  local LINE
  LINE=$(echo "$OUT" | grep '^K6_RESULT ' | head -1 | sed 's/^K6_RESULT //')
  [ -z "$LINE" ] && { echo "  ⚠️ k6 결과 파싱 실패 — $RES/k6-raw-$POST.log 확인" >&2; LINE="-|-|-|-|-|-|-|-|-|-|-"; }

  # 응답 직후 스냅샷 (비동기 여부)
  local IMM
  IMM=$($MYSQL_N -e "SELECT COUNT(*) FROM Orders WHERE orderStatus='PAID';" 2>/dev/null)
  local HP TB
  HP=$(bench_pmax hikaricp_connections_pending $((S-5)) $((E+5)))
  TB=$(bench_pmax tomcat_threads_busy_threads $((S-5)) $((E+5)))
  echo "  drain 대기..." >&2
  bench_wait_lag 300
  local DB
  DB=$(bench_dbstat)
  echo "$POST|$LINE|$IMM|$DB|$HP|$TB"
}

echo "════════ k6 open-loop: baseline 30 → burst ${BURST} TPS ════════"
A=$(run_one sync)
B=$(run_one kafka)

HDR="전략|처리TPS|p50|p95|p99|max|총요청|실패|실패율%|5xx|연결실패|dropped|응답직후PAID|drain후PAID|PENDING|완결avg(ms)|Hikari대기|Tomcat busy"
{ echo "$HDR"; echo "$A"; echo "$B"; } > $RES/k6-summary.txt
echo
column -t -s'|' $RES/k6-summary.txt
echo
echo "⚠️ dropped 가 0 이 아니면 부하 생성기가 목표 도착률을 못 만든 것 → 그 회차는 무효"
echo "원본: $RES/k6-raw-sync.log, $RES/k6-raw-kafka.log"
