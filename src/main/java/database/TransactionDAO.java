package database;

// ═══════════════════════════════════════════════════════════════════
//  TransactionDAO.java
//  Path: src/main/java/database/TransactionDAO.java
//
//  This class handles the most important database operation:
//  saving a complete checkout to two tables atomically.
//
//  LEARNING NOTE — What is "Atomically"?
//  ----------------------------------------
//  A checkout involves TWO write operations:
//    1. INSERT into `transactions`       (the header record)
//    2. INSERT into `transaction_items`  (one row per product)
//
//  What if step 2 fails halfway through (power cut, network error,
//  a bug in item #3 of 5)?
//  WITHOUT transactions: You'd have a transactions row with no items.
//  The data is inconsistent and the receipt would be wrong.
//
//  WITH transactions (setAutoCommit(false)):
//  Either ALL inserts succeed and we COMMIT, or ANY failure triggers
//  a ROLLBACK that undoes every insert in that batch. The database
//  stays consistent no matter what goes wrong.
//
//  This is the ACID guarantee:
//  A = Atomicity  (all or nothing)
//  C = Consistency (data is always valid)
//  I = Isolation  (concurrent transactions don't interfere)
//  D = Durability (committed data survives crashes)
// ═══════════════════════════════════════════════════════════════════

import java.sql.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TransactionDAO {

    private static final Logger LOGGER = Logger.getLogger(TransactionDAO.class.getName());

    // ─── SQL: Insert master transaction row ──────────────────────────
    // Modified to fully support updated schema tracking properties
    private static final String SQL_INSERT_TRANSACTION =
        "INSERT INTO transactions (date_time, customer_name, customer_email, subtotal, discount_amount, tax_amount, grand_total) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?)";

    // ─── SQL: Insert one line item ────────────────────────────────────
    private static final String SQL_INSERT_ITEM =
        "INSERT INTO transaction_items " +
        "(transaction_id, product_uid, product_name, base_price, quantity, " +
        " tax_rate, discount_pct, calculated_tax, final_item_price) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    // ─── saveTransaction ─────────────────────────────────────────────
    /**
     * Persist a complete checkout to the database.
     *
     * This method is the heart of the billing system. It:
     * 1. Turns off autocommit → begins a manual transaction
     * 2. Inserts the transaction header row
     * 3. Captures the AUTO_INCREMENT id MySQL generated
     * 4. Uses that id to insert each line item
     * 5. COMMITs if everything succeeded
     * 6. ROLLBACKs if ANYTHING failed
     *
     * @param transactionData  Map with keys: subtotal, discount, taxAmount, grandTotal
     * @param items            List of Maps, each with keys:
     * productUid, productName, basePrice, quantity,
     * taxRate, discountPct, calculatedTax, finalItemPrice
     * @return true if saved successfully, false otherwise
     */
    public boolean saveTransaction(
            Map<String, Object> transactionData,
            List<Map<String, Object>> items) {

        // ── Basic validation ──────────────────────────────────────────
        if (transactionData == null || items == null || items.isEmpty()) {
            LOGGER.warning("saveTransaction called with null or empty data.");
            return false;
        }

        // ── Get a connection from the pool ────────────────────────────
        // We DON'T use try-with-resources here for the Connection,
        // because we need to call rollback() in the catch block.
        // Instead, we close it manually in the finally block.
        Connection conn = null;

        try {
            conn = DBConnection.getConnection();

            // ── STEP 1: Disable autocommit ────────────────────────────
            // By default, each SQL statement is automatically committed.
            // We turn this off so we can group multiple statements into
            // one atomic unit.
            conn.setAutoCommit(false);
            LOGGER.fine("AutoCommit disabled — starting transaction.");

            // ── STEP 2: Insert the master transaction row ─────────────
            // Statement.RETURN_GENERATED_KEYS tells JDBC to give us
            // back the AUTO_INCREMENT id that MySQL assigned.
            long transactionId = insertTransactionHeader(conn, transactionData);
            LOGGER.fine("Inserted transaction header, id = " + transactionId);

            // ── STEP 3: Insert each line item ─────────────────────────
            // We pass transactionId so each item row points back to its parent.
            insertLineItems(conn, transactionId, items);
            LOGGER.fine("Inserted " + items.size() + " line item(s).");

            // ── STEP 4: COMMIT ─────────────────────────────────────────
            // All inserts succeeded. Make the changes permanent.
            conn.commit();
            LOGGER.info("Transaction " + transactionId + " committed successfully.");
            return true;

        } catch (SQLException e) {

            // ── STEP 5: ROLLBACK ──────────────────────────────────────
            // Something went wrong. Undo every insert we've made
            // in this batch so the database stays consistent.
            LOGGER.log(Level.SEVERE, "Transaction failed — rolling back.", e);

            if (conn != null) {
                try {
                    conn.rollback();
                    LOGGER.info("Rollback completed. Database unchanged.");
                } catch (SQLException rollbackEx) {
                    // If even the rollback fails, we log it but can't do much more.
                    LOGGER.log(Level.SEVERE, "Rollback itself failed!", rollbackEx);
                }
            }
            return false;

        } finally {
            // ── STEP 6: Always restore autocommit and close ───────────
            // Restore autocommit=true before returning the connection
            // to the pool. If we don't, the next operation that borrows
            // this connection will unexpectedly run without autocommit.
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();  // returns connection to the pool
                } catch (SQLException e) {
                    LOGGER.log(Level.WARNING, "Error resetting connection state.", e);
                }
            }
        }
    }

    // ─── PRIVATE: insertTransactionHeader ────────────────────────────
    /**
     * Insert one row into `transactions` and return the generated id.
     *
     * LEARNING NOTE — Statement.RETURN_GENERATED_KEYS:
     * MySQL assigns the AUTO_INCREMENT id on the server side.
     * We can't predict it before the insert. After inserting,
     * we call ps.getGeneratedKeys() which returns a ResultSet
     * containing the new id. We then use that id as the foreign key
     * in all the transaction_items inserts.
     */
    private long insertTransactionHeader(
            Connection conn,
            Map<String, Object> data) throws SQLException {

        // The second argument tells JDBC to capture generated keys
        try (PreparedStatement ps = conn.prepareStatement(
                SQL_INSERT_TRANSACTION, Statement.RETURN_GENERATED_KEYS)) {

            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(2, (String) data.get("customerName"));
            ps.setString(3, (String) data.get("customerEmail"));
            ps.setDouble(4, toDouble(data.get("subtotal")));
            ps.setDouble(5, toDouble(data.get("discount")));
            ps.setDouble(6, toDouble(data.get("taxAmount")));
            ps.setDouble(7, toDouble(data.get("grandTotal")));

            int affectedRows = ps.executeUpdate();
            if (affectedRows == 0) {
                throw new SQLException("Inserting transaction failed — no rows affected.");
            }

            // ── Retrieve the AUTO_INCREMENT id ─────────────────────────
            try (ResultSet generatedKeys = ps.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);  // column 1 = the generated id
                } else {
                    throw new SQLException("Inserting transaction failed — no generated key returned.");
                }
            }
        }
    }

    // ─── PRIVATE: insertLineItems ─────────────────────────────────────
    /**
     * Insert all line items for a transaction using a BATCH INSERT.
     *
     * LEARNING NOTE — addBatch() / executeBatch():
     * Instead of sending one INSERT per product to the database server,
     * we collect all of them and send them in one network round-trip.
     * For a cart with 10 items, this is 10× faster than individual inserts.
     *
     * LEARNING NOTE — Why throw on failure?
     * If any single item fails, we throw SQLException up to saveTransaction(),
     * which catches it, calls rollback(), and returns false. The whole
     * batch is undone — no partial data reaches the database.
     */
    private void insertLineItems(
            Connection conn,
            long transactionId,
            List<Map<String, Object>> items) throws SQLException {

        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_ITEM)) {

            for (Map<String, Object> item : items) {

                ps.setLong(1,   transactionId);
                ps.setString(2, (String) item.get("productUid"));
                ps.setString(3, (String) item.get("productName"));
                ps.setDouble(4, toDouble(item.get("basePrice")));
                ps.setInt(5,    toInt(item.get("quantity")));
                ps.setDouble(6, toDouble(item.get("taxRate")));
                ps.setDouble(7, toDouble(item.get("discountPct")));
                ps.setDouble(8, toDouble(item.get("calculatedTax")));
                ps.setDouble(9, toDouble(item.get("finalItemPrice")));

                // Queue this insert in the batch — does NOT execute yet
                ps.addBatch();
            }

            // ── Execute ALL queued inserts in one round-trip ──────────
            int[] results = ps.executeBatch();

            // Check that every insert affected exactly 1 row
            for (int i = 0; i < results.length; i++) {
                if (results[i] == 0) {
                    throw new SQLException(
                        "Batch insert: item at index " + i + " affected 0 rows."
                    );
                }
            }
        }
    }

    // ─── PRIVATE: Type-safe helpers ───────────────────────────────────
    // The Map<String, Object> values come from Gson parsing the JSON
    // payload. Gson deserialises numbers as Double by default.
    // These helpers safely convert Object → double/int.

    private double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) return Double.parseDouble((String) value);
        throw new IllegalArgumentException("Cannot convert to double: " + value);
    }

    private int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) return Integer.parseInt((String) value);
        throw new IllegalArgumentException("Cannot convert to int: " + value);
    }
}