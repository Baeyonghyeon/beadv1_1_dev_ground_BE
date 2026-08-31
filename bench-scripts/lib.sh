#!/bin/bash
# 공통 함수 — 각 실행 스크립트가 source 한다.
# 모든 경로/이름은 docker/docker-compose-bench.yml 기준.

COMPOSE="docker compose -f docker/docker-compose-bench.yml"
MYSQL="docker exec bench-mysql mysql -umysqlId -pmysqlPwd dbay_db"
MYSQL_N="docker exec bench-mysql mysql -umysqlId -pmysqlPwd -N dbay_db"
KAFKA_CLI="docker exec bench-kafka /opt/kafka/bin"
BOOTSTRAP="kafka:9090"     # ⚠️ 컨테이너 내부에서는 localhost:9092 가 아니다
COMMERCE="http://localhost:8081"
PAYMENTS="http://localhost:8085"
PROM="http://localhost:9090"
USERS=200                  # BENCH-0 ~ BENCH-199

die () { echo "❌ $*" >&2; exit 1; }

# 컨테이너가 떠 있는지 확인
bench_precheck () {
  docker ps --format '{{.Names}}' | grep -q '^bench-commerce$' || die "bench-commerce 가 없습니다. 먼저 '$COMPOSE up -d' 하세요."
  docker ps --format '{{.Names}}' | grep -q '^bench-mysql$'    || die "bench-mysql 이 없습니다."
  docker ps --format '{{.Names}}' | grep -q '^bench-kafka$'    || die "bench-kafka 가 없습니다."
}

# Arm 전환 후 기동 대기
#   $1: 후처리 전략 sync | kafka
#   $2: 결제 전략   feign | kafka  (생략 시 feign — 기존 회차와 호환)
#
# ⚠️ 결제 전략을 kafka 로 쓰려면 먼저 스키마 마이그레이션을 실행해야 한다:
#      docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < bench-scripts/migrate-async-payment.sql
#    안 하면 PAYMENT_PENDING 을 INSERT 하지 못해 주문이 전부 500 이 된다.
bench_arm () {
  local POST=$1 PAY=${2:-feign}
  [ "$POST" = "sync" ] || [ "$POST" = "kafka" ] || die "1번 인자는 sync 또는 kafka"
  [ "$PAY" = "feign" ] || [ "$PAY" = "kafka" ] || die "2번 인자는 feign 또는 kafka"
  echo "▶ 결제=$PAY / 후처리=$POST 로 commerce 재기동..." >&2
  BENCH_PAYMENT_STRATEGY=$PAY BENCH_POSTPROCESS_STRATEGY=$POST \
    $COMPOSE up -d commerce >/dev/null 2>&1
  for i in $(seq 1 40); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' $COMMERCE/actuator/health)" = "200" ] && { echo "  기동 완료" >&2; return 0; }
    sleep 3
  done
  die "commerce 기동 실패"
}

# consumer lag 이 0이 될 때까지 대기 — $1: 최대 대기 초 (기본 120)
bench_wait_lag () {
  local LIMIT=${1:-120} T0=$(date +%s) L
  while :; do
    L=$($KAFKA_CLI/kafka-consumer-groups.sh --bootstrap-server $BOOTSTRAP \
        --describe --all-groups 2>/dev/null | awk '$6 ~ /^[0-9]+$/ {s+=$6} END {print s+0}')
    [ "${L:-0}" -eq 0 ] && return 0
    [ $(( $(date +%s)-T0 )) -ge "$LIMIT" ] && { echo "  ⚠️ lag=$L 잔류 (${LIMIT}s 초과)" >&2; return 1; }
    sleep 3
  done
}

