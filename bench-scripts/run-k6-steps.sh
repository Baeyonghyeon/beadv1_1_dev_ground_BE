#!/bin/bash
# 저부하 → 고부하 계단식 부하 (임계점 찾기)
#
# 사용법: ./bench-scripts/run-k6-steps.sh <sync|kafka> [계단] [단계길이]
#   예:   ./bench-scripts/run-k6-steps.sh sync
#         ./bench-scripts/run-k6-steps.sh kafka "30,60,100,150,200,300" 60s
#
# k6 지표가 Prometheus 로 전송되어 Grafana 에서 서버 지표와 같은 시간축에 겹쳐 보인다.
# 실행 중 Grafana 를 열어두고 지켜볼 것 → http://localhost:3000
set -u
cd "$(dirname "$0")/.." || exit 1
source bench-scripts/lib.sh
bench_precheck

POST=${1:?"사용법: run-k6-steps.sh <sync|kafka> [계단] [단계길이]"}
STEPS=${2:-"30,60,100,150,200,300"}
STEP_DUR=${3:-60s}
REQ_TIMEOUT=${REQ_TIMEOUT:-4s}

# VU 프로비저닝 규칙 검사
PEAK=$(echo "$STEPS" | tr ',' '\n' | sort -n | tail -1)
NEED=$(python3 -c "print(int($PEAK * ${REQ_TIMEOUT%s}))")
MAX_VUS=${MAX_VUS:-$NEED}
if [ "$NEED" -gt "$MAX_VUS" ]; then
  echo "⚠️  필요 VU ${NEED} (최대 도착률 ${PEAK} × 타임아웃 ${REQ_TIMEOUT}) > maxVUs ${MAX_VUS}"
  echo "    dropped 발생이 확정적입니다. 계단 최대치를 낮추거나 MAX_VUS 를 올리세요."
fi
MEM=$(docker run --rm alpine sh -c "free -m | awk '/Mem:/{print \$7}'" 2>/dev/null || echo 0)
EST=$(( MAX_VUS * 2 ))
echo "VU ${MAX_VUS}개 ≈ ${EST}MB 예상 / VM 여유 ${MEM}MB"
[ "$MEM" -gt 0 ] && [ "$EST" -gt "$MEM" ] && echo "⚠️  메모리 부족 가능성 — MAX_VUS 를 낮추거나 REQ_TIMEOUT 을 줄이세요."

echo "▶ Arm=$POST  계단=[$STEPS]  단계길이=$STEP_DUR  타임아웃=$REQ_TIMEOUT  maxVUs=$MAX_VUS"
bench_arm "$POST"
bench_wait_lag 180; bench_reset
echo "  워밍업 100건..."
bench_warmup 100 10
bench_wait_lag 180; bench_reset

TOTAL_MIN=$(python3 -c "
n=len('$STEPS'.split(',')); d=int('${STEP_DUR%s}'); print(round(n*(d+5)/60,1))")
echo "  측정 시작 — 약 ${TOTAL_MIN}분 소요"
echo "  📊 지금 Grafana 를 열어두고 지켜보세요: http://localhost:3000"
echo

RES=k6-scripts/results; mkdir -p $RES
$COMPOSE run --rm -T \
  -e STEPS="$STEPS" -e STEP_DUR="$STEP_DUR" \
  -e REQ_TIMEOUT="$REQ_TIMEOUT" -e MAX_VUS="$MAX_VUS" \
  k6 run -o experimental-prometheus-rw /scripts/order-steps.js 2>&1 | tee "$RES/steps-$POST.log"

echo
echo "  drain 대기..."
bench_wait_lag 300
echo
echo "════════ 결과 ($POST) ════════"
grep '^K6_RESULT ' "$RES/steps-$POST.log" | sed 's/^K6_RESULT //' | \
  awk -F'|' '{printf "  전체 평균 TPS %s / p50 %sms / p95 %sms / p99 %sms / max %sms\n  총 %s건, 실패 %s건 (%s%%), 5xx %s, 연결실패 %s, dropped %s\n", $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11}'
echo
echo "  ⚠️ dropped 가 0 이 아니면 회차 무효 (부하 생성기가 목표 도착률을 못 만듦)"
echo "  📊 계단별 상세는 Grafana 또는 아래 쿼리로:"
echo "     sum(rate(k6_http_reqs_total[30s]))              # 실제 도착률"
echo "     k6_http_req_duration_p95                         # 응답 p95"
echo "     hikaricp_connections_pending{service=\"commerce\"} # 커넥션 대기"
echo "     sum(kafka_consumergroup_lag)                     # Kafka lag"
