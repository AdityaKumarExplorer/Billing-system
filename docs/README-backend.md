markdown

---

# Backend Module

Files:

* `src/main/java/backend/ProductServlet.java`
* `src/main/java/backend/CheckoutServlet.java`
* `src/main/java/backend/AppLifecycleListener.java`
* `src/main/webapp/WEB-INF/web.xml`

The backend is a plain Jakarta Servlet application. It runs as a WAR on Tomcat 11.

## Runtime Alignment

The project targets:

```text
Java 21
Tomcat 11.x
Jakarta Servlet 6.1

```

`pom.xml` declares:

```xml
<dependency>
    <groupId>jakarta.servlet</groupId>
    <artifactId>jakarta.servlet-api</artifactId>
    <version>6.1.0</version>
    <scope>provided</scope>
</dependency>

```

The `provided` scope is important. Tomcat supplies the servlet classes at runtime, so they should not be bundled into the WAR.

## `jakarta` vs `javax`

Tomcat 11 uses the `jakarta.servlet.*` namespace. The old `javax.servlet.*` namespace belongs to pre-Jakarta servlet stacks such as Tomcat 9 and older.

This project must use imports like:

```java
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

```

If a servlet imports `javax.servlet.*`, it may compile in some environments if an old API jar is present, but it will fail on Tomcat 11 at runtime.

## `web.xml`

The descriptor is aligned with Servlet 6.1:

```xml
<web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
                             https://jakarta.ee/xml/ns/jakartaee/web-app_6_1.xsd"
         version="6.1">

```

The file configures:

* `index.html` as the welcome file.
* 30 minute session timeout.
* Fallback error pages for 404 and 500 responses.

In alignment with production code maintenance, all conversational learning notes and text charts within `web.xml` have been stripped out, leaving only clean, functional configurations. Servlet URL mappings continue to be handled via annotations directly in the Java classes rather than inside `web.xml`.

## ProductServlet

Mapping:

```java
@WebServlet(name = "ProductServlet", urlPatterns = "/get-product")

```

Request:

```text
GET /billing-system/get-product?uid=P001

```

### Code Flow & Cleanup

All diagnostics, ASCII flow charts, and local mock-data fallback routines have been completely removed. The servlet executes lookup operations strictly and directly against the production MySQL database via the DAO layer. Comments have been condensed to high-level functional or operational summaries of the internal methods.

Flow:

```text
Read uid query parameter
  -> validate non-empty
  -> ProductDAO.findByUid(uid) [Production Database Lookup]
  -> return JSON product or JSON error

```

Successful response shape:

```json
{
  "uid": "P001",
  "name": "Basmati Rice 5kg",
  "basePrice": 450.0,
  "taxRate": 5.0
}

```

Error responses are JSON too:

```json
{
  "error": "Product not found: P001"
}

```

## CheckoutServlet

Mapping:

```java
@WebServlet(name = "CheckoutServlet", urlPatterns = "/checkout")

```

Request:

```text
POST /billing-system/checkout
Content-Type: application/json

```

### Customer Context Integration

The parsing payload architecture captures an optional `customer` block containing `name` and `email` properties sent by the frontend interface. These inputs populate the extended inner data fields of the `CheckoutPayload` transaction container class.

Checkout follows a strict execution order:

```text
1. Read incoming JSON body stream
2. Parse and validate transaction payload (extracting customer data if present)
3. Save transaction and transaction line items via TransactionDAO
4. Generate thermal-format receipt PDF in memory
5. Stream completed PDF bytes directly to the browser

```

The sequence matters because writing to the response output stream commits the HTTP response channel. Any database transactional failure or structural layout exception must take place prior to streaming so the servlet can accurately return a proper HTTP error status.

## PDF Generation & Native Printing

The receipt is generated with iText 5 into a `ByteArrayOutputStream`. No temporary files are written to server storage disks.

### Layout Controls and Direct Printing

The layout engine reads the customer configuration from the transaction payload. If customer properties are supplied, they are explicitly rendered into the receipt header space beneath the store information lines:

* **Customer Name:** Printed directly as `"Customer: " + payload.customerName`.
* **Customer Email:** Printed directly as `"Email: " + payload.customerEmail`.

To prevent structural clipping or cut-offs, the vertical canvas layout bounds (`headerHeight`) scale dynamically depending on the presence of these fields.

The application has decommissioned the legacy frontend modal iframe overlay container to facilitate direct, unhindered terminal printing. Response headers route the raw byte stream directly to the client's window framework for immediate native print job management:

```java
response.setContentType("application/pdf");
response.setContentLength(pdfBytes.length);
response.setHeader("Content-Disposition", "inline; filename=\"receipt.pdf\"");
response.setHeader("Cache-Control", "no-store");

```

## AppLifecycleListener

Mapping:

```java
@WebListener

```

Purpose:

* Log web app context startup initialization.
* Safely close down the open database connection pooling architecture when Tomcat stops or undeploys the app context.

Shutdown calls:

```java
DBConnection.shutdown();

```

This avoids dangling, stale MySQL connections from squatting and exhausting `max_connections` allocation spaces across server application redeploys. In line with comment cleanups, conversational troubleshooting guides regarding memory leaks have been removed in favor of short, operational descriptions.

## Thread Safety

Tomcat creates exactly one servlet instance and assigns incoming tasks across multiple concurrent request threads. All servlet class-level fields must be strictly thread-safe.

Safe shared fields utilized in this module:

* `Gson` parser instances (safe for concurrent re-use).
* `ProductDAO` and `TransactionDAO` instances (stateless across requests).

Unsafe instance patterns to avoid:

```java
private Connection connection; // UNSAFE — Shared mutable state across threads

```

Database connections must always be isolated inside the request thread scope, borrowed from the resource pool dynamically, and terminated safely through a try-with-resources statement block.

## Build Verification

The project compiles and packages using standard Maven processes:

```bash
mvn -Dmaven.compiler.showWarnings=true -Dmaven.compiler.compilerArgs=-Xlint:deprecation clean package

```

Expected clean build result:

```text
BUILD SUCCESS
target/billing-system.war

```

No automated test blocks are present in the pipeline profile, so Maven will bypass test phases with an empty report indicator.