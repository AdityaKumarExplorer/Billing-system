# Database Module

Files:

- `src/main/resources/schema.sql`
- `src/main/resources/db.properties`
- `src/main/java/database/DBConnection.java`
- `src/main/java/database/ProductDAO.java`
- `src/main/java/database/TransactionDAO.java`

This module owns all MySQL access. Servlets call DAOs; servlets do not write SQL directly.

## Schema Setup

Create or refresh the database with:

```bash
mysql -u root -p < src/main/resources/schema.sql
```

The script is designed to be re-runnable. It uses `CREATE DATABASE IF NOT EXISTS`, `CREATE TABLE IF NOT EXISTS`, and `ON DUPLICATE KEY UPDATE` for sample products.

## Tables

### `products`

Stores the product catalog.

| Column | Purpose |
|--------|---------|
| `uid` | Primary key and scanned product code. |
| `name` | Product display name. |
| `base_price` | Price before discount and tax. |
| `tax_rate` | GST/tax percentage. |
| `is_active` | Soft delete flag. |
| `created_at` | Creation timestamp. |

`ProductDAO` only returns rows where `is_active = 1`.

### `transactions`

Stores one row per completed checkout.

| Column | Purpose |
|--------|---------|
| `id` | Auto-increment primary key. |
| `date_time` | Checkout timestamp. |
| `subtotal` | Sum before discounts and tax. |
| `discount_amount` | Total discount. |
| `tax_amount` | Total tax. |
| `grand_total` | Final payable amount. |

### `transaction_items`

Stores one row per product line in a checkout.

| Column | Purpose |
|--------|---------|
| `transaction_id` | Foreign key to `transactions.id`. |
| `product_uid` | Product UID at sale time. |
| `product_name` | Product name snapshot at sale time. |
| `base_price` | Base price snapshot. |
| `quantity` | Quantity sold. |
| `tax_rate` | Tax rate snapshot. |
| `discount_pct` | Discount percentage used. |
| `calculated_tax` | Tax amount for the line. |
| `final_item_price` | Final line total. |

The product name and price are copied into the sale item so old receipts remain accurate even if the product catalog changes later.

## Indexes

The schema creates:

```sql
CREATE INDEX idx_items_transaction ON transaction_items(transaction_id);
CREATE INDEX idx_transactions_date ON transactions(date_time);
CREATE INDEX idx_items_product ON transaction_items(product_uid);
```

These support transaction lookups, history by date, and product-level reporting.

## `db.properties`

Example:

```properties
db.url=jdbc:mysql://localhost:3306/billdesk?useSSL=false&serverTimezone=Asia/Kolkata&allowPublicKeyRetrieval=true
db.username=root
db.password=your_password_here

pool.initialSize=3
pool.maxTotal=10
pool.minIdle=2
pool.maxWaitMillis=5000
```

The file is loaded from the classpath by `DBConnection`. Maven copies it from `src/main/resources` into `WEB-INF/classes` inside the WAR.

Do not put production credentials in a shared repository. Use this file for local development, or replace it during deployment with environment-specific values.

## DBConnection

`DBConnection` creates one shared Apache Commons DBCP2 `BasicDataSource`.

Important behavior:

- The pool is initialized once when the class loads.
- `getConnection()` borrows a connection from the pool.
- Calling `close()` on a borrowed pooled connection returns it to the pool.
- `shutdown()` closes the pool during webapp shutdown.

The cleaned code uses `Duration` based timeout setters:

```java
ds.setMaxWait(Duration.ofMillis(...));
ds.setValidationQueryTimeout(Duration.ofSeconds(3));
```

This avoids deprecated DBCP APIs.

Health checking:

```java
ds.setTestOnBorrow(true);
ds.setValidationQuery("SELECT 1");
```

This prevents stale MySQL connections from being handed to DAOs.

## ProductDAO

Primary method:

```java
Map<String, Object> findByUid(String uid)
```

It uses a parameterized query:

```sql
SELECT uid, name, base_price, tax_rate
FROM products
WHERE uid = ? AND is_active = 1
```

The `?` placeholder prevents SQL injection. User input is never concatenated into the SQL string.

`exists(String uid)` now returns `false` for null or blank input before touching the database.

## TransactionDAO

Primary method:

```java
boolean saveTransaction(Map<String, Object> transactionData,
                        List<Map<String, Object>> items)
```

The method saves a full checkout atomically:

```text
1. Borrow connection
2. setAutoCommit(false)
3. Insert transaction header
4. Read generated transaction id
5. Batch insert line items
6. commit()
7. Restore autocommit and close connection
```

On any `SQLException`, it calls `rollback()` and returns `false`.

Generated keys are retrieved with:

```java
Statement.RETURN_GENERATED_KEYS
```

Line items are inserted with `addBatch()` and `executeBatch()` to avoid one network round trip per item.

## Deployment Note

Because this is a WAR application, `src/main/resources` files are packaged into the application classpath. The application expects `db.properties` to be available there at runtime.

The generated `target/` directory is build output and is ignored by Git.