# ⚠️ 반드시 bench_wait_lag 이후에 호출할 것.
# 처리 중인 커맨드가 남은 상태에서 주문을 지우면 컨슈머가 ORDER_NOT_FOUND 로
# 지수 백오프 5회 재시도에 빠지고, 그 지연이 다음 측정 구간까지 밀려온다.
#
# ⚠️⚠️ 2026-08-30: 이 함수가 **조용히 실패**해서 회차를 통째로 날린 적이 있다.
#   컨슈머가 아직 Orders 에 쓰고 있으면 DELETE 가 lock wait timeout 으로 실패하는데,
#   에러가 2>/dev/null 로 묻혀 "초기화된 줄 알고" 다음 회차를 돌게 된다.
#   그러면 주문이 누적되어 drain 시간이 회차마다 불어난다(실측: 88s → 668s).
#   그래서 지금은 **비워졌는지 확인하고, 안 비워졌으면 재시도한 뒤 그래도 안 되면 죽는다.**
bench_reset () {
  local TRY
  for TRY in 1 2 3; do
    $MYSQL -e "
      DELETE FROM OrderItem; DELETE FROM Orders; DELETE FROM Payment; DELETE FROM DepositHistoryEntity;
      UPDATE DepositEntity SET balance=1000000000 WHERE userCode LIKE 'BENCH-%';
      DELETE FROM cartItem;
      INSERT INTO cartItem (code, productCode, cartId, deleteStatus, createdAt, updatedAt)
      SELECT UUID(), CONCAT('PROD-', SUBSTRING(c.userCode,7)), c.id, 'N', NOW(6), NOW(6)
      FROM cart c WHERE c.userCode LIKE 'BENCH-%';" 2>/dev/null

    local LEFT
    LEFT=$($MYSQL_N -e "SELECT COUNT(*) FROM Orders;" 2>/dev/null)
    [ "${LEFT:-1}" -eq 0 ] && return 0

    echo "  ⚠️ 초기화 실패(주문 ${LEFT}건 잔류) — 재시도 $TRY/3" >&2
    sleep 5
  done

  die "초기화 실패: Orders 가 비워지지 않았습니다. 컨슈머가 아직 쓰고 있는지 확인하세요."
}

# 워밍업 — $1: 건수(기본 60) $2: 동시수(기본 10)
bench_warmup () {
  local N=${1:-60} C=${2:-10}
  seq 1 $N | xargs -P $C -I{} sh -c \
    "U=\$(( {} % $USERS )); curl -s -o /dev/null -X POST \"$COMMERCE/api/commerce/order/PROD-\$U\" -H \"X-CODE: BENCH-\$U\""
}

# 부하 발생 — $1: 총건수 $2: 동시수 $3: 출력파일
# 응답코드는 그대로 기록한다: 동기 결제는 204, 비동기 결제는 202(접수 완료) 다.
# 둘 다 성공이므로 집계 시 2xx 로 묶어서 볼 것.
bench_load () {
  local N=$1 C=$2 OUT=$3
  : > "$OUT"
  seq 1 $N | xargs -P $C -I{} sh -c \
    "U=\$(( {} % $USERS )); curl -s -o /dev/null -w '%{http_code} %{time_total}\n' --max-time 30 \
     -X POST \"$COMMERCE/api/commerce/order/PROD-\$U\" -H \"X-CODE: BENCH-\$U\"" >> "$OUT"
}

# 백분위수 계산 — stdin 은 "코드 초" 형식 파일
bench_pct () { awk '{print $2*1000}' "$1" | sort -n | awk '{a[NR]=$1} END {printf "%.0f|%.0f|%.0f", a[int(NR*0.5)], a[int(NR*0.95)], a[int(NR*0.99)]}'; }

