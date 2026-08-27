# 정산 배치 (Settlement Batch) 성능 테스트 가이드

## 1. 테스트를 위해 켜야 하는 서버

### 필수 인프라 (Docker Compose)

```bash
cd docker
docker compose -f docker-compose-local.yml up -d mysql kafka
```

| 인프라 | 포트 | 용도 |
|--------|------|------|
| **MySQL** | 3306 | commerce + payments 데이터 저장소 |
| **Kafka** | 9092 (external) | depositStep → Saga Orchestrator → Deposit 충전 이벤트 발행/소비 |

> Redis, Elasticsearch, Logstash, Kibana, MariaDB 는 정산 배치 성능 테스트에 필수가 아니므로 선택적으로 켜면 된다.

### 필수 애플리케이션 서버

| 서버 | 포트 | 프로파일 | 역할 |
|------|------|----------|------|
| **eureka-server** | 8761 | default | 서비스 디스커버리 (Feign Client가 commerce를 찾기 위해 필요) |
| **commerce** | 8081 | local | `UnsettledOrderItemReader`가 Feign으로 호출하는 `/api/orders/unsettled-items` 엔드포인트 제공 |
| **payments** | 8085 | local | 정산 배치 Job (`settlementJob`) 실행 주체 |

### 서버 실행 방법 (IntelliJ)

1. **eureka-server**: `EurekaServerApplication` 실행
2. **commerce**: `CommerceApplication` 실행 (profile: `local`)
3. **payments**: `PaymentsApplication` 실행 (profile: `local`)

### 전체 배치 흐름과 의존 관계

```
[payments 서버] Spring Batch Job "settlementJob"
  │
  ├── Step 1: settlementStep (chunk size: 100)
  │   ├── Reader: UnsettledOrderItemReader
  │   │   └── Feign → [commerce 서버] GET /api/orders/unsettled-items
  │   │                  └── OrderItemJpaRepository.findOrderItemsDelivered()
  │   │                      (DELIVERED 상태, updatedAt 기준 2주 전 범위)
  │   ├── Processor: SettleConvertProcessor
  │   │   └── UnsettledOrderItemResponse → Settlement 엔티티 변환 (정산율 적용)
  │   └── Writer: SettlementDataWriter
  │       └── settlementRepository.saveAll() → MySQL 저장
  │
  └── Step 2: depositStep (chunk size: 100)
      ├── Reader: SettlementDepositReader
      │   └── settlementRepository.findBySettlementStatus(SETTLEMENT_CREATED)
      ├── Processor: SettlementDepositProcessor
      │   └── Settlement → SettlementChargeDeposit 커맨드 변환
      └── Writer: SettlementDepositWriter
          └── SettlementSagaOrchestrator.startSettlementDepositChargeSaga()
              └── KafkaTemplate.send("deposits-commands", command)
                  │
                  └── [Kafka] deposits-commands 토픽
                      │
                      └── DepositKafkaConsumer.handleSettlementChargeCommand()
                          └── depositEventApplication.charge() → 예치금 충전
                          └── KafkaTemplate.send("deposits-events", successEvent)
                              │
                              └── SettlementEventHandler.handleEvent()
                                  └── sagaOrchestrator.handleDepositChargeSuccess()
                                  └── KafkaTemplate.send("settlements-events", event)
```

### 실제 배치 트리거 방법

현재 정산 배치는 `SettlementJobScheduler`에 의해 `@Scheduled(cron = "0 0 2 2 * *")` (매월 2일 새벽 2시) 자동 실행된다. 성능 테스트 시에는 아래 방법 중 하나로 트리거한다:

**방법 A: REST API 엔드포인트 추가 (추천)**

```java
// 테스트용 컨트롤러 추가
@RestController
@RequestMapping("/api/test")
public class BatchTestController {
    private final JobLauncher jobLauncher;
    private final Job settlementJob;

    @PostMapping("/settlement-batch")
    public void triggerBatch() {
        JobParameters params = new JobParametersBuilder()
            .addString("executeTime", LocalDateTime.now().toString())
            .toJobParameters();
        jobLauncher.run(settlementJob, params);
    }
}
```

**방법 B: Spring Batch Test 프레임워크 사용**

기존 `SettlementStepPerformanceTest`처럼 `JobLauncherTestUtils.launchStep()` 으로 개별 Step만 측정.

---

## 2. Datafaker — 시드 데이터 생성 (k6는 필요 없다)

### 정산 배치 성능 테스트에서 k6가 필요 없는 이유

배치는 **스스로 부하를 만들어내는 구조**다:

