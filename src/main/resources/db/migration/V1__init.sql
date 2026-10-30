CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    price_cents BIGINT NOT NULL CHECK (price_cents > 0)
);
CREATE TABLE inventory (
    product_id BIGINT PRIMARY KEY REFERENCES products(id),
    available INTEGER NOT NULL CHECK (available >= 0),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id),
    user_id VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status VARCHAR(20) NOT NULL CHECK (status = 'CONFIRMED'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT one_order_per_user_per_product UNIQUE (product_id, user_id)
);
