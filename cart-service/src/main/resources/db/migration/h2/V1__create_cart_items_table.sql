-- Create cart_items table: one row per user and product (SKU) in a cart
CREATE TABLE IF NOT EXISTS cart_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- The "sub" claim of the login token (the id in user-service). There is no foreign key: the users live in another service.
    user_id BIGINT NOT NULL,
    sku_code VARCHAR(50) NOT NULL,
    quantity INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    CONSTRAINT uq_cart_items_user_sku UNIQUE (user_id, sku_code),
    CONSTRAINT chk_cart_items_quantity CHECK (quantity BETWEEN 1 AND 99)
);

-- Note: the cart stores only the SKU and the quantity. Name and price are looked up in
-- product-service every time the cart is shown, so a price change shows up at once.
-- The unique key (user_id, sku_code) also serves the lookup of all items of one user.
-- updated_at is maintained by Hibernate's @UpdateTimestamp on the CartItem entity,
-- not a DB-level trigger (same as the other services).
