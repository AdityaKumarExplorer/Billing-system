# 🗄️ Database Module Architecture (MySQL / Apache DBCP2)

This module encapsulates all persistence operations for the point-of-sale billing engine. It handles schema definitions, relational optimization constraints, parameterized lookups, and multi-statement atomic batch inserts. In keeping with a clean structural separation of concerns, servlets execute queries strictly via the Data Access Object (DAO) layer and never run direct SQL statements.

---

## 🏗️ Files & Component Mapping

- `src/main/resources/schema.sql` — Re-runnable DDL and DML data mapping layers.
- `src/main/resources/db.properties` — Classpath environment initialization properties configuration.
- `src/main/java/database/DBConnection.java` — Shared connection provider running Apache DBCP2 pooling libraries.
- `src/main/java/database/ProductDAO.java` — Parameterized data lookups for the product catalog.
- `src/main/java/database/TransactionDAO.java` — ACID-compliant transaction manager running batch writes.

---

## 🛠️ Schema Provisioning & Setup

The database layer can be initialized or completely refreshed across deployment targets using the execution script:
```bash
mysql -u root -p < src/main/resources/schema.sql

```

*Design Patterns:* The script is built to be safe and re-runnable across development cycles. It leverages `CREATE DATABASE IF NOT EXISTS`, `CREATE TABLE IF NOT EXISTS`, and an `ON DUPLICATE KEY UPDATE` checklist to establish stable mock catalog targets without destroying historical layouts.

---

## 📊 Relational Table Architecture

The normalized relational engine utilizes three foundational core tables:

### 1. `products` (Master Catalog)

Tracks active commercial items available for purchase.

| Column | Type | Target Role |
| --- | --- | --- |
| `uid` | `VARCHAR(20)` | Primary Key. Direct matching target for terminal barcode scans. |
| `name` | `VARCHAR(150)` | Product label string written out on invoices. |
| `base_price` | `DECIMAL(10,2)` | Base cost before taxes or dynamic discounts are applied. |
| `tax_rate` | `DECIMAL(5,2)` | Associated item tax parameter (e.g., GST percentage format). |
| `is_active` | `TINYINT(1)` | Soft delete tracker column (`1` = Active, `0` = Archived). |
| `created_at` | `TIMESTAMP` | Record generation timestamp. |

*Query Isolation:* `ProductDAO` limits parsing routines to available item sets where `is_active = 1`.

### 2. `transactions` (Invoice Headers)

Captures high-level operational variables for every processed invoice check.

| Column | Type | Target Role |
| --- | --- | --- |
| `id` | `INT` | Auto-increment Primary Key used as individual transaction references. |
| `date_time` | `TIMESTAMP` | Exact checkout confirmation moment. |
| `customer_name` | `VARCHAR(100)` | Captured target identifier (optional customer name). |
| `customer_email` | `VARCHAR(150)` | Delivery coordinates used by secondary async mailing services. |
| `subtotal` | `DECIMAL(10,2)` | Consolidated item cost baseline before adding additions or modifiers. |
| `discount_amount` | `DECIMAL(10,2)` | Total markdown aggregate deducted from the raw base sum. |
| `tax_amount` | `DECIMAL(10,2)` | Consolidated transactional tax fees derived across specific lines. |
| `grand_total` | `DECIMAL(10,2)` | Final settlement collection metric representing true checkout value. |

### 3. `transaction_items` (Invoice Line Elements)

Maps explicit quantity line items bound across individual receipt records.

| Column | Type | Target Role |
| --- | --- | --- |
| `transaction_id` | `INT` | Foreign Key reference linking back to `transactions.id`. |
| `product_uid` | `VARCHAR(20)` | Tracking SKU representing the base item state. |
| `product_name` | `VARCHAR(150)` | **Snapshot Field:** Retains item name at the exact point of purchase. |
| `base_price` | `DECIMAL(10,2)` | **Snapshot Field:** Retains the static base price at point of purchase. |
| `quantity` | `INT` | Number of distinct identical structural items purchased. |
| `tax_rate` | `DECIMAL(5,2)` | **Snapshot Field:** Retains tax modifiers assigned during transaction saves. |
| `discount_pct` | `DECIMAL(5,2)` | Specific reduction criteria attached across item rows. |
| `calculated_tax` | `DECIMAL(10,2)` | Net absolute tax pricing generated exclusively by this row line item. |
| `final_item_price` | `DECIMAL(10,2)` | Aggregated line totals encompassing items, taxes, and reductions. |

*Data Integrity Rule:* Product names, prices, and taxes are intentionally duplicated out into snapshot table attributes. This guarantees old historical records remain completely static and legally accurate, even if catalog items are updated or modified later.

