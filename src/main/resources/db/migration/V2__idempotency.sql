CREATE TABLE idempotency_keys (
    key VARCHAR(100) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    order_id BIGINT REFERENCES orders(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
