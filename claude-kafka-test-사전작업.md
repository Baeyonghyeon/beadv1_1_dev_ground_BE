# Kafka 부하 테스트 — 사전 작업 기록 (P0-6 처리)

> 작성일: 2026-08-26
> 설계 문서: [`claude-kafka-test-설계.md`](./claude-kafka-test-설계.md)
> 이 문서의 범위: **P0-6(관측 부재) 해소 + bench 실행 환경 구축**까지.
> 시나리오 코드(P0-1, P0-3, Arm A 분기)는 **아직 미완** — §5 참조.

---

## 1. 왜 이 작업을 먼저 했는가

설계 문서 §6.1 진단 결과, **부하를 정면으로 받는 commerce 를 관측할 수단이 아예 없었다.**

| 문제 | 상태 |
|------|------|
| `commerce/build.gradle` 에 actuator·micrometer 의존성 없음 | `/actuator/prometheus` 엔드포인트 자체가 존재하지 않음 |
| `docker/prometheus.yml` 의 `:8081` 타깃 | 엔드포인트가 없으니 **영구 DOWN** |
| `percentiles-histogram` 설정 없음 | `http_server_requests_seconds_bucket` 미생성 → Grafana `histogram_quantile` 패널이 빈 그래프 |
| Tomcat 스레드 메트릭 | `mbeanregistry` 비활성 → `tomcat_threads_*` 미노출 |

이번 실험에서 **P2(버퍼링 증명)의 핵심 증거**는 commerce 쪽 Tomcat 스레드 포화와 HikariCP 대기다.
그게 안 보이면 부하를 아무리 걸어도 "왜 무너졌는지" 를 설명할 수 없다.

---

## 2. 변경 파일

### 2.1 신규 생성

| 파일 | 목적 |
|------|------|
| `commerce/src/main/resources/application-bench.yml` | commerce 부하 테스트 프로파일 |
| `payments/src/main/resources/application-bench.yml` | payments 부하 테스트 프로파일 (**H2 → MySQL**) |
| `commerce/src/main/java/io/devground/dbay/common/bench/BenchAiConfig.java` | bench 전용 no-op `VectorStore` |
| `docker/docker-compose-bench.yml` | t3.large 재현 스택 (자원 상한 포함) |
| `docker/Dockerfile-bench` | 사전 빌드 jar 복사용 런타임 이미지 |
| `docker/prometheus-bench.yml` | 컨테이너 네트워크용 스크레이프 설정 + k6 remote write |
| `.dockerignore` | 빌드 컨텍스트 축소 (jar 는 제외하지 않음) |

### 2.2 수정

| 파일 | 변경 | 이유 |
|------|------|------|
| `commerce/build.gradle` | `spring-boot-starter-actuator`, `micrometer-registry-prometheus` 추가 | **P0-6 본체.** 이게 없으면 측정 불가 |
| `commerce/.../scheduler/OrderScheduler.java` | `@Scheduled(cron = "${order.scheduler.auto-delivery-cron:0 0 3 * * *}")` 로 외부화 | bench 에서 `-` 로 비활성화. 테스트 중 주문 상태가 자동 변경되면 **완결 시간 분포가 오염**된다 (설계 §4.5) |
| `payments/.../scheduler/SettlementJobScheduler.java` | `@Scheduled(cron = "${custom.settlement.cron:0 0 2 2 * *}")` 로 외부화 | 동일. 기본값은 그대로라 운영 동작은 변화 없음 |
| `payments/.../service/PaymentServiceImpl.java` | 이력 저장을 락 구간 밖으로 이동 + `payments.history.strategy` 스위치 | **P0-7 수정.** 락 임계 구간 안의 `REQUIRES_NEW` 가 FK 검사로 자기 X 락에 막혀 결제가 50초 타임아웃 |
| `payments/.../service/DepositHistoryRecorder.java` | `recordPaymentHistoryInTx`(join) + `onPaymentCompleted`(after-commit) 두 전략 구현 | 동일 |
| `payments/src/main/resources/application-bench.yml` | `payments.history.strategy` 추가 | 두 전략 비교 측정용 |
| `docker/docker-compose-bench.yml` | `PAYMENTS_HISTORY_STRATEGY` 환경변수 | 재빌드 없이 전략 전환 |
| `commerce/.../adapter/out/bench/BenchOrderUserAdapter.java` (신규) | `@Primary @Profile("bench")` 사용자 스텁 | **P0-3.** user-service 없이 주문 성립 |
| `commerce/.../adapter/out/bench/BenchOrderProductAdapter.java` (신규) | 동일, 상품 스텁 (5,000원 고정) | 동일. 설계 §3.2 변수 통제 |
| `commerce/src/main/resources/application-bench.yml` | `bench.product.price`, `bench.product.seller-pool` | 스텁 파라미터 |
| `payments/src/test/.../PaymentProcessIntegrationTest.java` | lost update 테스트를 회귀 방지 테스트로 전환 | 락 도입으로 전제가 뒤집힘 (§6.4) |
| `commerce/.../port/out/postprocess/OrderPostProcessPort.java` (신규) | 후속 처리 전략 인터페이스 | **산출물 ⑤ (Arm A 분기).** 기존 `OrderPaymentPort` 와 같은 패턴 |
| `commerce/.../adapter/out/postprocess/SyncOrderPostProcessAdapter.java` (신규) | 동기 DB 쓰기 (Arm A) | `paid()` + `removeCartItems()` 를 호출부 스레드에서 실행 |
| `commerce/.../adapter/out/postprocess/KafkaOrderPostProcessAdapter.java` (신규) | Kafka 커맨드 3건 발행 (Arm B) | 기존 `handlePaymentResult` 인라인 코드를 어댑터로 추출 |
| `commerce/.../service/OrderApplication.java` | `order.postprocess.strategy` 스위치 + 죽은 `orderKafkaEventPort` 의존성 제거 | 동일 |

