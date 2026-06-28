# 🎨 Frontend Module Architecture (Vanilla Browser Stack)

This module comprises the user-facing interface for the point-of-sale terminal dashboard. It is designed entirely as a **vanilla browser application** with zero compilation frameworks, zero transpilations, and zero external state dependencies. The client assets are served statically by the Tomcat container directly out of the web archive root.

---

## 🏗️ Files & Workspace Structure

- `src/main/webapp/index.html` — Document structural blueprint using structural semantic wrappers.
- `src/main/webapp/style.css` — High-contrast dark industrial-themed user interface stylesheets.
- `src/main/webapp/script.js` — Client runtime engine handling calculations, memory tracking, and network APIs.
- `src/main/webapp/logo.png` — Visual logo asset displayed in the terminal dashboard topbar.
- `src/main/webapp/payment.html` — Static loading page mock for payment gateway redirect transitions.

---

## ⚙️ Runtime Settings & Deployment Scope

The application is wired by default to integrate smoothly with the live enterprise Jakarta Servlet infrastructure:

```javascript
const CONFIG = {
  GET_PRODUCT_URL: "get-product",
  CHECKOUT_URL: "checkout",
  CURRENCY: "₹",
};

```

### Context-Relative Routing Rules

The API endpoint parameters (`GET_PRODUCT_URL`, `CHECKOUT_URL`) are purposefully configured as **context-relative paths**. When the compiled archive (`billing-system.war`) is deployed inside the container, network fetch calls map automatically onto the assigned deployment paths:

* `fetch("get-product")` resolves seamlessly to: `http://localhost:8080/billing-system/get-product`
* `fetch("checkout")` resolves seamlessly to: `http://localhost:8080/billing-system/checkout`



## 🧠 Vanilla State Engine Architecture

The client stack relies on an in-memory **Vanilla State Engine** implemented via native data models. It guarantees zero external dependencies and enforces a highly predictable, unidirectional flow of transactional mutations without scraping raw string data out of HTML table nodes.

```text
       State Engine Processing Lifecycle
─────────────────────────────────────────────────
[Scan/Input barcode (UID)] ──► fetchProduct(uid)
                                     │
                                     ▼
                        Mutate Memory Data Model
                       (cartMap.set(uid, item))
                                     │
                                     ▼
      ┌──────────────────────────────┴──────────────────────────────┐
      ▼                                                             ▼
recalcAll()                                                   renderCartUI()
(Calculates base, tax, and totals)         (Flushes & repopulates DOM elements)

```

### Unidirectional Event Pipeline

1. **Action Trigger:** The application listens for scanner input events on the tracking capture ID block (`#uid-input`).
2. **Data Model Mutation:** The product details are mapped directly into an in-memory JavaScript collection:
```javascript
const cart = new Map(); // Key: productUid, Value: Item attributes object

```


3. **Calculation Phase:** The system iterates over the active memory array to compute fractional tax thresholds, base values, and aggregate totals.
4. **UI Reflow Execution:** The DOM element array (`#js-cart-body`) is cleanly cleared out and completely reassembled dynamically using safe, structured text assignment templates.

---

## 📡 Core API Payload Contract

When a checkout transaction successfully resolves validation metrics, the frontend marshals the localized transactional engine map into a minified, structured JSON block transmitted directly via `POST` methods:

```json
{
  "customer": {
    "name": "Aditya Kumar",
    "email": "aditya@example.com"
  },
  "subtotal": 900.0,
  "discount": 0.0,
  "taxAmount": 45.0,
  "grandTotal": 945.0,
  "items": [
    {
      "productUid": "P001",
      "productName": "Basmati Rice 5kg",
      "basePrice": 450.0,
      "quantity": 2,
      "taxRate": 5.0,
      "discountPct": 0.0,
      "calculatedTax": 45.0,
      "finalItemPrice": 945.0
    }
  ]
}

```

---

## 💳 Checkout Completion & Printing

Upon successful checkout, the frontend clears the cart and redirects the browser session to a transitional loading state page.

### Workflow Sequence

1. **Submit Payload:** The frontend bundles customer context and purchase items, posting them to the `/checkout` servlet as a JSON request.
2. **Clear & Redirect:** If the servlet responds with HTTP 200 (indicating database transaction success and receipt compilation), the client calls `clearCart()`, resets the customer input fields, and navigates to `payment.html` via `window.location.href = "payment.html"`.
3. **Standard Printing:** A "Print Receipt" button in the sidebar panel (`#js-print-receipt-btn`) is enabled whenever items exist in the cart, allowing the cashier to print the current screen layout using the browser's native `window.print()` functionality.

---

## 🎨 Industrial Dark Styling Guidelines (`style.css`)

The structural layout uses an industrial-dark theme optimized specifically for target low-glare hardware environments.

* **Layout Structure:** Implements rigid CSS Grid alignments (`.layout`) to cleanly frame data columns between active terminal scans and sales breakdowns.
* **Data View Constraints:** The cart interface table wraps data content inside independent scrolling blocks (`.table-wrap`), freezing the layout footprint while items grow.
* **Adaptive Breakpoints:** Incorporates target hardware media constraints to drop double-column dashboards down into unified vertical views for small handheld scanner devices.

---

## 🏷️ System Element Tracking Dictionary

The system uses standard tracking IDs to map interactive operations safely without breaking internal state layers:

| Target Component ID | Operational Responsibility Group |
| --- | --- |
| `uid-input` | Captures raw scan signals from connected hardware scanners or barcode registers. |
| `js-cart-body` | Structural table zone used to clear and inject active receipt items. |
| `js-checkout-btn` | Intercepts confirmation signals to lock input screens and stream JSON outputs. |
