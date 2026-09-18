# Kafka 도입 증명 — 동기 vs Kafka A/B 부하 테스트 설계

> 마지막 수정: 2026-08-20
>
> 목적: **"왜 동기 HTTP가 아니라 Kafka인가"** 를 수치로 증명한다.
> `deepseek-physicaltest.md` (SLA 검증 3단계) 와 별개의 실험 문서.

---

## 0. 배경

기존 physicaltest 설계는 "예상 부하에서 SLA를 만족하는가" 가 목적이었다.
이 문서는 **Kafka의 존재 이유 자체를 부하 테스트로 증명** 하는 것이 목적이다.

### 증명할 4가지 포인트

| # | 포인트 | 부하 테스트에서 보여줄 모습 |
|---|--------|---------------------------|
| 1 | **큐잉/버퍼링** | consumer(DB 반영)가 밀려도 producer(API 쪽)는 계속 성공. 동기였다면 DB 락/커넥션 고갈로 실패했을 구간에서 **에러 0** |
| 2 | **무손실** | 버스트 종료 후 lag가 0으로 drain → DB 반영 건수 = produce 건수 일치 |
| 3 | **비동기 응답 안정성** | DB가 아무리 바빠도 produce latency는 평탄 (앞단이 뒤단에 노출되지 않음) |
| 4 | (선택) **내구성** | 버스트 중 consumer 중지/재시작 → offset부터 이어서 처리, 유실 0 |

---

## 1. 실험 설계: 변수 통제

**같은 연산**에 대해 "수신 경로" 하나만 바꾼 두 버전을 동일 조건에서 비교한다.

| | 버전1 (Kafka) | 버전2 (동기) |
|---|---|---|
| 코드 위치 | `main` 브랜치 (현재) | `deposit-sync-v2` 브랜치 (신규) |
| 연산 | charge / withdraw / refund | **동일** |
| DB·락 전략 | PESSIMISTIC_WRITE | **동일** |
| 시드·JVM·부하 프로파일 | 동일 | **동일** |
| 수신 경로 | k6 → Kafka `deposits-commands` → consumer → DB | k6 → `POST /api/deposits/*` → 서비스 직접 호출 → DB |
| 발신 이벤트 | 성공/실패 이벤트 produce | **없음 (순수 동기)** — "Kafka 완전 부재" 대조군 |

> 버전2에서 발신 이벤트까지 produce하면 "Kafka가 없는 세상" 재현이 불가능하므로,
> 버전2는 **Kafka 미사용 순수 동기** 로 정의한다. (이게 대조군의 순수성을 보장한다)

### 왜 `/api/payments/process` 를 대조군으로 쓰지 않는가

- 결제 `/process` 는 "다른 연산(결제)"이라 변수가 섞임 → 반박 여지 발생
- 버전2 방식은 **같은 연산에서 Kafka 유무의 차이만** 남김

---

## 2. 공통 조건 (양 버전 동일)

| 항목 | 값 |
|---|---|
| 유저 풀 | USER-0 ~ USER-9999 (10,000명) |
| 초기 잔액 | 계좌당 1,000,000원 |
| JVM | `-Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200` |
| DB | MySQL 8.0 (Docker), `innodb_buffer_pool_size=256M` |
| 연산 혼합 비율 | 충전 3 : 출금 1 : 환불 2 (충전 50% / 출금 16.7% / 환불 33.3%) |
| 금액 | 충전 +10,000원 / 출금 -5,000원 / 환불 +5,000원 |
| 부하 프로파일 | 아래 버스트 스케줄 동일 적용 |
| 모니터링 | Prometheus + Grafana (JVM/GC/HikariCP), MySQL slow query·row lock |

### 버스트 스케줄

```
baseline : 30 TPS × 10분        (안정기)
ramp-up  : 30 → R TPS × 2분
burst    : R TPS × 30분         (R = 예비 측정으로 확정, 후보 300~500)
k6 종료  → drain 관찰 (버전1: lag→0, 버전2: 부하 해제 후 회복)
```

