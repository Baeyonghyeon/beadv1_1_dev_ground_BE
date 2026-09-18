# Kafka 측정 TODO — 추후 진행

> 작성일: 2026-08-27
> 관련: [`claude-kafka-test-결과.md`](./claude-kafka-test-결과.md) · [`claude-bench-실행가이드.md`](./claude-bench-실행가이드.md)
>
> **결론이 아니라 할 일 메모.** 측정 후 결과 문서로 옮길 것.

---

## 왜 이 메모가 필요한가

Kafka 를 쓰는 이유는 **대용량 실시간 데이터를 손실 없이 빠르게 처리**하기 위함이다.
그런데 지금까지 한 측정은 **"HTTP API 의 동기 vs 이벤트 아키텍처 A/B"** 였다.
Kafka 를 **쓴 결과**(응답 시간·처리량)는 쟀지만, Kafka **자체의 가치**는 안 쟀다.

현재 이력서 항목은 이 질문들에 약하다.

- "Kafka 를 쓰셨다는데 컨슈머 lag 은 어떻게 관리하셨나요?"
- "파티션은 몇 개로 잡으셨고, 왜 그 숫자인가요?"
- "메시지 유실은 어떻게 보장하셨나요?"

---

## 이미 가진 것

| 항목 | 값 | 비고 |
|---|---|---|
| **E2E 지연** (발행 → 소비 완료) | 167ms → **6,977ms** | `Orders.createdAt → PAID updatedAt`. 사실상 lag 의 시간 표현 |
| 파티션 수 | 3 (DLT 포함 31개 토픽) | `custom.kafka.config.topic-partitions` |
| DLT | 전 토픽 구성됨 | `KafkaErrorHandler` — 5회 재시도 후 DLT |
| 프로듀서 설정 | `acks=all`, `enable.idempotence=true` | |
| lag 수집 경로 | ✅ **이미 동작 중** | `kafka-exporter` → Prometheus. `kafka_consumergroup_lag` 확인함 |

---

## 1. 대용량 — 처리량과 확장성

### 1-1. 파티션 확장성 (1 vs 3 vs 6) ★ 우선순위 높음

**질문:** 파티션을 늘리면 소비 병렬성이 실제로 늘어나는가?

**방법:** `BENCH_PARTITIONS` 환경변수가 이미 있다. 단 **토픽을 지우고 재생성해야 반영**된다.

```bash
docker compose -f docker/docker-compose-bench.yml down -v      # 토픽 삭제
BENCH_PARTITIONS=1 docker compose -f docker/docker-compose-bench.yml up -d
./bench-scripts/run-burst.sh 600 60
# → 파티션 3, 6 반복
```

**볼 것:** 최대 lag, **drain 시간**, 완결 시간 p95.
응답 시간은 거의 안 변해야 정상(앞단은 produce 만 함) → **drain 시간이 파티션 수에 반비례**하면 성공.

**주의:** `KafkaErrorHandler` 가 `factory.setConcurrency(topicPartitions)` 를 쓰므로
파티션 수 = 컨슈머 스레드 수다. 둘이 같이 움직인다는 점을 결과에 명시할 것.

**면접 답변 재료:** "키를 `orderCode` 로 고정해 같은 주문의 이벤트는 같은 파티션에 가도록 순서를 보장하면서
소비 병렬성을 확보했고, 파티션 N개에서 drain 시간이 X초 → Y초로 줄었다."

### 1-2. 브로커 자체 처리량 (우선순위 낮음)

`kafka-producer-perf-test.sh` 로 브로커 한계 측정.
애플리케이션 관점에선 병목이 DB(커넥션 풀 10개)라 브로커가 한계에 닿을 일이 없다.
**"병목이 Kafka 가 아니라 DB 였다"** 를 보여주는 용도로는 가치가 있다.

```bash
docker exec bench-kafka /opt/kafka/bin/kafka-producer-perf-test.sh \
  --topic perf-test --num-records 500000 --record-size 200 --throughput -1 \
  --producer-props bootstrap.servers=kafka:9090 acks=all
```

---

## 2. 실시간 — lag 과 지연

### 2-1. 컨슈머 lag 시계열 ★ 가장 우선

**질문:** 부하가 몰릴 때 lag 이 얼마나 쌓이고, 얼마 만에 빠지는가?

**이게 Kafka 의 "버퍼링" 을 보여주는 직접 증거다.** 지금은 `bench_wait_lag` 로
"0 될 때까지 기다리기" 만 하고 **그래프를 안 남겼다.**