```
Datafaker로 DELIVERED OrderItem 100,000건 DB에 채워둠
    ↓
배치 Job 한 번 실행
    ↓
UnsettledOrderItemReader 가 100,000건을 페이징 루프로 알아서 읽어감
    ↓
Processor → Writer 가 모든 건을 처리함
    ↓
"100,000건 처리하는 데 얼마나 걸렸나?" ← 이게 우리가 알고 싶은 것
```

k6는 외부에서 HTTP 트래픽을 추가로 쏘는 도구인데, 배치는 **이미 100,000건이라는 부하를 스스로 발생시키고 있다.** 밖에서 k6가 API를 더 호출해봤자 배치 성능 측정과는 무관한 commerce API 단독 성능만 나올 뿐이다.

> k6가 의미 있는 경우는 "API 서버가 동시 사용자 1000명을 견딜 수 있는가?" 같은 **HTTP 엔드포인트 부하 테스트**다. 정산 배치처럼 내부에서 데이터를 읽고 처리하는 배치 작업에는 맞지 않는다.

### 그래서 진짜 필요한 조합

| 순서 | 도구 | 역할 |
|------|------|------|
| **1** | **Datafaker** | DELIVERED OrderItem + 판매자 + 예치금 시드 데이터를 DB에 채운다 |
| **2** | **배치 실행 + 메트릭** | `JobLauncher.run()` → `StepExecution.getDuration()` → TPS, 병목 구간 측정 |

### 왜 Datafaker가 반드시 필요한가

정산 배치는 DB에 **처리할 데이터가 있어야** 의미 있는 성능 측정이 가능하다:

```
배치 Job 실행 → UnsettledOrderItemReader.read()
  → Feign 호출 → commerce 서버
    → OrderItemJpaRepository.findOrderItemsDelivered()
      → SELECT ... FROM order_item WHERE order_status = 'DELIVERED' AND updated_at BETWEEN ? AND ?
```

`DELIVERED` 상태 OrderItem이 0건이면 배치는 빈 페이지만 읽고 즉시 종료된다.  
→ **반드시 Datafaker로 시드 데이터를 먼저 채워야 한다.**

### 시드 데이터가 필요한 곳은 두 군데다

배치 전체를 완주하려면 commerce와 payments **양쪽 모듈**에 데이터가 필요하다:

```
Step 1 (settlementStep)
  Reader → Feign → commerce 서버: OrderItem + Order 데이터 필요
  Writer → payments 서버: Settlement 저장 (자동 생성됨)

Step 2 (depositStep)
  Reader → payments 서버: Step1 에서 생성된 Settlement 읽음
  Writer → Kafka → DepositKafkaConsumer: 판매자의 Deposit 계좌에 충전 ← 여기서 Deposit 필요!
```

| 데이터 | 모듈 | DB 테이블 | 필요한 이유 |
|--------|------|-----------|-------------|
| Order + OrderItem (DELIVERED) | **commerce** | `order_entity`, `order_item_entity` | `UnsettledOrderItemReader` 가 Feign으로 조회 |
| 판매자 Deposit 계좌 | **payments** | `deposit` | `DepositKafkaConsumer` 가 판매자 계좌에 충전. 계좌 없으면 Step 2 실패 |

### 시드 데이터 생성 코드 ① — commerce 모듈 (Order + OrderItem)

파일 위치: `commerce/src/test/java/io/devground/dbay/support/CommerceSeedDataGenerator.java`

```java
package io.devground.dbay.support;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import io.devground.dbay.order.domain.vo.OrderStatus;
import io.devground.dbay.order.infrastructure.model.persistence.OrderEntity;
import io.devground.dbay.order.infrastructure.model.persistence.OrderItemEntity;
import io.devground.dbay.order.infrastructure.adapter.out.persistence.OrderJpaRepository;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;

@Slf4j
@SpringBootTest
@ActiveProfiles("local")
class CommerceSeedDataGenerator {

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    private static final Faker faker = new Faker(new Locale("ko"));

    @Test
    void generateSettlementTestData() {
        int totalOrders = 10_000;
        int sellersCount = 1_000;
        int chunkSize = 500;
        LocalDateTime twoWeeksAgo = LocalDateTime.now().minusWeeks(2);

        // 판매자 코드 목록 (payments 쪽 Deposit 생성 시 같은 코드 사용)
        List<String> sellerCodes = IntStream.range(0, sellersCount)
            .mapToObj(i -> "SELLER-" + i)
            .toList();

        System.out.println("[SEED] 사용할 sellerCodes 예시: " + sellerCodes.subList(0, 5));
        System.out.println("[SEED] payments 모듈에서 이 코드로 Deposit 생성 필요!");

        List<OrderEntity> orders = new ArrayList<>();

        for (int i = 0; i < totalOrders; i++) {
            String sellerCode = sellerCodes.get(i % sellersCount);
            String buyerCode = "BUYER-" + i;

            OrderEntity order = OrderEntity.builder()
                .code("ORDER-" + UUID.randomUUID())
                .userCode(buyerCode)
                .orderStatus(OrderStatus.DELIVERED)   // ← 정산 대상 상태
                .updatedAt(twoWeeksAgo)                // ← 2주 전 = 정산 대상 범위
                .build();

            OrderItemEntity item = OrderItemEntity.builder()
                .code("ORDER-ITEM-" + UUID.randomUUID())
                .orderEntity(order)
                .sellerCode(sellerCode)
                .productPrice((long) faker.number().numberBetween(1000, 100000))
                .build();

            order.addOrderItem(item);
            orders.add(order);

            if (orders.size() >= chunkSize) {
                orderJpaRepository.saveAll(orders);
                orderJpaRepository.flush();
                orders.clear();
                log.info("[SEED-COMMERCE] {} / {} orders inserted", i + 1, totalOrders);
            }
        }

        if (!orders.isEmpty()) {
            orderJpaRepository.saveAll(orders);
            orderJpaRepository.flush();
        }

        System.out.println("[SEED-COMMERCE] 완료: " + totalOrders + " orders 생성");
    }
}
```

