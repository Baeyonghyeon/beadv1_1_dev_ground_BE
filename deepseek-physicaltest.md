# DeepSeek Physical Test — DAU 10만 기준 성능·부하·스트레스 테스트 설계

> 마지막 수정: 2026-08-12

---

## 0. 선결 조건: 테스트 대상 연산 정의

이번 테스트의 대상은 **예치금 테이블(`deposit`)을 건드리는 4개 연산**이다. 결제차감은 `PaymentServiceImpl.process()` 내에서 `deposit.withdraw()` 호출로 구현되어 있으며, 별도 API가 아닌 결제 트랜잭션의 일부로 처리된다.

| 연산 | 구현 위치 | 방식 |
|---|---|---|
| **충전** (charge) | `DepositEventApplication.charge()` | `balance = balance + amount` |
| **출금** (withdraw) | `DepositEventApplication.withdraw()` | `balance >= amount` 검증 후 차감 |
| **환불** (refund) | `DepositEventApplication.refund()` | `balance = balance + amount` (충전과 동일) |
| **결제차감** (payment) | `PaymentServiceImpl.process()` | 출금과 동일한 `deposit.withdraw()` 도메인 로직 |

---

## 1. 연산별 빈도 가정 (DAU 10만 기준)

| 연산 | 발생 빈도 가정 | 일 발생량 | 근거 |
|---|---|---|---|
| 충전 | DAU의 5% × 1건 | 5,000건 | 미리 채워두는 행위, 낮은 빈도 |
| 출금 | DAU의 1% × 1건 | 1,000건 | 현금화는 드묾 |
| 환불 | DAU의 2% × 1건 | 2,000건 | 거래 취소/분쟁 비율 |
| 결제차감 | DAU의 5% × 1건 | 5,000건 | 구매 전환율 5% 가정 |

---

## 2. 평균 TPS 계산 (활동시간 10시간 = 36,000초)

- 충전: 5,000 / 36,000 ≈ **0.14 TPS**
- 출금: 1,000 / 36,000 ≈ **0.03 TPS**
- 환불: 2,000 / 36,000 ≈ **0.06 TPS**
- 결제차감: 5,000 / 36,000 ≈ **0.14 TPS**

**핵심 인사이트**: DAU 10만에서 정직하게 역산한 평균 TPS는 한 자릿수도 안 된다. 200~300 TPS 같은 숫자는 "자연 발생 트래픽"으로는 절대 나오지 않는다.

---

## 3. 테스트 전략: 성능·부하·스트레스 3단계

테스트는 목적이 다른 **3단계** 로 분리한다. 각 단계는 질문하는 바가 다르고, 판정 기준도 다르다.

```
성능 테스트 ──→ "예상 트래픽에서 SLA를 만족하는가?"
     │            예상 피크 TPS, 30분 장기, 합격/불합격 판정
     │
     ▼
부하 테스트 ──→ "이 시스템은 최대 몇 TPS까지 버티는가?"
     │            TPS 단계적 증가, 최대 정상 처리량 측정
     │
     ▼
스트레스 테스트 ──→ "한계를 넘으면 어떻게 무너지고, 다시 회복되는가?"
                  최대 TPS의 2~3배, 복원력·데이터 정합성 확인
```

### 3.1 3단계 비교

| 구분 | 성능 테스트 | 부하 테스트 | 스트레스 테스트 |
|------|------------|------------|----------------|
| **목적** | 예상 부하에서 SLA 검증 | 최대 처리 용량 측정 | 한계 초과 시 거동 + 복원력 확인 |
| **TPS** | 예상 피크 (피크 배율 10~20배) | 점진 증가 (20→50→100→200) | 최대 TPS의 2~3배 |
| **시간** | 30분 지속 | 단계별 5분 | Spike 5분 → 회복 관찰 |
| **성공 기준** | p99 < 300ms, 에러율 0% | p99 < 1s 유지되는 최대 TPS 기록 | Crash 없음 + 부하 해제 후 자동 복원 |
| **에러 허용** | ❌ 0% | ❌ 0% (느려지는 건 OK) | ✅ 일부 에러는 예상된 결과 |
| **이력서 문구** | "DAU 10만 피크 TPS에서 30분 무중단" | "단일 연산 최대 n TPS 처리 가능" | "n TPS spike 에서도 graceful degradation 확인" |

### 3.2 피크 배율 계산

| 연산 | 평균 TPS | 피크 배율 | **성능 테스트 목표 TPS** |
|------|---------|-----------|----------------------|
| 충전 | 0.14 | ×20 | **2.8** |
| 출금 | 0.03 | ×20 | **0.6** |
| 환불 | 0.06 | ×20 | **1.2** |
| 결제차감 | 0.14 | ×20 | **2.8** |

