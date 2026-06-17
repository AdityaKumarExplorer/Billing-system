"use strict";

const CONFIG = {
  GET_PRODUCT_URL: "get-product",
  CHECKOUT_URL:    "checkout",
  CURRENCY:        "₹"
};

const cart = new Map();

// DOM Node Selections
const uidInput       = document.getElementById("uid-input");
const scanStatus     = document.getElementById("js-scan-status");
const cartBody       = document.getElementById("js-cart-body");
const emptyState     = document.getElementById("js-empty-state");
const checkoutBtn    = document.getElementById("js-checkout-btn");
const printReceiptBtn = document.getElementById("js-print-receipt-btn");
const clearBtn       = document.getElementById("js-clear-btn");
const checkoutMsg    = document.getElementById("js-checkout-msg");
const clockEl        = document.getElementById("js-clock");
const customerName   = document.getElementById("js-customer-name");
const customerEmail  = document.getElementById("js-customer-email");

const elSubtotal  = document.getElementById("js-subtotal");
const elTax       = document.getElementById("js-tax");
const elDiscount  = document.getElementById("js-discount");
const elGrand     = document.getElementById("js-grand");
const elItemCount = document.getElementById("js-item-count");
const elUnitCount = document.getElementById("js-unit-count");

// Keeps clock element updated to display the current time
function updateClock() {
  clockEl.textContent = new Date().toLocaleTimeString("en-IN", { hour12: false });
}
updateClock();
setInterval(updateClock, 1000);

// Contacts ProductServlet backend using the specified UID parameter
async function fetchProduct(uid) {
  const url = `${CONFIG.GET_PRODUCT_URL}?uid=${encodeURIComponent(uid)}`;
  const res = await fetch(url);

  if (res.status === 404) throw new Error(`Product not found: "${uid}"`);
  if (!res.ok)            throw new Error(`Server error: ${res.status}`);

  return res.json();
}

// Catches Enter key events to initialize item scanning sequence
uidInput.addEventListener("keydown", async (e) => {
  if (e.key !== "Enter") return;

  const uid = uidInput.value.trim();
  if (!uid) return;

  uidInput.value = "";
  setStatus("busy", "Looking up…");

  try {
    const product = await fetchProduct(uid);
    addOrIncrementItem(product);
    setStatus("ok", `✓ ${product.name.split(" ")[0]}`);
    setTimeout(() => setStatus("", ""), 2000);
  } catch (err) {
    setStatus("err", "✗ Not found");
    setTimeout(() => setStatus("", ""), 2500);
    console.warn("[BillDesk]", err.message);
  }
});

function setStatus(type, message) {
  scanStatus.textContent = message;
  scanStatus.className = "scanner-bar__status " + type;
}

// Appends scanned products or updates their current count quantities
function addOrIncrementItem(product) {
  if (cart.has(product.uid)) {
    cart.get(product.uid).qty += 1;
  } else {
    cart.set(product.uid, {
      uid:         product.uid,
      name:        product.name,
      basePrice:   parseFloat(product.basePrice),
      taxRate:     parseFloat(product.taxRate),
      qty:         1,
      discountPct: 0,
    });
  }
  renderCart();
}

function removeItem(uid) {
  cart.delete(uid);
  renderCart();
}

function clearCart() {
  cart.clear();
  renderCart();
  hideMsg();
}

// Computes totals for an individual cart item row
function calcItem(item) {
  const baseAmt    = item.basePrice * item.qty;
  const discount   = baseAmt * (item.discountPct / 100);
  const taxable    = baseAmt - discount;
  const tax        = taxable * (item.taxRate / 100);
  const total      = taxable + tax;
  return { baseAmt, discount, tax, total };
}

// Evaluates global totals based on current states across all map records
function recalcAll() {
  let subtotal   = 0;
  let totalDisc  = 0;
  let totalTax   = 0;
  let totalGrand = 0;
  let unitCount  = 0;

  for (const item of cart.values()) {
    const { baseAmt, discount, tax, total } = calcItem(item);
    subtotal   += baseAmt;
    totalDisc  += discount;
    totalTax   += tax;
    totalGrand += total;
    unitCount  += item.qty;

    const totalCell = document.getElementById(`total-${item.uid}`);
    if (totalCell) totalCell.textContent = fmt(total);
  }

  elSubtotal.textContent  = `${CONFIG.CURRENCY} ${fmt(subtotal)}`;
  elTax.textContent       = `+ ${CONFIG.CURRENCY} ${fmt(totalTax)}`;
  elDiscount.textContent  = `− ${CONFIG.CURRENCY} ${fmt(totalDisc)}`;
  elGrand.textContent     = `${CONFIG.CURRENCY} ${fmt(totalGrand)}`;
  elItemCount.textContent = cart.size;
  elUnitCount.textContent = unitCount;

  const hasItems = cart.size > 0;
  checkoutBtn.disabled = !hasItems;
  printReceiptBtn.disabled = !hasItems;
}

