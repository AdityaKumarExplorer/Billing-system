-- ═══════════════════════════════════════════════════════════════════
--  BillDesk — MySQL Schema
--  File: src/main/resources/schema.sql
--
--  Run this ONCE to set up your database.
--  Command:  mysql -u root -p < schema.sql
-- ═══════════════════════════════════════════════════════════════════

-- ─── 1. CREATE & SELECT DATABASE ────────────────────────────────────
CREATE DATABASE IF NOT EXISTS billdesk
  CHARACTER SET utf8mb4        -- supports all Unicode characters
  COLLATE utf8mb4_unicode_ci;  -- case-insensitive, accent-aware sorting

USE billdesk;

-- ─── 2. PRODUCTS TABLE ──────────────────────────────────────────────
--  Stores the master product catalog.
--  ProductDAO.java reads from this table when a UID is scanned.
-- ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS products (
    uid          VARCHAR(20)    NOT NULL,
    name         VARCHAR(150)   NOT NULL,
    base_price   DECIMAL(10, 2) NOT NULL CHECK (base_price >= 0),
    tax_rate     DECIMAL(5, 2)  NOT NULL DEFAULT 0.00
                                CHECK (tax_rate >= 0 AND tax_rate <= 100),
    is_active    TINYINT(1)     NOT NULL DEFAULT 1,  -- soft-delete flag
    created_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (uid)
);

-- ─── 3. TRANSACTIONS TABLE ──────────────────────────────────────────
--  One row per completed checkout session.
--  TransactionDAO.java inserts here first, then uses the
--  auto-generated id to link transaction_items.
-- ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS transactions (
    id              INT            NOT NULL AUTO_INCREMENT,
    date_time       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- ════════════ ADDED CUSTOMER CONTEXT FIELDS ════════════
    customer_name   VARCHAR(150)   NULL,  -- Stores customer's full name
    customer_email  VARCHAR(255)   NULL,  -- Stores customer's email address
    -- ════════════════════════════════════════════════════════
    
    subtotal        DECIMAL(12, 2) NOT NULL,  -- sum of (basePrice × qty)
    discount_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,  -- total discounts
    tax_amount      DECIMAL(12, 2) NOT NULL DEFAULT 0.00,  -- total taxes
    grand_total     DECIMAL(12, 2) NOT NULL,  -- what the customer actually pays

    PRIMARY KEY (id)
);

-- ─── 4. TRANSACTION ITEMS TABLE ─────────────────────────────────────
--  One row per product line in a transaction.
--  This is the "child" table; transactions is the "parent".
-- ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS transaction_items (
    id                INT            NOT NULL AUTO_INCREMENT,
    transaction_id    INT            NOT NULL,  -- FK → transactions.id
    product_uid       VARCHAR(20)    NOT NULL,
    product_name      VARCHAR(150)   NOT NULL,  -- snapshot at time of sale
    base_price        DECIMAL(10, 2) NOT NULL,
    quantity          INT            NOT NULL CHECK (quantity > 0),
    tax_rate          DECIMAL(5, 2)  NOT NULL DEFAULT 0.00,
    discount_pct      DECIMAL(5, 2)  NOT NULL DEFAULT 0.00,
    calculated_tax    DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    final_item_price  DECIMAL(10, 2) NOT NULL,  -- total for this line item

    PRIMARY KEY (id),

    CONSTRAINT fk_transaction
        FOREIGN KEY (transaction_id)
        REFERENCES transactions(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
);

-- ─── 5. INDEXES ─────────────────────────────────────────────────────
--  Indexes speed up SELECT queries on columns used in WHERE clauses.
-- ────────────────────────────────────────────────────────────────────

-- Find all items belonging to a transaction (used in receipts)
CREATE INDEX idx_items_transaction
    ON transaction_items(transaction_id);

-- Look up transaction history by date range
CREATE INDEX idx_transactions_date
    ON transactions(date_time);

-- Find all sales of a specific product (useful for reports)
CREATE INDEX idx_items_product
    ON transaction_items(product_uid);

-- ─── 6. SAMPLE DATA ─────────────────────────────────────────────────
--  These match the MOCK_PRODUCTS in script.js exactly.
-- ────────────────────────────────────────────────────────────────────
INSERT INTO products (uid, name, base_price, tax_rate) VALUES
    ('P001', 'Basmati Rice 5kg',    450.00,  5.00),
    ('P002', 'Sunflower Oil 1L',    180.00, 12.00),
    ('P003', 'Atta Flour 10kg',     380.00,  5.00),
    ('P004', 'Toor Dal 1kg',        140.00,  5.00),
    ('P005', 'Coffee Powder 200g',  220.00, 18.00),
    ('P006', 'Green Tea 25 bags',    95.00, 12.00)
ON DUPLICATE KEY UPDATE
    name       = VALUES(name),
    base_price = VALUES(base_price),
    tax_rate   = VALUES(tax_rate);

-- ─── 7. VERIFY ──────────────────────────────────────────────────────
SELECT 'Schema created successfully.' AS status;
SELECT uid, name, base_price, tax_rate FROM products ORDER BY uid;