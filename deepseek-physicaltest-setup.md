# Physical Test 실행 가이드 — Kafka 기반

> 마지막 수정: 2026-08-11
> 
> `deepseek-physicaltest.md` 의 설계를 실제로 실행하기 위한 단계별 가이드.
> HTTP가 아닌 **Kafka 기반**으로 예치금 충전/출금/환불을 테스트한다.

---

## 0. 설정 파일 경로

### 애플리케이션 설정

| 파일 | 용도 |
|---|---|
| `payments/src/main/resources/application.yml` | payments 기본 설정 (Docker 환경) |
| `payments/src/main/resources/application-local.yml` | payments 로컬 개발 설정 (H2 + localhost Kafka) |
| `commerce/src/main/resources/application.yml` | commerce 기본 설정 |
| `commerce/src/main/resources/application-local.yml` | commerce 로컬 개발 설정 |
| `docker/docker-compose.yml` | 프로덕션 Docker Compose |
| `docker/docker-compose-local.yml` | 로컬 개발 Docker Compose |

### Kafka 명령/이벤트 모델

| 파일 | 용도 |
|---|---|
| `core/src/main/java/io/devground/core/commands/deposit/ChargeDeposit.java` | 충전 커맨드 (userCode, paymentKey, amount, type) |
| `core/src/main/java/io/devground/core/commands/deposit/WithdrawDeposit.java` | 출금 커맨드 (userCode, amount, type, orderCode, productCodes) |
| `core/src/main/java/io/devground/core/commands/deposit/RefundDeposit.java` | 환불 커맨드 (userCode, amount, type) |
| `core/src/main/java/io/devground/core/commands/deposit/CreateDeposit.java` | 계좌 생성 커맨드 (userCode) |
| `core/src/main/java/io/devground/core/model/vo/DepositHistoryType.java` | 이력 타입 enum (CHARGE_TRANSFER, PAYMENT_INTERNAL, REFUND_INTERNAL, ...) |

### Kafka Consumer

| 파일 | 용도 |
|---|---|
| `payments/src/main/java/io/devground/payments/deposit/infrastructure/adapter/in/messaging/DepositKafkaConsumer.java` | deposits-commands 토픽 소비자 |

### 성능 테스트 스크립트 (신규)

| 파일 | 용도 |
|---|---|
| `k6-scripts/Dockerfile` | xk6-kafka 확장이 포함된 k6 Docker 이미지 빌드 |
| `k6-scripts/mixed-workload-kafka.js` | k6 혼합 부하 Kafka 테스트 스크립트 |
| `k6-scripts/seed-deposits.js` | 시드 데이터 생성 (Deposit 계좌 + 초기 잔액) |
| `k6-scripts/run-k6.sh` | k6 Docker 실행 헬퍼 스크립트 |

---

## 1. 전체 아키텍처 (테스트 구성)

```
┌─────────────────────────────────────────────────────────┐
│ Docker                                                    │
│  ┌──────────┐    ┌──────────┐                             │
│  │  MySQL   │    │  Kafka   │                             │
│  │  :3306   │    │  :9092   │                             │
│  └────▲─────┘    └──▲───┼──┘                             │
│       │              │   │                                │
└───────┼──────────────┼───┼────────────────────────────────┘
        │              │   │
┌───────┼──────────────┼───┼────────────────────────────────┐
│ Host (IntelliJ)       │   │                                │
│  ┌──────┴──────────┐  │   │  ┌──────────────────────┐     │
│  │  payments       │  │   └──│  k6-kafka (Docker)   │     │
│  │  :8085          │◄─┘      │  produce → deposit   │     │
│  │  local profile  │         │  commands topic      │     │
│  │  + MySQL override│        └──────────────────────┘     │
│  └─────────────────┘                                       │
└───────────────────────────────────────────────────────────┘
```

**실행 목록 (3개)**:

