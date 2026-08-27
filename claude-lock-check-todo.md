# 비관적 락 — 확인할 내용 메모

> 작성일: 2026-08-27
> 아직 소스를 다 안 봐서, 락 관련해서 추가로 확인해야 할 지점만 메모.
> 결론 아님 — 확인 후 갱신할 것.

---

## 1. `findByUserCode` (락 없는 조회) 사용처 전수 확인

- 위치: `DepositJpaRepository.java:19`
- 확인할 것: 이 메서드를 호출하는 모든 곳을 찾아서, **읽고 나서 쓰는 경로**(read-then-write)에
  쓰이는지 아니면 순수 조회(잔액 표시 등)에만 쓰이는지 구분
- 의심 지점:
  - `DepositPersistenceAdapter`
  - `DepositController` (잔액 조회 API)
  - `DepositHistoryRecorder` — `join`/`after-commit` 양쪽 다 `getDepositByUserCode` 사용 중이었음.
    이게 락 없이 조회 후 아무것도 안 쓰는지 재확인
- 왜 중요한가: 여기가 lost update 의 두 번째 구멍일 수 있음

## 2. `DepositEventApplication` 의 네 번째 메서드 (116번 줄)

- 락 사용 메서드가 하나 더 있는데 내용을 안 봄 (아마 삭제(delete) 관련)
- 실제로 락이 필요한 연산인지, 트랜잭션 경계는 어떤지 확인

## 3. `Deposit` 도메인 객체의 잔액 검증 로직 위치

- 잔액 부족 체크가 도메인 객체(`Deposit.withdraw()`) 안에 있는지, 서비스 레이어에 있는지 확인 안 함
- `PaymentServiceImpl` 은 서비스 레이어에서 별도로 `if (deposit.getBalance() < amount)` 체크했는데,
  `DepositEventApplication.withdraw()` 는 이 체크 없이 바로 `deposit.withdraw(amount)` 호출
  → 도메인 객체 내부에서 체크하는지 확인 필요

## 4. `@Version` 낙관적 락 병행 여부

- 커밋 메시지가 "PESSIMISTIC_WRITE 락 적용"이라고만 하고 `@Version` 은 언급 안 함
- `DepositEntity` 에 `@Version` 필드가 없어 보였음 — 재확인. 비관적 락 단독인지, 낙관적 락과 병행하는지

## 5. `PaymentServiceImpl.pay()` 경로 (Toss 결제)

- `process()` 만 확인했고, `pay()` → `handleDepositPayment()` 경로는 안 봄
- 여기도 예치금을 건드리는지, 건드린다면 락을 쓰는지 확인 필요

## 6. 락 잡은 트랜잭션 안에서 `save()` 누락 여부

- 락을 잡은 트랜잭션 안에서 `save()` 를 누락하면 락만 걸고 실제 반영이 안 될 수 있음
- 5곳(`DepositEventApplication` 3~4곳 + `PaymentServiceImpl`) 전부 `save()` 호출은 확인했으나,
  혹시 빠진 경로 있는지 재점검