---

## 4. 성능 테스트 (Performance Test)

### 4.1 목적

**"실제 서비스 규모에서 이 시스템이 여유 있게 버티는가?"**

예상 피크 TPS로 30분간 지속 부하를 걸었을 때 모든 SLA 지표를 만족하는지 확인한다.

### 4.2 시나리오: 혼합 부하 (실사용 패턴)

```
환경:     모드 A (MySQL + commerce + payments), 각 앱 JVM 힙 -Xms384m -Xmx384m
준비:     10,000 userCode × 계좌당 1,000,000원 시드, 3분 warm-up
부하:     충전 3 TPS + 출금 1 TPS + 환불 2 TPS + 결제차감 3 TPS (혼합 총 ~9 TPS)
시간:     Warm-up 3분 → 본 테스트 30분
```

### 4.3 판정 기준

| 지표 | 임계값 | 확인 방법 |
|------|--------|----------|
| **p50 응답시간** | < 20ms | k6 `http_req_duration` (med) |
| **p95 응답시간** | < 100ms | k6 `http_req_duration` (p95) |
| **p99 응답시간** | < 300ms | k6 `http_req_duration` (p99) |
| **에러율** | **0%** | k6 `http_req_failed` |
| **Full GC** | **0회** | GC log (`-Xlog:gc*=info:file=/tmp/gc.log`) |
| **MySQL slow query** | **0건** | `SHOW GLOBAL STATUS LIKE 'Slow_queries'` |
| **GC 후 힙 사용률** | < 60% (증가 추세 없을 것) | Actuator `jvm.memory.used` (after GC) |
| **DB 커넥션 Active** | 일정하게 유지 (증가 추세 = leak) | Actuator `hikaricp.connections.active` |
| **컨테이너 메모리** | flat (leak 없이 안정) | `docker stats --no-stream` |

> **합격 조건: 위 9개 지표 중 하나라도 위반 시 불합격.** 30분 후에도 모든 지표가 임계값 내에 있어야 한다.

### 4.4 특히 볼 것

- 30분 지속 후에도 **GC 후 힙 사용량이 60%를 넘지 않는지** — 넘으면 slow memory leak 의심
- **DB 커넥션 Active 수가 일정한지** — 증가 추세면 커넥션 릭
- **컨테이너 메모리 증가율이 flat 한지** — 우상향이면 Native Memory leak

---

## 5. 부하 테스트 (Load Test)

### 5.1 목적

**"이 시스템은 최대 몇 TPS까지 정상 동작하는가?"**

TPS를 단계적으로 올리면서 p99 응답시간이 1초를 초과하지 않는 최대 지점을 찾는다. 병목 구간(CPU, DB 락, 커넥션 풀)도 함께 식별한다.

### 5.2 시나리오 A: 단일 연산 부하

```
환경:     모드 A, JVM 힙 -Xms512m -Xmx512m
준비:     10,000 userCode × 계좌당 1,000,000원 시드 (출금은 단계마다 재시드)
부하:     충전  →  20 →  50 → 100 → 150 → 200 TPS (각 단계 5분)
          출금  →  20 →  50 → 100 → 150 → 200 TPS
          환불  →  20 →  50 → 100 → 150 → 200 TPS
          결제차감 → 20 → 50 → 100 → 150 TPS (POST 요청이므로 보수적으로)
```

**t3-large 예상치**:
- 충전(`balance = balance + amount`): 락 없음 → 150~200 TPS (CPU 바운드)
- 출금(`WHERE balance >= amount`): row lock → 80~130 TPS
- PESSIMISTIC_WRITE 상태면 출금 TPS 더 낮게 나올 수 있음

### 5.3 시나리오 B: 혼합 부하

```
환경:     모드 A, JVM 힙 -Xms512m -Xmx512m
부하:     총 TPS 20 → 50 → 80 → 120 → 150 (각 단계 5분)
          ┌ 충전 30% + 출금 10% + 환불 20% + 결제차감 30% 비율로 k6 scenarios 구성
```

**t3-large 혼합 부하 특이점**:
- 2 vCPU를 MySQL + commerce + payments가 공유 → 앱 로직 CPU와 DB CPU가 서로 경합
- HikariCP 공유 (commerce 5 + payments 5 = 합계 10커넥션) → 한쪽 독점 시 다른 쪽 대기
- 출금 비율이 높을수록 같은 `deposit` row 경합 증가 → 충전/환불까지 지연 전파

### 5.4 판정 기준: 최대 정상 TPS

아래 중 **가장 먼저 발생하는 지점**이 그 연산의 최대 정상 TPS:

| # | 중단 조건 | 의미 |
|---|-----------|------|
| ① | p99 응답시간 > 1,000ms 가 1분 이상 지속 | 시스템 과부하 |
| ② | 에러율 > 1% | 요청 처리 실패 시작 |
| ③ | TPS를 올려도 실제 처리량이 더 이상 증가하지 않음 (포화) | 병목 도달 |
| ④ | Full GC 발생 | 힙 부족 |
| ⑤ | HikariCP Active = maximumPoolSize | 커넥션 풀 고갈 |

### 5.5 단계별 메트릭 기록

매 단계 마지막 1분 스냅샷을 기록한다:

```
| 단계 TPS | 실제 TPS | p50 | p95 | p99 | 에러율 | GC힙사용률 | GCp95 | FullGC | BufferPoolHit | CPU% | 비고 |
|----------|---------|-----|-----|-----|--------|-----------|-------|--------|---------------|------|------|
| 20       |         |     |     |     |        |           |       |        |               |      |      |
| 50       |         |     |     |     |        |           |       |        |               |      |      |
| 100      |         |     |     |     |        |           |       |        |               |      |      |
| 150      |         |     |     |     |        |           |       |        |               |      |      |
| 200      |         |     |     |     |        |           |       |        |               |      |      |
```

---

## 6. 스트레스 테스트 (Stress Test)

### 6.1 목적

**"예상치 못한 트래픽 폭주에도 시스템이 죽지 않고 버티며, 부하가 줄면 스스로 회복하는가?"**

부하 테스트에서 찾은 최대 TPS의 2~3배를 인위적으로 가해 시스템의 **한계 거동**, **장애 전파 양상**, **복원력**을 확인한다.

### 6.2 시나리오 A: Spike 테스트

```
환경:     모드 A, JVM 힙 -Xms512m -Xmx512m
전제:     부하 테스트 결과 최대 정상 TPS = N
부하:     갑자기 2N ~ 3N TPS 로 치솟는 트래픽 (각 수준별 5분)
          ┌ Phase 1: 정상 (0.3N) 5분 → 안정 상태 확인
          ├ Phase 2: Spike (2N) 5분  → 버티는가?
          ├ Phase 3: Spike (3N) 5분  → 어디서 깨지는가?
          └ Phase 4: 정상 (0.3N) 10분 → 복원되는가?

예) N = 150 TPS 라면:
  Phase 1:  50 TPS (5분)  → 안정기
  Phase 2: 300 TPS (5분)  → 2배 spike
  Phase 3: 450 TPS (5분)  → 3배 spike
  Phase 4:  50 TPS (10분) → 회복 관찰
```

### 6.3 시나리오 B: Hotspot 집중 타격

```
환경:     모드 A, JVM 힙 -Xms384m -Xmx384m
준비:     특정 userCode 1개에 잔액 10,000,000원 시드
부하:     동일 userCode에 충전+출금+환불 동시 요청
          → 동시 5건 → 10건 → 20건 → 50건 (동시성 수준 증가)
```

**확인 포인트**:
- PESSIMISTIC_WRITE → 락 대기 시간이 동시성 수준에 비례하여 증가하는가?
- `innodb_lock_wait_timeout`(기본 50초) 도달하는가?
- Atomic UPDATE 전환 시 `updated=0` 실패 건이 정상 응답되는가?

### 6.4 시나리오 C: 장시간 Soak 테스트

```
환경:     모드 A, JVM 힙 -Xms384m -Xmx384m
부하:     성능 테스트 TPS(약 9 TPS)로 2시간 이상 지속
목적:     장시간 운영 시 메모리 누적 누수, GC 압박, DB 커넥션 릭 확인
```

**확인 포인트**:
- GC 후 힙 사용률이 2시간 동안 증가 추세인가?
- DB 커넥션 Active 수가 서서히 증가하는가? → leak
- 컨테이너 RSS가 지속적으로 증가하는가? → Native Memory leak
- MySQL Slow Query가 후반부로 갈수록 증가하는가?

### 6.5 판정 기준

| 지표 | 임계값 | 확인 방법 |
|------|--------|----------|
| **프로세스 생존** | Crash 없음 (OOM Kill, 무한 응답대기 감지) | `docker ps`, k6 `http_req_failed` |
| **Graceful degradation** | 에러는 나도 전체 서비스 중단 없음 | k6 `http_req_failed` < 100% |
| **복원 시간** | 부하 해제 후 p99가 정상 수준으로 회복되는 시간 | k6 `http_req_duration` |
| **데이터 정합성** | 잔액 불일치 0건 | `SELECT SUM(balance) 검증 쿼리` |
| **락 타임아웃** | `innodb_lock_wait_timeout` 도달 없음 | `SHOW STATUS LIKE 'innodb_row_lock%'` |