| # | 대상 | 타입 | 명령어 |
|---|---|---|---|
| 1 | MySQL | Docker | `docker compose -f docker-compose-local.yml up -d mysql` |
| 2 | Kafka | Docker | `docker compose -f docker-compose-local.yml up -d kafka` |
| 3 | payments | IntelliJ | `PaymentsApplication` (profile: `local`, DB override) |

---

## 2. Docker 인프라 실행

```bash
cd docker

# MySQL + Kafka 실행
docker compose -f docker-compose-local.yml up -d mysql kafka

# 상태 확인
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

---

## 3. Kafka 토픽 생성

`allow.auto.create.topics: false` 이므로 수동 생성이 필요하다.

```bash
TOPICS=(
  "deposits-commands"
  "deposits-events"
  "deposits-join-commands"
  "deposits-join-events"
  "deposits-purchase-commands"
  "deposits-purchase-events"
  "deposits-payment-events"
)

for topic in "${TOPICS[@]}"; do
  docker exec kafka /opt/kafka/bin/kafka-topics.sh \
    --create --if-not-exists \
    --bootstrap-server localhost:9092 \
    --topic "$topic" \
    --partitions 1 \
    --replication-factor 1
done

# 생성 확인
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --list --bootstrap-server localhost:9092
```

---

## 4. payments 서버 실행 (IntelliJ)

### 4.1 Run Configuration 설정

**IntelliJ → Run → Edit Configurations → PaymentsApplication**

| 항목 | 값 |
|---|---|
| **Main class** | `io.devground.payments.PaymentsApplication` |
| **VM options** | `-Xms384m -Xmx384m -XX:+UseG1GC -XX:MaxGCPauseMillis=200` |
| **Profile** | `local` |
| **Program arguments** | (아래 참고) |

### 4.2 Program arguments (DB 오버라이드)

`application-local.yml` 이 H2를 사용하므로, MySQL로 오버라이드한다:

```
--spring.datasource.url=jdbc:mysql://localhost:3306/dbay_db?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
--spring.datasource.username=mysqlId
--spring.datasource.password=mysqlPwd
--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect
--spring.jpa.hibernate.ddl-auto=update
--eureka.client.register-with-eureka=false
--eureka.client.fetch-registry=false
```

> Eureka 없이 실행하므로 등록 실패 WARNING 로그는 무시해도 된다.

### 4.3 서버 정상 기동 확인

```bash
# Kafka Consumer Group 확인
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group payments-consumer-group \
  --describe

# 헬스체크 (Actuator)
curl http://localhost:8085/actuator/health
```

---

## 5. 시드 데이터 생성

Deposit 계좌가 없는 상태에서 출금/환불 명령이 들어오면 전부 실패하므로,
**10,000개 Deposit 계좌 + 초기 잔액**을 미리 생성해야 한다.

### 5.1 방법 A: PaymentsSeedDataGenerator + 수동 충전

기존 `PaymentsSeedDataGenerator`는 SELLER-0 ~ SELLER-999 용이다.
USER-0 ~ USER-9999 용으로 확장이 필요하다.

### 5.2 방법 B: Kafka로 시드 전송 (k6-kafka 이미지 필요)

```bash
# k6-kafka 이미지 빌드 후
docker run --rm -i --network docker_default \
  -v $(pwd)/k6-scripts:/scripts \
  k6-kafka:latest run /scripts/seed-deposits.js
```

### 5.3 방법 C: SQL 직접 INSERT (가장 빠름)

```sql
-- MySQL 접속
docker exec -it mysql mysql -umysqlId -pmysqlPwd dbay_db

-- 10,000개 계좌 벌크 생성 + 잔액 충전
-- deposit 테이블 스키마에 맞게 조정
INSERT INTO deposit_entity (code, user_code, balance, created_at, updated_at)
SELECT UUID(), CONCAT('USER-', n), 1000000, NOW(), NOW()
FROM (SELECT ones.n + tens.n*10 + hundreds.n*100 + thousands.n*1000 AS n
      FROM (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
            UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) ones
      CROSS JOIN (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                  UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) tens
      CROSS JOIN (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                  UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) hundreds
      CROSS JOIN (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                  UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) thousands
      LIMIT 10000) AS nums
