DROP SCHEMA IF EXISTS index_lab CASCADE;
CREATE SCHEMA index_lab;
SET search_path TO index_lab;

CREATE TABLE users (
    user_id BIGSERIAL PRIMARY KEY,
    user_code VARCHAR(32) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    order_code VARCHAR(40) NOT NULL UNIQUE,
    buyer_code VARCHAR(32) NOT NULL,
    order_status VARCHAR(16) NOT NULL CHECK (
        order_status IN ('PAID', 'DELIVERED', 'CONFIRMED', 'CANCELLED')
    ),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE order_items (
    order_item_id BIGSERIAL PRIMARY KEY,
    order_item_code VARCHAR(48) NOT NULL UNIQUE,
    order_code VARCHAR(40) NOT NULL,
    seller_code VARCHAR(32) NOT NULL,
    product_price BIGINT NOT NULL,
    delivered_at TIMESTAMP NULL,
    settled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE settlements (
    settlement_id BIGSERIAL PRIMARY KEY,
    settlement_code VARCHAR(48) NOT NULL UNIQUE,
    order_code VARCHAR(40) NOT NULL,
    order_item_code VARCHAR(48) NOT NULL,
    seller_code VARCHAR(32) NOT NULL,
    settlement_status VARCHAR(24) NOT NULL CHECK (
        settlement_status IN ('SETTLEMENT_CREATED', 'SETTLEMENT_SUCCESS', 'SETTLEMENT_FAILED')
    ),
    settlement_date TIMESTAMP NOT NULL,
    settlement_balance BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE deposit_history (
    history_id BIGSERIAL PRIMARY KEY,
    history_code VARCHAR(48) NOT NULL UNIQUE,
    user_code VARCHAR(32) NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('CHARGE', 'WITHDRAW', 'SETTLEMENT', 'REFUND')),
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

