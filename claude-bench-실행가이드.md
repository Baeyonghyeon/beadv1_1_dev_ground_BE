d# 부하 테스트 직접 실행 가이드

> 동기 후처리 vs Kafka 후처리를 직접 돌려보고 TPS·대기·지연을 눈으로 확인하기 위한 문서.
> 스크립트: `bench-scripts/` · 측정 결과 해석: [`claude-kafka-test-결과.md`](./claude-kafka-test-결과.md)

---

## 0. 준비 (최초 1회)

### 0.1 무관한 컨테이너 정지

Docker Desktop 총 메모리가 8GB 라 다른 프로젝트 컨테이너가 떠 있으면 측정이 흔들린다.

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}'
docker stop <bench- 로 시작하지 않는 컨테이너들>
```

### 0.2 빌드 + 기동

```bash
./gradlew :commerce:bootJar :payments:bootJar
docker compose -f docker/docker-compose-bench.yml build
docker compose -f docker/docker-compose-bench.yml up -d
```

기동 확인 (둘 다 `200` 이어야 함):

```bash
curl -s -o /dev/null -w 'commerce %{http_code}\n' localhost:8081/actuator/health
curl -s -o /dev/null -w 'payments %{http_code}\n' localhost:8085/actuator/health
```

### 0.3 시드

```bash
docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < bench-scripts/seed.sql
```

`deposits=200, carts=200, cart_items=200` 이 나와야 한다.

> ⚠️ **장바구니가 없으면 동기 Arm 이 `CART_NOT_FOUND` 로 전부 실패한다.** 반드시 시드할 것.

### 0.4 관측 확인

```bash
open http://localhost:9090/targets     # 4개 전부 UP
open http://localhost:3000             # Grafana (admin/admin) → "🔬 Kafka A/B 부하 테스트" 대시보드
```

---

## 1. 테스트 다섯 가지

| 스크립트 | 무엇을 보나 | 소요 |
|---|---|---|
| `run-burst.sh` | **동기 vs Kafka 정면 비교** — TPS·p99·완결 시간 | 3~5분 |
| `run-ladder.sh` | **대기 곡선** — 동시 수를 올리면 어디서 포화되나 | 3~4분 |
| `run-decompose.sh` | 요청 하나에서 결제가 차지하는 비중 | 2분 |
| **`run-k6.sh`** | **거절률** — 도착률 고정(open-loop). xargs 로는 못 얻는 값 (§1.4) | 8~12분 |
| **`run-k6-steps.sh`** | **임계점 찾기** — 저부하→고부하 계단, Grafana 실시간 관찰 (§1.5) | 6~7분/Arm |

### 1.1 동기 vs Kafka 비교 ← 가장 먼저 볼 것

```bash
./bench-scripts/run-burst.sh              # 600건 / 동시 60 (기본)
./bench-scripts/run-burst.sh 1000 100     # 건수·동시수 지정
```

두 Arm 을 자동으로 연속 실행하고 비교표를 출력한다.

**출력 예시**

```
전략   TPS   p50   p95   p99   실패  응답직후PAID  drain후PAID  PENDING  완결avg(ms)  Hikari대기  Tomcat busy
sync   49.6  1086  1662  2379  0     600/600       600          0        167          39          50
kafka  65.2  884   1380  1741  0     48/600        600          0        6977         45          50
```

**볼 곳**

| 열 | 의미 |
|---|---|
| `TPS` | **처리량.** Kafka 쪽이 높아야 한다 |
| `p99` | 꼬리 지연. Kafka 쪽이 낮아야 한다 |
| **`응답직후PAID`** | **비동기가 실제로 동작하는 증거.** sync 는 전부 완료(600/600), kafka 는 일부만 |
| **`완결avg`** | **Kafka 의 대가.** sync 대비 수십 배 |
| `Hikari대기` | 커넥션을 못 받아 멈춰 있는 워커 수 |
| `Tomcat busy` | 50(최대)에 붙으면 워커 포화 |

### 1.2 대기 곡선

```bash
./bench-scripts/run-ladder.sh sync
./bench-scripts/run-ladder.sh kafka
```

동시 10 → 30 → 60 → 120 으로 올리며 측정한다.

**출력 예시 (sync)**

```
동시수  TPS    p50    p99    실패  Hikari대기  Tomcat busy
10      81.6   89     185    0     0           11
30      89.6   284    482    0     20          31
60      98.4   581    1087   0     39          50     <- 여기서 포화
120     102.2  1174   1976   0     39          50
```

**해석 포인트**

- **동시 12배(10→120) 늘 때 TPS 는 1.25배만 늘고 p50 은 13배 늘어난다** → 늘어난 시간은 전부 대기
- `Tomcat busy` 가 50 에 붙고 `Hikari대기` 가 39 → **워커 50개 중 39개가 커넥션 대기**.
  병목은 CPU 도 스레드도 아닌 **커넥션 10개**
- 리틀의 법칙으로 검산: `응답시간 = 동시수 ÷ 처리량` → `120 ÷ 102.2 = 1174ms` (실측과 일치)

> ⚠️ **이 스크립트는 단계 사이에 초기화하지 않는다** (앞 단계가 워밍업 역할).
> 그래서 절대값이 `run-burst.sh` 보다 작게 나온다. **동일 실행 내 상대 비교용**으로만 쓸 것.

### 1.3 요청 비용 분해

```bash
./bench-scripts/run-decompose.sh
```

`curl` 대신 **앱이 스스로 기록하는 서버 사이드 지표**를 읽는다.
결제(payments 내부)가 요청 전체에서 몇 % 인지 보여준다.

---

---

### 1.4 k6 도착률 고정 테스트 — 거절률 측정

### 왜 k6 인가

`run-burst.sh` 의 `xargs -P` 는 **폐쇄 루프**다. 워커가 응답을 받아야 다음 요청을 보내므로,
시스템이 느려지면 부하도 함께 줄어 **임계점을 넘지 못한다.** 그래서 지금까지 전 회차 실패 0건이었다.

k6 의 `ramping-arrival-rate` 는 시스템 상태와 무관하게 **초당 목표 건수를 계속 밀어넣는다.**
처리 못 한 요청이 쌓이다 거절(5xx/timeout)로 드러나므로, **"거절률" 은 이 방식으로만 측정된다.**

k6 는 compose 에 **자원 제한 없이** 올라간다 (SUT 예산 밖). 부하 생성기가 throttle 되면
목표 도착률을 못 만들어 측정 자체가 무효가 되기 때문이다.

### ⚠️ 먼저 알아야 할 것 — VU 프로비저닝 규칙

**이걸 모르면 회차를 통째로 날린다.** 실제로 한 번 날렸다.

```
필요 VU = 목표 도착률 × 클라이언트 타임아웃
```

VU 는 응답을 기다리는 동안 묶여 있고, 최대 묶이는 시간은 **클라이언트 타임아웃**이다(리틀의 법칙 상한).
VU 가 모자라면 k6 가 요청을 발사조차 못 하고 `dropped_iterations` 를 올린다.

| 타임아웃 | 250 TPS | 375 TPS | 600 TPS |
|---:|---:|---:|---:|
| 2s | 500 | 750 | 1,200 |
| **4s** | **1,000** | **1,500** | 2,400 |
| 10s | 2,500 | 3,750 | 6,000 |
| 30s | 7,500 | 11,250 | **18,000** |

**메모리가 상한이다.** VU 1개당 약 2MB → 여유 메모리 3GB 기준 **최대 약 1,500 VU**.

```bash
# 여유 메모리 확인
docker run --rm alpine sh -c "free -m | awk '/Mem:/{print \$7}'"
```

> **실패 사례:** 600 TPS 를 타임아웃 30초 · maxVUs 800 으로 돌렸다. 필요량은 18,000 VU —
> **가진 게 필요량의 4.4%** 였다. 결과적으로 두 Arm 이 받은 요청 수가 달랐고(27,762 vs 35,293),
> 같은 부하를 준 게 아니라 **서로 다른 부하**를 준 뒤 처리량을 비교한 꼴이 됐다. 회차 무효.

### 실행

```bash
# 기본값: 300 TPS / 60초 / 타임아웃 4s / maxVUs 1200  (규칙 만족: 300×4=1200)
./bench-scripts/run-k6.sh

