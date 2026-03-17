SET search_path TO index_lab;

-- 아래 쿼리를 인덱스 생성 전/후 동일하게 실행해서 비교하세요.

-- Q1: 판매자 정산 목록 (상태 + 날짜 정렬)
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    settlement_code,
    order_code,
    seller_code,
    settlement_status,
    settlement_date,
    settlement_balance
FROM settlements
WHERE seller_code = 'SELLER-0001'
  AND settlement_status = 'SETTLEMENT_CREATED'
ORDER BY settlement_date DESC
LIMIT 50;

-- Q2: 정산 배치 후보 조회 (미정산 + delivered_at 범위 + 정렬)
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    order_item_code,
    order_code,
    seller_code,
    product_price,
    delivered_at
FROM order_items
WHERE settled = FALSE
  AND delivered_at BETWEEN TIMESTAMP '2025-03-01 00:00:00' AND TIMESTAMP '2025-07-31 23:59:59'
ORDER BY delivered_at ASC
LIMIT 500;

-- Q3: 유저 이력 타임라인 (필터 + 정렬)
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    history_code,
    user_code,
    type,
    amount,
    balance_after,
    created_at
FROM deposit_history
WHERE user_code = 'SELLER-0001'
  AND type = 'SETTLEMENT'
  AND created_at >= TIMESTAMP '2025-03-01 00:00:00'
ORDER BY created_at DESC
LIMIT 100;

-- Q4: 정산 + 주문아이템 조인 조회
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    s.order_code,
    s.order_item_code,
    s.seller_code,
    s.settlement_status,
    s.settlement_balance,
    oi.product_price,
    oi.delivered_at
FROM settlements s
JOIN order_items oi ON oi.order_item_code = s.order_item_code
WHERE s.settlement_status = 'SETTLEMENT_CREATED'
  AND oi.delivered_at IS NOT NULL
ORDER BY s.created_at DESC
LIMIT 200;