> 두 스케줄러 모두 **기본값을 유지**했으므로 bench 프로파일이 아닌 환경의 동작은 바뀌지 않는다.

---

## 3. 주요 설정 결정과 근거

### 3.1 관측 (P0-6 해소)

```yaml
management:
  metrics:
    tags:
      service: commerce            # Grafana 대시보드의 service 라벨과 일치시킴
    distribution:
      percentiles-histogram:
        http.server.requests: true # ← 없으면 p95/p99 패널이 빈 그래프
      slo:
        http.server.requests: 50ms,100ms,300ms,1s,3s
server:
  tomcat:
    mbeanregistry:
      enabled: true                # ← 없으면 tomcat_threads_* 미노출
```

`percentiles-histogram` 과 `mbeanregistry` 두 줄이 실질적인 핵심이다. commerce·payments 양쪽에 동일하게 넣어 **같은 대시보드에서 나란히 비교**할 수 있게 했다.

### 3.2 의도적 자원 제약 (설계 §3.4)

```yaml
server.tomcat.threads.max: 50      # 워커 포화 지점을 만든다
server.tomcat.accept-count: 100
spring.datasource.hikari:
  maximum-pool-size: 10
  connection-timeout: 3000         # 3초 내 미획득 → 예외 → "거절"로 계측
spring.jpa.open-in-view: false     # 커넥션 점유 시간 왜곡 방지
```

### 3.3 결제 시나리오와 무관한 것 제거

| 대상 | 처리 |
|------|------|
| Eureka | `eureka.client.enabled: false` + `spring.cloud.discovery.enabled: false` |
| Spring Batch Job | `spring.batch.job.enabled: false` |
| 스케줄러 (주문 자동전이 / 정산) | cron `-` |
| Swagger | `springdoc.*.enabled: false` |
| Elasticsearch / VectorStore | 자동 구성 제외 + no-op 빈 (§3.4) |
| Toss 외부 결제 | 더미 키. 호출 경로 자체를 타지 않음 |

### 3.4 ⚠️ Elasticsearch — 예상 밖의 기동 실패와 해결

**증상:** ES 없이 commerce 기동 시도 → 실패

```
BeanCreationException: Error creating bean with name 'vectorStore'
  ... ElasticsearchVectorStore.indexExists(ElasticsearchVectorStore.java:305)
  ... Caused by: java.net.ConnectException: Connection refused
```

**원인:** `ElasticsearchVectorStore.afterPropertiesSet()` 이 `initialize-schema` 값과 **무관하게** `indexExists()` 를 호출한다. 즉 `initialize-schema: false` 로는 회피되지 않는다.

**그런데 자동 구성만 제외해도 안 된다:** `CartVectorSearchAdapter` 가 `VectorStore` 를 생성자 주입받는 `@Component` 라, 이번엔 그쪽에서 기동이 깨진다.