> R 결정 규칙: 버전1 consumer(단일 파티션·단일 스레드)가 lag 0을 유지하는 최대 rate의
> **5~10배** 로 설정한다. "동기였다면 죽는 구간"을 만드는 것이 목적이므로
> consumer 한계를 크게 초과해야 한다.

### 잔액 수지 (R=500, 30분 버스트 기준)

- 총 요청 ≈ 500×1800 + 30×600 + ramp ≈ **95만건**
- 출금 ≈ 16.7% × 95만 = 15.8만건 × 5,000원 = **7.9억원**
- 전체 풀: 10,000명 × 100만원 = **100억원** → 충분 ✅
- 유저당 출금 평균 ~16건 = 8만원 < 잔액 100만원 ✅
- 충전+환불이 유입이라 순 유출은 출금뿐 → 테스트 중 잔액 고갈 없음 ✅

---

## 3. 측정 지표

| 구분 | 버전1 (Kafka) | 버전2 (동기) |
|---|---|---|
| 처리 지표 | produce TPS / produce 에러 / produce latency | `http_req_duration` p50/p95/p99 / 에러율 / 실제 처리 TPS |
| 큐잉 | **consumer lag** (5초 간격 CSV) | 해당 없음 (큐 없음) |
| 복구 | **drain 시간** (k6 종료 → lag 0) | 부하 해제 후 p99 정상화 시간 |
| DB | Slow query, `innodb_row_lock_waits`, HikariCP active | **동일** |
| 정합성 | `deposit_history_entity` 건수 = produce 건수, `SUM(balance)` 사전/사후 | **동일** |

---

## 4. 핵심 비교 포인트 (기대 결과)

| 지표 | 기대: v1 Kafka | 기대: v2 동기 |
|---|---|---|
| 버스트 중 에러 | **0%** (큐가 흡수) | 락 대기 폭발 → 타임아웃/5xx |
| 버스트 중 지연 | produce latency 평탄 (ms 단위) | p99 급증 (락 대기 직격) |
| 과부하 흡수 | lag로 버퍼링 | 불가 (요청이 DB에 직행) |
| 회복 | lag drain ~분 (큐에 쌓인 건 유실 없이 처리) | 부하 해제 후 즉시 |
| 무손실 | produced = DB 반영 건수 | 해당 없음 |

> 이 테이블이 채워지면 "같은 부하에서 동기는 무너지고, Kafka는 흡수하고 회복한다"는
> 증명이 완성된다.

---

## 5. 구현물

| 파일 | 용도 |
|---|---|
| `k6-scripts/burst-kafka-v1.js` | 버전1 버스트 테스트 (produce, ramping-arrival-rate) |
| `k6-scripts/burst-http-v2.js` | 버전2 버스트 테스트 (HTTP, 동일 프로파일) |
| `k6-scripts/lag-monitor.sh` | consumer lag 5초 간격 CSV 기록 |
| `k6-scripts/reset-balance.sql` | 각 버전 테스트 전 잔액 리셋 |
| `deposit-sync-v2` 브랜치 | `DepositController` POST 3개 추가 (charge/withdraw/refund 동기) |

---

## 6. 실행 순서