### 시드 데이터 생성 코드 ② — payments 모듈 (판매자 Deposit 계좌)

파일 위치: `payments/src/test/java/io/devground/payments/support/PaymentsSeedDataGenerator.java`

```java
package io.devground.payments.support;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import io.devground.payments.deposit.domain.deposit.Deposit;
import io.devground.payments.deposit.infrastructure.adapter.out.persistence.DepositJpaRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootTest
@ActiveProfiles("local")
class PaymentsSeedDataGenerator {

    @Autowired
    private DepositJpaRepository depositJpaRepository;

    // ⚠️ commerce 모듈과 sellerCode 를 동일하게 맞춰야 한다
    private static final int SELLERS_COUNT = 1_000;

    @Test
    void generateSellerDeposits() {
        List<String> sellerCodes = IntStream.range(0, SELLERS_COUNT)
            .mapToObj(i -> "SELLER-" + i)
            .toList();

        int created = 0;
        int skipped = 0;

        for (String sellerCode : sellerCodes) {
            if (depositJpaRepository.findByUserCode(sellerCode).isPresent()) {
                skipped++;
                continue; // 이미 존재하면 건너뜀
            }

            Deposit deposit = new Deposit(sellerCode);
            depositJpaRepository.save(deposit);
            created++;

            if (created % 100 == 0) {
                depositJpaRepository.flush();
                log.info("[SEED-PAYMENTS] {} / {} deposits created", created, SELLERS_COUNT);
            }
        }

        depositJpaRepository.flush();
        System.out.println("[SEED-PAYMENTS] 완료: " + created + " deposits 생성, " + skipped + " 건 스킵됨");
    }
}
```

### 빌드 의존성 추가

```groovy
// commerce/build.gradle
dependencies {
    testImplementation 'net.datafaker:datafaker:2.4.2'
}

// payments/build.gradle — datafaker 불필요 (Deposit 생성은 단순해서)
```

### 실행 순서

```
1. docker compose -f docker-compose-local.yml up -d mysql kafka
2. IntelliJ 에서 CommerceSeedDataGenerator.generateSettlementTestData() 실행
3. IntelliJ 에서 PaymentsSeedDataGenerator.generateSellerDeposits() 실행
4. payments 서버 띄워서 배치 실행 또는 SettlementBatchPerformanceTest 실행
```

> **sellerCode 를 두 모듈이 동일하게 맞추는 게 핵심이다.** `CommerceSeedDataGenerator` 가 `SELLER-0` ~ `SELLER-999` 를 OrderItem 에 할당하고, `PaymentsSeedDataGenerator` 가 같은 `SELLER-0` ~ `SELLER-999` 로 Deposit 계좌를 만든다. 실행 직전에 양쪽 코드의 `SELLERS_COUNT` 값이 같은지 확인하면 된다.

### 테스트 규모별 권장 시드 데이터량

| 규모 | DELIVERED OrderItem | 판매자 수 | 예상 배치 소요 시간 | 용도 |
|------|---------------------|-----------|---------------------|------|
| **스모크 테스트** | 1,000건 | 100명 | 수 초 | 배치 동작 확인, 기본 흐름 점검 |
| **기본 성능** | 10,000건 | 1,000명 | 수십 초 ~ 1분 | 청크 사이즈 튜닝 |
| **스트레스** | 100,000건 | 5,000명 | 수 분 | GC, 메모리, DB 커넥션 풀 포화 확인 |

