SET search_path TO index_lab;

-- Q1 최적화: seller + status 필터 + date 정렬
CREATE INDEX idx_settlements_seller_status_date
ON settlements (seller_code, settlement_status, settlement_date DESC);

-- Q2 최적화: settled + delivered_at 범위 + 정렬
CREATE INDEX idx_order_items_settled_delivered_at
ON order_items (settled, delivered_at, order_item_code);

-- Q3 최적화: user + type + created_at 정렬
CREATE INDEX idx_deposit_history_user_type_created
ON deposit_history (user_code, type, created_at DESC);

-- Q4 최적화: status 필터 + 최신순
CREATE INDEX idx_settlements_status_created
ON settlements (settlement_status, created_at DESC, order_item_code);

-- 조인 보조
CREATE INDEX idx_order_items_code_delivered
ON order_items (order_item_code, delivered_at);

ANALYZE settlements;
ANALYZE order_items;
ANALYZE deposit_history;

