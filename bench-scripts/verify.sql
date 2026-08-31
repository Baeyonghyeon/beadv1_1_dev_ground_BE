-- 측정 후 검증 쿼리 모음
-- 실행: docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < bench-scripts/verify.sql
--
-- ⚠️ PENDING 과 PAYMENT_PENDING 은 뜻이 다르다.
--      PENDING         = 결제 완료, 후처리 대기        (동기 결제 경로)
--      PAYMENT_PENDING = 결제 미확정, 돈이 빠졌는지 모름 (비동기 결제 경로)
--    drain 후 PAYMENT_PENDING 이 남으면 결제 커맨드나 결과 이벤트가 유실된 것이다 — 회차 무효.

SELECT '=== 주문 상태 분포 ===' AS '';
SELECT orderStatus, COUNT(*) AS cnt FROM Orders GROUP BY orderStatus;

SELECT '=== drain 잔류 (둘 다 0이어야 정상) ===' AS '';
SELECT (SELECT COUNT(*) FROM Orders WHERE orderStatus='PENDING')         AS postprocess_pending,
       (SELECT COUNT(*) FROM Orders WHERE orderStatus='PAYMENT_PENDING') AS payment_unsettled;

SELECT '=== 완결 시간 분포 (createdAt -> PAID updatedAt) ===' AS '';
SELECT COUNT(*) AS paid_cnt,
       ROUND(AVG(TIMESTAMPDIFF(MICROSECOND, createdAt, updatedAt))/1000, 1) AS avg_ms,
       ROUND(MAX(TIMESTAMPDIFF(MICROSECOND, createdAt, updatedAt))/1000, 1) AS max_ms
FROM Orders WHERE orderStatus = 'PAID';

SELECT '=== 완결 시간 p95 ===' AS '';
SELECT ROUND(MAX(ms),1) AS p95_ms FROM (
  SELECT TIMESTAMPDIFF(MICROSECOND, createdAt, updatedAt)/1000 AS ms,
         PERCENT_RANK() OVER (ORDER BY TIMESTAMPDIFF(MICROSECOND, createdAt, updatedAt)) AS pr
  FROM Orders WHERE orderStatus='PAID'
) t WHERE pr <= 0.95;

SELECT '=== 결제 정합성 ===' AS '';
SELECT paymentStatus, COUNT(*) AS cnt, SUM(amount) AS sum_amount FROM Payment GROUP BY paymentStatus;

SELECT '=== 결제 실패는 차감이 없어야 한다 ===' AS '';
SELECT (SELECT COUNT(*) FROM Orders WHERE orderStatus='PAYMENT_FAILED') AS payment_failed_orders,
       (SELECT COUNT(*) FROM Payment p JOIN Orders o ON o.code = p.orderCode
         WHERE o.orderStatus='PAYMENT_FAILED')                          AS should_be_zero;

SELECT '=== 멱등키: orderCode 중복 결제가 없어야 한다 ===' AS '';
SELECT COUNT(*) AS duplicate_order_payments FROM (
  SELECT orderCode FROM Payment WHERE orderCode IS NOT NULL
  GROUP BY orderCode HAVING COUNT(*) > 1
) t;

SELECT '=== 잔액 수지 (차감액 = 결제건수 x 5000 이어야 함) ===' AS '';
SELECT (SELECT COUNT(*)*1000000000 FROM DepositEntity WHERE userCode LIKE 'BENCH-%')
       - (SELECT SUM(balance) FROM DepositEntity WHERE userCode LIKE 'BENCH-%') AS deducted,
       (SELECT COUNT(*)*5000 FROM Payment) AS expected;

SELECT '=== 예치금 이력 (이중 차감 탐지) ===' AS '';
SELECT type, COUNT(*) AS cnt FROM DepositHistoryEntity GROUP BY type;
