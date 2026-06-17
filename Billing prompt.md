Billing prompt

Structure to be following:

billing-system/
├── pom.xml
└── src/
    └── main/
        ├── java/                  # Maven compiles everything inside here
        │   ├── backend/           # Servlets (The Controllers)
        │   │   ├── ProductServlet.java
        │   │   └── CheckoutServlet.java
        │   └── database/          # MySQL Logic (The DAO & Utils)
        │       ├── DBConnection.java
        │       ├── ProductDAO.java
        │       └── TransactionDAO.java
        │
        ├── resources/             # Keeps configuration files out of your Java source
        │   └── db.properties      # DB Credentials
        │
        └── webapp/                # Frontend (The View)
            ├── index.html
            ├── style.css
            ├── script.js
            └── WEB-INF/
                └── web.xml        # Deployment Configuration

Act as a senior full-stack developer. I need the complete code for the frontend files of my billing application using vanilla HTML, CSS, and modern JavaScript:
1. `src/main/webapp/index.html`
2. `src/main/webapp/style.css`
3. `src/main/webapp/script.js`

Requirements:
- The UI should look like a clean, dark-themed or minimalist modern point-of-sale (POS) terminal.
- An input field for "Scan/Enter Product UID". When the user types a UID and hits Enter, JavaScript must use the Fetch API to call `GET /get-product?uid=VALUE`.
- If found, add the product to an interactive data table with columns: UID, Name, Base Price, Quantity, Tax Rate (%), Discount (%), and Total.
- If the product is scanned again, increment the quantity.
- **Runtime Calculations:** The table must allow updating quantities or adding individual item discounts inline. JavaScript must instantly recalculate and display the bottom summary box showing: Subtotal, Cumulative Taxes, Total Discounts, and Grand Total.
- A "Complete Checkout" button that captures the entire cart state and prepares it for a JSON POST request.

Act as a database engineer. Provide the MySQL table scripts and the complete Java code for `src/main/java/database/TransactionDAO.java`.

Requirements:
- Provide the SQL schema for two interconnected transaction tables: `transactions` (id, date_time, subtotal, tax_amount, grand_total) and `transaction_items` (id, transaction_id, product_uid, quantity, calculated_tax, final_item_price).
- In `TransactionDAO.java`, write a method `public boolean saveTransaction(Map<String, Object> transactionData, List<Map<String, Object>> items)`.
- Use standard JDBC with explicit Transaction Management (`connection.setAutoCommit(false)`). If inserting an individual line item fails, roll back the entire batch to keep data consistent.
- Use `Statement.RETURN_GENERATED_KEYS` to grab the auto-incremented `transaction_id` from the master table and seamlessly map it to the child items.

Act as a Java backend developer specializing in enterprise printing. Write the complete code for `src/main/java/backend/CheckoutServlet.java` using iText 5.

Requirements:
- The servlet must handle a `POST` request to `/checkout` containing the cart data in a JSON payload.
- Parse this JSON using Google Gson and invoke `TransactionDAO` to commit the data to MySQL.
- **PDF Architecture:** If the database operation is successful, use iText to generate an in-memory PDF. Set a custom page size optimized for a thin thermal receipt profile (e.g., a fixed 80mm/226pt width, with a fluid, dynamic height based on item count).
- The receipt template must output: Store Header/Metadata, Timestamp, Itemized Table (Qty x Name @ Price), explicit Tax Breakdown lines, and a bold **Grand Total**.
- Stream the generated PDF bytes directly back into the `HttpServletResponse` output stream with the content type set to `application/pdf` so the client-side dashboard immediately triggers a clean print preview or download dialog.