### 배치 성능 측정 코드 (payments 모듈)

파일 위치: `payments/src/test/java/io/devground/payments/support/SettlementBatchPerformanceTest.java`

```java
package io.devground.payments.support;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootTest
@ActiveProfiles("local")
class SettlementBatchPerformanceTest {

    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    private Job settlementJob;

    @Test
    void measureFullBatchPerformance() throws Exception {
        // ⚠️ 실행 전에 CommerceSeedDataGenerator + PaymentsSeedDataGenerator 먼저 실행해야 함

        JobParameters params = new JobParametersBuilder()
            .addString("executeTime", LocalDateTime.now().toString())
            .toJobParameters();

        long start = System.nanoTime();
        JobExecution execution = jobLauncher.run(settlementJob, params);
        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        for (StepExecution step : execution.getStepExecutions()) {
            log.info("[PERF] Step={}, Read={}, Write={}, Skip={}, Duration={}ms",
                step.getStepName(),
                step.getReadCount(),
                step.getWriteCount(),
                step.getSkipCount(),
                step.getDuration().toMillis()
            );
        }

        long totalItems = execution.getStepExecutions().stream()
            .mapToLong(StepExecution::getWriteCount).sum();
        double tps = (totalItems * 1000.0) / Math.max(1L, totalMs);
        log.info("[PERF] Total={}ms, Items={}, TPS={}", totalMs, totalItems, String.format("%.2f", tps));
    }
}
```

---

## 3. 구간별 병목 확인 방법 — k6 없이도 가능하다

### 핵심 원리: 배치 내부에서 Reader / Processor / Writer 각각을 타이밍한다

k6는 외부에서 HTTP 응답 시간만 볼 수 있지만, 배치 내부에서는 **Spring Batch Listener** 와 **`System.nanoTime()`** 으로 각 구간을 개별 측정할 수 있다. k6보다 더 정밀하게 "어디서 시간을 많이 쓰는가"를 찾을 수 있다.

### 3.1 청크 단위 성능 리스너 — 가장 실용적인 방법

이 파일을 생성해서 붙이면 청크마다 Reader/Processor/Writer 시간이 자동 로깅된다:

```java
// payments/src/test/java/.../support/PerfMeasurementChunkListener.java

package io.devground.payments.support;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.ItemProcessListener;
import org.springframework.batch.core.ItemReadListener;
import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.item.Chunk;

import lombok.extern.slf4j.Slf4j;

/**
 * 각 청크의 Read / Process / Write 구간별 소요 시간을 측정하는 리스너.
 * 청크 단위로 누적 시간을 로깅하므로 직접 어디가 병목인지 한눈에 보인다.
 */
@Slf4j
public class PerfMeasurementChunkListener
		implements ChunkListener, ItemReadListener<Object>, ItemProcessListener<Object, Object>, ItemWriteListener<Object> {

	private static final int LOG_INTERVAL = 10; // 10청크마다 요약 로그 출력

	private final AtomicLong readTotalNs = new AtomicLong(0);
	private final AtomicLong processTotalNs = new AtomicLong(0);
	private final AtomicLong writeTotalNs = new AtomicLong(0);
	private final AtomicLong itemCount = new AtomicLong(0);
	private final AtomicLong chunkCount = new AtomicLong(0);
	private final AtomicLong totalReadCount = new AtomicLong(0);
	private final AtomicLong totalWriteCount = new AtomicLong(0);

	private final AtomicReference<Long> readStartNs = new AtomicReference<>();
	private final AtomicReference<Long> processStartNs = new AtomicReference<>();
	private final AtomicReference<Long> writeStartNs = new AtomicReference<>();

	// --- ItemReadListener: 개별 아이템 읽기 시작/종료 ---
	@Override
	public void beforeRead() {
		readStartNs.set(System.nanoTime());
	}

	@Override
	public void afterRead(Object item) {
		if (item != null) {
			long elapsed = System.nanoTime() - readStartNs.get();
			readTotalNs.addAndGet(elapsed);
			totalReadCount.incrementAndGet();
		}
	}

	@Override
	public void onReadError(Exception ex) {
		readStartNs.set(null); // 에러 발생 시 타이밍 무효화
	}

	// --- ItemProcessListener: 개별 아이템 처리 시작/종료 ---
	@Override
	public void beforeProcess(Object item) {
		processStartNs.set(System.nanoTime());
	}

	@Override
	public void afterProcess(Object item, Object result) {
		if (result != null) {
			long elapsed = System.nanoTime() - processStartNs.get();
			processTotalNs.addAndGet(elapsed);
		}
	}

	@Override
	public void onProcessError(Object item, Exception e) {
		processStartNs.set(null);
	}

	// --- ItemWriteListener: 청크 쓰기 시작/종료 ---
	@Override
	public void beforeWrite(Chunk<?> items) {
		itemCount.addAndGet(items.size());
		writeStartNs.set(System.nanoTime());
	}

	@Override
	public void afterWrite(Chunk<?> items) {
		long elapsed = System.nanoTime() - writeStartNs.get();
		writeTotalNs.addAndGet(elapsed);
		totalWriteCount.addAndGet(items.size());
	}

	@Override
	public void onWriteError(Exception ex, Chunk<?> items) {
		writeStartNs.set(null);
	}

	// --- ChunkListener: 청크 종료 시 누적 요약 ---
	@Override
	public void afterChunk(ChunkContext context) {
		long currentChunk = chunkCount.incrementAndGet();
		if (currentChunk % LOG_INTERVAL == 0) {
			long readMs = TimeUnit.NANOSECONDS.toMillis(readTotalNs.get());
			long processMs = TimeUnit.NANOSECONDS.toMillis(processTotalNs.get());
			long writeMs = TimeUnit.NANOSECONDS.toMillis(writeTotalNs.get());
			long totalMs = readMs + processMs + writeMs;
			long total = totalReadCount.get();

			if (total == 0) return;

			log.info("[PERF-CHUNK] 청크#{} 완료 | 총 {}건 | Read={}ms({:.0f}%) Process={}ms({:.0f}%) Write={}ms({:.0f}%) | 총합={}ms | 평균={}μs/건",
				currentChunk, total,
				readMs, (double) readMs / totalMs * 100,
				processMs, (double) processMs / totalMs * 100,
				writeMs, (double) writeMs / totalMs * 100,
				totalMs,
				(double) totalMs * 1000 / total
			);
		}
	}

	// 최종 요약은 별도 메서드로 호출
	public void printFinalSummary() {
		long readMs = TimeUnit.NANOSECONDS.toMillis(readTotalNs.get());
		long processMs = TimeUnit.NANOSECONDS.toMillis(processTotalNs.get());
		long writeMs = TimeUnit.NANOSECONDS.toMillis(writeTotalNs.get());
		long totalMs = readMs + processMs + writeMs;

		log.info("==================================================");
		log.info("[PERF-FINAL] 구간별 소요 시간 요약");
		log.info("  Read:    {}ms ({:.1f}%)", readMs, (double) readMs / totalMs * 100);
		log.info("  Process: {}ms ({:.1f}%)", processMs, (double) processMs / totalMs * 100);
		log.info("  Write:   {}ms ({:.1f}%)", writeMs, (double) writeMs / totalMs * 100);
		log.info("  Total:   {}ms", totalMs);
		log.info("  Items:   read={}, write={}", totalReadCount.get(), totalWriteCount.get());
		log.info("==================================================");
	}
}
```

### 3.2 Step에 리스너 등록하기

`SettlementStepConfiguration.java` 의 두 Step에 `.listener()` 만 추가하면 된다:

```java
// SettlementStepConfiguration.java 수정
private final PerfMeasurementChunkListener settlementPerfListener = new PerfMeasurementChunkListener();
private final PerfMeasurementChunkListener depositPerfListener = new PerfMeasurementChunkListener();

@Bean
public Step settlementStep(...) {
    return new StepBuilder("settlementStep", jobRepository)
        .<UnsettledOrderItemResponse, Settlement>chunk(batchSize, ptManager)
        .reader(unsettledOrderItemReader)
        .processor(settleConvertProcessor)
        .writer(settlementDataWriter)
        .faultTolerant()
        .skip(IllegalArgumentException.class)
        .skipLimit(skipLimit)
        .retry(DataAccessException.class)
        .retry(KafkaException.class)
        .retryLimit(retryLimit)
        .listener(new SettlementStepListener())       // 기존 Skip 리스너
        .listener(settlementPerfListener)              // ← 성능 측정 리스너 추가
        .build();
}
```

### 3.3 실행 결과 예시 — 병목이 한눈에 보인다

```
[PERF-CHUNK] 청크#10 완료 | 총 1000건 | Read=3200ms(62.1%) Process=150ms(2.9%) Write=1800ms(35.0%) | 총합=5150ms | 평균=5150μs/건
[PERF-CHUNK] 청크#20 완료 | 총 2000건 | Read=6300ms(60.8%) Process=310ms(3.0%) Write=3750ms(36.2%) | 총합=10360ms | 평균=5180μs/건
...
[PERF-FINAL] 구간별 소요 시간 요약
  Read:    31200ms (61.0%)   ← Feign 호출 + commerce DB 조회가 병목!
  Process:  1550ms ( 3.0%)   ← 변환 로직은 문제없음
  Write:   18400ms (36.0%)   ← DB 저장도 약간 무거움
  Total:   51150ms
```