WHERE NOT EXISTS (SELECT 1 FROM deposit_entity d WHERE d.user_code = CONCAT('USER-', nums.n));
```

---

## 6. k6-kafka Docker 이미지 빌드

```bash
cd k6-scripts

# 빌드 (5~10분 소요, Go 컴파일)
docker build -t k6-kafka:latest -f Dockerfile .

# 빌드 확인
docker images k6-kafka
```

### Dockerfile (`k6-scripts/Dockerfile`)

```dockerfile
FROM golang:1.23-alpine AS builder
RUN go install go.k6.io/xk6/cmd/xk6@latest
RUN xk6 build --with github.com/mostafa/xk6-kafka@latest --output /k6

FROM alpine:3.20
RUN apk add --no-cache ca-certificates
COPY --from=builder /k6 /usr/local/bin/k6
ENTRYPOINT ["k6"]
```

---

## 7. Kafka 메시지 포맷

Spring Kafka `JsonDeserializer` 는 `__TypeId__` 헤더로 역직렬화 클래스를 결정한다.

### 7.1 충전 (ChargeDeposit)

```json
// 헤더: __TypeId__ = io.devground.core.commands.deposit.ChargeDeposit
// 토픽: deposits-commands
{
  "userCode": "USER-42",
  "paymentKey": "k6-test",
  "amount": 10000,
  "type": "CHARGE_TRANSFER"
}
```

### 7.2 출금 (WithdrawDeposit)

```json
// 헤더: __TypeId__ = io.devground.core.commands.deposit.WithdrawDeposit
// 토픽: deposits-commands
{
  "userCode": "USER-42",
  "amount": 5000,
  "type": "PAYMENT_INTERNAL",
  "orderCode": "k6-order-123",
  "productCodes": ["prod-1"]
}
```

### 7.3 환불 (RefundDeposit)

```json
// 헤더: __TypeId__ = io.devground.core.commands.deposit.RefundDeposit
// 토픽: deposits-commands
{
  "userCode": "USER-42",
  "amount": 5000,
  "type": "REFUND_INTERNAL"
}
```

### 7.4 수동 테스트 (kcat)

이미지 빌드 없이 빠르게 메시지 하나를 테스트:

```bash
# 충전 메시지 발행
echo '{"userCode":"USER-0","paymentKey":"manual-test","amount":10000,"type":"CHARGE_TRANSFER"}' | \
  docker exec -i kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic deposits-commands

# 잔액 확인
curl -H "X-CODE: USER-0" http://localhost:8085/api/deposits/balance
```

---

## 8. 테스트 실행

### 8.1 k6 실행 스크립트 (`k6-scripts/run-k6.sh`)

```bash
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT_NAME="${1:-mixed-workload-kafka.js}"
shift 2>/dev/null || true

docker run --rm -i \
    --network docker_default \
    -v "${SCRIPT_DIR}:/scripts" \
    -e KAFKA_BROKER=kafka:9090 \
    k6-kafka:latest \
    run "/scripts/${SCRIPT_NAME}" "$@"
```

### 8.2 설계기준 테스트 (스모크 1분)

```bash
cd k6-scripts
./run-k6.sh mixed-workload-kafka.js --duration 1m
```

### 8.3 설계기준 테스트 (본 테스트 30분)

```bash
./run-k6.sh mixed-workload-kafka.js --duration 30m
```

### 8.4 k6-kafka 이미지 미빌드 시 대안

kafka-console-producer 로 간이 부하 발생:

```bash
# 1000건의 충전 메시지를 순차 발행
for i in $(seq 0 999); do
  echo "{\"userCode\":\"USER-$i\",\"paymentKey\":\"load-$i\",\"amount\":10000,\"type\":\"CHARGE_TRANSFER\"}" | \
    docker exec -i kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 \
      --topic deposits-commands