**스트레스 테스트 성공 기준**:
- ① Spike 중에도 프로세스 Crash 없음
- ② 부하 해제 후 3분 이내 p99 응답시간 정상 복귀
- ③ 테스트 종료 후 데이터 정합성 검증 통과 (잔액 불일치 0건)

---

## 7. 동시성 제어 현황과 테스트 시사점

### 7.1 현재 구현: PESSIMISTIC_WRITE (비관적 락)

```java
// DepositJpaRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT d FROM DepositEntity d WHERE d.userCode = :userCode")
Optional<DepositEntity> findByUserCodeForUpdate(@Param("userCode") String userCode);
```

### 7.2 문제점: 충전/환불에도 불필요한 락

```
[현재 - 전부 비관적 락]
charge  → SELECT FOR UPDATE → Java 연산 → UPDATE (다른 트랜잭션 대기)
refund  → SELECT FOR UPDATE → Java 연산 → UPDATE (다른 트랜잭션 대기)
withdraw → SELECT FOR UPDATE → Java 검증 → UPDATE (다른 트랜잭션 대기)
payment → SELECT FOR UPDATE → Java 검증 → UPDATE (다른 트랜잭션 대기)
```

충전/환불은 `balance = balance + amount` 이므로 **읽을 필요 자체가 없다**. SELECT FOR UPDATE로 락을 거는 것은 불필요한 경합만 유발한다.

### 7.3 개선 방향: Atomic UPDATE

```sql
-- 충전/환불 (락 불필요)
UPDATE deposit SET balance = balance + ? WHERE code = ?;

-- 출금/결제 (DB가 atomic 하게 검증)
UPDATE deposit SET balance = balance - ? WHERE code = ? AND balance >= ?;
```

이 방식으로 전환 시 충전/환불은 완전 병렬 처리가 가능하고, 출금/결제도 락 대기 없이 DB 레벨에서 atomic 하게 처리된다.

### 7.4 테스트 시사점

| 테스트 단계 | PESSIMISTIC_WRITE | Atomic UPDATE |
|------------|-------------------|---------------|
| 부하 테스트 | 출금 TPS 한계가 낮음 (락 경합) | 더 높은 TPS 기대 |
| 스트레스 테스트 | Hotspot 시 락 대기열 급증 | `updated=0` 실패 건만 증가, 정상 처리 계속됨 |
| 측정 포인트 | `innodb_row_lock_waits` 메트릭 | 영향받은 row 수, 예외 발생률 |

---

## 8. 테스트 인프라 구성

### 8.1 서버 스펙

| 항목 | 스펙 |
|---|---|
| 인스턴스 | **t3-large** (2 vCPU, 8GB RAM) |
| OS | Amazon Linux 2023 |
| Java | OpenJDK 21 |
| 컨테이너 런타임 | Docker (docker-compose) |

### 8.2 운영 모드 — 8GB 메모리 예산 내 선택

전체 서비스를 8GB에서 전부 띄우는 것은 불가능하므로 시나리오에 따라 구성을 선택한다.

#### 모드 A: 테스트 기본 구성 (예치금 API + 결제)

| 서비스 | 타입 | 힙 메모리 | 비고 |
|---|---|---|---|
| MySQL | Docker | 컨테이너 512MB | commerce + payments DB |
| commerce | Spring Boot | `-Xms256m -Xmx512m` | Feign → payments 직접 호출 |
| payments | Spring Boot | `-Xms256m -Xmx512m` | 테스트 대상 |
| **합계** | | **~1.5GB** (OS 포함 ~2.3GB) | |

- Kafka, MariaDB, Redis, ES, Logstash, Kibana: **전부 off**
- Gateway, product, user 모듈: **off**
- Eureka: Feign direct URL 시 off 가능

#### 모드 B: 전체 서비스 구동 (프로덕션 유사)

> ⚠️ 8GB에서 전부 띄우려면 인프라 컨테이너 다이어트 + 앱 힙 축소 필수.

| 서비스 | 타입 | 힙 메모리 |
|---|---|---|
| MySQL | Docker | 384MB |
| MariaDB | Docker | 256MB |
| Kafka | Docker | 384MB |
| Redis | Docker | 64MB |
| Elasticsearch | Docker | `-Xms256m -Xmx256m` |
| Logstash | Docker | `-Xms128m -Xmx128m` |
| Kibana | Docker | 256MB |
| commerce | Spring Boot | `-Xms192m -Xmx320m` |
| payments | Spring Boot | `-Xms192m -Xmx320m` |
| product | Spring Boot | `-Xms128m -Xmx256m` |
| user | Spring Boot | `-Xms128m -Xmx256m` |
| eureka-server | Spring Boot | `-Xms128m -Xmx192m` |
| gateway | Spring Boot | `-Xms128m -Xmx192m` |
| **합계** | | **~3.4GB** |

