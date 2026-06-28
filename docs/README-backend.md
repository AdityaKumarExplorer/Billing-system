# 🖥️ Backend Module Architecture (Tomcat 11 / Servlet 6.1)

This module manages the business logic, transaction workflows, dynamic iText PDF generation, and connection pool lifetimes. By utilizing the **Jakarta Servlet 6.1** specification running on **Tomcat 11**, the backend avoids heavy frameworks to maximize throughput and maintain clear visibility over the HTTP request/response lifecycle.

---

## 🏗️ Files & Directory Overview

- `src/main/java/backend/ProductServlet.java` — Catalogs product barcodes/UID scans.
- `src/main/java/backend/CheckoutServlet.java` — Coordinates atomic saves, triggers receipt emails, and returns the generated PDF stream.
- `src/main/java/backend/EmailService.java` — Handles asynchronous dispatch of PDF receipts via SMTP configurations.
- `src/main/java/backend/AppLifecycleListener.java` — Context listener running resource connection pool tear-downs.
- `src/main/webapp/WEB-INF/web.xml` — Deployment descriptor configuration sheet.

---

## ⚙️ Runtime Alignment & Dependency Setup

The project targets modern enterprise execution platforms:
- **Java:** Version 21 (compiled with `--release 21`).
- **Tomcat:** Version 11.x series.
- **Jakarta Specification:** Servlet API 6.1.

### `pom.xml` Dependencies
```xml
<dependency>
    <groupId>jakarta.servlet</groupId>
    <artifactId>jakarta.servlet-api</artifactId>
    <version>6.1.0</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>com.itextpdf</groupId>
    <artifactId>itext-core</artifactId>
    <version>9.0.0</version>
    <type>pom</type>
</dependency>
<dependency>
    <groupId>jakarta.mail</groupId>
    <artifactId>jakarta.mail-api</artifactId>
    <version>2.1.3</version>
</dependency>
<dependency>
    <groupId>org.eclipse.angus</groupId>
    <artifactId>jakarta.mail</artifactId>
    <version>2.0.3</version>
    <scope>runtime</scope>
</dependency>

```

*Note on Scope:* The `provided` scope is mandatory. Tomcat supplies the core servlet classes at runtime, meaning they should never be bundled directly into the packaged WAR output.

### ⚠️ Namespace Migration: `jakarta` vs `javax`

Tomcat 11 relies fully on the modern `jakarta.servlet.*` namespace. The legacy `javax.servlet.*` namespace is native to pre-Jakarta servlet stacks (such as Tomcat 9 and older) and is completely unsupported here.

All controllers must utilize the updated imports:

```java
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

```

---

## 📝 Deployment Descriptor Configuration (`web.xml`)

The web deployment descriptor is explicitly aligned with the Servlet 6.1 validation schema:

```xml
<web-app xmlns="[https://jakarta.ee/xml/ns/jakartaee](https://jakarta.ee/xml/ns/jakartaee)"
         xmlns:xsi="[http://www.w3.org/2001/XMLSchema-instance](http://www.w3.org/2001/XMLSchema-instance)"
         xsi:schemaLocation="[https://jakarta.ee/xml/ns/jakartaee](https://jakarta.ee/xml/ns/jakartaee)
                             [https://jakarta.ee/xml/ns/jakartaee/web-app_6_1.xsd](https://jakarta.ee/xml/ns/jakartaee/web-app_6_1.xsd)"
         version="6.1">

```

### Configuration Highlights:

* **Welcome File:** Registers `index.html` as the default application root dashboard landing page.
* **Session Management:** Enforces a 10-minute absolute session timeout limit.
* **Error Mapping:** Provides structural fallback error page routings for `404 (Not Found)` and `500 (Internal Server Error)` states.

*Operational Note:* In alignment with code maintenance standards, all informational notes and text charts have been stripped from `web.xml`. All servlet route endpoints are declared cleanly using `@WebServlet` annotations inside the respective Java source files rather than here.

---

## 🔍 Servlet Implementations

### 1. `ProductServlet` (Catalog Scans)

* **Endpoint Mapping:** `@WebServlet(name = "ProductServlet", urlPatterns = "/get-product")`
* **Target Request:** `GET /billing-system/get-product?uid=P001`

#### Execution Architecture:

All old mock-data fallbacks, diagnostic code blocks, and ASCII flow representations have been removed. The servlet executes database lookups strictly through the database access layer (`ProductDAO`).

```text
Extract 'uid' Parameter ──► Validate Non-Empty ──► ProductDAO.findByUid(uid) ──► Flush JSON Output

```

#### API Data Output Examples:

* **Successful Lookups (HTTP 200):**
```json
{
  "uid": "P001",
  "name": "Basmati Rice 5kg",
  "basePrice": 450.0,
  "taxRate": 5.0
}

```


* **Error Handling Exceptions (HTTP 404/500):**
```json
{
  "error": "Product not found: P001"
}

```



