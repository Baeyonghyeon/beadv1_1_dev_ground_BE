# 이어서 작업하기 — 인수인계 메모

> 최종 갱신: 2026-08-28
> **컨텍스트가 초기화된 뒤 이 파일부터 읽으면 됩니다.**

---

## 0. 지금 어디까지 왔나

**목표:** 이력서에 쓸 "Kafka 기반 이벤트 아키텍처 적용" 항목을 **실측 수치로** 뒷받침하기.

**Kafka 를 쓰는 이유(본인 정의):** 대용량 실시간 데이터를 손실 없이 빠르게 처리 +
**뒤에 있는 별도 도메인과의 결합도를 낮추기 위해.**
→ 그래서 **결제(예치금 차감)는 동기**, **후처리(주문완료·장바구니·상품)만 비동기**로 경계를 그었다.

### ⚠️ 브랜치 상태 — 가장 먼저 확인할 것

```bash
git branch --show-current     # refactor/remove-kafka-payment-path
git status --short | wc -l    # 약 70건 (전부 미커밋)
```

- **`main` 이 아니라 `refactor/remove-kafka-payment-path` 브랜치에 있다.**
- **아직 아무것도 커밋하지 않았다.** 작업 내용은 워킹 디렉터리에만 있다.
- `main` 으로 돌아가면 Kafka 결제 경로 삭제 이전 상태.

---

## 1. 문서 지도

| 파일 | 내용 |
|---|---|
| **`claude-bench-실행가이드.md`** | **테스트 실행 방법 — 여기부터** |
| `claude-kafka-test-결과.md` | 측정 결과 + 이력서 문장 + 면접 예상 질문 |
| `claude-kafka-test-설계.md` | 실험 설계 + §13 진행 현황 |
| `claude-kafka-test-사전작업.md` | 환경 구축 내역 (bench 프로파일, 컨테이너 등) |
| `claude-kafka-측정-todo.md` | **추후 측정할 Kafka 지표** (lag, 파티션, 내구성) |
| `claude-lock-check-todo.md` | 비관적 락 소스 확인 항목 6개 |

---

## 2. 확정된 실측값 (이력서에 쓸 수 있는 것)

| 항목 | 값 |
|---|---|
| 락 임계 구간 수정 | 응답 **50.71초/500 → 0.09초/200**, 동시 100건 성공률 **60% → 100%** |
| 후처리 Kafka 전환 | 처리량 **49.6 → 65.2 TPS (+31%)**, **p95 1,662 → 1,380ms (−17%)** |
| 트레이드오프 | 완결 시간 **167ms → 6,977ms** (42배) |
| 병목 특정 | 동시 60에서 워커 50개 중 39개가 커넥션 대기 = **응답의 92%가 대기** |
| 교차점 | **동시 30 미만에서는 Kafka 가 오히려 느림** (89ms vs 276ms) |

> **p99 를 쓰지 말 것.** 표본이 600건이라 상위 6건이 결정해 ±190ms 흔들린다 (p95 는 ±32ms).
> 자세한 근거는 `claude-kafka-test-결과.md` §3.1 주석.

---

## 3. 오늘(8/27~28) 한 일

1. **P0-7 락 임계 구간 수정** — `REQUIRES_NEW` 이력 저장이 FK 검사로 자기 X 락에 막혀
   결제가 매번 50초 타임아웃. 이력 저장을 락 밖으로(`join` 전략) 옮김
2. **p99 → p95 정정** — 이력서 수치를 부트스트랩 검증 후 교체
3. **k6 도입** — 도착률 고정(open-loop) 부하. `run-k6.sh`, `run-k6-steps.sh`
4. **Grafana 대시보드** — "🔬 Kafka A/B 부하 테스트" 8패널. k6 지표를 Prometheus 로 remote write
5. **Arm C(Kafka 결제 경로) 폐기** — 이중 차감 원인이었던 AS-IS 잔재 제거

### Arm C 삭제 상세

| 삭제 | 내용 |
|---|---|
| 파일 | `OrderSaga.java`, `KafkaPaymentAdapter.java`, `PaymentPerformanceTest.java` |
| 메서드 | `PaymentKafkaHandler.handleEvent(PaymentCreateCommand)`, `DepositKafkaConsumer.handleWithdrawCommand`(2차 차감 지점), `OrderKafkaEventPort` 죽은 발행 6개 |
| core 계약 10개 | `PaymentCreateCommand`, `WithdrawDeposit`, `DepositWithdrawnSuccess` 등 (고아) |
| 이름 정정 | `OrderCreatedEventPublisher` → `OrderRefundEventPublisher` |

**검증 완료:** 주문 3건에 차감 5,000원씩 정확, 이력 3건(이전엔 6건). 빌드·통합테스트 통과.