**해결:** 자동 구성을 제외하고 bench 전용 no-op 빈으로 대체.

```yaml
spring.autoconfigure.exclude:
  - org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration
  - org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration
```
+ `BenchAiConfig` 의 `NoOpVectorStore` (호출되면 `UnsupportedOperationException` — 부하 테스트가 검색 경로를 타면 즉시 드러나게 함)

> **대안이었던 "ES 컨테이너 추가"를 택하지 않은 이유:** ES 최소 힙 512MB 는 t3.large 8GB 예산에서 큰 비중이고,
> 결제 시나리오와 무관한 컴포넌트가 SUT 자원을 갉아먹으면 측정이 왜곡된다.

---

## 4. t3.large (2 vCPU / 8 GiB) 재현

### 4.1 자원 예산

**서비스마다 8GB가 아니라, 전체 합이 8GB 안에 들어가도록 배분했다.**

| 구분 | 서비스 | cpus | memory | JVM 힙 |
|------|--------|-----:|-------:|--------|
| SUT | mysql | 0.50 | 1400M | — (`innodb_buffer_pool=512M`) |
| SUT | kafka | 0.40 | 1200M | 384m |
| SUT | commerce | 0.65 | 1400M | 512m |
| SUT | payments | 0.45 | 1300M | 512m |
| **SUT 소계** | | **2.00** | **5300M (5.18 GiB)** | ← **t3.large 재현 대상** |
| 관측 | prometheus | 0.30 | 600M | — |
| 관측 | grafana | 0.20 | 500M | — |
| 관측 | kafka-exporter | 0.10 | 128M | — |
| **관측 소계** | | 0.60 | 1228M (1.20 GiB) | |
| **총계** | | **2.60** | **6528M (6.38 GiB)** | Docker Desktop 총량 7.65 GiB 이내 ✅ |

JVM non-heap 도 상한을 고정했다 (컨테이너 OOM 방지 + Arm 간 조건 동일화):

```
-Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+AlwaysPreTouch
-XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=128m -XX:ReservedCodeCacheSize=128m -Xss512k
-XX:+ExitOnOutOfMemoryError
```

`AlwaysPreTouch` — 힙을 기동 시 전부 커밋해 측정 중 페이지 폴트 변동을 없앤다.
`ExitOnOutOfMemoryError` — OOM 을 좀비 상태가 아니라 **명확한 종료**로 드러낸다.

### 4.2 예산에서 의도적으로 벗어난 것 (결과 문서에 명시할 이탈 사항)

| 이탈 | 내용 | 이유 |
|------|------|------|
| 관측 스택 | SUT 2 vCPU 예산 **밖**에 별도 배정 | 관측 오버헤드가 SUT 자원을 잠식하면 측정 왜곡 |
| 부하 생성기 | k6 는 compose 밖(호스트)에서 실행 | 같은 예산 안이면 설계 §6.5 "회차 무효 조건"에 즉시 걸림 |
| CPU 모델 | 18코어 VM 에 `cpus` 상한으로 2 vCPU 를 흉내냄 | 실제 2코어 머신과 스케줄링·캐시 지역성이 다름. **CPU 시간 총량은 제한되므로 임계점 재현에는 충분** |
| 포트 | MySQL 13306, Kafka 19092 | 기존 로컬 개발 컨테이너(3306/9092)와 충돌 방지 |

---

## 5. ⚠️ 아직 안 된 것 — 지금은 부하 테스트를 돌릴 수 없다

**현재 상태: 실행 환경과 관측 기반은 완료. 시나리오 코드는 미완.**

~~실제로 주문 요청을 보내면 실패한다~~ → **P0-3 해결로 정상 동작한다** (§6.2 참조).
남은 블로커는 **P0-1(Kafka 경로 이중 차감)** 과 **Arm A 분기 미구현** 두 가지다.
즉 **Arm B 는 지금 바로 측정 가능**하고, Arm A/A′/C 는 아직이다.