# 도착률·시간 지정
./bench-scripts/run-k6.sh 250 90s

# VU·타임아웃 직접 조정
MAX_VUS=1500 REQ_TIMEOUT=4s ./bench-scripts/run-k6.sh 375 60s
```

규칙을 어기면 실행 전에 경고가 뜬다:

```
⚠️  필요 VU 2400 > maxVUs 1200 — dropped 발생이 확정적입니다.
    BURST 를 낮추거나 REQ_TIMEOUT 을 줄이거나 MAX_VUS 를 올리세요.
```

부하 프로파일은 `baseline 30 TPS → ramp → burst → recover` 4단계다.
각 구간 길이는 `T_BASE`/`T_RAMP`/`T_BURST`/`T_RECOVER` 로 조정한다.

### 결과 읽기

```
전략   처리TPS  p50  p95  p99  max  총요청  실패  실패율%  5xx  연결실패  dropped  ...
sync   ...
kafka  ...
```

| 열 | 의미 |
|---|---|
| **`dropped`** | **0 이어야 한다.** 0 이 아니면 부하 생성기가 목표 도착률을 못 만든 것 → **회차 무효** |
| `총요청` | 두 Arm 이 **같아야** 정상. 다르면 서로 다른 부하를 준 것 |
| `5xx` | 서버가 거절 (Hikari 커넥션 타임아웃 등) |
| `연결실패` | 클라이언트 타임아웃 / 연결 실패 |
| `실패율%` | **이게 "거절률"** — xargs 방식으로는 못 얻는 값 |
| `완결avg` | Kafka 의 대가 (최종 일관성 지연) |

### 회차 유효성 체크

```bash
# ① dropped 가 0 인가
grep "K6_RESULT" k6-scripts/results/k6-raw-sync.log

