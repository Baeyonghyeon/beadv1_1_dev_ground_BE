# 정산 배치 성능 테스트 결과

## 실행 환경

| 항목 | 값 |
|------|-----|
| 실행 일시 | 2026-07-10 17:31 |
| 시드 데이터 | Order 10,000건 (DELIVERED, updatedAt 2주 전) |
| 청크 사이즈 | 100 |
| DB | MySQL 8.0 (Docker, localhost) |
| Kafka | Apache Kafka (Docker, localhost:9092) |
| JVM | OpenJDK 21 (temurin) |
| 서버 | commerce(8081) + payments(8085), Eureka/Gateway 미사용 |

---

## 전체 Job 실행 결과

| Step | Read | Write | Commit | Duration | TPS | 비중 |
|------|------|-------|--------|----------|-----|------|
| **settlementStep** | 10,000 | 10,000 | 101 | **4,849ms** | 2,062/s | 85.2% |
| **depositStep** | 1,000 | 1,000 | 11 | **839ms** | 1,192/s | 14.8% |
| **Total Job** | 11,000 | 11,000 | 112 | **5,688ms** | 1,934/s | 100% |

---

## 구간별 병목 분석

### settlementStep (4,849ms — 전체의 85.2%)

```
청크 100개 × (Feign 호출 100회 + Processor 100건 + Writer DB 저장 100건)
```

| 하위 구간 | 추정 소요 시간 | 비중 | 설명 |
|-----------|-------------|------|------|
| **Reader (Feign 호출)** | ~3,200ms | 66% | commerce 서버로 100회 HTTP 호출. 페이지당 평균 ~32ms |
| **Writer (DB 저장)** | ~1,500ms | 31% | 100건씩 `saveAll()` + flush × 100회 |
| **Processor (변환)** | ~150ms | 3% | `UnsettledOrderItemResponse → Settlement` 변환 (건당 ~0.015ms) |

### depositStep (839ms — 전체의 14.8%)

```
청크 10개 × (DB 조회 1회 + Processor 100건 + Kafka Saga 전송 100건)
```

| 하위 구간 | 추정 소요 시간 | 비중 | 설명 |
|-----------|-------------|------|------|
| **Kafka 전송 + Saga** | ~600ms | 72% | `SettlementSagaOrchestrator` → Kafka → `DepositKafkaConsumer` → Deposit 충전 |
| **DB 조회** | ~200ms | 24% | `SETTLEMENT_CREATED` 상태 조회 (1,000건 한 번에) |
| **Processor** | ~40ms | 4% | `Settlement → SettlementChargeDeposit` 변환 |

---

## 병목 지점 TOP 3

### 1. Feign HTTP 호출 (settlementStep Reader) — 전체 시간의 ~56%

- **원인**: 페이지당 1회 HTTP 호출, 총 100회 RTT (Round Trip Time)
- **현재**: `PAGE_SIZE=100`, Feign 100회 호출
- **개선안**:
  - `PAGE_SIZE` 100 → 500 으로 증가 → Feign 호출 20회로 감소 → ~2.5s 절감 예상
  - `PAGE_SIZE` 100 → 1000 → Feign 호출 10회 → ~3.0s 절감 예상

### 2. DB 저장 (settlementStep Writer) — 전체 시간의 ~26%

- **현재**: 청크 단위 `saveAll()` + flush, 100회 커밋
- **개선안**:
  - `chunk.size` 100 → 500 → 커밋 20회로 감소
  - `spring.jpa.properties.hibernate.jdbc.batch_size=100` (청크 사이즈와 동기화)
  - `spring.jpa.properties.hibernate.order_inserts=true`

### 3. SettlementDepositReader 페이지 제한 — 1,000건만 처리

- **현재 버그**: `SettlementDepositReader` 가 단일 페이지만 읽음 (PageRequest.of(0, 1000))
- **영향**: 10,000건 중 1,000건만 depositStep 처리, 나머지 9,000건 미처리
- **개선안**: Reader에 페이징 루프 추가 (UnsettledOrderItemReader 와 동일 패턴)

---

## 최적화 시뮬레이션

| 튜닝 | settlementStep | depositStep | Total | 개선율 |
|------|---------------|-------------|-------|--------|
| **현재** | 4,849ms | 839ms | 5,688ms | baseline |
| PAGE_SIZE 100→500 | ~1,600ms | 839ms | ~2,439ms | **57% ↓** |
| + chunk.size 100→500 | ~1,200ms | 839ms | ~2,039ms | **64% ↓** |
| + depositStep 페이징 (10,000건) | 1,200ms | ~4,000ms | ~5,200ms | 9% ↓ |

> depositStep 페이징 루프를 추가하면 10,000건 전체를 처리하게 되어 depositStep 시간이 늘어나지만, 그만큼 처리량은 10배 증가한다.

---

## 현재 설정값

```yaml
# payments application.yml
custom:
  batch:
    chunk:
      size: 100    # 청크 크기
    skip:
      limit: 10
    retry:
      limit: 3
  settlement:
    rate: 0.95     # 정산 수수료율
```

```java
// UnsettledOrderItemReader
private static final int PAGE_SIZE = 100;  // Feign 페이지 크기
```

---

## 발견된 코드 이슈

수정한 3가지 버그:

| # | 파일 | 문제 | 수정 |
|---|------|------|------|
| 1 | `OrderItemJpaRepository` | `BETWEEN :start AND :end` 로 윈도우 제한 → 2주 이상 된 데이터 누락 | `o.updatedAt <= :cutoff` 로 변경 |
| 2 | `UnsettledOrderItemReader` | `currentPage = 0` → PageQuery 검증(page <= 0)에서 실패 | `currentPage = 1` 로 수정 |
| 3 | `UnsettledOrderItemReader` | `currentPage = pageDto.currentPageNumber()` → 같은 페이지 무한 재요청 | `currentPage = pageDto.currentPageNumber() + 1` 로 수정 |

---

## 다음 액션

- [ ] `PAGE_SIZE` 100 → 500 으로 증대 후 재측정
- [ ] `chunk.size` 100 → 200, 500 순차 증대 테스트
- [ ] `SettlementDepositReader` 페이징 루프 추가
- [ ] 100,000건 데이터로 스트레스 테스트
- [ ] `PerfMeasurementChunkListener` 적용하여 정밀한 Reader/Processor/Writer 비중 측정
