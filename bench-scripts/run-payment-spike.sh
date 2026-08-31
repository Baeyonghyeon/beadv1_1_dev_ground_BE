#!/bin/bash
# 순간 스파이크 흡수 — 결제 동기 vs 비동기
#
# 사용법: ./bench-scripts/run-payment-spike.sh [스파이크TPS] [길이] [워밍업회차]
#   기본:  220 TPS / 40s / 7회
#
# ── 강도를 이렇게 잡은 이유 ──────────────────────────────────────────────────
# 실측 접수 용량: sync 203 TPS / async 371 TPS.
# 스파이크를 **두 용량 사이**에 두면 "동기는 못 버티고 비동기는 버티는" 구간이 된다.
# 220 TPS 는 sync 의 1.08배, async 의 0.59배다.
#
# 기존 스파이크 문서의 "용량 약 100 TPS" 가정은 틀렸다 — 콜드 JVM 을 잰 값이었다.
# 그 가정으로 잡은 강도(300~500 TPS)는 양쪽 다 붕괴시켜 차이를 묻어버린다.
#
# ⚠️ 클라이언트 타임아웃(3.5s) > Hikari connection-timeout(3s) 이어야
#    서버 거절과 클라이언트 이탈이 분리 관측된다.
set -u
cd "$(dirname "$0")/.." || exit 1
source bench-scripts/lib.sh
bench_precheck

SPIKE=${1:-220}; T_SPIKE=${2:-40s}; WARM=${3:-7}
REQ_TIMEOUT=${REQ_TIMEOUT:-3.5s}
MAX_VUS=${MAX_VUS:-1000}
BASELINE=${BASELINE:-30}; T_BASE=${T_BASE:-20s}; T_RECOVER=${T_RECOVER:-40s}
WARM_N=${WARM_N:-2400}; WARM_C=${WARM_C:-80}

RES=k6-scripts/results; mkdir -p $RES
OUT=bench-scripts/results/payment-spike.txt

MEM=$(docker run --rm alpine sh -c "free -m | awk '/Mem:/{print \$7}'" 2>/dev/null || echo 0)
echo "VU ${MAX_VUS}개 ≈ $((MAX_VUS*2))MB / VM 여유 ${MEM}MB"
[ "$((MAX_VUS*2))" -gt "$MEM" ] && echo "⚠️ VU 메모리가 여유를 넘습니다 — 생성기가 SUT 를 압박해 회차가 무효가 됩니다" >&2

run_arm () {
  local PAY=$1 LABEL=$2
  echo "════════ Arm: 결제=$PAY ════════"
  bench_arm kafka "$PAY"

  echo "  워밍업 ${WARM}회..." >&2
  local i
  for i in $(seq 1 "$WARM"); do
    bench_wait_completed 300 >/dev/null 2>&1; bench_wait_lag 240 >/dev/null 2>&1; bench_reset >/dev/null 2>&1
    bench_load "$WARM_N" "$WARM_C" /dev/null >/dev/null 2>&1
    echo -n "." >&2
  done
  echo " 완료" >&2

  bench_wait_completed 300 >/dev/null 2>&1; bench_wait_lag 240 >/dev/null 2>&1; bench_reset >/dev/null 2>&1

  local RAW
  RAW=$($COMPOSE run --rm -T \
        -e SPIKE="$SPIKE" -e T_SPIKE="$T_SPIKE" -e BASELINE="$BASELINE" \
        -e T_BASE="$T_BASE" -e T_RECOVER="$T_RECOVER" \
        -e REQ_TIMEOUT="$REQ_TIMEOUT" -e MAX_VUS="$MAX_VUS" \
        k6 run /scripts/order-spike.js 2>&1)
  echo "$RAW" > "$RES/payment-spike-$LABEL.log"

  # 응답 종료 직후 스냅샷 (아직 큐가 남아 있는 시점)
  local IMM PEND
  IMM=$($MYSQL_N -e "SELECT COUNT(*) FROM Orders WHERE orderStatus='PAID';" 2>/dev/null)
  PEND=$($MYSQL_N -e "SELECT COUNT(*) FROM Orders WHERE orderStatus IN ('PENDING','PAYMENT_PENDING');" 2>/dev/null)

  bench_wait_completed 600 >/dev/null 2>&1
  local DB VUMAX
  DB=$(bench_dbstat)
  # VU 천장 접촉 여부 — 붙었으면 open-loop 가 closed-loop 로 퇴화해 회차 무효
  VUMAX=$(echo "$RAW" | sed 's/\x1b\[[0-9;]*m//g' | grep -o '[0-9]\{4\}/[0-9]\{4\} VUs' | \
          sed 's|/.*||' | sort -n | tail -1)

  {
    echo "$RAW" | grep '^K6_PHASE ' | sed "s/^K6_PHASE /$LABEL|/"
    echo "$RAW" | grep '^K6_RESULT ' | sed "s/^K6_RESULT /$LABEL|RESULT|/"
    echo "$LABEL|EXTRA|vumax=${VUMAX:-?}/$MAX_VUS|직후PAID=$IMM|직후미완결=$PEND|drain후=$DB"
  } >> "$OUT"

  echo "$RAW" | grep '^K6_PHASE ' | sed 's/^K6_PHASE //' | \
    awk -F'|' '{printf "   %-9s 요청 %6s  실패 %6s%%  p50 %5s  p95 %5s  max %5s\n",$1,$2,$3,$4,$5,$6}'
  echo "   VU 최대 ${VUMAX:-?}/$MAX_VUS · 직후 미완결 $PEND"
  echo
}

: > "$OUT"
echo "════════ 스파이크 흡수: 결제 동기 vs 비동기 ════════"
echo "평상 ${BASELINE} → ${SPIKE} TPS (${T_SPIKE}) → 평상 / 타임아웃 ${REQ_TIMEOUT} / 워밍업 ${WARM}회"
echo
run_arm feign sync
run_arm kafka async

echo "════════ 요약 ════════"
grep '|spike|' "$OUT" | awk -F'|' '{printf "  %-6s 스파이크 구간: 요청 %s / 실패율 %s%% / p95 %sms\n",$1,$3,$4,$6}'
grep 'RESULT' "$OUT" | awk -F'|' '{printf "  %-6s 전체: %s건 / 실패 %s(%s%%) / 5xx %s / 연결실패 %s / dropped %s\n",$1,$4,$5,$6,$7,$8,$9}'
grep 'EXTRA' "$OUT" | awk -F'|' '{printf "  %-6s %s %s %s\n",$1,$3,$4,$5}'
echo
echo "원본: $OUT"