이 결과가 나오면 바로 알 수 있다: **Reader(Feign)가 가장 큰 병목**이므로 chunk size 증대, 인덱스 추가, Feign 커넥션 풀 튜닝을 먼저 시도하면 된다.

### 3.4 Step 바깥의 Kafka/Saga 구간 측정

Step 2의 Writer가 Kafka로 전송한 후, Consumer가 처리하는 시간은 Step 내에서 측정되지 않는다. 이 구간은 별도로 측정한다:

```java
// SettlementDepositWriter.java 수정
private void startSettlementDepositChargeSaga(SettlementChargeDeposit command) {
    long start = System.nanoTime();
    sagaOrchestrator.startSettlementDepositChargeSaga(command);
    long kafkaSendMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    perfStats.recordKafkaSend(kafkaSendMs);  // Kafka 발행 시간만 별도 누적
}
```

그리고 Consumer 측에서는 이벤트 수신 시점과 처리 완료 시점의 차이를 로깅한다:

```java
// DepositKafkaConsumer.java - handleSettlementChargeCommand() 진입 시점과 종료 시점에 로깅
@KafkaHandler
public void handleSettlementChargeCommand(@Payload SettlementChargeDeposit command) {
    long start = System.nanoTime();
    // ... 예치금 충전 처리 ...
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    log.info("[PERF-KAFKA] SettlementChargeDeposit 처리 완료: {}ms (userCode={}, amount={})",
        elapsedMs, command.userCode(), command.amount());
}
```

### 3.5 별도로 확인해야 하는 외부 요소

| 확인 대상 | 방법 | 명령어 / 도구 |
|-----------|------|--------------|
| **commerce DB 슬로우 쿼리** | MySQL slow query log | `SET GLOBAL long_query_time = 0.5;` |
| **Kafka Consumer Lag** | Consumer group offset 확인 | `docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --group payments-consumer-group --describe` |
| **JVM GC 횟수/시간** | Actuator metrics | `http://localhost:8085/actuator/metrics/jvm.gc.pause` |
| **DB 커넥션 풀 고갈** | HikariCP metrics | `http://localhost:8085/actuator/metrics/hikaricp.connections.active` |

### 3.6 종합 병목 진단 플로우

```
배치 Job 실행 → 로그 확인
  │
  ├── [PERF-FINAL] Read 비중 > 50%
  │   → Feign 호출 또는 commerce DB 쿼리가 병목
  │   → 해결: 인덱스 추가, PAGE_SIZE 증가, Feign 커넥션 풀 확대
  │
  ├── [PERF-FINAL] Write 비중 > 50%
  │   ├── Step1 이면 → settlementRepository.saveAll() 병목
  │   │   → 해결: JDBC batch_size 증가, order_inserts=true
  │   └── Step2 이면 → Kafka 전송 병목
  │       → 해결: linger.ms 조정, batch.size 증가
  │
  ├── [PERF-KAFKA] 로그가 수 초 이상
  │   → Deposit 충전 DB 락 경합 또는 Kafka Consumer 처리 지연
  │   → 해결: PESSIMISTIC_WRITE 범위 확인, Consumer concurrency 증대
  │
  └── 전체 Step Duration이 기대치의 2배 이상
      → chunk.size 를 100 → 200 → 500 순으로 증가시켜 재실험
```

---

## 4. IntelliJ에서 서버 메모리 제한 설정

### 4.1 Run Configuration VM Options (애플리케이션별)

**IntelliJ → Run → Edit Configurations → 해당 Application 선택 → VM options:**

```
-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

| 옵션 | 설명 | 권장값 |
|------|------|--------|
| `-Xms` | 초기 힙 메모리 | 512m |
| `-Xmx` | 최대 힙 메모리 | 2048m (commerce), 1024m (eureka) |
| `-XX:+UseG1GC` | G1 가비지 컬렉터 사용 | 배치 처리에 적합 |
| `-XX:MaxGCPauseMillis` | GC 최대 일시 정지 시간 | 200 |
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 시 힙 덤프 생성 | 디버깅용 |
| `-XX:HeapDumpPath=./heapdumps/` | 힙 덤프 저장 경로 | |

### 4.2 Gradle JVM 메모리 설정

`gradle.properties` 파일 (프로젝트 루트 또는 `~/.gradle/gradle.properties`):

```properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
```

### 4.3 IntelliJ 자체 메모리 설정

**Help → Change Memory Settings** 또는 **IntelliJ IDEA → Settings → Appearance & Behavior → Appearance → Memory Settings**

또는 직접 파일 수정 (`~/Library/Application Support/JetBrains/IntelliJIdea2025.X/idea.vmoptions`):

```
-Xms512m
-Xmx4096m
-XX:ReservedCodeCacheSize=512m
```

### 4.4 애플리케이션 서버별 권장 VM Options

#### eureka-server (가벼움)
```
-Xms256m -Xmx512m
```

#### commerce (중간 - DB 조회, Elasticsearch 포함)
```
-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