done
```

---

## 9. Prometheus + Grafana 모니터링 스택

### 9.0 개념 — Prometheus와 Grafana가 뭔가요?

**Prometheus**는 메트릭 시계열 데이터베이스야.  
payments 서버가 `/actuator/prometheus`에 현재 상태를 숫자로 뿌리면,  
Prometheus가 5초마다 그 숫자들을 긁어가서 시계열 DB에 저장해.

**Grafana**는 그 저장된 데이터를 그래프로 그려주는 대시보드 도구야.

```
payments ──"지금 힙 200MB, GC 15ms, 커넥션 3개"──▶ Prometheus ──시계열DB──▶ Grafana 그래프
  :8085                                               :9090                    :3000
```

쉽게 비유하면:
- **payments** = 몸에 붙은 심박계 (현재 상태를 숫자로 출력)
- **Prometheus** = 운동 기록 앱 (숫자를 주기적으로 수집해서 저장)
- **Grafana** = 대시보드 화면 (저장된 데이터를 그래프로 보여줌)

테스트 돌리는 동안 Grafana 화면 하나 띄워두면, JVM 메모리·GC·DB 커넥션·CPU가
실시간 그래프로 움직이는 걸 볼 수 있어. 어디서 병목이 생기는지 바로 눈에 보이는 거지.

#### 설정된 파일 구성

| 파일 | 역할 | 상태 |
|---|---|---|
| `payments/build.gradle` | actuator + micrometer-registry-prometheus 의존성 | ✅ 추가됨 |
| `payments/.../application-local.yml` | `/actuator/prometheus` 엔드포인트 노출 | ✅ 추가됨 |
| `docker/prometheus.yml` | payments:8085에서 5초 간격으로 메트릭 수집 설정 | ✅ 생성됨 |
| `docker/grafana/provisioning/datasources/prometheus.yml` | Grafana ↔ Prometheus 자동 연결 | ✅ 생성됨 |
| `docker/grafana/provisioning/dashboards/deposit-performance.json` | 예치금 성능 대시보드 (14개 패널) | ✅ 생성됨 |
| `docker/docker-compose-local.yml` | prometheus + grafana 서비스 정의 | ✅ 추가됨 |

> 아직 `docker compose up`만 실행 안 한 상태. 아래 9.2절에서 실행하면 바로 동작한다.

---

payments 모듈에 actuator + prometheus 의존성이 추가되어 있어야 한다:

```groovy
// payments/build.gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
```

`application-local.yml`에 Prometheus 엔드포인트 노출 설정:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
  metrics:
    export:
      prometheus:
        enabled: true
```

### 9.2 Prometheus + Grafana 실행

```bash
cd docker
docker compose -f docker-compose-local.yml up -d prometheus grafana
```

### 9.3 접속 정보

| 서비스 | URL | 계정 |
|---|---|---|
| **Grafana** | http://localhost:3000 | admin / admin |
| **Prometheus** | http://localhost:9090 | - |
| **Payments Metrics** | http://localhost:8085/actuator/prometheus | - |

### 9.4 사전 로드된 대시보드

Grafana 접속 후 **Dashboards → 💰 예치금 성능 테스트 대시보드** 에서 다음 패널을 볼 수 있다:

