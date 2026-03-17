SET search_path TO index_lab;

-- users: 3000
INSERT INTO users (user_code, created_at)
SELECT
    'USER-' || LPAD(gs::text, 5, '0'),
    TIMESTAMP '2025-01-01' + ((gs % 365) || ' days')::interval
FROM generate_series(1, 3000) gs;

-- sellers: 1200
INSERT INTO users (user_code, created_at)
SELECT
    'SELLER-' || LPAD(gs::text, 4, '0'),
    TIMESTAMP '2025-01-01' + ((gs % 365) || ' days')::interval
FROM generate_series(1, 1200) gs;

-- orders: 10000
INSERT INTO orders (order_code, buyer_code, order_status, created_at)
SELECT
    'ORDER-' || LPAD(gs::text, 8, '0'),
    'USER-' || LPAD(((gs % 3000) + 1)::text, 5, '0'),
    CASE
        WHEN gs % 10 IN (0, 1) THEN 'CONFIRMED'
        WHEN gs % 10 = 2 THEN 'DELIVERED'
        WHEN gs % 10 = 3 THEN 'CANCELLED'
        ELSE 'PAID'
    END,
    TIMESTAMP '2025-01-01'
      + ((gs % 180) || ' days')::interval
      + ((gs % 24) || ' hours')::interval
FROM generate_series(1, 10000) gs;

-- 3 items per order => 30000 rows
INSERT INTO order_items (
    order_item_code, order_code, seller_code, product_price, delivered_at, settled, created_at
)
SELECT
    'OI-' || LPAD(o.order_id::text, 8, '0') || '-' || li.line_no::text,
    o.order_code,
    'SELLER-' || LPAD((((o.order_id + li.line_no) % 1200) + 1)::text, 4, '0'),
    10000 + ((o.order_id * li.line_no) % 190000),
    CASE
        WHEN o.order_status IN ('DELIVERED', 'CONFIRMED')
            THEN o.created_at + (((o.order_id % 14) + 1) || ' days')::interval
        ELSE NULL
    END,
    CASE
        WHEN o.order_status = 'CONFIRMED' AND (o.order_id + li.line_no) % 3 = 0 THEN TRUE
        ELSE FALSE
    END,
    o.created_at
FROM orders o
CROSS JOIN (VALUES (1), (2), (3)) li(line_no);

-- settlements from settled items
INSERT INTO settlements (
    settlement_code, order_code, order_item_code, seller_code,
    settlement_status, settlement_date, settlement_balance, created_at
)
SELECT
    'ST-' || LPAD(oi.order_item_id::text, 10, '0'),
    oi.order_code,
    oi.order_item_code,
    oi.seller_code,
    CASE
        WHEN oi.order_item_id % 20 = 0 THEN 'SETTLEMENT_FAILED'
        WHEN oi.order_item_id % 3 = 0 THEN 'SETTLEMENT_SUCCESS'
        ELSE 'SETTLEMENT_CREATED'
    END,
    COALESCE(oi.delivered_at, oi.created_at) + INTERVAL '14 days',
    (oi.product_price * 0.95)::BIGINT,
    COALESCE(oi.delivered_at, oi.created_at) + INTERVAL '14 days'
FROM order_items oi
WHERE oi.settled = TRUE;

-- settlement history
INSERT INTO deposit_history (history_code, user_code, type, amount, balance_after, created_at)
SELECT
    'DH-ST-' || LPAD(s.settlement_id::text, 10, '0'),
    s.seller_code,
    'SETTLEMENT',
    s.settlement_balance,
    100000 + ((s.settlement_id * 13) % 700000),
    s.created_at + INTERVAL '1 hour'
FROM settlements s;

-- charge history 10000
INSERT INTO deposit_history (history_code, user_code, type, amount, balance_after, created_at)
SELECT
    'DH-CH-' || LPAD(gs::text, 8, '0'),
    'USER-' || LPAD(((gs % 3000) + 1)::text, 5, '0'),
    'CHARGE',
    5000 + ((gs * 7) % 50000),
    50000 + ((gs * 11) % 200000),
    TIMESTAMP '2025-01-01' + ((gs % 180) || ' days')::interval
FROM generate_series(1, 10000) gs;

-- withdraw history 10000
INSERT INTO deposit_history (history_code, user_code, type, amount, balance_after, created_at)
SELECT
    'DH-WD-' || LPAD(gs::text, 8, '0'),
    'USER-' || LPAD(((gs % 3000) + 1)::text, 5, '0'),
    'WITHDRAW',
    3000 + ((gs * 5) % 30000),
    20000 + ((gs * 9) % 150000),
    TIMESTAMP '2025-01-01'
      + ((gs % 180) || ' days')::interval
      + INTERVAL '12 hours'
FROM generate_series(1, 10000) gs;

ANALYZE;

SELECT 'users' AS table_name, COUNT(*) AS row_count FROM users
UNION ALL
SELECT 'orders', COUNT(*) FROM orders
UNION ALL
SELECT 'order_items', COUNT(*) FROM order_items
UNION ALL
SELECT 'settlements', COUNT(*) FROM settlements
UNION ALL
SELECT 'deposit_history', COUNT(*) FROM deposit_history;

