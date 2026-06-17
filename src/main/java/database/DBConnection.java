package database;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

// ═══════════════════════════════════════════════════════════════════
//  DBConnection.java
//  Path: src/main/java/database/DBConnection.java
//
//  PURPOSE: One centralised place to get a database connection.
//  Every DAO (ProductDAO, TransactionDAO) calls DBConnection.getConnection()
//  instead of building its own — this is the Single Responsibility Principle.
//
//  LEARNING NOTE — Why a Connection Pool?
//  ----------------------------------------
//  Naive approach (DON'T DO THIS):
//      Connection c = DriverManager.getConnection(url, user, pass);
//  Problem: Opening a TCP connection + MySQL handshake takes ~50–200ms.
//  With 10 concurrent users, you open 10 new connections per request.
//  Under load, this exhausts MySQL's connection limit and your app crashes.
//
//  Pool approach (what we do):
//  The pool opens N connections at startup and keeps them alive.
//  getConnection() grabs one from the pool (fast, ~1ms).
//  close() on a pooled connection returns it to the pool instead
//  of actually closing it. The next caller reuses it instantly.
// ═══════════════════════════════════════════════════════════════════

import org.apache.commons.dbcp2.BasicDataSource;

public class DBConnection {

    private static final Logger LOGGER = Logger.getLogger(DBConnection.class.getName());

    // ─── Singleton DataSource ────────────────────────────────────────
    // 'static final' means this is created ONCE when the class is first
    // loaded by the JVM, and shared across all requests.
    // This is the Singleton pattern — one pool for the whole application.
    private static final BasicDataSource dataSource;

    // ─── Static Initializer Block ────────────────────────────────────
    // This block runs ONCE when the class is first loaded.
    // If initialisation fails, we throw a RuntimeException to stop
    // the application from starting with a broken DB config.
    static {
        try {
            dataSource = createDataSource();
            LOGGER.info("Database connection pool initialised successfully.");
        } catch (IOException e) {
            // We wrap the checked IOException in an unchecked
            // ExceptionInInitializerError so the JVM surfaces it clearly.
            throw new ExceptionInInitializerError(
                "Failed to load db.properties: " + e.getMessage()
            );
        }
    }

    // Private constructor — nobody should instantiate this utility class.
    // It exists only as a static factory.
    private DBConnection() {}

    // ─── Factory Method ──────────────────────────────────────────────
    private static BasicDataSource createDataSource() throws IOException {

        // Load db.properties from the classpath
        // Maven puts src/main/resources/ on the classpath automatically
        Properties props = new Properties();
        try (InputStream in = DBConnection.class.getResourceAsStream("/db.properties")) {
            if (in == null) {
                throw new IOException("db.properties not found on classpath.");
            }
            props.load(in);
        }

        BasicDataSource ds = new BasicDataSource();

        // ── Core connection settings ──────────────────────────────────
        ds.setUrl(props.getProperty("db.url"));
        ds.setUsername(props.getProperty("db.username"));
        ds.setPassword(props.getProperty("db.password"));

        // ── Register the MySQL JDBC driver explicitly ─────────────────
        // LEARNING NOTE: With modern JDBC (4.0+) and MySQL Connector/J 8+,
        // the driver is auto-registered via ServiceLoader. We still set
        // this explicitly here for clarity and older environments.
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // ── Pool sizing (read from properties) ───────────────────────
        ds.setInitialSize(
            Integer.parseInt(props.getProperty("pool.initialSize", "3"))
        );
        ds.setMaxTotal(
            Integer.parseInt(props.getProperty("pool.maxTotal", "10"))
        );
        ds.setMinIdle(
            Integer.parseInt(props.getProperty("pool.minIdle", "2"))
        );
        ds.setMaxWait(
            Duration.ofMillis(Long.parseLong(props.getProperty("pool.maxWaitMillis", "5000")))
        );

        // ── Health checks ─────────────────────────────────────────────
        // testOnBorrow: validate the connection before handing it out.
        // If a stale connection is found (e.g. MySQL closed it after
        // 8h idle), the pool discards it and gives you a fresh one.
        ds.setTestOnBorrow(true);
        ds.setValidationQuery("SELECT 1");
        ds.setValidationQueryTimeout(Duration.ofSeconds(3));

        return ds;
    }

    // ─── Public API ───────────────────────────────────────────────────
    /**
     * Borrow a Connection from the pool.
     *
     * IMPORTANT: Always call this in a try-with-resources block:
     *
     *   try (Connection conn = DBConnection.getConnection()) {
     *       // use conn
     *   }
     *   // conn.close() is called automatically, returning it to the pool
     *
     * @return a live Connection from the pool
     * @throws SQLException if no connection is available
     */
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Gracefully shut down the pool.
     * Call this from a ServletContextListener when the web app stops.
     * Without this, the JVM may leave MySQL connections open
     * after Tomcat undeploys the application.
     */
    public static void shutdown() {
        try {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                LOGGER.info("Database connection pool shut down cleanly.");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Error shutting down connection pool", e);
        }
    }
}
