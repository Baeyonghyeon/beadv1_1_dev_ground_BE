#!/bin/bash
# 버스트 비교 — 동기 vs Kafka 후처리를 같은 조건에서 측정
#
# 사용법: ./bench-scripts/run-burst.sh [총건수] [동시수]
#   기본값: 600건, 동시 60
#
# 두 Arm 을 연속으로 돌리고 비교표를 출력한다. 약 3~5분 소요.
set -u
cd "$(dirname "$0")/.." || exit 1
source bench-scripts/lib.sh
bench_precheck

N=${1:-600}; C=${2:-60}
RES=bench-scripts/results; mkdir -p $RES

run_one () {
  local POST=$1
  local OUT=$RES/burst-$POST.txt   # ⚠️ 같은 local 문에서 $POST 를 참조하면 set -u 에 걸린다
  bench_arm "$POST"
  echo "  초기화 (lag drain 대기 후)..." >&2
  bench_wait_lag 120; bench_reset
  echo "  워밍업 60건..." >&2
  bench_warmup 60 10
  bench_wait_lag 120; bench_reset

  echo "  본 측정: $N 건 / 동시 $C ..." >&2
  local S=$(date +%s) T0=$(date +%s%N)
  bench_load "$N" "$C" "$OUT"
  local T1=$(date +%s%N) E=$(date +%s)

  local MS=$(( (T1-T0)/1000000 ))
  local OK=$(awk '$1=="204"' "$OUT" | wc -l | tr -d ' ')
  local NG=$(( N - OK ))
  local TPS=$(echo "scale=1; $OK*1000/$MS" | bc)
  local P=$(bench_pct "$OUT")
  # 응답 직후 스냅샷 — 비동기 여부가 여기서 드러난다
  local IMM=$($MYSQL_N -e "SELECT COUNT(*) FROM Orders WHERE orderStatus='PAID';" 2>/dev/null)
  local HP=$(bench_pmax hikaricp_connections_pending $((S-5)) $((E+5)))
  local TB=$(bench_pmax tomcat_threads_busy_threads $((S-5)) $((E+5)))
  echo "  drain 대기..." >&2
  bench_wait_lag 180
  local DB=$(bench_dbstat)
  echo "$POST|$TPS|$P|$NG|$IMM/$N|$DB|$HP|$TB"
}

echo "════════ 버스트 비교: $N 건 / 동시 $C ════════"
A=$(run_one sync)
B=$(run_one kafka)

echo
echo "전략|TPS|p50|p95|p99|실패|응답직후PAID|drain후PAID|PENDING|완결avg(ms)|Hikari대기|Tomcat busy" > $RES/burst-summary.txt
echo "$A" >> $RES/burst-summary.txt
echo "$B" >> $RES/burst-summary.txt
column -t -s'|' $RES/burst-summary.txt
echo
echo "원본: $RES/burst-sync.txt, $RES/burst-kafka.txt"