#### payments (배치 처리 - 많은 데이터 처리)
```
-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError
```

> **주의**: commerce + payments + eureka 3개 서버를 동시에 로컬에서 실행할 경우, 합계 최대 4.5GB 이상을 할당하지 않도록 조절해야 한다 (호스트 머신 메모리 여유분 확보). 16GB 머신 기준 각각 1GB 정도로 시작해서 모자라면 올리는 것이 안전하다.

### 4.5 Spring Boot 자체 메모리 제한

`application.yml` 에서도 일부 JVM 옵션을 설정할 수 있다:

```yaml
# Spring Boot 3.x - 가상 스레드 설정 가능
spring:
  threads:
    virtual:
      enabled: true  # Java 21 가상 스레드로 배치 처리량 향상 가능
```

---

## 5. 성능 테스트 시나리오 체크리스트

### 사전 준비

- [ ] Docker: MySQL + Kafka 실행 확인 (`docker ps`)
- [ ] MySQL `dbay_db` 데이터베이스에 충분한 테스트 데이터 확보 (DELIVERED 상태 OrderItem 최소 10,000건 이상)
- [ ] Kafka 토픽 (`deposits-commands`, `deposits-events`, `settlements-events`) 자동 생성 확인
- [ ] eureka-server → commerce → payments 순서로 서버 모두 정상 기동
- [ ] Eureka 대시보드 (`http://localhost:8761`)에서 모든 서비스 등록 확인

### 테스트 시나리오

| 시나리오 | 데이터 규모 | 측정 항목 |
|----------|------------|----------|
| 소규모 | DELIVERED OrderItem 1,000건 | Step1/Step2 Duration, TPS, DB 쿼리 횟수 |
| 중규모 | DELIVERED OrderItem 10,000건 | 청크 처리 시간, Feign 호출 횟수, Kafka 전송 지연 |
| 대규모 | DELIVERED OrderItem 100,000건 | 전체 Job Duration, GC 횟수/시간, 힙 메모리 사용량, Consumer Lag |

### 주요 튜닝 파라미터

```yaml
# payments application.yml - 배치 성능 튜닝
custom:
  batch:
    chunk:
      size: 100   # 기본값. 100 → 200 → 500 순으로 증가시키며 테스트
    skip:
      limit: 10
    retry:
      limit: 3

spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 100          # JDBC 배치 크기 (chunk size와 맞춤)
        order_inserts: true         # INSERT 문 정렬 배치
        order_updates: true         # UPDATE 문 정렬 배치
```

> `chunk.size`를 너무 크게 설정하면 트랜잭션 타임아웃이 발생할 수 있으므로, 100 → 200 → 500 → 1000 순으로 점진적으로 증가시키며 최적값을 찾는다.

---

## 부록 A: 한눈에 보는 실행 체크리스트

### 켜야 하는 것

| 대상 | 실행 방법 | 포트 | 필수 여부 |
|------|----------|------|-----------|
| **MySQL** | `docker compose -f docker-compose-local.yml up -d mysql` | 3306 | ✅ 필수 |
| **Kafka** | `docker compose -f docker-compose-local.yml up -d kafka` | 9092 | ✅ 필수 |
| **commerce 서버** | IntelliJ: `CommerceApplication` (profile: `local`) | 8081 | ✅ 필수 |
| **payments 서버** | IntelliJ: `PaymentsApplication` (profile: `local`) | 8085 | ✅ 필수 |
| Eureka | `EurekaServerApplication` | 8761 | ❌ 불필요 (Feign이 URL 직접 호출) |
| Gateway | `GatewayApplication` | 8000 | ❌ 불필요 |
| MariaDB | Docker | 3307 | ❌ 불필요 (User 모듈용) |
| Redis | Docker | 6379 | ❌ 불필요 |
| Elasticsearch | Docker | 9200 | ❌ 불필요 |

### 실행 순서

