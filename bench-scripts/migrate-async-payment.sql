-- ============================================================================
-- 결제 비동기화 스키마 마이그레이션
--
-- ⚠️ 이 프로젝트는 Flyway/Liquibase 없이 `ddl-auto: update` 로 스키마를 맞춘다.
--    그런데 update 는 **기존 컬럼의 정의를 바꾸지 못한다.** 특히:
--      · MySQL ENUM 컬럼에 값을 추가하지 못한다  → 새 주문 상태 INSERT 가 실패한다
--      · 기존 컬럼에 unique 제약을 붙이지 못한다  → 멱등키가 동작하지 않는다
--    그래서 앱 기동 전에 이 스크립트를 한 번 실행해야 한다.
--
-- 실행:
--   docker exec -i bench-mysql mysql -umysqlId -pmysqlPwd dbay_db < bench-scripts/migrate-async-payment.sql
--
-- 관련 문서: claude-결제-비동기화-구현.md
-- ============================================================================

-- ── ① 주문 상태에 PAYMENT_PENDING / PAYMENT_FAILED 추가 ─────────────────────
-- 증상: Data truncated for column 'orderStatus' at row 1
ALTER TABLE Orders
    MODIFY COLUMN orderStatus ENUM(
        'ALL','CANCELLED','CONFIRMED','DELIVERED','PAID','PENDING','START_DELIVERY',
        'PAYMENT_PENDING','PAYMENT_FAILED'
    );

-- ── ② 결제 멱등키: Payment.orderCode 에 unique 제약 ─────────────────────────
-- 중복이 있으면 아래 ALTER 가 실패한다. 먼저 확인할 것:
--   SELECT orderCode, COUNT(*) FROM Payment GROUP BY orderCode HAVING COUNT(*) > 1;
--
-- NULL 은 여러 행 허용된다(Toss 충전 등 orderCode 없는 결제가 있을 수 있음).
ALTER TABLE Payment
    ADD UNIQUE INDEX uk_payment_order_code (orderCode);

-- ── 확인 ────────────────────────────────────────────────────────────────────
SELECT COLUMN_TYPE AS 'Orders.orderStatus'
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Orders' AND COLUMN_NAME = 'orderStatus';

SELECT INDEX_NAME AS 'Payment.orderCode index', NON_UNIQUE
  FROM INFORMATION_SCHEMA.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Payment' AND COLUMN_NAME = 'orderCode';