| 패널 | 메트릭 | deepseek-physicaltest.md 임계값 |
|---|---|---|
| **TPS** | `rate(http_server_requests_seconds_count[30s])` | 설계기준: 목표 TPS ±5% |
| **p50/p95/p99 응답시간** | `histogram_quantile(http_server_requests_seconds_bucket)` | p95<100ms, p99<300ms |
| **에러율** | 5xx / total | 0% (설계기준), <0.1% (한계) |
| **JVM Heap** | `jvm_memory_used_bytes{area="heap"}` | GC 후 <60% (설계), <85% (한계) |
| **GC 후 힙 사용률** | used/max % | 게이지: 60% 노랑, 85% 빨강 |
| **GC Pause Time** | `jvm_gc_pause_seconds` | <30ms 정상, >100ms 경고 |
| **Full GC 횟수** | `increase(jvm_gc_pause_seconds_count[5m])` | 0회 (발생 시 중단) |
| **Threads** | `jvm_threads_live_threads` | <100 정상, >200 경고 |
| **CPU** | `system_cpu_usage * 100` | <50% (설계), <90% (한계) |
| **HikariCP Connections** | `hikaricp_connections_active/idle/pending/max` | active < max 도달 시 경고 |

### 9.5 PromQL 쿼리 예시 (직접 조회)

```bash
# GC 후 힙 사용량
curl 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes{area="heap"}' | jq

# GC pause p95 (지난 1분)
curl 'http://localhost:9090/api/v1/query?query=histogram_quantile(0.95,rate(jvm_gc_pause_seconds_bucket[1m]))' | jq

# HikariCP 활성 커넥션
curl 'http://localhost:9090/api/v1/query?query=hikaricp_connections_active' | jq
```

### 9.6 Kafka Consumer Lag

```bash
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group payments-consumer-group \
  --describe
```

### 9.7 MySQL 메트릭

```bash
# 슬로우 쿼리
docker exec mysql mysql -uroot -p'R00t!2025@Secure1' -e \
  "SHOW GLOBAL STATUS LIKE 'Slow_queries';"

# 락 경합
docker exec mysql mysql -uroot -p'R00t!2025@Secure1' -e \
  "SHOW STATUS LIKE 'innodb_row_lock%';"
```

### 9.8 프로세스 메모리

```bash
ps -o pid,rss,vsz,comm -p $(pgrep -f payments)
```

### 9.9 Grafana 사용법 — 테스트 중 실시간 모니터링

#### 화면 구성 익히기

Grafana 접속 후 왼쪽 사이드바에서:

1. **Dashboards** 클릭 → **💰 예치금 성능 테스트 대시보드** 선택
2. 대시보드가 열리면 오른쪽 위 **시간 범위**를 `Last 15 minutes` → `Last 5 minutes` 로 변경 (테스트 직전)
3. **Refresh** 드롭다운을 `5s` 로 설정 (실시간 갱신)

#### 테스트 단계별로 봐야 할 패널

**① 웜업 단계 (JVM Warm-up)**
- `JVM Heap` 패널: Eden이 채워지고 Old Gen이 안정화되는지
- `GC Pause Time`: Young GC가 정상 범위(30ms 이하)인지
- `HikariCP Connections`: 커넥션 풀이 minimumIdle까지 채워졌는지

**② 설계기준 테스트 (30분)**

- `TPS`: 목표 TPS ±5% 범위 내 유지되는지
- `p95/p99 응답시간`: 꾸준히 평탄한지 (치솟는 구간 = GC 또는 락 경합)
- `GC 후 힙 사용률` 게이지: 30분 후에도 60%를 넘지 않는지 → 넘으면 slow memory leak 의심
- `Full GC 횟수`: 무조건 **0** 이어야 함

**③ 한계 테스트 (TPS 단계적 증가)**

- `HikariCP Connections`: active가 max에 도달하는 순간이 커넥션 풀 병목
- `GC Pause Time`: TPS 올릴수록 GC pause가 선형 증가하는지, 특정 지점에서 급증하는지
- `CPU`: 90%에 도달하면 CPU 바운드 → 그 이상 TPS를 올려도 처리량은 증가하지 않음

**④ 혼합 부하 테스트**

- `에러율`: 특정 연산에서만 실패율이 급증하는지 = 그 연산이 공유 리소스 경합에 가장 취약
- `TPS` 패널의 URI별 라인: 특정 URI만 처리량이 떨어지면 해당 연산이 병목

