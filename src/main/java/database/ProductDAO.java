package database;

// ═══════════════════════════════════════════════════════════════════
//  ProductDAO.java
//  Path: src/main/java/database/ProductDAO.java
//
//  DAO = Data Access Object.
//  This class is the ONLY place in the codebase that knows how to
//  talk to the `products` table. Servlets call DAO methods;
//  they never write SQL themselves.
//
//  LEARNING NOTE — Why the DAO Pattern?
//  ----------------------------------------
//  If you ever change your database (MySQL → PostgreSQL), or even
//  your storage layer (DB → file → API), you only change the DAO.
//  The Servlet code above it doesn't change at all.
//  This is the Separation of Concerns principle.
// ═══════════════════════════════════════════════════════════════════

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ProductDAO {

    private static final Logger LOGGER = Logger.getLogger(ProductDAO.class.getName());

    // ─── SQL Queries ─────────────────────────────────────────────────
    // LEARNING NOTE: We define SQL as named constants at the top of
    // the class. This makes them easy to find and change.
    // The '?' placeholders are filled in by PreparedStatement.
    // NEVER concatenate user input into SQL — that creates SQL Injection
    // vulnerabilities (e.g. entering  '; DROP TABLE products; --  as a UID).

    private static final String SQL_FIND_BY_UID =
        "SELECT uid, name, base_price, tax_rate " +
        "FROM products " +
        "WHERE uid = ? AND is_active = 1";

    // ─── findByUid ───────────────────────────────────────────────────
    /**
     * Look up a single product by its UID.
     *
     * Returns a Map with keys: uid, name, basePrice, taxRate
     * Returns null if the product does not exist or is inactive.
     *
     * LEARNING NOTE — try-with-resources:
     * Connection, PreparedStatement, and ResultSet all implement
     * AutoCloseable. The try-with-resources block guarantees they are
     * closed even if an exception is thrown mid-execution.
     * Failing to close these causes "Too many open cursors" errors
     * that crash the app after enough requests.
     */
    public Map<String, Object> findByUid(String uid) {

        // Basic validation before hitting the database
        if (uid == null || uid.trim().isEmpty()) {
            return null;
        }

        // Try-with-resources: Connection → PreparedStatement → ResultSet
        // All three are closed automatically when the block exits.
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_FIND_BY_UID)) {

            // Set the '?' placeholder — safe from SQL Injection
            ps.setString(1, uid.trim().toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {

                if (rs.next()) {
                    // rs.next() returns true if a row was found.
                    // We build a Map to return the product data.
                    Map<String, Object> product = new HashMap<>();
                    product.put("uid",       rs.getString("uid"));
                    product.put("name",      rs.getString("name"));
                    product.put("basePrice", rs.getDouble("base_price"));
                    product.put("taxRate",   rs.getDouble("tax_rate"));
                    return product;
                }

                // rs.next() returned false — no product found
                return null;
            }

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error finding product by UID: " + uid, e);
            // We re-throw as an unchecked exception so the Servlet layer
            // can catch it and return a 500 error to the client.
            throw new RuntimeException("Database error looking up product: " + uid, e);
        }
    }

    // ─── exists ──────────────────────────────────────────────────────
    /**
     * Quick check: does this product UID exist and is active?
     * More efficient than findByUid() when you only need a yes/no answer,
     * because COUNT(*) is faster than fetching all columns.
     */
    public boolean exists(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }

        final String sql = "SELECT COUNT(*) FROM products WHERE uid = ? AND is_active = 1";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, uid.trim().toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error checking product existence: " + uid, e);
            throw new RuntimeException("Database error checking product: " + uid, e);
        }
    }
}
