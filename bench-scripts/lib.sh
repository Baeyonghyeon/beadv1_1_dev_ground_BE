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

# Arm 전환 후 기동 대기 — $1: sync | kafka
bench_arm () {
  local POST=$1
  [ "$POST" = "sync" ] || [ "$POST" = "kafka" ] || die "인자는 sync 또는 kafka"
  echo "▶ 후처리 전략 = $POST 로 commerce 재기동..." >&2
  BENCH_PAYMENT_STRATEGY=feign BENCH_POSTPROCESS_STRATEGY=$POST \
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
bench_reset () {
  $MYSQL -e "
    DELETE FROM OrderItem; DELETE FROM Orders; DELETE FROM Payment; DELETE FROM DepositHistoryEntity;
    UPDATE DepositEntity SET balance=1000000000 WHERE userCode LIKE 'BENCH-%';
    DELETE FROM cartItem;
    INSERT INTO cartItem (code, productCode, cartId, deleteStatus, createdAt, updatedAt)
    SELECT UUID(), CONCAT('PROD-', SUBSTRING(c.userCode,7)), c.id, 'N', NOW(6), NOW(6)
    FROM cart c WHERE c.userCode LIKE 'BENCH-%';" 2>/dev/null
}

# 워밍업 — $1: 건수(기본 60) $2: 동시수(기본 10)
bench_warmup () {
  local N=${1:-60} C=${2:-10}
  seq 1 $N | xargs -P $C -I{} sh -c \
    "U=\$(( {} % $USERS )); curl -s -o /dev/null -X POST \"$COMMERCE/api/commerce/order/PROD-\$U\" -H \"X-CODE: BENCH-\$U\""
}

# 부하 발생 — $1: 총건수 $2: 동시수 $3: 출력파일
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

# 측정 후 DB 상태 — "drain후PAID|PENDING|완결avg(ms)"
bench_dbstat () {
  $MYSQL_N -e "
    SELECT (SELECT COUNT(*) FROM Orders WHERE orderStatus='PAID'),
           (SELECT COUNT(*) FROM Orders WHERE orderStatus='PENDING'),
           (SELECT IFNULL(ROUND(AVG(TIMESTAMPDIFF(MICROSECOND,createdAt,updatedAt))/1000,0),0)
              FROM Orders WHERE orderStatus='PAID');" 2>/dev/null | tr '\t' '|'
}