#### 중단 기준 발생 시 대응

deepseek-physicaltest.md 8.6절의 중단 조건 중 하나라도 Grafana에서 확인되면:

| Grafana에서 보이는 현상 | 의미 | 조치 |
|---|---|---|
| `에러율` 패널이 1% 돌파 | OOM, 커넥션 고갈, DB 락 타임아웃 | k6 중단, payments 로그 확인 |
| `Full GC 횟수`가 1 이상 | 힙 부족 | `-Xmx` 증설 또는 메모리 leak 점검 |
| `GC Pause Time`이 100ms 초과 지속 | GC가 응답시간에 영향 | 힙 증설 또는 G1GC 리전 사이즈 조정 |
| `HikariCP` active = max | 커넥션 풀 고갈 | `maximumPoolSize` 증가 |
| `JVM Heap` 사용률 85% 초과 | 곧 OOM | 힙 증설, GC 로그 분석 |

#### 테스트 종료 후 분석

1. **스냅샷 저장**: Grafana 우측 상단 📷 아이콘 → **Snapshot** → 로컬에 저장
2. **시간 범위 조정**: 테스트 전체 구간(`Last 1 hour` 등)으로 넓혀서 전체 추이 확인
3. **특정 구간 확대**: 그래프에서 드래그로 특정 시간대 확대 → TPS 변경 시점의 메트릭 변화 분석
4. **Prometheus 직접 조회**: Grafana Explore 탭에서 raw PromQL로 세밀한 분석

#### 자주 쓰는 Grafana 조작

| 동작 | 방법 |
|---|---|
| 그래프 확대 | 그래프 영역 드래그 |
| 전체 시간으로 복귀 | 그래프 더블클릭 |
| 특정 범례만 보기 | 범례 클릭 (토글) |
| 범례 하나만 보기 | 범례 Ctrl+Click |
| 패널 전체화면 | 패널 제목 옆 ▼ → View |
| Y축 범위 고정 | 패널 Edit → Standard options → Min/Max |

### 9.10 테스트 시나리오별 Grafana 체크리스트

```
[설계기준 30분]
 □ p95 < 100ms 유지
 □ p99 < 300ms 유지
 □ Full GC 0회
 □ GC 후 힙 사용률 < 60%
 □ HikariCP active 평균 < 5
 □ CPU < 50%
 □ 에러율 0%

[한계 - 단일 연산]
 □ 단계별로 GC Pause Time 급증 지점 기록
 □ HikariCP active = max 도달 시점 기록
 □ CPU 90% 도달 시점 기록
 □ 에러율 첫 발생 시점 기록
 → 가장 먼저 발생한 지표 = 그 연산의 병목

[한계 - 혼합 부하]
 □ 특정 URI만 TPS 하락하는지
 □ 특정 연산만 에러율 급증하는지
 □ 공유 리소스(HikariCP, Buffer Pool) 경합 발생 시점
```

---

## 10. 전체 실행 순서 (처음부터 끝까지)

아래 순서대로 복사해서 터미널에 붙여넣으면 된다.  
[⏎] 는 Enter 키를 의미하고, 들여쓰기는 복사하지 말 것.

### Step 1: Docker 인프라 실행

```bash
cd /Users/kurt/IdeaProjects/beadv1_1_dev_ground_BE/docker

# MySQL + Kafka 실행
docker compose -f docker-compose-local.yml up -d mysql kafka

# 실행 확인
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
# → mysql, kafka 둘 다 Up 상태여야 함
```

### Step 2: Kafka 토픽 생성

```bash
TOPICS=(
  "deposits-commands" "deposits-events"
  "deposits-join-commands" "deposits-join-events"
  "deposits-purchase-commands" "deposits-purchase-events"
  "deposits-payment-events"
)

for topic in "${TOPICS[@]}"; do
  docker exec kafka /opt/kafka/bin/kafka-topics.sh \
    --create --if-not-exists \
    --bootstrap-server localhost:9092 \
    --topic "$topic" --partitions 1 --replication-factor 1
done

# 생성 확인
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --list --bootstrap-server localhost:9092 | grep deposits
```

