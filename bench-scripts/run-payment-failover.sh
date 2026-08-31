#!/bin/bash
# 결제 서비스 장애 격리 A/B — payments 를 강제 중단한 상태에서 주문을 받는다
#
# 사용법: ./bench-scripts/run-payment-failover.sh [장애초] [초당주문] [워밍업회차]
#   기본:  60초 / 10 TPS / 7회  → 장애 구간에 정확히 600건
#
# ── 무엇을 재는가 ────────────────────────────────────────────────────────────
# "결제 서비스가 죽어 있는 동안 들어온 주문이 살아남는가."
#
# ⚠️ HTTP 상태로 판정하면 안 된다. 동기 경로는 payments 가 죽어도 **204(성공)를 돌려주면서
#    주문을 CANCELLED 로 만든다** — FeignPaymentAdapter 가 예외를 삼키고 PaymentResult.fail 을
#    반환하면, handlePaymentResult 가 order.cancel() 을 호출하기 때문이다.
#    사용자는 성공한 줄 아는데 주문은 사라진다. 그래서 지표는 **주문 생존율**로 잡는다.
#
#      주문 생존율 = 장애 구간에 들어온 주문 중 최종 PAID 비율
#
# 두 Arm 에 **정확히 같은 건수**를 같은 속도로 넣는다(초당 RATE 건 × 장애초).
set -u
cd "$(dirname "$0")/.." || exit 1
source bench-scripts/lib.sh
bench_precheck

OUTAGE=${1:-60}; RATE=${2:-10}; WARM=${3:-7}
TOTAL=$((OUTAGE * RATE))
RES=bench-scripts/results; mkdir -p $RES
OUT=$RES/payment-failover.txt

wait_payments () {
  local i
  for i in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' $PAYMENTS/actuator/health)" = "200" ] && return 0
    sleep 2
  done
  die "payments 기동 실패"
}

run_arm () {
  local PAY=$1 LABEL=$2
  echo "════════ Arm: 결제=$PAY ════════"
  docker start bench-payments >/dev/null 2>&1; wait_payments
  bench_arm kafka "$PAY"

  echo "  워밍업 ${WARM}회..." >&2
  local i
  for i in $(seq 1 "$WARM"); do
    bench_wait_completed 300 >/dev/null 2>&1; bench_wait_lag 240 >/dev/null 2>&1; bench_reset >/dev/null 2>&1
    bench_load 2400 80 /dev/null >/dev/null 2>&1; echo -n "." >&2
  done; echo " 완료" >&2
  bench_wait_completed 300 >/dev/null 2>&1; bench_wait_lag 240 >/dev/null 2>&1; bench_reset >/dev/null 2>&1

  # ── 장애 주입 ──────────────────────────────────────────────────────────
  echo "  ▼ payments 강제 중단 — ${OUTAGE}초간 초당 ${RATE}건 주문" >&2
  docker stop bench-payments >/dev/null 2>&1

  local RAW=$RES/failover-$LABEL.txt; : > "$RAW"
  local s
  for s in $(seq 1 "$OUTAGE"); do
    seq 1 "$RATE" | xargs -P "$RATE" -I{} sh -c \
      "U=\$(( ( $s * $RATE + {} ) % $USERS )); curl -s -o /dev/null \
       -w '%{http_code}\n' --max-time 10 \
       -X POST \"$COMMERCE/api/commerce/order/PROD-\$U\" -H \"X-CODE: BENCH-\$U\"" >> "$RAW" &
    sleep 1
  done
  wait

  local DURING; DURING=$(bench_dbstat)
  echo "  ▲ 장애 중 주문 상태: $DURING" >&2

  # ── 복구 ───────────────────────────────────────────────────────────────
  echo "  payments 복구..." >&2
  docker start bench-payments >/dev/null 2>&1
  local T0; T0=$(python3 -c 'import time;print(time.time())')
  wait_payments
  bench_wait_completed 600 >/dev/null 2>&1
  local T1; T1=$(python3 -c 'import time;print(time.time())')
  local DRAIN; DRAIN=$(python3 -c "print(f'{$T1-$T0:.1f}')")

  # ── 집계 ───────────────────────────────────────────────────────────────
  local REQ OK AFTER PAID SURV DEDUCT PAYCNT DUP
  REQ=$(wc -l < "$RAW" | tr -d ' ')
  OK=$(awk '$1 ~ /^2/' "$RAW" | wc -l | tr -d ' ')
  AFTER=$(bench_dbstat)
  PAID=$($MYSQL_N -e "SELECT COUNT(*) FROM Orders WHERE orderStatus='PAID';" 2>/dev/null)
  SURV=$(python3 -c "print(f'{$PAID*100/max($REQ,1):.1f}')")
  DEDUCT=$($MYSQL_N -e "SELECT (SELECT COUNT(*)*1000000000 FROM DepositEntity WHERE userCode LIKE 'BENCH-%')
                             - (SELECT SUM(balance) FROM DepositEntity WHERE userCode LIKE 'BENCH-%');" 2>/dev/null)
  PAYCNT=$($MYSQL_N -e "SELECT COUNT(*) FROM Payment;" 2>/dev/null)
  DUP=$($MYSQL_N -e "SELECT COUNT(*) FROM (SELECT orderCode FROM Payment WHERE orderCode IS NOT NULL
                     GROUP BY orderCode HAVING COUNT(*)>1) t;" 2>/dev/null)

  echo "$LABEL|$REQ|$OK|$PAID|$SURV|$DRAIN|$DURING|$AFTER|$DEDUCT|$PAYCNT|$DUP" >> "$OUT"

  echo "   요청 $REQ / HTTP 2xx $OK / 최종 PAID $PAID (생존율 ${SURV}%)"
  echo "   장애중=$DURING → 복구후=$AFTER / 적체소비 ${DRAIN}s"
  echo "   차감 ${DEDUCT}원 (결제 ${PAYCNT}건 × 5,000 = $((PAYCNT*5000))원) / 중복결제 ${DUP}건"
  echo
}

: > "$OUT"
echo "════════ 결제 서비스 장애 격리: 동기 vs 비동기 ════════"
echo "payments 강제 중단 ${OUTAGE}초 · 초당 ${RATE}건 (총 ${TOTAL}건) · 워밍업 ${WARM}회"
echo
run_arm feign sync
run_arm kafka async

echo "════════ 요약 ════════"
awk -F'|' 'BEGIN{printf "  %-6s %7s %8s %8s %9s %10s %12s %8s\n","Arm","요청","HTTP2xx","최종PAID","생존율%","적체소비s","차감원","중복결제"}
           {printf "  %-6s %7s %8s %8s %9s %10s %12s %8s\n",$1,$2,$3,$4,$5,$6,$9,$11}' "$OUT"
echo
echo "  drain후(PAID|PENDING|PAYMENT_PENDING|PAYMENT_FAILED|완결avg ms)"
awk -F'|' '{printf "  %-6s 장애중 %s → 복구후 %s\n",$1,$7,$8}' "$OUT"
echo
echo "원본: $OUT"
echo FAILOVER_DONE
