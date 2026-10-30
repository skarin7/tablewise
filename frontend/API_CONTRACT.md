# API contract the frontend relies on

Re-verified on 2026-10-30 against the finished backend: read from the controllers and run end to end through `docker compose` (place, advance, cash payment, cancel, dashboard, reports, customers, online orders, platforms, devices, pairing, staff, outlet, CSV export). JSON is camelCase, null fields are omitted. Staff calls send `Authorization: Bearer <token>` and `X-Outlet-Id`; errors are `{ "error": "..." }` and shown as toasts.

| Area | Calls the frontend makes |
|---|---|
| Who am I | **No `/me` exists.** The role is probed: `GET /outlet` (any role; gives the outlet id and name), then `GET /staff` (200 = owner), then `GET /counters` (200 = cashier, 403 = kitchen). A real `/me` returning `{role, name, outletId}` would be cleaner, and would also allow staff with several outlets to choose one. |
| POS | `GET /menu`, `GET /tables`, `POST /orders` |
| Orders | `GET /orders?status=&type=&unpaid=&q=`, `POST /orders/{id}/advance`, `POST /orders/{id}/cancel {reason}` |
| Payments | `POST /orders/{id}/payments {method: cash\|card\|upi_qr, counterId, tenderedAmount?, approvalCode?}` returns only `{paymentId, status, billStatus}`; the QR (`qrPayload`, `expiresAt`) and later status come from `GET /orders/{id}/payments?counterId=` → `{billStatus, total, invoiceNo, displayOnline, payments[]}`, polled every 3 s while a QR is pending (expiry and failure are not announced on the orders channel). `POST /payments/{id}/cancel`. |
| Customer display | public `POST /devices/pairing-codes {deviceKey}`, `GET /devices/pairing-codes/{deviceKey}` → `{status: pending\|paired\|expired\|unknown, code?, expiresAt?, token?, counterId?}` (token once). Socket CONNECT with `X-Device-Token`, topic `/topic/counters/{id}/display` with `{state: idle\|bill\|paid\|failed, ...}`, a SEND to `/app/heartbeat` every 5 s. |
| Dashboard | `GET /dashboard` (today only) plus `GET /orders`, `/ingredients`, `/menu-items`; the 7-day view uses `GET /reports/summary`. The hourly chart and order mix are computed from `/orders`. |
| Online | `GET /online-orders?tab=incoming\|progress\|today`, `POST …/accept {prepMinutes}`, `POST …/reject {reason}`, `GET /platforms`, `PATCH /platforms/{p} {accepting}` |
| Menu, inventory, categories | `/menu-items`, `/categories`, `/ingredients` (+ `/restock`, `/adjust`), `/stock-movements` |
| Customers | `GET /customers?q=` → `{customers[{orders,totalSpent,averageBill,…}], walkIns}` |
| Reports | `GET /reports/summary?from&to&compare` → `current`/`comparison` with `totals`, `channels`, `series`, `paymentModes`, `topItems`, `categories`, `gst`; `GET /reports/export?kind&from&to` (CSV) |
| Admin | `GET/PUT /outlet`, `GET/POST/PUT /staff`, `GET/POST/PUT/DELETE /counters` and `/tables`, `GET /devices`, `POST /devices/pair`, `DELETE /devices/{id}` |

## Where the backend and the prototype differ (the UI follows the backend)

- `PUT /outlet` overwrites address, phone, GSTIN and FSSAI with what it receives, so the Admin form sends the existing values back.
- Tables and counters are managed one by one (add, rename, remove) instead of the prototype's "number of tables / counters" fields. Moving a display to another counter means removing and re-pairing it (no PATCH).
- Staff toggles use `PUT /staff/{id}` with the full form; invites need an email.
- Online cards show only items, instructions and kitchen progress: the platform owns customer names, totals and veg flags, so those are not shown. The total is the sum of item prices.
- Order rows do not say how a bill was paid, so the board shows "Paid" without the "· Cash/UPI" suffix. Reports' top dishes have no veg marker.
- The display tablet receives no restaurant name and the backend has no "current display state" call, so after a reconnect it shows the welcome screen until the next message. The paid screen shows the invoice number.
- Report comparison "previous period" is the N days straight before the range (a Feb report compares with 28 days, not all of January); the screen shows the exact dates the server used.
- Other prototype differences: no role switcher, no "Simulate…" buttons, cancel asks for a reason, "Print QR" and "Send payment link" are not shown (no endpoints), card payments ask for the approval code.