### 2. `CheckoutServlet` (Transaction Engine)

* **Endpoint Mapping:** `@WebServlet(name = "CheckoutServlet", urlPatterns = "/checkout")`
* **Target Request:** `POST /billing-system/checkout` (`Content-Type: application/json`)

#### Customer Context Capturing:

The parsing infrastructure handles an optional `customer` block containing `name` and `email` fields passed by the frontend. These elements populate fields inside the `CheckoutPayload` transaction container.

#### Processing Pipeline Sequence:

```text
1. Parse Incoming JSON Stream ──► 2. Validate Payload & Extract Customer Data ──► 
3. Execute TransactionDAO Batch Save ──► 4. Assemble iText PDF in Memory ──► 
5. Dispatch Async Receipt Email (if email provided) ──► 6. Stream Bytes to Client
```

*Sequence Constraint:* The step order is critical. Writing bytes to the network stream commits the HTTP response channel. All database mutations and transaction operations must execute and succeed before the PDF bytes are written, allowing the servlet to safely return error codes if a step fails.

### 3. `EmailService` (Asynchronous Notification Dispatcher)

* **Role:** Dispatches digital receipt notifications in the background to avoid blocking the main servlet execution thread.
* **Trigger Conditions:** Activated within `CheckoutServlet` post-checkout when the customer's email address is non-blank and configuration credentials exist in `db.properties`.

#### Execution Pipeline:

1. **Retrieve Configurations:** Reads SMTP configurations (`mail.smtp.host`, `mail.smtp.port`, `mail.sender.email`, `mail.sender.password`) from `db.properties`.
2. **Spawn Worker Thread:** Spawns a new background execution thread (`new Thread(...)`) to handle SMTP handshakes asynchronously.
3. **Assemble Multipart Message:** Integrates a localized welcome message in the body part and packages the compiled PDF receipt as a binary attachment.
4. **Dispatch:** Streams the SMTP transport transaction using standard Secure Connection protocols (STARTTLS).

---

## 🖨️ PDF Generation & Native Printing Pipeline

Invoices are dynamically compiled using **iText 9** straight into an in-memory `ByteArrayOutputStream`. No temporary files are generated on server storage drives.

### Dynamic Canvas Controls

* **Flexible Headers:** The rendering engine checks the parsed `CheckoutPayload` for customer details. If present, it maps them cleanly into the receipt header space under the store fields:
* **Customer Name:** Printed as `"Customer: " + payload.customerName`
* **Customer Email:** Printed as `"Email: " + payload.customerEmail`


* **Height Scaling:** To eliminate layout clipping or cut-offs on thermal paper styles, the vertical layout boundaries (`headerHeight`) scale dynamically to accommodate these extra entries.

The response headers route raw binary data streams straight to the client window for download/display:

```java
response.setContentType("application/pdf");
response.setContentLength(pdfBytes.length);
response.setHeader("Content-Disposition", "inline; filename=\"" + uniqueReceiptName + "\"");
response.setHeader("Cache-Control", "no-store");

```

---

## 🔒 Multi-Threaded Safety Guardrails

Tomcat instantiates **exactly one instance** of each servlet class and shares it across incoming concurrent worker threads. To prevent data corruption or variable intermixing across cashier points, strict thread-safety isolation rules are enforced.

### Thread-Safe Shared Instanced Components:

* **`Gson` Utility Instances:** Safe for shared multi-threaded data marshaling.
* **DAO Abstractions (`ProductDAO`, `TransactionDAO`):** Completely stateless data access units.

### Unsafe Anti-Patterns Avoided:

```java
// !!! VIOLATION !!! This introduces a shared mutable state across threads.
private Connection connection; 

```

*Rule of Isolation:* Database connections, command statements, and transactional variables are strictly confined within the local scope of individual execution methods (allocated exclusively on the thread's private stack frame) using **try-with-resources** blocks.

---

## ⏳ Application Lifecycle Hooks (`AppLifecycleListener`)

The application context monitoring hook uses the `@WebListener` registration layer to manage server scope boundaries:

* **Initialization:** Logs startup indicators as the application finishes deployment on Tomcat.
* **Tear-Down Phase:** Triggers on application context shutdowns or WAR updates to systematically release resource handles:
```java
DBConnection.shutdown();

```



This hook isolates and cleans database connection pool allocations, preventing dangling connections from exhausting available thread capacity (`max_connections`) across redeployments.

---

## 🛠️ Build Verification Command

To compile, verify, and package the backend archive while actively monitoring for outdated functions, use the production Maven command pipeline:

```bash
mvn -Dmaven.compiler.showWarnings=true -Dmaven.compiler.compilerArgs=-Xlint:deprecation clean package

```

### Expected Target Result:

```text
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Built war: /path/to/project/target/billing-system.war

```

The pipeline profile contains no automated unit testing blocks, so Maven will successfully bypass the test cycles and return an empty validation statement.