| 남은 과제 | 영향 | 설계 문서 |
|-----------|------|----------|
| ~~**P0-3** user/product Feign 스텁~~ | ✅ **해결됨** — 주문 전체 플로우 동작 (§6.2) | §2, §8-①② |
| ~~P0-7 락 임계 구간~~ | ✅ **해결됨** — 50.7s/500 → 0.09s/200 (§6.1) | §2 |
| ~~**Arm A 분기 미구현**~~ | ✅ **해결됨** — `order.postprocess.strategy=sync\|kafka` (§6.5) | §8-⑤ |
| **P0-1** kafka 경로 이중 차감 | 🔴 **실측 재현됨** — 5,000원 상품에 10,000원 차감, 이력 2건 → **Arm C 측정 불가** | §2 |
| **P0-1** kafka 경로 예치금 이중 차감 | Arm C 정합성 검증이 전부 실패 | §2 |
| **Arm A 분기** `order.postprocess.strategy=sync` | yml 에 값만 있고 코드 분기 없음 → **Arm A/A′ 측정 불가** | §8-⑤ |
| **P0-4** `Payment.orderCode` UNIQUE | 재시도 시 중복 결제 → 무손실 검증 오염 | §2 |
| k6 스크립트 / 시드 SQL | 부하 자체를 만들 수단 | §8-⑥⑦⑧⑨ |
| Grafana bench 대시보드 | 지표는 나오지만 패널이 payments 전용 | §8-⑬ |

---

## 6. 검증 결과 (2026-08-26 실측)

```
$ docker compose -f docker/docker-compose-bench.yml ps
grafana          Up (0.20 cpu / 500M)
kafka            Up (healthy)
kafka-exporter   Up
mysql            Up (healthy)   MySQL 8.0.46  buffer_pool=512M  max_conn=200
payments         Up (healthy)   http://localhost:8085
commerce         Up (healthy)   http://localhost:8081   기동 16.8s
```

| 검증 항목 | 결과 |
|-----------|------|
| Prometheus 타깃 4개 | **전부 UP** (commerce / payments / kafka-exporter / prometheus) |
| `http_server_requests_seconds_bucket` | commerce 148개 / payments 148개 노출 ✅ |
| `hikaricp_connections_*` | 각 16개 노출 ✅ |
| `tomcat_threads_*` | 각 3개 노출 ✅ |
| `service` 라벨 | `{application="commerce",service="commerce"}` ✅ |
| Kafka 토픽 | **31개 자동 생성, 전부 PartitionCount=3** (DLT 포함) ✅ |
| 컨슈머 그룹 | `commerce-bench`, `payments-bench` 등록 ✅ |
| Grafana | `/api/health` 200, datasource `http://prometheus:9090` 연결 ✅ |
| 유휴 메모리 | bench 스택 합계 약 3.1 GiB / 상한 6.38 GiB |

### 6.1 P0-7 수정 검증 (2026-08-26)

단건 결제:

| | 수정 전 | 수정 후 |
|---|---|---|
| 응답 | **50.71s / HTTP 500** | **87~100ms / HTTP 200** |
| DB 반영 | Payment 0건, History 0건 | Payment 3건, History 3건, 차감 15,000원 정확 |

동시 20 × 100건 (같은 유저 = 최악의 행 락 경합, HikariCP pool=10):

| 전략 | 성공 | 실패 | 소요 | 차감액 | 결제 | 이력 |
|---|---:|---:|---:|---:|---:|---:|
| **`join` (기본)** | **100** | **0** | **9,291ms** | 100,000 | 100 | **100** ✅ |
| `after-commit` | 60 | 40 | 19,496ms | 60,000 | 60 | **41** ❌ |

`after-commit` 은 요청당 커넥션을 2개 요구해 좁은 풀에서 임계점을 절반으로 당긴다.
성공한 결제 60건 중 19건은 리스너가 두 번째 커넥션을 못 받아 이력이 누락됐다
(`CannotCreateTransactionException` × 18). 그래서 기본값은 `join`.

**비관적 락은 유지됐다** — 두 전략 모두 차감액 = 결제건수 × 금액이 정확히 일치(lost update 없음).

자세한 분석은 설계 문서 §2 P0-7 참조.

---

### 6.2 P0-3 스텁 검증 (2026-08-26)

`BenchOrderUserAdapter` / `BenchOrderProductAdapter` 를 `@Primary @Profile("bench")` 로 추가해
user-service / product-service 없이 주문이 성립하게 했다.

```
POST /api/commerce/order/PROD-1  (X-CODE: BENCH-1)
  #1  HTTP=204  0.792s   ← 최초 요청(JIT·커넥션 워밍업 포함)
  #2  HTTP=204  0.166s
  #3  HTTP=204  0.095s
```

Kafka 후처리까지 완결 확인:

| 검증 | 결과 |
|---|---|
| 주문 상태 | 3건 전부 **PAID** (PENDING 잔류 0) |
| 예치금 차감 | 주문당 정확히 5,000원 |
| consumer lag | **0** (drain 완료) |
| **완결 시간** (`createdAt → PAID updatedAt`) | 122ms / 970ms / 1,131ms |

**설계 §4.1 의 핵심 지표인 "완결 시간" 이 실제로 산출된다.** Arm B 측정 준비 완료.

### 6.3 P0-1 재현 확인 (2026-08-26)

`order.payment.strategy=kafka` 로 5,000원 상품 1건 주문:

```
차감액 10,000원          ← 상품가의 2배
DepositHistoryEntity: PAYMENT_INTERNAL 2건
```

`PaymentKafkaHandler` 가 `process()` 로 한 번 차감하고,
`OrderSaga` 가 발행한 `WithdrawDeposit` 을 `DepositKafkaConsumer` 가 받아 또 차감한다.
**Arm C 측정 전에 반드시 수정해야 한다.** 자세한 내용은 설계 문서 §2 P0-1.

### 6.5 Arm A 분기 검증 + 예비 비교 (2026-08-26)

`OrderPostProcessPort` + Sync/Kafka 어댑터로 산출물 ⑤ 구현. **순차 10건, 부하 없음** (예비 확인용).

| Arm | 체감 응답 avg | 응답 직후 PAID | drain 후 PAID | **완결 시간 avg** | 잔여 장바구니 |
|---|---:|---:|---:|---:|---:|
| **A (sync)** | **37.3ms** | 10 / 10 | 10 | **16.1ms** | 0 |
| **B (kafka)** | **52.9ms** | **8 / 10** | 10 | **225.9ms** | 0 |

**확인된 것 (설계 의도대로 동작):**
- Arm A 는 응답 시점에 이미 10건 전부 PAID — 사용자가 후속 처리를 전부 기다린다
- Arm B 는 응답 직후 8건만 PAID — **비동기임이 관측된다**
- **완결 시간이 14배 차이** (16.1ms vs 225.9ms) — 설계 §4.1 이 노린 트레이드오프 정량화가 실제로 잡힌다
- 양쪽 모두 장바구니가 정상 삭제됨 (동기/비동기 경로 모두 같은 작업 수행)

**⚠️ 가설과 어긋난 것:** 설계 §5 는 baseline 에서 Arm B 가 더 빠를 것으로 예상했는데,
**Arm A 가 오히려 15.6ms 빨랐다.** 추정 원인:
1. 부하가 없어 큐잉의 이점이 발생할 여지가 없다
2. Kafka send 3건(`acks=all`, `enable.idempotence=true`)의 직렬화·버퍼 비용 > 로컬 DB UPDATE 2건
3. 소비자가 **같은 JVM·같은 0.65 CPU** 안에서 백그라운드 처리를 하며 요청 스레드와 CPU 를 경합한다

**이 수치를 결론으로 쓰면 안 된다.** 표본 10건·순차·백분위수 없음이다.
Kafka 의 이점은 정의상 **버스트 구간**에서만 나타나므로, 판단은 §7 단계 2 이후로 미룬다.
다만 **"저부하에서는 Kafka 가 손해일 수 있다"** 는 가능성이 실측으로 제기됐고,
이는 기존 380ms → 302ms 측정(저부하 평균)과 방향이 반대라 **k6 본 측정으로 반드시 검증해야 한다.**

### 6.6 측정 절차에서 발견한 함정

첫 회차는 **무효**였다. 워밍업 주문의 Kafka 커맨드가 처리 중인데 `DELETE FROM Orders` 를 실행해,
소비자가 `ORDER_NOT_FOUND` 로 **지수 백오프 5회 재시도**에 빠졌고 그 지연이 측정 구간까지 밀려왔다
(Arm B 가 PAID 0 / PENDING 10 으로 나옴).

→ **초기화 전에 반드시 `lag=0` 을 확인해야 한다.** 설계 §6.5 회차 무효 조건에 추가했다.

### 6.4 테스트 결과

`PaymentProcessIntegrationTest` (Testcontainers MySQL + Kafka) **5/5 통과**.

