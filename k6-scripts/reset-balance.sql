-- reset-balance.sql — 각 테스트(버전1/버전2) 시작 전 USER 계좌 잔액 100만원으로 리셋
-- deepseek-kafka-ab-test.md 단계 3
--
-- 실행:
--   docker exec -i mysql mysql -umysqlId -pmysqlPwd dbay_db < k6-scripts/reset-balance.sql
--   docker exec mysql mysql -umysqlId -pmysqlPwd dbay_db -e \
--     "SELECT SUM(balance) AS total FROM DepositEntity WHERE userCode LIKE 'USER-%';"
--   → 10,000,000,000 (100억) 이 나와야 정상

UPDATE DepositEntity
SET balance = 1000000, updatedAt = NOW(6)
WHERE userCode LIKE 'USER-%';