---

## 🎯 Optimization Tracking Boundaries (Indexes)

To keep performance fast under heavy transaction volume, the structural schema enforces explicit indexing tracking boundaries:

```sql
CREATE INDEX idx_items_transaction ON transaction_items(transaction_id);
CREATE INDEX idx_transactions_date ON transactions(date_time);
CREATE INDEX idx_items_product ON transaction_items(product_uid);

```

* **`idx_items_transaction`:** Binds a tight tracking boundary in the B-Tree array to rapidly isolate matching items for a single receipt layout.
* **`idx_transactions_date`:** Restricts range scans strictly within specified temporal boundaries for rapid daily business audits.
* **`idx_items_product`:** Limits analytics lookups to specific product codes without triggering full table searches.

---

## ⚙️ Resource Pool Configuration (`db.properties`)

Properties are loaded directly out of the application classpath framework using a dedicated un-tracked environment file format:

```properties
db.url=jdbc:mysql://localhost:3306/billdesk?useSSL=false&serverTimezone=Asia/Kolkata&allowPublicKeyRetrieval=true
db.username=root
db.password=your_password_here

pool.initialSize=3
pool.maxTotal=10
pool.minIdle=2
pool.maxWaitMillis=5000

```

*Security Guidelines:* Production infrastructure credentials should never be committed into public code repositories. Maintain this template configuration layer for local staging environments or use CI/CD runners to substitute production-ready credentials upon compilation tasks.

---

## 🔄 Resource Connection Abstraction (`DBConnection.java`)

`DBConnection` initializes and manages exactly one static instantiation of an Apache Commons DBCP2 `BasicDataSource` object.

### Operational Mechanics

* **Single-Stage Lifecycle:** The database connection socket pool is established exactly once when the host class is loaded into active memory.
* **Socket Allocation:** calling `getConnection()` safely pulls an active, pre-authenticated socket resource directly from the shared pool.
* **Recycling Handles:** Executing `.close()` on a borrowed reference intercepts the connection framework and returns the socket to the pool instead of breaking the connection down.
* **Modern Timeout Architecture:** The framework avoids deprecated integer-based configuration utilities, leveraging Java 8 time abstractions instead:
```java
ds.setMaxWait(Duration.ofMillis(Integer.parseInt(props.getProperty("pool.maxWaitMillis"))));
ds.setValidationQueryTimeout(Duration.ofSeconds(3));

```


* **Active Validation Routine:** The data source maintains active socket verifications to intercept and drop dead database links before they hit active processing stacks:
```java
ds.setTestOnBorrow(true);
ds.setValidationQuery("SELECT 1");

```



---

## 🔍 Data Access Object Operations

### 1. `ProductDAO.java`

Executes strict catalog verifications. The primary lookup method utilizes a parameterized structure to block application injection attack windows:

```java
public Map<String, Object> findByUid(String uid)

```

* **SQL Implementation:**
```sql
SELECT uid, name, base_price, tax_rate 
FROM products 
WHERE uid = ? AND is_active = 1

```


* **Validation Guard:** The parameter evaluation layer intercepts calls early, short-circuiting empty strings or `null` entries to return `false` before triggering relational database connections.

### 2. `TransactionDAO.java`

Manages the checkout pipeline. It wraps multi-table operations into an isolated transaction context block to maintain strict database reliability:

```java
public boolean saveTransaction(Map<String, Object> transactionData, List<Map<String, Object>> items)

```

```text
       Isolation Pipeline Sequence Flow
─────────────────────────────────────────────────
[Borrow Connection] ──► setAutoCommit(false)
                             │
                             ▼
                 Insert Transaction Header 
                             │
                             ▼
                 Read Generated ID Reference 
                             │
                             ▼
                 Execute Item Batch Processing (addBatch / executeBatch)
                             │
                             ▼
      [Success] ──► commit() ──► Reset Defaults & Close Connection
                             │
     (Catch SQLException) ──► rollback() ──► Return False

```

* **ID Generation Tracking:** The auto-increment keys for checkout targets are captured from database rows via standard tracking markers:
```java
Statement.RETURN_GENERATED_KEYS

```


* **Network Round-Trip Optimization:** Sales item rows are consolidated using internal execution chains (`addBatch()` and `executeBatch()`). This sends data to MySQL in a single action, eliminating individual query slowdowns.

---

## 📦 Deployment Constraints

Since this system functions as a modular WAR architecture, configurations defined under `src/main/resources` are compiled directly onto the primary application classpath platform (`WEB-INF/classes`). The runtime environment expects `db.properties` to exist within this relative boundary to boot properly. The compiled output paths inside `target/` are ignored by version control mechanisms.

```