// Rebuilds entire tbody element contents to mirror updated data rows
function renderCart() {
  cartBody.innerHTML = "";

  if (cart.size === 0) {
    emptyState.hidden = false;
    recalcAll();
    return;
  }

  emptyState.hidden = true;

  for (const item of cart.values()) {
    const { total } = calcItem(item);
    const tr = document.createElement("tr");
    tr.classList.add("row-new");

    tr.innerHTML = `
      <td class="cell-uid col-uid">${item.uid}</td>
      <td class="col-name">${escHtml(item.name)}</td>
      <td class="cell-price col-price">${CONFIG.CURRENCY} ${fmt(item.basePrice)}</td>
      <td class="col-qty">
        <input type="number" class="tbl-input" id="qty-${item.uid}" value="${item.qty}" min="1" max="9999"/>
      </td>
      <td class="col-tax"><span class="tax-badge">${item.taxRate}%</span></td>
      <td class="col-disc">
        <input type="number" class="tbl-input" id="disc-${item.uid}" value="${item.discountPct}" min="0" max="100" step="0.5"/>
      </td>
      <td class="cell-total col-total" id="total-${item.uid}">${fmt(total)}</td>
      <td class="col-del"><button class="btn-del" data-uid="${item.uid}">✕</button></td>
    `;
    cartBody.appendChild(tr);
  }

  attachInputListeners();
  recalcAll();
}

// Attaches input updating listeners to numeric input parameters
function attachInputListeners() {
  for (const item of cart.values()) {
    const qtyEl  = document.getElementById(`qty-${item.uid}`);
    const discEl = document.getElementById(`disc-${item.uid}`);

    qtyEl.addEventListener("input", () => {
      const val = parseInt(qtyEl.value, 10);
      if (isNaN(val) || val < 1) return;
      cart.get(item.uid).qty = val;
      recalcAll();
    });

    discEl.addEventListener("input", () => {
      const val = parseFloat(discEl.value);
      if (isNaN(val) || val < 0 || val > 100) return;
      cart.get(item.uid).discountPct = val;
      recalcAll();
    });
  }

  cartBody.querySelectorAll(".btn-del").forEach(btn => {
    btn.addEventListener("click", () => removeItem(btn.dataset.uid));
  });
}

clearBtn.addEventListener("click", () => {
  if (cart.size === 0) return;
  if (confirm("Clear the entire cart?")) clearCart();
});

// Triggers direct native sheet interface layout printing
printReceiptBtn.addEventListener("click", () => {
  window.print();
});

// Compiles sales payloads and posts the processed data to the CheckoutServlet backend
checkoutBtn.addEventListener("click", async () => {
  if (cart.size === 0) return;

  const name = customerName.value.trim();
  const email = customerEmail.value.trim();

  if (!name || !email) {
    showMsg("error", "Please input required Customer Name and Email credentials before checking out.");
    return;
  }

  checkoutBtn.disabled = true;
  checkoutBtn.textContent = "Processing…";
  hideMsg();

  let subtotal = 0, totalTax = 0, totalDisc = 0, grand = 0;
  const items = [];

  for (const item of cart.values()) {
    const { baseAmt, discount, tax, total } = calcItem(item);
    subtotal  += baseAmt;
    totalTax  += tax;
    totalDisc += discount;
    grand     += total;

    items.push({
      productUid:     item.uid,
      productName:    item.name,
      basePrice:      item.basePrice,
      quantity:       item.qty,
      taxRate:        item.taxRate,
      discountPct:    item.discountPct,
      calculatedTax:  round2(tax),
      finalItemPrice: round2(total),
    });
  }

  const payload = {
    customer: { name, email },
    subtotal:   round2(subtotal),
    taxAmount:  round2(totalTax),
    discount:   round2(totalDisc),
    grandTotal: round2(grand),
    items,
  };

  try {
    const res = await fetch(CONFIG.CHECKOUT_URL, {
      method:  "POST",
      headers: { "Content-Type": "application/json" },
      body:    JSON.stringify(payload),
    });

    if (!res.ok) {
      const errText = await res.text();
      throw new Error(`Server: ${res.status} — ${errText}`);
    }

    clearCart();
    customerName.value = "";
    customerEmail.value = "";
    window.location.href = "payment.html";

  } catch (err) {
    showMsg("error", `✗ Checkout failed: ${err.message}`);
    checkoutBtn.disabled = false;
    checkoutBtn.textContent = "Complete Checkout";
  }
});

function fmt(n) { return n.toFixed(2); }
function round2(n) { return Math.round(n * 100) / 100; }

function escHtml(str) {
  return String(str).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function showMsg(type, text) {
  checkoutMsg.className  = `checkout-msg ${type}`;
  checkoutMsg.textContent = text;
  checkoutMsg.hidden     = false;
}

function hideMsg() { checkoutMsg.hidden = true; }

renderCart();