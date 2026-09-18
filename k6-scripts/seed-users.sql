-- seed-users.sql — A/B 부하 테스트 시드: USER-0 ~ USER-9999 계좌 생성 + 잔액 100만원
-- deepseek-kafka-ab-test.md 단계 0
-- ⚠️ 테이블명/컬럼명은 PhysicalNamingStrategyStandardImpl(application.yml) 기준 camelCase
--
-- 실행:
--   docker exec -i mysql mysql -umysqlId -pmysqlPwd dbay_db < k6-scripts/seed-users.sql
--   docker exec mysql mysql -umysqlId -pmysqlPwd dbay_db -e "SELECT COUNT(*) FROM DepositEntity WHERE userCode LIKE 'USER-%';"

INSERT IGNORE INTO DepositEntity (code, userCode, balance, createdAt, updatedAt)
SELECT UUID(), CONCAT('USER-', nums.n), 1000000, NOW(6), NOW(6)
FROM (
  SELECT ones.n + tens.n*10 + hundreds.n*100 + thousands.n*1000 AS n
  FROM (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) ones
  CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
              UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) tens
  CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
              UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) hundreds
  CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
              UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) thousands
  LIMIT 10000
) nums;
