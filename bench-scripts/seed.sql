-- 부하 테스트 시드: BENCH-0 ~ BENCH-199 (유저 200명)
--   · 예치금 계정 + 잔액 10억  (테스트 중 고갈 방지)
--   · 유저당 장바구니 1개 + 상품 1개
--     ⚠️ 장바구니가 없으면 sync Arm 이 CART_NOT_FOUND 로 주문 자체가 실패한다.
--
-- 실행: docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < bench-scripts/seed.sql

INSERT IGNORE INTO DepositEntity (code, userCode, balance, deleteStatus, createdAt, updatedAt)
SELECT UUID(), CONCAT('BENCH-', n), 1000000000, 'N', NOW(6), NOW(6)
FROM (
  SELECT a.N + b.N*10 + c.N*100 AS n
  FROM (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
       (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
       (SELECT 0 N UNION SELECT 1) c
) t WHERE n < 200;

INSERT IGNORE INTO cart (code, userCode, deleteStatus, createdAt, updatedAt)
SELECT UUID(), d.userCode, 'N', NOW(6), NOW(6)
FROM DepositEntity d
WHERE d.userCode LIKE 'BENCH-%'
  AND d.userCode NOT IN (SELECT userCode FROM cart);

DELETE FROM cartItem;
INSERT INTO cartItem (code, productCode, cartId, deleteStatus, createdAt, updatedAt)
SELECT UUID(), CONCAT('PROD-', SUBSTRING(c.userCode, 7)), c.id, 'N', NOW(6), NOW(6)
FROM cart c WHERE c.userCode LIKE 'BENCH-%';

SELECT (SELECT COUNT(*) FROM DepositEntity WHERE userCode LIKE 'BENCH-%') AS deposits,
       (SELECT COUNT(*) FROM cart)     AS carts,
       (SELECT COUNT(*) FROM cartItem) AS cart_items;
