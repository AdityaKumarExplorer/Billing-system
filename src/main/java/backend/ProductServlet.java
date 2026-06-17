package backend;

import com.google.gson.Gson;
import database.ProductDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles looking up products based on their unique item identification code (UID).
 */
@WebServlet(name = "ProductServlet", urlPatterns = "/get-product")
public class ProductServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(ProductServlet.class.getName());

    private final Gson gson = new Gson();
    private final ProductDAO productDAO = new ProductDAO();

    /**
     * Responds to GET requests by fetching the relevant product parameters from MySQL via the ProductDAO layer.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        String uid = request.getParameter("uid");

        if (uid == null || uid.trim().isEmpty()) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing required parameter: 'uid'");
            return;
        }

        try {
            Map<String, Object> product = productDAO.findByUid(uid.trim());

            if (product == null) {
                sendError(response, HttpServletResponse.SC_NOT_FOUND, "Product not found: " + uid);
                return;
            }

            sendJson(response, HttpServletResponse.SC_OK, product);

        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Error in ProductServlet.doGet for uid: " + uid, e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error. Please try again.");
        }
    }

    /**
     * Formats and writes structural JSON data blocks out to the client channel stream wrapper.
     */
    private void sendJson(HttpServletResponse response, int statusCode, Object data)
            throws IOException {
        response.setStatus(statusCode);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");

        try (PrintWriter writer = response.getWriter()) {
            writer.print(gson.toJson(data));
        }
    }

    /**
     * Formats structured error parameter maps as JSON objects out to the client stream.
     */
    private void sendError(HttpServletResponse response, int statusCode, String message)
            throws IOException {
        Map<String, String> errorBody = new HashMap<>();
        errorBody.put("error", message);
        sendJson(response, statusCode, errorBody);
    }
}