```
Step 0. Docker 인프라 실행
  docker compose -f docker-compose-local.yml up -d mysql kafka

Step 1. 시드 데이터 생성 (순서 지킬 것!)
  ① commerce 모듈: CommerceSeedDataGenerator.generateSettlementTestData()
  ② payments 모듈: PaymentsSeedDataGenerator.generateSellerDeposits()

Step 2. 애플리케이션 서버 실행
  ① commerce: CommerceApplication (profile: local)
  ② payments: PaymentsApplication (profile: local)

Step 3. 배치 실행 & 성능 측정
  방법 A) 배치 트리거 REST API 호출
  방법 B) SettlementBatchPerformanceTest 실행
  방법 C) SettleJobScheduler 스케줄러가 cron에 따라 자동 실행될 때까지 대기

Step 4. 결과 확인
  - StepExecution 로그: [PERF] Read/Write/Duration
  - PerfMeasurementChunkListener 로그: [PERF-FINAL] Read/Process/Write 비중
  - MySQL slow query log
  - Kafka consumer lag
```

### 새로 추가된 파일

```
commerce/
├── build.gradle                                    # ← datafaker 의존성 추가됨
└── src/test/java/io/devground/dbay/support/
    └── CommerceSeedDataGenerator.java              # ← 신규: Order + OrderItem 시드 생성

payments/
└── src/test/java/io/devground/payments/support/
    ├── PaymentsSeedDataGenerator.java              # ← 신규: 판매자 Deposit 계좌 시드 생성
    └── PerfMeasurementChunkListener.java           # ← 문서 3.1절 참고 (성능 리스너)
```

### 시드 데이터 요약

| 생성기 | 모듈 | 생성 데이터 | 수량 |
|--------|------|-----------|------|
| `CommerceSeedDataGenerator` | commerce | `OrderEntity` + `OrderItemEntity` (DELIVERED, updatedAt 2주 전) | 10,000건 |
| `PaymentsSeedDataGenerator` | payments | `DepositEntity` (sellerCode = SELLER-0 ~ SELLER-999) | 1,000건 |

> ⚠️ sellerCode 가 두 생성기에서 동일해야 Step 2 입금 처리가 정상 완료된다.  
> `SELLERS_COUNT` 상수를 맞춰서 실행하면 된다 (기본값: commerce 1,000 / payments 1,000).

---

## 부록 B: 프로젝트 배치 관련 파일 구조

```
payments/
├── src/main/java/io/devground/payments/settlement/
│   ├── batch/
│   │   ├── config/
│   │   │   ├── SettlementJobConfiguration.java    # Job 정의 (2 Step 체인)
│   │   │   └── SettlementStepConfiguration.java   # Step 정의 (Reader/Processor/Writer)
│   │   ├── reader/
│   │   │   ├── UnsettledOrderItemReader.java      # Step1 Reader (Feign 호출)
│   │   │   └── SettlementDepositReader.java       # Step2 Reader (DB 조회)
│   │   ├── processor/
│   │   │   ├── SettleConvertProcessor.java        # Step1 Processor (DTO→Entity)
│   │   │   └── SettlementDepositProcessor.java    # Step2 Processor (Entity→Command)
│   │   ├── writer/
│   │   │   ├── SettlementDataWriter.java          # Step1 Writer (DB 저장)
│   │   │   └── SettlementDepositWriter.java       # Step2 Writer (Kafka Saga 시작)
│   │   └── listener/
│   │       └── SettlementStepListener.java        # Skip/Retry 리스너
│   ├── scheduler/
│   │   └── SettlementJobScheduler.java            # @Scheduled 배치 트리거
│   ├── saga/
│   │   └── SettlementSagaOrchestrator.java        # Saga 패턴 Orchestrator
│   ├── service/handler/
│   │   └── SettlementEventHandler.java            # Kafka 이벤트 소비자
│   └── client/
│       └── OrderFeignClient.java                  # Commerce 서버 Feign 인터페이스
│
├── src/test/java/.../settlement/batch/
│   └── SettlementStepPerformanceTest.java         # 기존 Step 성능 테스트
│
├── src/test/java/.../support/                     # ← 신규 추가
│   ├── PaymentsSeedDataGenerator.java
│   └── PerfMeasurementChunkListener.java
│
└── src/main/resources/
    ├── application.yml           # Docker 환경 (host: mysql, kafka:9090)
    ├── application-local.yml     # 로컬 IntelliJ (host: localhost, kafka:9092)
    └── application-test.yml      # H2 인메모리 테스트

commerce/
├── build.gradle                  # ← datafaker 의존성 추가
│
├── src/test/java/.../support/    # ← 신규 추가
│   └── CommerceSeedDataGenerator.java
│
└── src/main/resources/
    ├── application.yml           # Docker 환경
    └── application-local.yml     # 로컬 IntelliJ (MySQL localhost:3306)
```
