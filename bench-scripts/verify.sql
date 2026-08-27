-- 측정 후 검증 쿼리 모음
-- 실행: docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < bench-scripts/verify.sql

SELECT '=== 주문 상태 분포 (PENDING 잔류 = 후처리 미완료) ===' AS '';
SELECT orderStatus, COUNT(*) AS cnt FROM Orders GROUP BY orderStatus;

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

SELECT '=== 잔액 수지 (차감액 = 결제건수 x 5000 이어야 함) ===' AS '';
SELECT (SELECT COUNT(*)*1000000000 FROM DepositEntity WHERE userCode LIKE 'BENCH-%')
       - (SELECT SUM(balance) FROM DepositEntity WHERE userCode LIKE 'BENCH-%') AS deducted,
       (SELECT COUNT(*)*5000 FROM Payment) AS expected;

SELECT '=== 예치금 이력 (이중 차감 탐지) ===' AS '';
SELECT type, COUNT(*) AS cnt FROM DepositHistoryEntity GROUP BY type;