# ② VU 가 천장에 붙지 않았는가 (붙으면 open-loop 가 closed-loop 로 퇴화)
grep -a "running (" k6-scripts/results/k6-raw-sync.log | sed 's/\x1b\[[0-9;]*m//g' | tail -20
#   → "796/800 VUs" 처럼 maxVUs 에 근접하면 무효
```

**둘 다 통과해야 수치를 신뢰할 수 있다.**

### 타임아웃을 얼마로 잡을 것인가

타임아웃은 단순한 기술 파라미터가 아니라 **"몇 초 넘으면 거절로 칠 것인가"의 정의**다.

- **4초** — 권장. Hikari 커넥션 타임아웃 3초 바로 위라, 커넥션을 못 받아 실패한 요청을 잡아낸다
- 짧게 잡으면 → 측정 가능한 도착률 천장이 올라가지만, 거절 기준이 엄격해진다
- 길게 잡으면 → 필요 VU 가 비례해서 늘어 메모리에 막힌다

### 어디까지 측정 가능한가

현재 장비(여유 3GB) 기준 **타임아웃 4초에 약 375 TPS 가 상한**이다.
앞선 측정에서 커넥션 풀은 동시 50~60 에서 포화됐고 최대 처리량은 100~120 TPS 였으므로,
**375 TPS 면 용량의 3배가 넘어 임계점을 보기에 충분하다.** 600 TPS 까지 갈 필요가 없다.

---

### 1.5 계단식 부하 — 저부하에서 고부하까지 눈으로 보기 ★

**임계점이 어디인지 직접 보고 싶을 때 쓴다.** 도착률을 30 → 60 → 100 → 150 → 200 → 300 TPS 로
단계적으로 올리며, k6 지표와 서버 지표를 **Grafana 한 화면에서 같은 시간축**으로 본다.

```bash
# 기본: 30,60,100,150,200,300 TPS × 각 60초 (약 6.5분)
./bench-scripts/run-k6-steps.sh sync
./bench-scripts/run-k6-steps.sh kafka