### Step 3: Prometheus + Grafana 실행

```bash
cd /Users/kurt/IdeaProjects/beadv1_1_dev_ground_BE/docker
docker compose -f docker-compose-local.yml up -d prometheus grafana

# 확인
docker ps --format "table {{.Names}}\t{{.Status}}"
# → prometheus, grafana 도 Up
```

### Step 4: payments 서버 실행 (IntelliJ)

IntelliJ에서 직접 실행한다 (터미널 명령어 아님).

1. **IntelliJ → Run → Edit Configurations → ＋ → Application**
2. 아래 표대로 입력:

| 항목 | 값 |
|---|---|
| Name | `payments-perf-test` |
| Main class | `io.devground.payments.PaymentsApplication` |
| VM options | `-Xms384m -Xmx384m -XX:+UseG1GC -XX:MaxGCPauseMillis=200` |
| Profile | `local` |

3. **Program arguments** 에 아래 내용을 한 줄로 붙여넣기:

```
--spring.datasource.url=jdbc:mysql://localhost:3306/dbay_db?serverTimezone=Asia/Seoul&characterEncoding=UTF-8 --spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver --spring.datasource.username=mysqlId --spring.datasource.password=mysqlPwd --spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect --spring.jpa.hibernate.ddl-auto=update --eureka.client.register-with-eureka=false --eureka.client.fetch-registry=false
```

4. **Run** 클릭

5. 서버 로그에서 확인할 것:
```
Started PaymentsApplication in X.XXX seconds
```

6. Prometheus 엔드포인트 확인:
```bash
curl http://localhost:8085/actuator/prometheus | head -5
# → jvm_ 메트릭이 주르륵 나오면 OK
```

### Step 5: 시드 데이터 생성

Deposit 계좌가 없으면 Kafka 메시지를 보내도 전부 실패하므로,
USER-0 ~ USER-9999 까지 계좌를 미리 만든다.

```bash
# MySQL에 직접 벌크 INSERT (가장 빠름)
docker exec -i mysql mysql -umysqlId -pmysqlPwd dbay_db << 'SQL'
INSERT IGNORE INTO deposit_entity (code, user_code, balance, created_at, updated_at)
SELECT UUID(), CONCAT('USER-', nums.n), 1000000, NOW(), NOW()
FROM (
  SELECT ones.n + tens.n*10 + hundreds.n*100 + thousands.n*1000 AS n
  FROM (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) ones
  CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
              UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) tens
  CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
              UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) hundreds
  CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
              UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) thousands
  LIMIT 10000
) nums;
SQL

# 생성 확인
docker exec mysql mysql -umysqlId -pmysqlPwd dbay_db -e \
  "SELECT COUNT(*) AS deposit_count FROM deposit_entity;"
# → 10000 이 나와야 함
```

### Step 6: k6-kafka Docker 이미지 빌드

```bash
cd /Users/kurt/IdeaProjects/beadv1_1_dev_ground_BE/k6-scripts
docker build -t k6-kafka:latest -f Dockerfile .
# ⏳ 5~10분 소요

# 빌드 확인
docker images k6-kafka
# → REPOSITORY: k6-kafka, TAG: latest
```

### Step 7: Grafana 대시보드 열기

```bash
open http://localhost:3000
```

- 로그인: `admin` / `admin`
- 왼쪽 사이드바 → **Dashboards** → **💰 예치금 성능 테스트 대시보드**
- 우측 상단 시간 범위 → `Last 5 minutes`
- Refresh → `5s`

### Step 8: 스모크 테스트 (1분)