`process_concurrentWithoutLock_demonstratesLostUpdate` 는 락 도입 *이전에* "lost update 가 발생함" 을
증명하려고 쓴 테스트라 락이 정상 동작하자 오히려 실패했다.
전제가 뒤집혔으므로 **정합성을 검증하는 회귀 테스트로 전환**했다
(이름·단언 반전 + `int[]` → `AtomicInteger` 경쟁 조건 제거 + 이력 건수 검증 추가).

---

**토픽은 수동 생성이 필요 없다.** 각 모듈의 `NewTopic` 빈(`OrderKafkaTopicConfig`, `CartKafkaTopicConfig`, `DepositKafkaConfig`, `PaymentKafkaConfig`)이 `custom.kafka.config.topic-partitions` 값으로 DLT까지 자동 생성한다.

---

## 7. 사용법

### 7.1 기동

```bash
# 1) jar 빌드 (코드를 고칠 때마다 필요)
./gradlew :commerce:bootJar :payments:bootJar

# 2) 이미지 빌드 + 전체 기동
docker compose -f docker/docker-compose-bench.yml build
docker compose -f docker/docker-compose-bench.yml up -d

# 3) 상태 확인
docker compose -f docker/docker-compose-bench.yml ps
curl -s localhost:8081/actuator/health && curl -s localhost:8085/actuator/health
```

### 7.2 접속 정보

| 대상 | 주소 |
|------|------|
| commerce | http://localhost:8081 |
| payments | http://localhost:8085 |
| Prometheus | http://localhost:9090 (타깃: `/targets`) |
| Grafana | http://localhost:3000 (admin / admin, 익명 조회 허용) |
| MySQL | `localhost:13306` — `mysqlId / mysqlPwd / dbay_db` |
| Kafka (호스트에서) | `localhost:19092` |
| Kafka (컨테이너 내부) | `kafka:9090` ← **CLI 는 반드시 이 주소** |

### 7.3 Arm 전환

앱 재시작 없이 환경변수만 바꿔 재기동한다.

```bash
# Arm B (결제 동기 + 후처리 Kafka) — 현재 기본값
BENCH_PAYMENT_STRATEGY=feign BENCH_POSTPROCESS_STRATEGY=kafka \
  docker compose -f docker/docker-compose-bench.yml up -d commerce

# Arm C (전 구간 Kafka Saga)
BENCH_PAYMENT_STRATEGY=kafka BENCH_POSTPROCESS_STRATEGY=kafka \
  docker compose -f docker/docker-compose-bench.yml up -d commerce

# 파티션 수 실험 (설계 §3.6) — 토픽 삭제 후 재기동해야 반영됨
BENCH_PARTITIONS=6 docker compose -f docker/docker-compose-bench.yml up -d
```

### 7.4 종료 / 초기화

```bash
docker compose -f docker/docker-compose-bench.yml down          # 데이터 유지
docker compose -f docker/docker-compose-bench.yml down -v       # 볼륨까지 삭제 (완전 초기화)
```

---

## 8. 트러블슈팅

| 증상 | 원인 | 조치 |
|------|------|------|
| commerce 가 `vectorStore` 빈 생성 실패 | ES 자동 구성이 다시 활성화됨 | `application-bench.yml` 의 `spring.autoconfigure.exclude` 확인 (§3.4) |
| Prometheus 타깃 DOWN | 앱 미기동 또는 actuator 미노출 | `curl localhost:8081/actuator/prometheus \| head` |
| Grafana p95/p99 패널이 빈 그래프 | `percentiles-histogram` 누락 | `curl localhost:8081/actuator/prometheus \| grep -c http_server_requests_seconds_bucket` → 0이면 설정 확인 |
| Kafka CLI 가 `DisconnectException` | 컨테이너 안에서 `localhost:9092` 사용 | `--bootstrap-server kafka:9090` 으로 변경 |
| 컨테이너 OOM kill (exit 137) | 힙 외 영역 초과 | `docker stats` 확인 후 `JAVA_OPTS` 의 non-heap 상한 조정 |
| jar 를 고쳤는데 반영 안 됨 | 이미지 재빌드 누락 | `bootJar` → `compose build` 순서 확인 |
| 측정값이 회차마다 튐 | 무관한 컨테이너가 자원 경합 | `docker stop noteu-app noteu-mysql` 등 |

---

## 9. 다음 단계

설계 문서 §12 우선순위 기준, 다음은 **P0-3(스텁) → Arm A 분기 → P0-1(이중 차감)** 순이다.
이 셋이 끝나야 스모크 런(설계 §6.7)을 돌릴 수 있다.
