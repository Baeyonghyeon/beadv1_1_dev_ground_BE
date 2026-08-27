#!/bin/bash
# 요청 비용 분해 — 주문 API 전체 중 결제(payments 호출)가 차지하는 비중
#
# 사용법: ./bench-scripts/run-decompose.sh [건수] [동시수]
#   기본값: 300건, 동시 10
#
# 측정 방식: curl 이 아니라 **앱이 스스로 기록하는 서버 사이드 지표**(Micrometer)를 쓴다.
#   · curl 은 매 요청마다 TCP 연결을 새로 열어, 그 비용이 10ms 대 차이를 덮어버린다.
#   · commerce 의 /api/commerce/order/{productCode} 와
#     payments 의 /api/payments/process 를 같은 부하 구간에서 동시에 읽으면
#     "주문 1건 처리 중 결제가 몇 ms 였는지" 를 정확히 알 수 있다.
#     (결제는 Feign 으로 호출되므로 주문 요청 안에 포함되어 계측된다)
set -u
cd "$(dirname "$0")/.." || exit 1
source bench-scripts/lib.sh
bench_precheck

N=${1:-300}; C=${2:-10}

# 특정 URI 의 평균 응답 시간(ms) — $1: 호스트 $2: uri 패턴
uri_mean_ms () {
  curl -s "$1/actuator/prometheus" | awk -v uri="$2" '
    $0 ~ "http_server_requests_seconds_sum" && $0 ~ uri {s+=$NF}
    $0 ~ "http_server_requests_seconds_count" && $0 ~ uri {c+=$NF}
    END {if (c>0) printf "%.2f", s/c*1000; else print 0}'
}

bench_arm sync
bench_wait_lag 120; bench_reset

echo "워밍업 100건 (JIT·커넥션풀·버퍼풀)..."
bench_warmup 100 10
bench_wait_lag 120

# 워밍업까지의 누적값을 기준선으로 잡고, 이후 증분만으로 평균을 낸다
read -r S_ORD C_ORD < <(curl -s $COMMERCE/actuator/prometheus | awk '
  /http_server_requests_seconds_sum/   && /api\/commerce\/order/ {s+=$NF}
  /http_server_requests_seconds_count/ && /api\/commerce\/order/ {c+=$NF}
  END {print s, c}')
read -r S_PAY C_PAY < <(curl -s $PAYMENTS/actuator/prometheus | awk '
  /http_server_requests_seconds_sum/   && /api\/payments\/process/ {s+=$NF}
  /http_server_requests_seconds_count/ && /api\/payments\/process/ {c+=$NF}
  END {print s, c}')

echo "본 측정: ${N}건 / 동시 ${C} ..."
TMP=$(mktemp); bench_load "$N" "$C" "$TMP"; OK=$(awk '$1=="204"' "$TMP" | wc -l | tr -d ' '); rm -f "$TMP"

read -r S_ORD2 C_ORD2 < <(curl -s $COMMERCE/actuator/prometheus | awk '
  /http_server_requests_seconds_sum/   && /api\/commerce\/order/ {s+=$NF}
  /http_server_requests_seconds_count/ && /api\/commerce\/order/ {c+=$NF}
  END {print s, c}')
read -r S_PAY2 C_PAY2 < <(curl -s $PAYMENTS/actuator/prometheus | awk '
  /http_server_requests_seconds_sum/   && /api\/payments\/process/ {s+=$NF}
  /http_server_requests_seconds_count/ && /api\/payments\/process/ {c+=$NF}
  END {print s, c}')

echo "성공 $OK / $N 건"
python3 - "$S_ORD" "$C_ORD" "$S_ORD2" "$C_ORD2" "$S_PAY" "$C_PAY" "$S_PAY2" "$C_PAY2" <<'PY'
import sys
so, co, so2, co2, sp, cp, sp2, cp2 = map(float, sys.argv[1:9])
do, dco = so2-so, co2-co
dp, dcp = sp2-sp, cp2-cp
if dco <= 0 or dcp <= 0:
    print("측정 실패: 요청 증분이 없습니다."); sys.exit(1)
a = do/dco*1000
p = dp/dcp*1000
print()
print("─" * 52)
print(f"주문 API 전체 (서버 사이드)   {a:7.1f} ms   n={int(dco)}")
print(f"├─ 결제 (Feign -> payments)   {p:7.1f} ms   ({p/a*100:4.1f}%)  <- 양쪽 Arm 동일, 손대지 않음")
print(f"└─ 나머지(주문생성+후처리)     {a-p:7.1f} ms   ({(a-p)/a*100:4.1f}%)  <- 이 중 후처리만 Kafka 로 이동")
print("─" * 52)
if p >= a:
    print("주의: 결제가 전체보다 큽니다. 워밍업 부족이거나 다른 부하가 섞였습니다.")
PY
bench_wait_lag 120