### 8.3 JVM 공통 옵션

```
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/tmp/heapdumps/
-Xlog:gc*=info:file=/tmp/gc.log:time,level,tags:filecount=5,filesize=10M
```

> 성능 테스트 시에는 `-Xms` = `-Xmx`로 고정 (cold start 편차 제거).

### 8.4 MySQL 최적화 (8GB 환경)

```sql
SET GLOBAL innodb_buffer_pool_size = 268435456;   -- 256MB
SET GLOBAL innodb_log_file_size = 134217728;       -- 128MB
SET GLOBAL max_connections = 100;
SET GLOBAL thread_cache_size = 8;
SET GLOBAL table_open_cache = 512;
```

---

## 9. 측정 지표 및 임계값

### 9.1 애플리케이션 메트릭 (k6 / Micrometer)

| 지표 | 수집 방법 | 성능 테스트 | 부하/스트레스 |
|------|----------|-----------|-------------|
| **p50 응답시간** | k6 `http_req_duration` (med) | < 20ms | < 50ms |
| **p95 응답시간** | k6 `http_req_duration` (p95) | < 100ms | < 500ms |
| **p99 응답시간** | k6 `http_req_duration` (p99) | < 300ms | < 1,000ms (부하 테스트는 이게 한계선) |
| **에러율** | k6 `http_req_failed` | 0% | 성능 0% / 부하 0% / 스트레스 기록 |
| **트랜잭션 시간** | Micrometer `@Timed` | DB+로직 < 50ms | < 200ms |

### 9.2 JVM 메트릭 (Actuator)

| 지표 | 메트릭 키 | 성능 테스트 | 부하/스트레스 |
|------|----------|-----------|-------------|
| **GC 후 힙 사용량** | `jvm.memory.used` (after GC) | < 60% | < 85% |
| **GC pause p95** | `jvm.gc.pause` (p95) | < 30ms | < 100ms |
| **Full GC 횟수** | `jvm.gc.pause` (major count) | 0회 | 0회 (발생 시 중단) |
| **Thread 수 (live)** | `jvm.threads.live` | < 100 | < 200 |

### 9.3 DB 메트릭 (MySQL)

| 지표 | 성능 테스트 | 부하/스트레스 |
|------|-----------|-------------|
| **Buffer Pool Hit Rate** | > 99.9% | > 99.0% |
| **Row lock waits** | 0건 | < 10건/분 |
| **HikariCP Active** | < 5 | < 10 (최대 도달 시 중단) |
| **Slow query** | 0건 | 분당 5건 이하 |

### 9.4 스트레스 테스트 전용: 복원력 지표

| 지표 | 측정 방법 | 목표 |
|------|----------|------|
| **복원 시간 (RTO)** | Spike 해제 후 p99가 정상 수준으로 돌아오는 시간 | < 3분 |
| **데이터 정합성** | `SUM(balance)` 사전/사후 비교 | 불일치 0건 |
| **락 타임아웃** | `SHOW STATUS LIKE 'innodb_row_lock%'` | `innodb_lock_wait_timeout` 도달 없음 |

---

## 10. k6 테스트 스크립트

### 10.1 성능/부하 테스트용 (혼합 부하)

```javascript
// k6 mixed-workload.js
import http from 'k6/http';
import { sleep } from 'k6';

const BASE_ORDER = 'http://localhost:8081';
const BASE_DEPOSIT = 'http://localhost:8085';
const USER_POOL = 10000;

export const options = {
    scenarios: {
        charge: {
            executor: 'constant-arrival-rate',
            rate: 3,  // 성능 테스트: 3 TPS, 부하 테스트: 단계별 조정
            timeUnit: '1s',
            duration: '30m',
            preAllocatedVUs: 10,
            exec: 'charge',
        },
        withdraw: {
            executor: 'constant-arrival-rate',
            rate: 1,
            timeUnit: '1s',
            duration: '30m',
            preAllocatedVUs: 10,
            exec: 'withdraw',
        },
        refund: {
            executor: 'constant-arrival-rate',
            rate: 2,
            timeUnit: '1s',
            duration: '30m',
            preAllocatedVUs: 10,
            exec: 'refund',
        },
        payment: {
            executor: 'constant-arrival-rate',
            rate: 3,
            timeUnit: '1s',
            duration: '30m',
            preAllocatedVUs: 10,
            exec: 'payment',
        },
    },
};

function randomUser() {
    return `USER-${Math.floor(Math.random() * USER_POOL)}`;
}

export function charge() {
    const userCode = randomUser();
    http.post(`${BASE_DEPOSIT}/api/deposits/charge`,
        JSON.stringify({ amount: 10000 }),
        { headers: { 'X-CODE': userCode, 'Content-Type': 'application/json' } });
}

export function withdraw() {
    const userCode = randomUser();
    http.post(`${BASE_DEPOSIT}/api/deposits/withdraw`,
        JSON.stringify({ amount: 5000 }),
        { headers: { 'X-CODE': userCode, 'Content-Type': 'application/json' } });
}

export function refund() {
    const userCode = randomUser();
    http.post(`${BASE_DEPOSIT}/api/deposits/refund`,
        JSON.stringify({ amount: 5000 }),
        { headers: { 'X-CODE': userCode, 'Content-Type': 'application/json' } });
}

export function payment() {
    const userCode = randomUser();
    http.post(`${BASE_ORDER}/api/orders`,
        JSON.stringify({ productCode: 'prod-1' }),
        { headers: { 'X-CODE': userCode, 'Content-Type': 'application/json' } });
}
```

