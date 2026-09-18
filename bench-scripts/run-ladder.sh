#!/bin/bash
# 동시성 사다리 — 동시 수를 올려가며 대기 시간 곡선 측정
#
# 사용법: ./bench-scripts/run-ladder.sh <sync|kafka> [건수]
#   예:   ./bench-scripts/run-ladder.sh sync
#
# ⚠️ 단계 사이에 초기화하지 않는다(앞 단계가 워밍업 역할).
#    따라서 이 결과의 절대값은 run-burst.sh 와 다르다.
#    동일 실행 내 상대 비교(어디서 포화되는가)용으로만 쓸 것.
set -u
cd "$(dirname "$0")/.." || exit 1
source bench-scripts/lib.sh
bench_precheck

POST=${1:?"사용법: run-ladder.sh <sync|kafka> [건수]"}
N=${2:-800}
RES=bench-scripts/results; mkdir -p $RES

bench_arm "$POST"
bench_wait_lag 120; bench_reset
echo "워밍업 60건..."; bench_warmup 60 10; bench_wait_lag 120

echo "동시수|TPS|p50|p95|p99|실패|Hikari대기|Tomcat busy" > $RES/ladder-$POST.txt
for C in 10 30 60 120; do
  OUT=$RES/ladder-$POST-$C.raw
  local_S=$(date +%s); T0=$(date +%s%N)
  bench_load "$N" "$C" "$OUT"
  T1=$(date +%s%N); local_E=$(date +%s)
  MS=$(( (T1-T0)/1000000 ))
  OK=$(awk '$1=="204"' "$OUT" | wc -l | tr -d ' ')
  TPS=$(echo "scale=1; $OK*1000/$MS" | bc)
  P=$(bench_pct "$OUT")
  HP=$(bench_pmax hikaricp_connections_pending $((local_S-5)) $((local_E+5)))
  TB=$(bench_pmax tomcat_threads_busy_threads $((local_S-5)) $((local_E+5)))
  echo "$C|$TPS|$P|$(( N - OK ))|$HP|$TB" >> $RES/ladder-$POST.txt
  echo "  동시 $C 완료 (TPS $TPS, p50 ${P%%|*}ms)"
  sleep 12   # 다음 단계와 지표 창이 겹치지 않게
done
bench_wait_lag 180

echo
column -t -s'|' $RES/ladder-$POST.txt