**방법:** 수집 경로는 이미 있다. Prometheus 쿼리만 하면 된다.

```promql
# 컨슈머 그룹별 총 lag
sum by (consumergroup) (kafka_consumergroup_lag{consumergroup=~"commerce-bench|payments-bench"})

# 토픽별
sum by (topic) (kafka_consumergroup_lag)
```

**남길 것:**
- 부하 시작 → lag 상승 → k6/xargs 종료 → **drain 곡선** 그래프 (Grafana 스크린샷)
- **최대 lag 건수**, **drain 완료까지 걸린 시간**
- 같은 그래프에 `hikaricp_connections_pending` 을 겹치면 "DB 가 밀린 만큼 큐가 흡수했다" 가 보인다

### 2-2. E2E 지연 분포

이미 avg 만 있다(6,977ms). **p50/p95/p99** 를 SQL 로 뽑을 것 — `bench-scripts/verify.sql` 에 p95 쿼리 있음.

---

## 3. 손실 없이 — 내구성과 정합성

### 3-1. 컨슈머 중단 → 재시작 후 유실 0 ★ 우선순위 높음

**질문:** 처리 도중 컨슈머가 죽으면 메시지는 어떻게 되는가?

```bash
# 부하 중간에
docker stop bench-commerce      # 컨슈머 정지 (lag 쌓임 확인)
sleep 30
docker start bench-commerce     # 오프셋부터 재개
# → drain 후 건수 대조
```

**검증:** k6/xargs 가 2xx 받은 건수 `S` 에 대해
`S == COUNT(Orders WHERE status='PAID') == COUNT(Payment WHERE status='PAYMENT_COMPLETED')`

**이게 "손실 없이" 의 직접 증거다.** 인메모리 큐(`@Async`)와의 결정적 차이이기도 하다.

### 3-2. DLT 적재 확인

현재 모든 회차에서 DLT 0 을 확인만 하고 지나갔다.
**의도적으로 실패를 만들어** DLT 로 가는지, 몇 번 재시도 후 가는지 확인할 것.
(`KafkaErrorHandler`: 지수 백오프 5회 → DLT)

### 3-3. 멱등성 — 미구현 상태

- `Payment.orderCode` UNIQUE 제약 **아직 미적용** (설계 P0-4)
- consumer 멱등 테이블 없음
- **재시도 시 중복 결제 가능** → 무손실 검증이 오염될 수 있음

→ 3-1 측정 전에 최소한 UNIQUE 제약은 넣어야 한다.

---

## 보류 중인 것

### k6 dropped_iterations 문제

`maxVUs ≥ 도착률 × 클라이언트 타임아웃` 을 만족해야 dropped=0 이 된다.
현재 여유 메모리 3.2GB / VU 당 약 2MB → **최대 약 1,500 VU**.

| 타임아웃 | 측정 가능 최대 도착률 |
|---:|---:|
| 2s | 750 TPS |
| 4s | 375 TPS |
| 30s | 50 TPS |

600 TPS 회차가 무효였던 이유: 타임아웃 30초 + maxVUs 800 = **필요량의 4.4%**.
두 Arm 이 받은 요청 수가 달랐다(27,762 vs 35,293) → 같은 부하를 준 게 아님.

**재측정 시:** `BURST=300, REQ_TIMEOUT=4s, MAX_VUS=1200` 으로 dropped=0 확인 후 수치 확정.
다만 **위 1~3 항목이 이력서 가치가 더 크므로 우선순위는 뒤.**

### 비관적 락 소스 확인

[`claude-lock-check-todo.md`](./claude-lock-check-todo.md) 6개 항목 — 별도 진행.

---

## 우선순위

| 순위 | 항목 | 이유 |
|---|---|---|
| 1 | **2-1 lag 시계열** | Kafka 버퍼링의 직접 증거. 수집 경로 이미 있어서 부하만 걸면 됨 |
| 2 | **3-1 컨슈머 중단 → 유실 0** | "손실 없이" 의 직접 증거. `@Async` 와의 차별점 |
| 3 | **1-1 파티션 확장성** | "왜 파티션 N개인가" 답변 재료 |
| 4 | 3-3 멱등성 (UNIQUE) | 2·3번 검증이 오염되지 않도록 |
| 5 | 2-2 E2E 지연 분포 | 이미 avg 는 있음, 백분위만 추가 |
| 6 | k6 dropped 해결 | 거절률 확보용 |
| 7 | 1-2 브로커 처리량 | "병목은 Kafka 가 아니었다" 보조 근거 |