### 10.2 스트레스 Spike 테스트용

```javascript
// k6 stress-spike.js
import http from 'k6/http';
import { sleep } from 'k6';

const BASE_DEPOSIT = 'http://localhost:8085';
const USER_POOL = 10000;
const MAX_TPS = 150;  // 부하 테스트에서 찾은 최대 TPS

export const options = {
    scenarios: {
        spike_test: {
            executor: 'ramping-arrival-rate',
            startRate: Math.floor(MAX_TPS * 0.3),  // Phase 1: 정상
            timeUnit: '1s',
            preAllocatedVUs: 50,
            stages: [
                { target: Math.floor(MAX_TPS * 0.3), duration: '5m' },   // 안정기
                { target: MAX_TPS * 2,              duration: '30s' },   // 급증
                { target: MAX_TPS * 2,              duration: '4m30s' }, // 2배 유지
                { target: MAX_TPS * 3,              duration: '30s' },   // 추가 급증
                { target: MAX_TPS * 3,              duration: '4m30s' }, // 3배 유지
                { target: Math.floor(MAX_TPS * 0.3), duration: '1m' },  // 급감
                { target: Math.floor(MAX_TPS * 0.3), duration: '9m' },  // 회복 관찰
            ],
        },
    },
};

function randomUser() {
    return `USER-${Math.floor(Math.random() * USER_POOL)}`;
}

export default function () {
    const op = Math.random();
    const userCode = randomUser();
    if (op < 0.5) {
        // 50% 충전
        http.post(`${BASE_DEPOSIT}/api/deposits/charge`,
            JSON.stringify({ amount: 10000 }),
            { headers: { 'X-CODE': userCode, 'Content-Type': 'application/json' } });
    } else if (op < 0.8) {
        // 30% 환불
        http.post(`${BASE_DEPOSIT}/api/deposits/refund`,
            JSON.stringify({ amount: 5000 }),
            { headers: { 'X-CODE': userCode, 'Content-Type': 'application/json' } });
    } else {
        // 20% 출금
        http.post(`${BASE_DEPOSIT}/api/deposits/withdraw`,
            JSON.stringify({ amount: 5000 }),
            { headers: { 'X-CODE': userCode, 'Content-Type': 'application/json' } });
    }
}
```

---

## 11. 시드 데이터 및 사전 점검

### 11.1 시드 데이터

| 데이터 | 수량 | 생성 방법 |
|--------|------|-----------|
| Deposit 계좌 (userCode별) | 10,000건 | `PaymentsSeedDataGenerator` |
| 잔액 충전 | 계좌당 1,000,000원 | `UPDATE deposit SET balance = 1000000` |

> **중요**: 출금/결제 테스트로 잔액이 소진되므로 **부하 테스트 단계마다, 그리고 스트레스 테스트 전에 잔액을 재충전**해야 한다.

### 11.2 사전 점검 (Cold Start → Warm Start)

| 점검 항목 | 확인 방법 | 기준 |
|----------|----------|------|
| **JVM Warm-up** | 초당 1건씩 500건 예열 요청 | p50이 안정화될 때까지 (~3분) |
| **MySQL Buffer Pool** | `SHOW STATUS LIKE 'Innodb_buffer_pool_reads'` | Reads 증가율 0 수렴 |
| **HikariCP Pool** | `/actuator/metrics/hikaricp.connections.active` | Pool이 minimum idle까지 채워짐 |
| **Docker 메모리** | `docker stats --no-stream` | 모든 컨테이너 할당량 내 안정화 |
| **데이터 정합성 (스트레스 전)** | `SELECT SUM(balance) FROM deposit` | 사전 값 기록 → 사후 비교 |

---

## 12. 전체 실행 순서

