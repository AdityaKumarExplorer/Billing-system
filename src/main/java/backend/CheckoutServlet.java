package backend;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// iText 9 Modern Layout & Image Imports
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;

import database.TransactionDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Dynamic framework-light Checkout Servlet upgraded to iText 9.
 * Dynamically renders business metadata and receipts using explicit Noto Font profiles.
 */
@WebServlet(name = "CheckoutServlet", urlPatterns = "/checkout")
public class CheckoutServlet extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(CheckoutServlet.class.getName());
    private final TransactionDAO transactionDAO = new TransactionDAO();

    // Dynamic Business Config State Variables
    private String storeName;
    private String storeAddress;
    private String storePhone;
    private String storeGstin;
    
    // Path A: Logo Configurations
    private String storeLogoPath;
    private float storeLogoWidth;

    // Dynamic Structural Sizing Variables
    private float receiptWidth;
    private float marginPt;
    private float headerBaseHeight;
    private float itemBaseHeight;
    private float summaryBaseHeight;
    private float footerBaseHeight;
    private float[] columnRatios;

    /**
     * Runs exactly once when Tomcat initializes the servlet context.
     * Pulls config and imagery properties limits from src/main/resources/app.properties
     */
    @Override
    public void init() throws ServletException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("app.properties")) {
            Properties prop = new Properties();
            if (input == null) {
                throw new ServletException("Configuration framework error: Unable to find app.properties inside resources.");
            }
            prop.load(input);

            // Text profile settings
            this.storeName    = prop.getProperty("store.name", "Default Store");
            this.storeAddress = prop.getProperty("store.address", "Store Address Line");
            this.storePhone   = prop.getProperty("store.phone", "+91 00000 00000");
            this.storeGstin   = prop.getProperty("store.gstin", "");
            
            // Path A Variables Init
            String pathVal = prop.getProperty("store.logo.path", "/logo.png");
            this.storeLogoPath  = pathVal.trim().isEmpty() ? "/logo.png" : pathVal.trim();
            this.storeLogoWidth = Float.parseFloat(prop.getProperty("store.logo.width", "50.0"));

            // Layout structural metrics
            this.receiptWidth      = Float.parseFloat(prop.getProperty("receipt.width", "226.0"));
            this.marginPt          = Float.parseFloat(prop.getProperty("receipt.margin", "12.0"));
            this.headerBaseHeight  = Float.parseFloat(prop.getProperty("receipt.height.base.header", "145.0"));
            this.itemBaseHeight    = Float.parseFloat(prop.getProperty("receipt.height.base.item", "28.0"));
            this.summaryBaseHeight = Float.parseFloat(prop.getProperty("receipt.height.base.summary", "100.0"));
            this.footerBaseHeight  = Float.parseFloat(prop.getProperty("receipt.height.base.footer", "40.0"));

            // Parse Table proportions
            float leftRatio  = Float.parseFloat(prop.getProperty("receipt.table.ratio.left", "65.0"));
            float rightRatio = Float.parseFloat(prop.getProperty("receipt.table.ratio.right", "35.0"));
            this.columnRatios = new float[]{leftRatio, rightRatio};

        } catch (Exception ex) {
            throw new ServletException("Failed to load business parameters environment configuration profile: " + ex.getMessage(), ex);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        String jsonBody = readRequestBody(request);

        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Empty request body.");
            return;
        }

        CheckoutPayload payload;
        try {
            payload = parsePayload(jsonBody);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse checkout JSON: " + jsonBody, e);
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON payload: " + e.getMessage());
            return;
        }

        if (payload.items.isEmpty()) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Cart is empty — nothing to checkout.");
            return;
        }

        Map<String, Object> transactionData = new HashMap<>();
        transactionData.put("customerName",      payload.customerName);
        transactionData.put("customerEmail",     payload.customerEmail);
        transactionData.put("subtotal",          payload.subtotal);
        transactionData.put("discount",          payload.discount);
        transactionData.put("taxAmount",         payload.taxAmount);
        transactionData.put("grandTotal",        payload.grandTotal);

        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (ItemPayload item : payload.items) {
            Map<String, Object> m = new HashMap<>();
            m.put("productUid",     item.productUid);
            m.put("productName",    item.productName);
            m.put("basePrice",      item.basePrice);
            m.put("quantity",       item.quantity);
            m.put("taxRate",        item.taxRate);
            m.put("discountPct",    item.discountPct);
            m.put("calculatedTax",  item.calculatedTax);
            m.put("finalItemPrice", item.finalItemPrice);
            itemMaps.add(m);
        }

        boolean saved = transactionDAO.saveTransaction(transactionData, itemMaps);

        if (!saved) {
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to save transaction. Please try again.");
            return;
        }

        LOGGER.info("Transaction saved. Generating receipt PDF using specified Noto font styles.");

        byte[] pdfBytes;
        try {
            pdfBytes = buildPdf(payload);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "PDF generation failed after successful DB save.", e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Sale saved successfully but receipt could not be generated.");
            return;
        }

        if (payload.customerEmail != null && !payload.customerEmail.trim().isEmpty()) {
            String cleanFileName = "Receipt_" + System.currentTimeMillis() + ".pdf";
            EmailService.sendReceiptEmail(
                payload.customerEmail.trim(), 
                payload.customerName, 
                pdfBytes, 
                cleanFileName
            );
        }

        LocalDateTime currentDateTime = LocalDateTime.now();
        DateTimeFormatter fileDateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        String formattedTimestamp = currentDateTime.format(fileDateTimeFormatter);
        
        String uniqueReceiptName = "Receipt-Billing-System " + formattedTimestamp + ".pdf";

        response.setContentType("application/pdf");
        response.setContentLength(pdfBytes.length);
        response.setHeader("Content-Disposition", "inline; filename=\"" + uniqueReceiptName + "\"");
        response.setHeader("Cache-Control", "no-store");

        try (OutputStream out = response.getOutputStream()) {
            out.write(pdfBytes);
            out.flush();
        }
    }
    
    private byte[] buildPdf(CheckoutPayload payload) throws IOException {
        float dynamicHeader = this.headerBaseHeight;
        
        if (payload.customerName != null && !payload.customerName.isEmpty()) {
            dynamicHeader += 12f;
        }
        if (payload.customerEmail != null && !payload.customerEmail.isEmpty()) {
            dynamicHeader += 12f;
        }
        if (this.storeLogoPath != null && !this.storeLogoPath.trim().isEmpty()) {
            dynamicHeader += (this.storeLogoWidth * 0.6f); 
        }
        
        float totalHeight = dynamicHeader + (payload.items.size() * this.itemBaseHeight) + this.summaryBaseHeight + this.footerBaseHeight;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);

        com.itextpdf.kernel.geom.Rectangle sizeLayout = new com.itextpdf.kernel.geom.Rectangle(this.receiptWidth, totalHeight);
        pdfDoc.setDefaultPageSize(new PageSize(sizeLayout));

        Document doc = new Document(pdfDoc);
        doc.setMargins(this.marginPt, this.marginPt, this.marginPt, this.marginPt);

        // Logo Image Generation
        if (this.storeLogoPath != null && !this.storeLogoPath.trim().isEmpty()) {
            String resolvedPath = this.storeLogoPath.trim();
            if (!resolvedPath.startsWith("/")) {
                resolvedPath = "/" + resolvedPath;
            }
            try (InputStream logoStream = getServletContext().getResourceAsStream(resolvedPath)) {
                if (logoStream != null) {
                    byte[] imageBytes = logoStream.readAllBytes();
                    Image receiptLogo = new Image(ImageDataFactory.create(imageBytes))
                        .setHorizontalAlignment(HorizontalAlignment.CENTER)
                        .setMaxWidth(this.storeLogoWidth)
                        .setMarginBottom(6f);
                    doc.add(receiptLogo);
                } else {
                    LOGGER.log(Level.WARNING, "Logo image file missing from webapp container root path: " + resolvedPath);
                }
            } catch (Exception imageEx) {
                LOGGER.log(Level.SEVERE, "Unexpected layout failure processing receipt branding logo elements: " + imageEx.getMessage(), imageEx);
            }
        }

        // Initialize and Load the distinct Noto fonts from resources/fonts/ with identity-H encoding
        PdfFont fontSans;
        PdfFont fontSerif;
        PdfFont fontCondensed;
        try {
            byte[] sansBytes = readResourceBytes("fonts/NotoSans-Regular.ttf");
            byte[] serifBytes = readResourceBytes("fonts/NotoSerif-Regular.ttf");
            byte[] condensedBytes = readResourceBytes("fonts/NotoSans_Condensed-Light.ttf");
            
            fontSans      = PdfFontFactory.createFont(sansBytes, com.itextpdf.io.font.PdfEncodings.IDENTITY_H);
            fontSerif     = PdfFontFactory.createFont(serifBytes, com.itextpdf.io.font.PdfEncodings.IDENTITY_H);
            fontCondensed = PdfFontFactory.createFont(condensedBytes, com.itextpdf.io.font.PdfEncodings.IDENTITY_H);
        } catch (Exception fontEx) {
            LOGGER.log(Level.WARNING, "Noto fonts missing or unreadable in resources/fonts/. Using standard system fallbacks.", fontEx);
            fontSans      = PdfFontFactory.createFont(StandardFonts.HELVETICA);
            fontSerif     = PdfFontFactory.createFont(StandardFonts.TIMES_ROMAN);
            fontCondensed = PdfFontFactory.createFont(StandardFonts.COURIER);
        }

        com.itextpdf.kernel.colors.Color mutedColor = new DeviceRgb(110, 110, 110);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd-MM-yyyy  HH:mm:ss");
        String timestamp = LocalDateTime.now().format(dtf);

        // Header Branding Typography Layout Configuration
        addCenteredParagraph(doc, this.storeName,    fontSerif,     9.5f, ColorConstants.BLACK, 6f);
        addCenteredParagraph(doc, this.storeAddress, fontCondensed, 7.5f, ColorConstants.DARK_GRAY, 2f);
        addCenteredParagraph(doc, "Ph: " + this.storePhone, fontCondensed, 7.5f, ColorConstants.DARK_GRAY, 2f);
        if (this.storeGstin != null && !this.storeGstin.trim().isEmpty()) {
            addCenteredParagraph(doc, "GSTIN: " + this.storeGstin, fontCondensed, 7.5f, ColorConstants.DARK_GRAY, 2f);
        }
        addCenteredParagraph(doc, timestamp, fontCondensed, 7.5f, mutedColor, 4f);
        
        if (payload.customerName != null && !payload.customerName.isEmpty()) {
            addCenteredParagraph(doc, "Customer: " + payload.customerName, fontSans, 7.5f, ColorConstants.DARK_GRAY, 2f);
        }
        if (payload.customerEmail != null && !payload.customerEmail.isEmpty()) {
            addCenteredParagraph(doc, "Email: " + payload.customerEmail, fontSans, 7.5f, ColorConstants.DARK_GRAY, 2f);
        }
        
        addDivider(doc, fontSans);

        // Cart Line Items Listing Table Framework
        Table itemTable = new Table(UnitValue.createPercentArray(this.columnRatios));
        itemTable.useAllAvailableWidth();
        itemTable.setMarginTop(4f);
        itemTable.setMarginBottom(4f);

        for (ItemPayload item : payload.items) {
            String itemLabel = item.quantity + "\u00D7 " + item.productName;
            Cell leftCell = new Cell().add(new Paragraph(itemLabel).setFont(fontSans).setFontSize(7.5f).setFontColor(ColorConstants.BLACK));
            leftCell.setBorder(Border.NO_BORDER);
            leftCell.setPaddingBottom(3f);

            String priceLabel = "Rs. " + String.format("%.2f", item.finalItemPrice);
            Cell rightCell = new Cell().add(new Paragraph(priceLabel).setFont(fontSans).setFontSize(7.5f).setFontColor(ColorConstants.BLACK));
            rightCell.setBorder(Border.NO_BORDER);
            rightCell.setTextAlignment(TextAlignment.RIGHT);
            rightCell.setPaddingBottom(3f);

            if (item.discountPct > 0 || item.taxRate > 0) {
                StringBuilder sub = new StringBuilder();
                sub.append("@ Rs.").append(String.format("%.2f", item.basePrice));
                if (item.discountPct > 0) sub.append("  disc ").append(item.discountPct).append("%");
                if (item.taxRate > 0)     sub.append("  tax ").append(item.taxRate).append("%");

                leftCell = new Cell().setBorder(Border.NO_BORDER).setPaddingBottom(3f);
                Paragraph p = new Paragraph()
                    .add(new Text(itemLabel).setFont(fontSans).setFontSize(7.5f).setFontColor(ColorConstants.BLACK))
                    .add(new Text("\n" + sub.toString()).setFont(fontCondensed).setFontSize(7f).setFontColor(mutedColor));
                leftCell.add(p);
            }

            itemTable.addCell(leftCell);
            itemTable.addCell(rightCell);
        }

        doc.add(itemTable);
        addDivider(doc, fontSans);

        // Core Financial Calculation Summaries Balance Sheet Layout
        Table summaryTable = new Table(UnitValue.createPercentArray(new float[]{55f, 45f}));
        summaryTable.useAllAvailableWidth();
        summaryTable.setMarginTop(4f);

        addSummaryRow(summaryTable, "Subtotal",    "Rs. " + fmt(payload.subtotal),   fontSans, 7.5f, ColorConstants.BLACK);
        addSummaryRow(summaryTable, "Discount",    "- Rs. " + fmt(payload.discount), fontSans, 7.5f, ColorConstants.BLACK);
        addSummaryRow(summaryTable, "Tax (GST)",   "+ Rs. " + fmt(payload.taxAmount),fontSans, 7.5f, ColorConstants.BLACK);
        doc.add(summaryTable);

        addThickDivider(doc, fontSans);

        // Grand Total Block styled with elegant Noto Serif
        Table grandTable = new Table(UnitValue.createPercentArray(new float[]{50f, 50f}));
        grandTable.useAllAvailableWidth();
        grandTable.setMarginTop(4f);
        grandTable.setMarginBottom(6f);
        addSummaryRow(grandTable, "GRAND TOTAL", "Rs. " + fmt(payload.grandTotal), fontSerif, 9.5f, ColorConstants.BLACK);
        doc.add(grandTable);

        addThickDivider(doc, fontSans);

        // Footer Section using Noto Condensed
        addCenteredParagraph(doc, "Thank you for shopping!", fontCondensed, 7.5f, mutedColor, 6f);
        addCenteredParagraph(doc, "Please visit again.",     fontCondensed, 7.5f, mutedColor, 2f);

        doc.close();
        return baos.toByteArray();
    }

    private byte[] readResourceBytes(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Font stream resource missing at classpath location: " + path);
            }
            return is.readAllBytes();
        }
    }

    private CheckoutPayload parsePayload(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        CheckoutPayload payload  = new CheckoutPayload();
        
        if (root.has("customer") && !root.get("customer").isJsonNull()) {
            JsonObject customer = root.getAsJsonObject("customer");
            JsonElement nameEl = customer.get("name");
            payload.customerName = (nameEl != null && !nameEl.isJsonNull()) ? nameEl.getAsString() : null;
            JsonElement emailEl = customer.get("email");
            payload.customerEmail = (emailEl != null && !emailEl.isJsonNull()) ? emailEl.getAsString() : null;
        }

        payload.subtotal   = getDouble(root, "subtotal");
        payload.discount   = getDouble(root, "discount");
        payload.taxAmount  = getDouble(root, "taxAmount");
        payload.grandTotal = getDouble(root, "grandTotal");

        JsonArray itemsArray = root.getAsJsonArray("items");
        if (itemsArray == null) {
            throw new IllegalArgumentException("'items' array is missing from payload.");
        }

        for (JsonElement el : itemsArray) {
            JsonObject obj = el.getAsJsonObject();
            ItemPayload item = new ItemPayload();
            item.productUid     = getString(obj, "productUid");
            item.productName    = getString(obj, "productName");
            item.basePrice      = getDouble(obj, "basePrice");
            item.quantity       = getInt(obj,    "quantity");
            item.taxRate        = getDouble(obj, "taxRate");
            item.discountPct    = getDouble(obj, "discountPct");
            item.calculatedTax  = getDouble(obj, "calculatedTax");
            item.finalItemPrice = getDouble(obj, "finalItemPrice");
            payload.items.add(item);
        }

        return payload;
    }

    private static class CheckoutPayload {
        String customerName;
        String customerEmail;
        double subtotal;
        double discount;
        double taxAmount;
        double grandTotal;
        List<ItemPayload> items = new ArrayList<>();
    }

    private static class ItemPayload {
        String productUid;
        String productName;
        double basePrice;
        int    quantity;
        double taxRate;
        double discountPct;
        double calculatedTax;
        double finalItemPrice;
    }

    private String readRequestBody(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private void sendError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        try (var writer = response.getWriter()) {
            writer.print("{\"error\":\"" + message.replace("\"", "'") + "\"}");
        }
    }

    private void addCenteredParagraph(Document doc, String text, PdfFont font, float fontSize, com.itextpdf.kernel.colors.Color color, float spacingAfter) {
        Paragraph p = new Paragraph(text)
            .setFont(font)
            .setFontSize(fontSize)
            .setFontColor(color)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginBottom(spacingAfter);
        doc.add(p);
    }

    private void addDivider(Document doc, PdfFont font) {
        Paragraph divider = new Paragraph("- - - - - - - - - - - - - - - - -")
            .setFont(font)
            .setFontSize(7f)
            .setFontColor(ColorConstants.LIGHT_GRAY)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginTop(3f)
            .setMarginBottom(3f);
        doc.add(divider);
    }

    private void addThickDivider(Document doc, PdfFont font) {
        Paragraph divider = new Paragraph("===================================")
            .setFont(font)
            .setFontSize(7f)
            .setFontColor(ColorConstants.BLACK)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginTop(3f)
            .setMarginBottom(3f);
        doc.add(divider);
    }

    private void addSummaryRow(Table table, String label, String value, PdfFont font, float fontSize, com.itextpdf.kernel.colors.Color color) {
        Cell left = new Cell().add(new Paragraph(label).setFont(font).setFontSize(fontSize).setFontColor(color));
        left.setBorder(Border.NO_BORDER);
        left.setPaddingBottom(3f);

        Cell right = new Cell().add(new Paragraph(value).setFont(font).setFontSize(fontSize).setFontColor(color));
        right.setBorder(Border.NO_BORDER);
        right.setTextAlignment(TextAlignment.RIGHT);
        right.setPaddingBottom(3f);

        table.addCell(left);
        table.addCell(right);
    }

    private String fmt(double value) {
        return String.format("%.2f", value);
    }

    private double getDouble(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            throw new IllegalArgumentException("Missing numeric field: '" + key + "'");
        }
        return el.getAsDouble();
    }

    private int getInt(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            throw new IllegalArgumentException("Missing integer field: '" + key + "'");
        }
        return el.getAsInt();
    }

    private String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            throw new IllegalArgumentException("Missing string field: '" + key + "'");
        }
        return el.getAsString();
    }
}