```bash
# k6 Kafka 부하 테스트 실행 (Kafka producer 측정)
docker run --rm -i --network docker_default \
  -v /Users/kurt/IdeaProjects/beadv1_1_dev_ground_BE/k6-scripts:/scripts \
  -e KAFKA_BROKER=kafka:9090 \
  k6-kafka:latest run /scripts/mixed-workload-kafka.js --duration 1m
```

테스트 돌리는 동안:
- **Grafana**: TPS/Heap/GC/HikariCP 그래프가 실시간으로 움직이는지 확인
- **터미널**: k6 출력에서 producer rate, error count 확인

테스트 종료 후:
```bash
# Kafka Consumer Lag 확인 (0이어야 정상)
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group payments-consumer-group \
  --describe

# MySQL 슬로우 쿼리 확인 (0이어야 정상)
docker exec mysql mysql -umysqlId -pmysqlPwd dbay_db -e \
  "SHOW GLOBAL STATUS LIKE 'Slow_queries';"
```

### Step 9: 설계기준 테스트 (30분)

스모크 테스트 통과했으면 본 테스트:

```bash
docker run --rm -i --network docker_default \
  -v /Users/kurt/IdeaProjects/beadv1_1_dev_ground_BE/k6-scripts:/scripts \
  -e KAFKA_BROKER=kafka:9090 \
  k6-kafka:latest run /scripts/mixed-workload-kafka.js --duration 30m
```

30분 후 Grafana에서 확인할 것:
- [ ] p95 < 100ms 유지
- [ ] Full GC 횟수 = 0
- [ ] GC 후 힙 사용률 < 60%
- [ ] HikariCP active 평균 < 5
- [ ] 에러율 0%

### Step 10: 한계 테스트 (TPS 단계적 증가)

`mixed-workload-kafka.js`의 `rate` 값을 수정해서 각 단계별로 실행한다:

| 단계 | rate (charge/withdraw/refund) | duration |
|---|---|---|
| 20 TPS | 6 / 4 / 4 | 5m |
| 50 TPS | 15 / 10 / 10 | 5m |
| 100 TPS | 30 / 20 / 20 | 5m |
| 150 TPS | 45 / 30 / 30 | 5m |
| 200 TPS | 60 / 40 / 40 | 5m |

각 단계 종료 후 Grafana 스냅샷 저장 (📷 → Snapshot).

### Step 11: 정리

```bash
# Grafana 스냅샷 저장 후 컨테이너 중지
cd /Users/kurt/IdeaProjects/beadv1_1_dev_ground_BE/docker
docker compose -f docker-compose-local.yml stop prometheus grafana

# MySQL/Kafka는 필요하면 유지
docker compose -f docker-compose-local.yml stop mysql kafka

# 전체 삭제 (데이터도 날아감)
docker compose -f docker-compose-local.yml down -v
```

## 11. 테스트 체크리스트

---

## 11. Kafka 기반 테스트의 한계와 보완

k6로 Kafka produce까지만 측정하기 때문에, **end-to-end 응답 시간**을 직접 알 수 없다.
대신 아래로 보완한다:

| 측정 항목 | 측정 방법 | 도구 |
|---|---|---|
| **Producer TPS** | k6 `kafka.writer.produce.rate` | k6 |
| **Producer latency** | k6 `kafka.writer.produce.duration` | k6 |
| **Consumer 처리량** | `StepExecution.getDuration()` | 배치의 경우 |
| **Consumer Lag** | `kafka-consumer-groups --describe` | Kafka CLI |
| **DB 락 경합** | `SHOW STATUS LIKE 'innodb_row_lock%'` | MySQL |
| **JVM 메모리/GC** | `/actuator/metrics/jvm.*` | Actuator |

> 진정한 end-to-end 측정이 필요하면, `deposits-events` 토픽을 k6에서 consume하여
> 명령 발행 시각과 이벤트 수신 시각의 차이를 계산하는 별도 스크립트가 필요하다.