```
단계 0: 환경 셋업
  ├── Docker 컨테이너 구동 (모드 A)
  ├── commerce + payments 앱 구동 (JVM 옵션 적용)
  ├── 시드 데이터 생성 (10,000 userCode)
  └── 11.2 사전 점검 통과 (Warm-up 확인)

단계 1: 성능 테스트 (4장)
  ├── k6 혼합 부하 9 TPS, 30분
  ├── 4.3 판정 기준 전 항목 확인
  └── 결과: ✅ 합격 / ❌ 불합격

단계 2: 부하 테스트 - 단일 연산 (5.2)
  ├── 충전: 20→50→100→150→200 TPS (단계별 5분)
  ├── 출금: 20→50→100→150→200 TPS (단계마다 잔액 재시드)
  ├── 환불: 20→50→100→150→200 TPS
  ├── 결제차감: 20→50→100→150 TPS
  └── 연산별 최대 정상 TPS 기록

단계 3: 부하 테스트 - 혼합 부하 (5.3)
  ├── 총 20→50→80→120→150 TPS (단계별 5분)
  └── 단일 vs 혼합 한계 차이 분석

단계 4: 스트레스 테스트 - Spike (6.2)
  ├── Phase 1~4 실행 (Spike → 회복)
  ├── 복원 시간 측정
  └── 데이터 정합성 검증

단계 5: 스트레스 테스트 - Hotspot (6.3)
  ├── 동일 userCode 동시 5→10→20→50건
  └── 락 대기 시간, timeout 발생 여부

단계 6: 스트레스 테스트 - Soak (6.4, 선택)
  └── 9 TPS × 2시간 → 장기 안정성 확인
```

---

## 13. 테스트 결과 정리 템플릿

```markdown
## 환경 정보
- 인스턴스:    t3-large (2 vCPU, 8GB RAM)
- Java:        OpenJDK 21
- MySQL:       Docker mysql:8.0 (컨테이너 [512]MB, innodb_buffer_pool_size=[256]MB)
- 운영 모드:   [모드 A / 모드 B]
- commerce:    -Xms[384]m -Xmx[384]m -XX:+UseG1GC
- payments:    -Xms[384]m -Xmx[384]m -XX:+UseG1GC
- 시드 데이터: [10,000] userCode, 계좌당 [1,000,000]원

## 사전 점검
- JVM Warm-up: [3분 / p50 안정화 확인]
- MySQL Buffer Pool Hit Rate: [99.9%]
- Docker memory 안정화: [flat 확인]

───────────────────────────────────────

## 1. 성능 테스트 결과
목표: 혼합 9 TPS, 30분

| 연산 | 목표 TPS | 실제 TPS | p50 | p95 | p99 | 에러율 |
|------|---------|---------|-----|-----|-----|--------|
| 충전 | 3 |         |     |     |     |        |
| 출금 | 1 |         |     |     |     |        |
| 환불 | 2 |         |     |     |     |        |
| 결제차감 | 3 |         |     |     |     |        |

30분 장기 안정성:
- Full GC 횟수: [0]
- GC 후 힙 사용률: [시작 45% → 종료 48%] (증가폭 5% 미만 = leak 없음)
- MySQL slow query: [0건]
- 컨테이너 메모리: [시작 → 종료] (flat)
- HikariCP Active: [평균 3, 최대 5]

판정: [✅ 합격 / ❌ 불합격] — [사유]

───────────────────────────────────────

## 2. 부하 테스트 결과

### 단일 연산 (각 단계 5분)

| 연산 | 20 TPS | 50 TPS | 100 TPS | 150 TPS | 200 TPS | 최대 정상 TPS | 병목 원인 |
|------|--------|--------|---------|---------|---------|-------------|----------|
| 충전 |        |        |         |         |         |             |          |
| 출금 |        |        |         |         |         |             |          |
| 환불 |        |        |         |         |         |             |          |
| 결제차감 |    |        |         |         |   -     |             |          |

- ✓ = 통과 (p99 < 1,000ms, 에러율 0%)
- ✗ = 한계 도달 → 직전 단계가 최대 정상 TPS

### 혼합 부하 (충전:출금:환불:결제 = 3:1:2:3)

| 총 TPS | 충전 | 출금 | 환불 | 결제 | p99 | CPU% | GC힙% | 판정 |
|--------|------|------|------|------|-----|------|-------|------|
| 20     |      |      |      |      |     |      |       |      |
| 50     |      |      |      |      |     |      |       |      |
| 80     |      |      |      |      |     |      |       |      |
| 120    |      |      |      |      |     |      |       |      |
| 150    |      |      |      |      |     |      |       |      |

단일 vs 혼합 비교:
- 충전 단일 [ ] TPS → 혼합 시 [ ] TPS (차이: [ ]%)
- 출금 단일 [ ] TPS → 혼합 시 [ ] TPS (차이: [ ]%)

───────────────────────────────────────

## 3. 스트레스 테스트 결과

### Spike 테스트 (최대 정상 TPS = N)

| Phase | 목표 TPS | 실제 TPS | p99 | 에러율 | GC Full | 비고 |
|-------|---------|---------|-----|--------|---------|------|
| Phase 1 (안정기) | 0.3N |         |     |        |         |      |
| Phase 2 (2N)     | 2N   |         |     |        |         |      |
| Phase 3 (3N)     | 3N   |         |     |        |         |      |
| Phase 4 (회복)   | 0.3N |         |     |        |         |      |

복원력:
- 복원 시간: [ ]초 (Phase 4 진입 후 p99 정상화까지)
- Crash 발생: [없음 / 있음]
- 복원 후 데이터 정합성: [✅ 통과 / ❌ 불일치 ( ]건)]

### Hotspot 경합

| 동시 요청 수 | 충전 p99 | 출금 p99 | 환불 p99 | 락 대기 (평균) | timeout |
|-------------|---------|---------|---------|---------------|---------|
| 5건         |         |         |         |               |         |
| 10건        |         |         |         |               |         |
| 20건        |         |         |         |               |         |
| 50건        |         |         |         |               |         |

- 락 전략: [PESSIMISTIC_WRITE / Atomic UPDATE]

### Soak 테스트 (선택)

| 경과 | p99 | GC 후 힙% | HikariCP Active | 컨테이너 RSS | Slow Query |
|------|-----|----------|-----------------|-------------|------------|
| 0분  |     |          |                 |             |            |
| 30분 |     |          |                 |             |            |
| 60분 |     |          |                 |             |            |
| 90분 |     |          |                 |             |            |
| 120분|     |          |                 |             |            |

───────────────────────────────────────

## 종합 판정

| 단계 | 결과 | 핵심 수치 |
|------|------|----------|
| 성능 테스트 | [✅/❌] | p99 [ ]ms, Full GC [ ]회 |
| 부하 - 단일 | 충전 [ ] TPS, 출금 [ ] TPS | 병목: [ ] |
| 부하 - 혼합 | [ ] TPS | 병목: [ ] |
| 스트레스 - Spike | [PASS/FAIL] | 복원 [ ]초 |
| 스트레스 - Hotspot | [PASS/FAIL] | 동시 [ ]건 p99 [ ]ms |
| 스트레스 - Soak | [PASS/FAIL] | 2시간 leak [있음/없음] |

## 발견된 병목 및 개선 TODO

1. [병목]: [관측된 현상과 수치]
   → 개선: [조치 방안]
2. ...
```

