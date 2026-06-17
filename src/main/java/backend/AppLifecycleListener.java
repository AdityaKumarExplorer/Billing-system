package backend;

import database.DBConnection;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.util.logging.Logger;

/**
 * Listens to web application start and stop lifecycle events
 * to manage application-wide resources like database connection pools.
 */
@WebListener
public class AppLifecycleListener implements ServletContextListener {

    private static final Logger LOGGER = Logger.getLogger(AppLifecycleListener.class.getName());

    /**
     * Triggered automatically upon application startup. Logs initialization status.
     */
    @Override
    public void contextInitialized(ServletContextEvent event) {
        LOGGER.info("BillDesk application started. Connection pool will initialise on first use.");
    }

    /**
     * Triggered automatically before the application shuts down or redeploys.
     * Environment resource connections are explicitly closed here to prevent memory leaks.
     */
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        LOGGER.info("BillDesk application stopping — shutting down connection pool...");
        DBConnection.shutdown();
        LOGGER.info("Connection pool shut down. Goodbye.");
    }
}