# Prometheus 구간 최대값 — $1: 메트릭 $2: 시작(epoch) $3: 종료(epoch)
bench_pmax () {
  curl -sG "$PROM/api/v1/query_range" \
    --data-urlencode "query=$1{service=\"commerce\"}" \
    --data-urlencode "start=$2" --data-urlencode "end=$3" --data-urlencode "step=5" \
  | python3 -c "
import sys,json
try:
    r=json.load(sys.stdin)['data']['result']
    print(f\"{max((float(v[1]) for s in r for v in s['values']), default=0):.0f}\")
except Exception: print('-')" 2>/dev/null || echo '-'
}

# 측정 후 DB 상태 — "PAID|PENDING|PAYMENT_PENDING|PAYMENT_FAILED|완결avg(ms)"
#
# ⚠️ PENDING 과 PAYMENT_PENDING 은 뜻이 다르다. 섞어 세면 안 된다.
#      PENDING         = 결제 완료, 후처리 대기        (동기 결제 경로의 잔류)
#      PAYMENT_PENDING = 결제 미확정, 돈이 빠졌는지 모름 (비동기 결제 경로의 잔류)
#    drain 후에도 PAYMENT_PENDING 이 남아 있으면 결제 커맨드나 결과 이벤트가 유실된 것이다.
bench_dbstat () {
  $MYSQL_N -e "
    SELECT (SELECT COUNT(*) FROM Orders WHERE orderStatus='PAID'),
           (SELECT COUNT(*) FROM Orders WHERE orderStatus='PENDING'),
           (SELECT COUNT(*) FROM Orders WHERE orderStatus='PAYMENT_PENDING'),
           (SELECT COUNT(*) FROM Orders WHERE orderStatus='PAYMENT_FAILED'),
           (SELECT IFNULL(ROUND(AVG(TIMESTAMPDIFF(MICROSECOND,createdAt,updatedAt))/1000,0),0)
              FROM Orders WHERE orderStatus='PAID');" 2>/dev/null | tr '\t' '|'
}

# 아직 확정되지 않은 주문 수 — 비동기 결제의 drain 완료 판정에 쓴다.
# consumer lag 이 0이어도 결제 결과 반영이 끝났다는 보장은 없으므로 이 값으로 한 번 더 확인한다.
bench_unsettled () {
  $MYSQL_N -e "SELECT COUNT(*) FROM Orders WHERE orderStatus='PAYMENT_PENDING';" 2>/dev/null
}

# 모든 주문이 확정(PAID)될 때까지 대기 — $1: 최대 대기 초 (기본 300)
#
# 두 Arm 의 "완결" 기준을 하나로 맞추기 위한 함수다.
#   동기 결제 : 주문이 PENDING 으로 남았다가 후처리 커맨드로 PAID 가 된다
#   비동기 결제: PAYMENT_PENDING → (결제 결과) → PAID
# 둘 다 "PAID 가 아닌 진행중 주문이 0" 을 완결로 본다. consumer lag 을 기준으로 삼으면
# 장바구니 등 결제와 무관한 토픽의 지연이 섞여 비교가 오염된다.
bench_wait_completed () {
  local LIMIT=${1:-300} T0=$(date +%s) N
  while :; do
    N=$($MYSQL_N -e "SELECT COUNT(*) FROM Orders WHERE orderStatus IN ('PENDING','PAYMENT_PENDING');" 2>/dev/null)
    [ "${N:-0}" -eq 0 ] && return 0
    [ $(( $(date +%s)-T0 )) -ge "$LIMIT" ] && { echo "  ⚠️ 미완결 ${N}건 잔류 (${LIMIT}s 초과)" >&2; return 1; }
    sleep 1
  done
}

# 결제 확정까지 대기 — $1: 최대 대기 초 (기본 120)
bench_wait_settled () {
  local LIMIT=${1:-120} T0=$(date +%s) N
  while :; do
    N=$(bench_unsettled)
    [ "${N:-0}" -eq 0 ] && return 0
    [ $(( $(date +%s)-T0 )) -ge "$LIMIT" ] && { echo "  ⚠️ PAYMENT_PENDING=$N 잔류 (${LIMIT}s 초과)" >&2; return 1; }
    sleep 2
  done
}