---

## 14. 현재 코드베이스 기준 확인 사항

### 14.1 정산 → 실시간 vs 배치

**배치**다. `SettlementJobScheduler`가 `@Scheduled(cron = "0 0 2 2 * *")`로 매월 2일 새벽 2시에 트리거한다. 성능 테스트 시에는 `POST /api/settlements/trigger-batch`로 수동 실행 가능하다.

### 14.2 환불 → 정산 롤백 로직

- **OrderSaga (Choreography)**: `DepositWithdrawFailed` 이벤트 발생 시 `DepositRefundCommand`를 발행하여 결제 취소에 따른 환불을 처리한다. 이미 판매자에게 정산이 나간 경우의 롤백은 **구현되지 않음**.
- **SettlementSagaOrchestrator**: `SettlementSagaOrchestrator.java:132`에 보상 트랜잭션(COMPENSATING)이 TODO로 남아 있다.

**테스트 시사점**: 환불-정산 롤백이 미구현 상태다. 스트레스 테스트에서 환불 연타로 인한 정산 무결성 검증은 현재 테스트 범위에서 제외한다.

### 14.3 혼합 부하 테스트

`deposit` 테이블 동시 경합 측정이 핵심이다. PESSIMISTIC_WRITE → Atomic UPDATE 전환 전/후로 부하 테스트 결과를 비교하면 유의미한 개선 수치를 얻을 수 있다.

---

## 부록: 관련 문서

| 문서 | 내용 |
|------|------|
| [`deepseek-batch.md`](./deepseek-batch.md) | 정산 배치 성능 테스트 상세 |
| [`deepseek-deposit.md`](./deepseek-deposit.md) | 예치금 동시성 제어 분석 (PESSIMISTIC_WRITE vs Atomic UPDATE) |
| [`deepseek-saga-information.md`](./deepseek-saga-information.md) | Saga 패턴 구조 (Orchestration vs Choreography) |
| [`deepseek-test.md`](./deepseek-test.md) | 변경 파일 목록 및 단위/통합 테스트 가이드 |
| [`deepseek-kafka-idempotency.md`](./deepseek-kafka-idempotency.md) | Kafka 멱등성 설계 |
