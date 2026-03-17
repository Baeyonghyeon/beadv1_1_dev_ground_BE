# index-lab-jpa (PostgreSQL + JPA 인덱스 비교 실습)

이 모듈은 `JPA 쿼리 동일 / 인덱스만 변경` 조건에서  
실행 로그와 실행계획 차이를 확인하기 위한 별도 실습 모듈입니다.

## 핵심 포인트
- H2가 아니라 PostgreSQL(Testcontainers) 기준으로 측정
- 같은 Repository 메서드를 인덱스 전/후로 반복 실행
- `EXPLAIN (ANALYZE, BUFFERS)` 결과를 로그로 출력
- Hibernate SQL + 바인딩 + p6spy 로그로 시간 비교

## 사전 조건
1. Docker 실행 가능 환경
2. JDK 21

## 실행 방법
기본적으로 테스트는 실수 실행 방지를 위해 환경변수 조건이 있습니다.

```bash
RUN_INDEX_LAB=true ./gradlew :index-lab-jpa:test --tests "io.devground.indexlabjpa.SettlementIndexLoggingComparisonTest" --rerun-tasks
```

## 학습 순서 (권장)
1. 테스트를 1회 실행해서 baseline 로그를 확인한다.
2. 로그에서 `withoutIndexAvgMs`, `withIndexAvgMs`를 비교한다.
3. `EXPLAIN without index`와 `EXPLAIN with index` 차이를 본다.
4. 왜 plan이 바뀌었는지(Seq Scan -> Index Scan/Bitmap 등) 정리한다.
5. 인덱스 컬럼 순서를 바꿔서 다시 실행해 본다.

## 주요 파일
- `src/test/java/io/devground/indexlabjpa/SettlementIndexLoggingComparisonTest.java`
- `src/test/java/io/devground/indexlabjpa/domain/LabSettlement.java`
- `src/test/java/io/devground/indexlabjpa/domain/LabSettlementRepository.java`

## 참고
- 컨테이너가 없으면 Testcontainers 설정(`disabledWithoutDocker=true`)에 따라 테스트가 스킵될 수 있습니다.
- 데이터 건수는 테스트 코드 `seedData()`에서 조정 가능합니다.