```
단계 0: 환경 셋업
  ├── Docker: mysql + kafka + prometheus + grafana up
  ├── Kafka 토픽 7개 수동 생성 (allow.auto.create.topics=false)
  ├── k6-kafka 이미지 빌드
  ├── payments 기동 (local profile + MySQL 오버라이드, JVM 512m)
  ├── 시드: USER-0~9999 × 100만원 (10,000건)
  └── 웜업 (수 초~분, buffer pool 채우기)

단계 1: 예비 측정 (버전1)
  ├── produce rate 단계적 증가 (50 → 100 → 150 ...)
  ├── lag가 0을 유지하는 최대 rate = consumer 한계 확인
  └── 버스트 rate R 확정 (한계의 5~10배)

단계 2: 버전1 (Kafka) 버스트 본 테스트
  ├── baseline 30 TPS × 10분 → ramp-up → burst R TPS × 30분
  ├── lag-monitor.sh 동시 실행 (CSV 기록)
  ├── k6 종료 → drain 관찰 (lag → 0 시간 측정)
  └── 검증: deposit_history_entity 건수 = produce 건수, SUM(balance) 정합성

단계 3: 버전2 (동기) 동일 조건 테스트
  ├── 잔액 리셋 (reset-balance.sql)
  ├── deposit-sync-v2 체크아웃 → 앱 재기동 (Kafka 미사용)
  ├── 동일 버스트 프로파일로 burst-http-v2.js 실행
  └── 검증: 에러율, p99, row_lock_waits 기록

단계 4: 결과 비교 테이블 작성 (7장 템플릿)
```

---

## 7. 결과 템플릿

```markdown
## 환경 정보
- 인스턴스: macOS (Docker Desktop, 18 CPU / 8GB)
- Java: OpenJDK 21, payments -Xms512m -Xmx512m G1GC
- MySQL: mysql:8.0 Docker, innodb_buffer_pool_size=256M
- 시드: 10,000 userCode × 1,000,000원
- 연산 혼합: 충전3 : 출금1 : 환불2
- 버스트 프로파일: baseline 30 TPS × 10분 → ramp 2분 → burst [R] TPS × 30분

## 예비 측정 (버전1 consumer 한계)
| rate | lag 유지 여부 | 비고 |
|---|---|---|
| 50  | ✅ | |
| 100 | ✅ | |
| ... | | |
→ consumer 한계: [ ] TPS → 버스트 R = [ ] TPS

## 버전1 (Kafka) 결과
- produce 총 건수: [ ]
- produce 에러: [ ]건 (에러율 [ ]%)
- produce latency: avg [ ]ms, p95 [ ]ms, p99 [ ]ms
- 최대 lag: [ ]건 / drain 완료까지: [ ]분
- DB 반영 검증: deposit_history_entity [ ]건 (= produce 건수 ✅/❌)
- 잔액 정합성: ✅/❌

## 버전2 (동기) 결과
- 총 요청: [ ]건, 실제 처리 TPS: [ ]
- p50 [ ]ms / p95 [ ]ms / p99 [ ]ms
- 에러율: [ ]% (5xx [ ]건, timeout [ ]건)
- innodb_row_lock_waits: [ ]
- 잔액 정합성: ✅/❌

## A/B 비교
| 지표 | v1 Kafka | v2 동기 |
|---|---|---|
| 버스트 중 에러율 | | |
| 버스트 중 지연 | | |
| 과부하 흡수 | lag [ ]건 | 없음 |
| 회복 시간 | drain [ ]분 | [ ] |
| 무손실 | ✅ | - |

## 결론
- Kafka 도입 근거: [핵심 수치 한 줄]
```

---

## 8. 진행 현황

- [ ] 단계 0: 환경 셋업
- [ ] 단계 1: 예비 측정
- [ ] 단계 2: 버전1 버스트 본 테스트
- [ ] 단계 3: 버전2 동기 테스트
- [ ] 단계 4: 결과 비교

## 부록: 관련 문서

| 문서 | 내용 |
|------|------|
| [`deepseek-physicaltest.md`](./deepseek-physicaltest.md) | 성능·부하·스트레스 3단계 테스트 설계 |
| [`deepseek-physicaltest-setup.md`](./deepseek-physicaltest-setup.md) | Kafka 기반 실행 가이드 (컨테이너/토픽/시드) |
| [`deepseek-kafka-idempotency.md`](./deepseek-kafka-idempotency.md) | Kafka 멱등성 설계 |
| [`deepseek-deposit.md`](./deepseek-deposit.md) | 예치금 동시성 제어 분석 |