# 계단·단계길이 지정
./bench-scripts/run-k6-steps.sh sync "50,100,200,400" 45s
```

**실행 전에 Grafana 를 열어두세요** → http://localhost:3000 → **🔬 Kafka A/B 부하 테스트**
(admin / admin, 5초 자동 갱신)

스크립트가 시작 시 VU 필요량과 메모리를 미리 계산해 경고한다.

```
VU 1200개 ≈ 2400MB 예상 / VM 여유 3243MB
▶ Arm=sync  계단=[30,60,100,150,200,300]  단계길이=60s  타임아웃=4s  maxVUs=1200
```

### 대시보드에서 볼 것 — 패널 8개

| 패널 | 무엇을 보나 | 임계점 신호 |
|---|---|---|
| **① 도착률 / 처리량** | k6 가 보낸 양 vs 서버가 처리한 양 | **두 선이 벌어지기 시작하는 지점 = 임계점** |
| **② 응답 시간** | p50 / p95 / p99 | p95 가 꺾여 올라가는 계단 |
| **③ 거절 / 유실** | 실패율, `dropped` | **dropped 가 0 이 아니면 그 회차 무효** |
| **④ 병목 — 커넥션/워커** | Hikari 대기·사용중, Tomcat busy | pending 상승 = 커넥션 풀 병목 / busy 50 = 워커 포화 |
| **⑤ Kafka lag** | 컨슈머 그룹별 lag | 쌓였다 0으로 빠지면 정상. **계속 우상향이면 컨슈머 임계점 초과** |
| **⑥ 생산 vs 소비** | 초당 레코드 생산·소비 | **생산 > 소비면 lag 증가** |
| **⑦ 리스너 처리 시간** | `spring_kafka_listener_seconds` | 컨슈머가 느려진 원인 |
| **⑧ JVM / CPU** | CPU %, GC pause | 포화면 다른 원인 의심 |

### 읽는 순서

1. **① 에서 두 선이 갈라지는 계단을 찾는다** — 그게 임계점
2. 그 시점의 **④** 를 본다 → `Hikari 대기`가 올라가면 병목은 커넥션 풀
3. Kafka Arm 이면 **⑤⑥** 을 본다 → 생산 > 소비로 갈라지면 컨슈머가 못 따라가는 것
4. **③ 의 dropped 가 0 인지 반드시 확인** — 아니면 위 해석 전부 무효

### 두 Arm 비교하는 법

같은 계단으로 sync·kafka 를 연달아 돌린 뒤, Grafana 시간 범위를 각 구간으로 맞춰 비교한다.
스크립트가 시작·종료 시각을 출력하므로 그 구간을 지정하면 된다.

**핵심은 "어느 계단에서 무너지는가" 다.** 앞선 측정에서 커넥션 풀은 동시 50~60,
최대 처리량 100~120 TPS 였으므로 **150~200 TPS 계단 부근에서 갈라질 것으로 예상**된다.

---

## 2. 돌아가는 동안 볼 것

### 터미널 — consumer lag 실시간

```bash
watch -n 2 'docker exec bench-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9090 --describe --all-groups \
  | awk "NR==1 || \$6 ~ /^[0-9]+$/" | sort -k6 -rn | head -15'
```

- Kafka Arm 에서 부하 중 **lag 이 쌓였다가** 종료 후 **0으로 내려가면** 정상 (버퍼링이 작동한 것)
- 종료 후에도 안 줄면 컨슈머 문제 → 로그 확인

### 브라우저 — Prometheus 즉석 쿼리

http://localhost:9090/graph 에서:

```promql
# 커넥션 대기 (병목의 직접 증거)
hikaricp_connections_pending{service="commerce"}

# 워커 포화도
tomcat_threads_busy_threads{service="commerce"}

# 서버 사이드 p95
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{service="commerce"}[30s])) by (le)) * 1000

# 초당 처리량
sum(rate(http_server_requests_seconds_count{service="commerce",status="204"}[30s]))
```

### 터미널 — 주문 상태 실시간

```bash
watch -n 2 'docker exec bench-mysql mysql -umysqlId -pmysqlPwd dbay_db \
  -e "SELECT orderStatus, COUNT(*) FROM Orders GROUP BY orderStatus;" 2>/dev/null'
