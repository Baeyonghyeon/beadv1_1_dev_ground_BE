#!/bin/bash
# 순간 몰림 비교 — 결제 동기 vs 비동기
#
# 사용법: ./bench-scripts/run-payment-burst.sh [동시수목록] [레벨당반복] [워밍업회차]
#   예:   ./bench-scripts/run-payment-burst.sh "100,200,300,400" 3 7
#
# ── 왜 k6 가 아니라 xargs 인가 ────────────────────────────────────────────────
# k6 는 compose 안에서 돌아 **SUT 와 같은 Docker VM 메모리를 다툰다.**
# VU 1,500개(≈3GB)를 띄운 회차에서 80 TPS 스파이크에 14.82% 타임아웃이 났는데,
# 같은 시스템을 경량 생성기로 재니 400 TPS 가 멀쩡히 나왔다 — 생성기가 SUT 를 느리게 만든 것이다.
# xargs/curl 은 **호스트에서** 돌아 Docker VM 메모리를 전혀 쓰지 않는다.
#
# ── 무엇을 재는가 ────────────────────────────────────────────────────────────
# "동시에 C명이 한꺼번에 주문하면 몇 명이 제한 시간 안에 응답을 받는가."
# 각 사용자는 1건씩만 요청하고 최대 REQ_TIMEOUT 초를 기다린다.
#   2xx  = 접수 성공 (동기 204 / 비동기 202)
#   5xx  = 서버가 거절 (Hikari 커넥션 타임아웃 등)
#   000  = 제한 시간 안에 응답 못 받음
#
# ⚠️ 클라이언트 타임아웃은 Hikari connection-timeout(3초)보다 커야 한다.
#    작으면 서버가 거절을 만들기 전에 클라이언트가 떠나 5xx 를 한 건도 못 본다.
set -u
cd "$(dirname "$0")/.." || exit 1
source bench-scripts/lib.sh
bench_precheck

LEVELS=${1:-"100,200,300,400"}
REPEAT=${2:-3}
WARM=${3:-7}
REQ_TIMEOUT=${REQ_TIMEOUT:-5}
WARM_N=${WARM_N:-2400}
WARM_C=${WARM_C:-80}

RES=bench-scripts/results; mkdir -p $RES
OUT=$RES/payment-burst.txt

# 순간 몰림 1회 — $1: 동시수  $2: 결과파일
burst_once () {
  local C=$1 OUT_F=$2
  : > "$OUT_F"
  seq 1 "$C" | xargs -P "$C" -I{} sh -c \
    "U=\$(( {} % $USERS )); curl -s -o /dev/null -w '%{http_code} %{time_total}\n' \
     --max-time $REQ_TIMEOUT -X POST \"$COMMERCE/api/commerce/order/PROD-\$U\" -H \"X-CODE: BENCH-\$U\"" \
    >> "$OUT_F"
}

run_arm () {
  local PAY=$1 LABEL=$2
  echo "════════ Arm: 결제=$PAY ════════"
  bench_arm kafka "$PAY"

  echo "  워밍업 ${WARM}회 (JVM 수렴 — 콜드로 재면 실제 용량의 1/4 을 잰다)..." >&2
  local i
  for i in $(seq 1 "$WARM"); do
    bench_wait_completed 300 >/dev/null 2>&1; bench_wait_lag 240 >/dev/null 2>&1; bench_reset
    bench_load "$WARM_N" "$WARM_C" /dev/null >/dev/null 2>&1
    echo -n "." >&2
  done
  echo " 완료" >&2

  local C
  for C in $(echo "$LEVELS" | tr ',' ' '); do
    local ACC=$RES/burst-$LABEL-$C.txt; : > "$ACC"
    local r
    for r in $(seq 1 "$REPEAT"); do
      bench_wait_completed 300 >/dev/null 2>&1; bench_wait_lag 240 >/dev/null 2>&1; bench_reset
      burst_once "$C" /tmp/burst-round.txt
      cat /tmp/burst-round.txt >> "$ACC"
    done

    # 집계 (macOS awk 에는 asort 가 없으므로 sort 로 백분위를 낸다)
    local COUNTS PCTS
    COUNTS=$(awk '{n++; if($1 ~ /^2/) ok++; else if($1 ~ /^5/) e5++}
                  END{printf "%d|%.2f|%.2f", n, (n-ok)*100/n, e5*100/n}' "$ACC")
    PCTS=$(awk '{print $2*1000}' "$ACC" | sort -n | \
           awk '{a[NR]=$1} END{printf "%.0f|%.0f|%.0f", a[int(NR*0.5)], a[int(NR*0.95)], a[NR]}')
    echo "$LABEL|$C|$COUNTS|$PCTS" >> "$OUT"

    awk -v c="$C" '{n++; if($1 ~ /^2/) ok++} END{printf "   동시 %3d: 성공 %d/%d (%.1f%%)\n", c, ok, n, ok*100/n}' "$ACC" >&2
  done
  echo
}

: > "$OUT"
echo "════════ 순간 몰림: 결제 동기 vs 비동기 ════════"
echo "동시수=[$LEVELS] / 레벨당 ${REPEAT}회 / 타임아웃 ${REQ_TIMEOUT}s / 워밍업 ${WARM}회"
echo
run_arm feign sync
run_arm kafka async

echo "════════ 결과 ════════"
awk -F'|' 'BEGIN{printf "  %-6s %6s %7s %9s %8s %8s %8s %8s\n","Arm","동시","요청","실패율%","5xx%","p50","p95","max"}
           {printf "  %-6s %6s %7s %9s %8s %8s %8s %8s\n",$1,$2,$3,$4,$5,$6,$7,$8}' "$OUT"
echo
echo "원본: $OUT"