### 🔴 세션 종료 시 사라지는 백업

```
/private/tmp/claude-501/.../scratchpad/removed/
  ├ KafkaPaymentAdapter.java
  └ PaymentPerformanceTest.java
```

**미추적 파일이라 git 으로 복구 불가.** 보관이 필요하면 **오늘 안에** 다른 곳으로 옮길 것.
(판단 근거는 `claude-kafka-test-결과.md` §3.6 에 문서로 보존해뒀으므로, 코드가 꼭 필요한 게 아니면 그냥 버려도 됨)

---

## 4. Docker 내렸다 올려도 되나 → **문제없음**

```bash
docker compose -f docker/docker-compose-bench.yml down     # ✅ 안전
docker compose -f docker/docker-compose-bench.yml up -d
```

| 대상 | 볼륨 | down 후 |
|---|---|---|
| MySQL | `bench-mysql-data` ✅ | **시드·주문 데이터 유지** |
| Prometheus | `bench-prometheus-data` ✅ | 지표 이력 유지 |
| Grafana | `bench-grafana-data` ✅ | 대시보드 유지 |
| **Kafka** | **없음** ⚠️ | 토픽·오프셋 삭제 → **앱 기동 시 `NewTopic` 빈이 31개 자동 재생성** |

Kafka 데이터가 날아가도 상관없다. 토픽은 자동 생성되고, 컨슈머는
`auto-offset-reset: earliest` 로 빈 토픽부터 시작한다.

### ❌ 이건 하지 말 것

```bash
docker compose ... down -v     # 볼륨까지 삭제 → 시드 소실, 재시드 필요
```

만약 `-v` 로 내렸다면:
```bash
docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < bench-scripts/seed.sql
```

---

## 5. 내일 바로 시작하는 법

```bash
# 1) 브랜치 확인
git branch --show-current        # refactor/remove-kafka-payment-path 여야 함

# 2) 기동 (이미지는 이미 빌드돼 있음. 코드를 고쳤다면 3)부터)
docker compose -f docker/docker-compose-bench.yml up -d

# 3) 코드를 고쳤다면 재빌드
./gradlew :commerce:bootJar :payments:bootJar
docker compose -f docker/docker-compose-bench.yml build commerce payments
docker compose -f docker/docker-compose-bench.yml up -d commerce payments

# 4) 상태 확인 (둘 다 200)
curl -s -o /dev/null -w 'commerce %{http_code}\n' localhost:8081/actuator/health
curl -s -o /dev/null -w 'payments %{http_code}\n' localhost:8085/actuator/health

# 5) 데이터 초기화
docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < bench-scripts/seed.sql

# 6) Grafana 열고 테스트
open http://localhost:3000       # admin/admin → "🔬 Kafka A/B 부하 테스트"
./bench-scripts/run-k6-steps.sh sync
./bench-scripts/run-k6-steps.sh kafka
```

---

## 6. 다음 할 일

| 순위 | 작업 | 비고 |
|---|---|---|
| 1 | **부하 테스트 직접 실행** | Arm C 삭제 후 `run-burst.sh`/`run-k6-steps.sh` 는 **아직 안 돌려봄** (단건 3개만 확인) |
| 2 | **Kafka lag 시계열 측정** | `claude-kafka-측정-todo.md` §2-1. 수집 경로는 이미 있음 |
| 3 | **컨슈머 중단→재시작 유실 0** | 같은 문서 §3-1. "손실 없이" 의 직접 증거 |
| 4 | 파티션 1/3/6 확장성 | 같은 문서 §1-1 |
| 5 | 이력서 문장에 "경계를 그은 판단" 반영 | 결제=동기 / 후처리=비동기 (§3.6 근거) |
| 6 | 비관적 락 소스 확인 6개 | `claude-lock-check-todo.md` |

---

## 7. 알아두면 좋은 함정

- **lag drain 전에 DB 를 지우지 말 것** — 컨슈머가 `ORDER_NOT_FOUND` 로 지수 백오프 5회
  재시도에 빠져 다음 측정 구간까지 오염된다. 실제로 한 회차 날렸다. 스크립트에는 반영돼 있음
- **k6 VU 규칙**: `maxVUs ≥ 도착률 × 타임아웃`. 어기면 `dropped_iterations` 가 올라가고 회차 무효
- **여유 메모리 3GB / VU 당 ~2MB** → 최대 약 1,500 VU. 타임아웃 4초면 **375 TPS 가 상한**
- **`products-purchase-command` 는 소비자가 없다** (product 서비스 미기동). lag 이 쌓이는 게 정상
- 컨테이너 내부에서 Kafka CLI 는 **`kafka:9090`** (localhost:9092 아님)
- MySQL 은 **13306**, Kafka 는 **19092** (기존 로컬 개발 컨테이너와 충돌 방지)
