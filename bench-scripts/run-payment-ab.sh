#!/bin/bash
# 결제 동기 vs 비동기 A/B — claude-결제-비동기화-설계안.md §10
#
# 사용법: ./bench-scripts/run-payment-ab.sh [총건수] [동시수] [워밍업회차] [측정회차]
#   예:   ./bench-scripts/run-payment-ab.sh 2400 80 7 3
#
# ── 이 스크립트가 지키는 두 가지 규칙 ────────────────────────────────────────
#
# ① 워밍업 회차를 버린다 (bench-measurement-pitfalls ④)
#    같은 설정으로 반복하면 처리량이 58 → 232 TPS 로 7회차까지 단조 증가한다.
#    콜드 1회차만 재면 실제 용량의 1/4 을 잰다. 양쪽 Arm 모두 수렴 회차끼리만 비교한다.
#
# ② 접수와 완결을 따로 잰다
#    비동기 Arm 은 202 를 즉시 돌려주므로 "접수 TPS" 만 보면 공짜로 빨라 보인다.
#    실제 용량은 마지막 주문이 PAID 가 될 때까지의 시간이 정한다. 둘 다 기록한다.
#
# ⚠️ 사전 조건: 스키마 마이그레이션이 적용돼 있어야 한다.
#      docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < bench-scripts/migrate-async-payment.sql
set -u
cd "$(dirname "$0")/.." || exit 1
source bench-scripts/lib.sh
bench_precheck

N=${1:-2400}; C=${2:-80}; WARM=${3:-7}; MEASURE=${4:-3}
RES=bench-scripts/results; mkdir -p $RES
OUT=$RES/payment-ab.txt

# 마이그레이션 확인 — 안 돼 있으면 비동기 Arm 이 전부 500 이 난다
HAS=$($MYSQL_N -e "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='Orders' AND COLUMN_NAME='orderStatus'
  AND COLUMN_TYPE LIKE '%PAYMENT_PENDING%';" 2>/dev/null)
[ "${HAS:-0}" -eq 1 ] || die "스키마 마이그레이션이 필요합니다: bench-scripts/migrate-async-payment.sql"

# 한 회차 실행 — 접수 지연/처리량 + 완결까지의 총 소요시간을 잰다
# $1: 라벨  $2: 결과를 기록할지(1/0)
run_once () {
  local LABEL=$1 RECORD=$2
  local RAW=$RES/ab-$LABEL.txt SAMP=$RES/ab-hikari-$LABEL.txt

  bench_wait_completed 300 >/dev/null 2>&1; bench_wait_lag 240 >/dev/null 2>&1; bench_reset

  # Hikari 1초 샘플러
  : > "$SAMP"
  ( while :; do
      c=$(curl -s --max-time 2 $COMMERCE/actuator/prometheus | awk '/^hikaricp_connections_active\{/{a=$2} /^hikaricp_connections_pending\{/{p=$2} END{printf "%.0f %.0f",a,p}')
      p=$(curl -s --max-time 2 $PAYMENTS/actuator/prometheus | awk '/^hikaricp_connections_active\{/{a=$2} /^hikaricp_connections_pending\{/{p=$2} END{printf "%.0f %.0f",a,p}')
      echo "$c $p" >> "$SAMP"; sleep 1
    done ) & local SPID=$!

  local T0 T1 T2
  T0=$(python3 -c 'import time;print(time.time())')
  bench_load "$N" "$C" "$RAW"
  T1=$(python3 -c 'import time;print(time.time())')      # 접수 종료

  # 완결 대기: 두 Arm 공통 기준 — 진행중(PENDING/PAYMENT_PENDING) 주문이 0이 될 때까지
  bench_wait_completed 300 >/dev/null 2>&1
  T2=$(python3 -c 'import time;print(time.time())')      # 완결 종료
  { kill $SPID; wait $SPID; } 2>/dev/null

  local ACCEPT_S DONE_S
  ACCEPT_S=$(python3 -c "print(f'{$T1-$T0:.1f}')")
  DONE_S=$(python3 -c "print(f'{$T2-$T0:.1f}')")

  if [ "$RECORD" = "1" ]; then
    {
      printf '%s|%s|%s|' "$LABEL" "$ACCEPT_S" "$DONE_S"
      awk '{print $2*1000}' "$RAW" | sort -n | \
        awk '{a[NR]=$1} END{printf "%.0f|%.0f|%.0f|",a[int(NR*0.5)],a[int(NR*0.95)],a[int(NR*0.99)]}'
      awk '$1 ~ /^2/ {ok++} END{printf "%d|", ok+0}' "$RAW"
      awk 'BEGIN{ma=0;mp=0}{if($1>ma)ma=$1; if($2>mp)mp=$2}END{printf "%d/%d|",ma,mp}' "$SAMP"
      awk 'BEGIN{ma=0;mp=0}{if($3>ma)ma=$3; if($4>mp)mp=$4}END{printf "%d/%d|",ma,mp}' "$SAMP"
      echo "$(bench_dbstat)"
    } >> "$OUT"
  fi

  echo "    접수 ${ACCEPT_S}s / 완결 ${DONE_S}s" >&2
}

run_arm () {
  local PAY=$1 LABEL=$2
  echo "════════ Arm: 결제=$PAY ════════"
  bench_arm kafka "$PAY"

  echo "  워밍업 ${WARM}회 (수렴시키고 버린다)..."
  for i in $(seq 1 "$WARM"); do
    echo -n "   [워밍 $i]" >&2
    run_once "$LABEL-w$i" 0
  done

  echo "  측정 ${MEASURE}회..."
  for i in $(seq 1 "$MEASURE"); do
    echo -n "   [측정 $i]" >&2
    run_once "$LABEL-m$i" 1
  done
  echo
}

: > "$OUT"
echo "════════ 결제 동기 vs 비동기 A/B ════════"
echo "부하 ${N}건 / 동시 ${C} / 워밍업 ${WARM}회 후 ${MEASURE}회 측정"
echo
run_arm feign sync
run_arm kafka async

echo "════════ 결과 (수렴 회차만) ════════"
awk -F'|' 'BEGIN{
  printf "  %-9s %8s %8s %7s %7s %7s %7s %11s %11s\n","회차","접수s","완결s","p50","p95","p99","2xx","commerce","payments"}
  {printf "  %-9s %8s %8s %7s %7s %7s %7s %11s %11s\n",$1,$2,$3,$4,$5,$6,$7,$8,$9}' "$OUT"
echo
echo "  drain후(PAID|PENDING|PAYMENT_PENDING|PAYMENT_FAILED|완결avg ms)"
awk -F'|' '{printf "  %-9s %s|%s|%s|%s|%s\n",$1,$10,$11,$12,$13,$14}' "$OUT"
echo
echo "원본: $OUT"