```

Kafka Arm 에서 **`PENDING` 이 쌓였다가 서서히 `PAID` 로 넘어가는** 게 보인다. 이게 최종 일관성 지연이다.

---

## 3. 측정 후 검증

```bash
docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < bench-scripts/verify.sql
```

| 확인 항목 | 정상 |
|---|---|
| 주문 상태 분포 | `PENDING` 잔류 0 |
| 잔액 수지 | `deducted` = `expected` (결제건수 × 5,000) |
| 예치금 이력 | `PAYMENT_INTERNAL` 이 결제 건수와 일치 (2배면 이중 차감) |

DLT 확인:

```bash
for t in orders-purchase-commands carts-purchase-commands payments-purchase-commands; do
  echo -n "  $t.DLT: "
  docker exec bench-kafka /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
    --bootstrap-server kafka:9090 --topic "$t.DLT" 2>/dev/null | awk -F: '{s+=$3} END{print s+0}'
done
```

전부 `0` 이어야 한다. 0이 아니면 그 회차는 무효.

---

## 4. 함정 — 반드시 지킬 것

### 4.1 lag drain 전에 DB 를 지우지 말 것 ← 실제로 밟았던 함정

처리 중인 Kafka 커맨드가 남은 상태에서 `Orders` 를 지우면, 컨슈머가 `ORDER_NOT_FOUND` 를 만나
**지수 백오프 5회 재시도**에 들어가고 그 지연이 다음 측정 구간까지 밀려온다.
(실제로 Kafka Arm 이 `PAID 0 / PENDING 10` 으로 나와 한 회차를 날렸다.)

→ `bench-scripts/lib.sh` 의 `bench_reset` 은 항상 `bench_wait_lag` **다음에** 호출한다. 스크립트에 이미 반영됨.

### 4.2 회차 무효 조건

| 조건 | 확인 방법 |
|---|---|
| 머신 CPU 포화 | `top -l 1 \| head -5` — 여유 20% 미만이면 무효 |
| DLT 에 메시지 적재 | §3 의 DLT 확인 |
| Prometheus 타깃 DOWN | http://localhost:9090/targets |
| 워밍업 미실시 | 스크립트가 자동 수행 |

> **로컬 테스트의 최대 함정**: 부하 생성기(`xargs`)와 앱이 같은 머신에 있다.
> CPU 가 100% 에 붙으면 "서버가 느린 것" 이 아니라 **"측정 장비가 부족한 것"** 이다.

### 4.3 이 측정으로 증명되지 않는 것

`xargs -P` 는 **폐쇄 루프**다 — 워커가 응답을 받아야 다음 요청을 보내므로,
시스템이 느려지면 부하도 함께 줄어 임계점을 넘지 못한다.

따라서 **"거절률 0%" 는 증명할 수 없다.** 실제로 전 회차 실패 0건이었다.
거절을 보려면 도착률을 고정하는 도구(k6 `ramping-arrival-rate`)가 필요하다.

---

## 5. 자주 쓰는 명령

```bash
# Arm 만 바꿔서 수동 확인
BENCH_POSTPROCESS_STRATEGY=sync  docker compose -f docker/docker-compose-bench.yml up -d commerce
BENCH_POSTPROCESS_STRATEGY=kafka docker compose -f docker/docker-compose-bench.yml up -d commerce

# 주문 1건 수동 호출
curl -i -X POST localhost:8081/api/commerce/order/PROD-1 -H 'X-CODE: BENCH-1'

# 전체 초기화 (볼륨까지)
docker compose -f docker/docker-compose-bench.yml down -v

# 로그
docker logs -f bench-commerce
docker logs -f bench-payments
```

---

## 6. 접속 정보

| 대상 | 주소 |
|---|---|
| commerce | http://localhost:8081 |
| payments | http://localhost:8085 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin / admin) |
| MySQL | `localhost:13306` — mysqlId / mysqlPwd / dbay_db |
| Kafka (호스트) | `localhost:19092` |
| Kafka (컨테이너 내부) | **`kafka:9090`** ← CLI 는 반드시 이 주소 |
