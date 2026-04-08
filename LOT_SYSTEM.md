# Legend of Toys — System Understanding Document
**Version:** 2.2 | **Last Updated:** April 2026
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth. At this trajectory, the system must be designed to handle ~120,000 units/month by end of Year 1 and potentially 700,000+ units/month by end of Year 2.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Google Sheets + Apps Script replaced. New integrated production operations system on Supabase + Cloudflare Workers + PWA is live and scanning on the factory floor. System confirmed working in live floor testing. PKG thermal label printing live via polling architecture v2.2. Per-line PKG and WKS devices deployed. Operator session tracking live. Dispatch attribution corrected (SHARED line removed). Hourly achievement chart live. Store procurement BY UNITS mode live.

---

## 2. Organisation Structure

```
Afshaan (Co-founder)
├── Varun (Head of Production — owns Stores, Assembly, QC, Dispatch)
│   ├── Siddhant (Production Manager — owns assembly lines)
│   │   ├── Kishan (Line Manager — owns the lines day-to-day)
│   │   │   └── Line Leaders / Supervisors (per line)
│   │   └── Karthik (Inline QC Supervisor — across all 3 lines)
│   └── Reann (Temporary Store Manager — not production, reports to Varun until proper hire)
└── Mahesh Reddy (External QC Auditor — reports to Afshaan directly)
```

**Why Mahesh reports to Afshaan and not Varun:** Deliberate structural decision. Mahesh cross-references inline QC data against actual audit findings. Mixed incentives avoided. Scope expanding to cover stores and dispatch.

**Mahesh's system access:** Read-only across all data. Cannot amend, void, or modify any record — ever. **Permanent system constraint**, enforced at worker level.

**Operator profile:** Unskilled workers on the factory floor. Any interface they touch must be **descriptive, visual, and foolproof** — assume worst-case user at all times.

---

## 3. Products

LOT currently makes RC cars. Future product categories include die cast and DIY sets (no remotes). A finished RC car unit consists of: car body (UPC sticker on bottom), remote control (UPC sticker — independent), accessories, packaging tray + box, shrink wrap.

**SKUs:** 86 active SKUs across ~28 products with multiple model/color variants.

**`has_remote` flag:** Product master boolean. RC cars = true. Die cast, DIY sets = false. Drives scanner behaviour at QC_PASS and PKG.

---

## 4. UPC System ✅ LOCKED

### Format — LOCKED
**`LOT-00000001`** — 8 digits, zero-padded, globally sequential. QR encodes this string. Never reassigned, never reused, never deleted.

### Display Code — LOCKED
Human-readable code printed below the QR: **`KNAK00000007`** (product_code + raw 8-digit LOT sequence, no hyphens).

### Product Code System ✅ LOCKED
**Formula:** PP+M+C (2-char product + 1-char model + 1-char color)

**Color key:** K=Black, B=Blue, G=Green, R=Red, W=White, E=Grey, S=Silver, P=Purple, V=Purple2, M=Multi, N=Pink, X=Excavator, Y=Yellow, O=Orange, D=Desert

**Remote:** car code + 'R'. If car is KNAK, remote is KNAKR.

### Print Batch Workflow
1. Admin generates batch in dashboard
2. System assigns LOT- numbers + display codes, creates batch record
3. Print file sent to external printer (A3, 21×29 grid = 609 stickers/sheet, no borders, 13mm cells)
4. Stickers received → admin clicks Received → all UPCs flip `generated` → `available`
5. Stickers applied on floor → each UPC marked `applied` when scanned at INW

**Critical:** Batch must be marked Received before any sticker can be inwarded. Status `applied` at INW means duplicate sticker — fix by applying a fresh available sticker.

---

## 5. Physical Production Flow

### High-Level Flow
```
Parts (Store) → Assembly (car + remote on same line) → QC → Packaging → RTD → Dispatch
```

### Scan Points — LOCKED
| Scan Code | Stage | Devices | Notes |
|---|---|---|---|
| INW | End of Assembly | 3 (one per line) | Single scan — car or remote independently |
| QC_PASS | End of QC | 3 (one per line) | Two-scan flow if has_remote = true; pairing created here |
| QC_FAIL | End of QC | 3 (one per line) | Single scan — whichever component failed |
| WKS | Workshop | 3 (WKS-L1/L2/L3) | System-inferred direction. Per-line April 2026. |
| PKG | Packaging station | 3 (PKG-L1/L2/L3) | Two-scan (car + remote), channel toggle, prints batch label |
| PKG_OUT | Dispatch Out | 1 shared | Scans batch label — auto-routes RTE/RTR or RTD_RETURN. Line attributed via pkg_scans.line. |
| RTO_IN | Returns | 1 shared | Two paths: intact → direct RTD, damaged → full production flow |

### Stage Notes
**Stage 4 (PKG):** `pkg_scans.line` is the source of truth for which line a unit came from. PKG_OUT device is SHARED — do not use `scans.line` for RTE/RTR dispatch counts.

**Stage 5 (PKG_OUT):** `effectiveLine = pkgScan.line || device.line`. This ensures all dispatch metrics (hourly chart, line cards, PVA cards) attribute correctly.

### Batch Label Format — LOCKED
**`LOT-XXXXXXXX-E`** (ecom) or **`LOT-XXXXXXXX-R`** (retail). 50×25mm on TSC TE244.

---

## 6. Devices & Station Mapping ✅ LOCKED

| Device Code | Station | Line |
|---|---|---|
| INW-L1/L2/L3 | INW | L1/L2/L3 |
| QCP-L1/L2/L3 | QC_PASS | L1/L2/L3 |
| QCF-L1/L2/L3 | QC_FAIL | L1/L2/L3 |
| WKS-L1/L2/L3 | WKS | L1/L2/L3 |
| PKG-L1/L2/L3 | PKG | L1/L2/L3 |
| PKG-OUT | PKG_OUT | SHARED |
| RTO | RTO_IN | SHARED |

Total: 19 devices. **SHARED devices must never be used to attribute per-line counts** — always join via pkg_scans for dispatch.

---

## 7. Operators & Sessions

### Operator Master (`operators` table)
- Name, role (enum), default line, QR code, active status
- Managed via dashboard Operators tab
- QR code format: `LOT-OP-XXXXXXXX` (auto-generated on creation)
- Physical QR ID cards printed from dashboard → used for scanner login

### Operator Roles (enum)
`assembly | qc_inline | qc_audit | repair | packing | rtd | store | supervisor | line_manager | production_manager | admin`

### Session Tracking (`operator_sessions` table)
- Created automatically on every QR card scan login
- Closed when next operator scans on same device
- Fields: operator_id, device_id, station, line, production_run_id, shift, login_at, logout_at

### Shift Time Boundaries (IST)
- **Regular:** 9:00 AM – 6:00 PM (Assembly/QC), 9:00 AM – 7:00 PM (Packing)
- **OT:** 6:00 PM – 9:00 PM
- **Double OT:** After 9:00 PM
- One-hour overlap (6–7pm) intentional for PKG load balancing

---

## 8. Dashboard Access

| Role | Access |
|---|---|
| Afshaan | Full access including Operators add/edit |
| Vinay | Executive + financial views |
| Varun | Executive + full production |
| Siddhant | Production manager — hourly live |
| Kishan | Line manager |
| Karthik | QC — defect heatmap, training flags |
| Mahesh | Read-only audit — enforced at worker level |

**Dashboard tabs (April 2026):** Dashboard, Lines, QC, UPC Generator, Scans, Corrections, Alerts, Returns, Reporting, Operators, Print.

---

## 9. Returns System

### Return Categories
| Code | Name | Definition |
|---|---|---|
| UDR | Undamaged Return | Box sealed, product untouched. Batch label scannable on outside. |
| CXR | Customer Return | Customer opened, returned for any reason. |
| BRV | Bulk Return — Vendor | Platform or trade partner returning unsold/excess stock in bulk. |

### Return Flow
1. Store receives return shipment
2. Store does intake → inspection → disposition (3-stage UI)
3. Store hands over to production
4. Production — UDR: Scan batch label at PKG_OUT → `RTD_RETURN`. Does not count in RTE/RTR tally.
5. Production — Damaged: `wks_repair` → repair run (pending design)

---

## 10. Print System

### Architecture
- `print_jobs` table in public schema
- Print server (Node.js v2.2) on each line's PKG laptop
- Each server filters by its own line — no cross-line contamination
- Atomic claiming via conditional PATCH prevents duplicate prints

v2.2 deployed to L1. **L2 and L3 need deployment.**

---

## 11. Store Procurement — BY UNITS Mode ← new April 2026

Third line item mode in the PO form alongside BOM and MANUAL.

**Flow:** Select product → grid shows all model+color combinations → enter quantities for desired variants → ADD TO ORDER → accumulated queue shown → PO submitted with product/variant/color on each line.

**Data model:** Each line uses `po_lines.product`, `po_lines.variant` (model), `po_lines.color`. `store.po_lines.color TEXT` column added April 2026.

**item_type:** Defaults to 'RC Car'. Future: derive from product master when price master is built.

**Note:** `PRODUCT_SUBVARIANTS` in the store app needs updating for Nitro, Dash, Fang, Atlas (currently empty arrays — no color grid rendered for those products).

---

## 12. Key Technical Learnings (don't repeat these mistakes)

- The `unit_status` enum uses lowercase values; the `activity_type` enum uses uppercase — mixing these caused live bugs
- Supabase's default row limit (1000) silently caps query results; use Postgres RPCs to bypass
- WKS direction (IN/OUT) must be system-inferred from unit status, not operator-selected
- Worker endpoints must be in the `SCANNER_ACTIONS` array or auth will fail
- Void rollback is essential: voiding a scan must restore the unit's prior status
- PKG_OUT auto-routing from batch label suffix eliminates mode selection and prevents cross-channel errors
- Mahesh Reddy's read-only access must be enforced at the worker level, not just the UI
- `product_master` lookup must use `product_code`, not product name — name with LIMIT 1 returns first alphabetical match
- `production_runs.id` in store schema is an integer, NOT a UUID — do not write to `scans.plan_id` without UUID guard
- All RPCs and views counting QC_PASS/INW must JOIN units and filter `component_type='car'` — otherwise car+remote scans both counted
- `get_plan_vs_actual` actuals CTE must join via `units.upc` not `product_master.ean`
- `v_operator_output` grouping by shift caused duplicate rows — shift removed from GROUP BY
- Print server race condition: use conditional PATCH to atomically claim print jobs
- **PKG_OUT scans always have `scans.line = 'SHARED'`** — never use `scans.line` for dispatch attribution. Always join via `pkg_scans.car_upc` to get originating line.
- **Supabase Auth Admin API** requires `SUPABASE_SERVICE_KEY` for both `apikey` AND `Authorization` headers — publishable key is rejected
- **`element.style.display = ''`** removes the display property from inline styles entirely — if the element had `display:grid` as inline style, it will fall back to block. Restore with explicit `style.display = 'grid'`.
- **UPC sticker status `applied` at INW** means the sticker was already used — it's a physical floor issue (duplicate/reused sticker), not a system bug. Fix: fresh available sticker from same batch.

---

## 13. Thermal Printer Setup

- **Model:** TSC TE244 (confirmed, on-site)
- **Label size:** 50mm × 25mm
- **Connection:** USB to Windows laptop at each line's PKG station
- **Print server:** Node.js (`printserver.js` v2.2) — polling Supabase, filtered by line, atomic claim
- **Config:** `config.json` — only `"line"` value differs between laptops (L1/L2/L3)
- **Auto-start:** Windows Startup shortcut on each laptop
- **Label X position:** 24 dots (April 2026). Not yet confirmed on floor.

---

## 14. Shift & Time

- **Regular shift:** 9:00 AM – 6:00 PM (Assembly/QC), 9:00 AM – 7:00 PM (Packing)
- **OT:** 6:00 PM – 9:00 PM
- **Double OT:** After 9:00 PM
- Biometric attendance exists on-site, not connected to system.

---

## 15. Lines & Factory

- 3 lines: L1, L2, L3. Each runs one product at a time.
- Line switches happen mid-day when parts run out (parts availability = primary daily bottleneck).
- **New factory acquired, move pending** — system must support more lines without structural changes. Device and line configuration fully dynamic via dashboard.

---

## 16. Scale

| Month | Units/Month | Scan Events/Month | upc_pool rows (cumulative) |
|---|---|---|---|
| Now | 20,000 | ~100,000 | ~20,000 |
| +6 | 50,000 | ~250,000 | ~220,000 |
| +12 | 124,000 | ~620,000 | ~920,000 |
| +18 | 308,000 | ~1,540,000 | ~3M |
| +24 | 763,000 | ~3,800,000 | ~10M |

Partition scans table by month at ~500K events/month (~month 10). Dashboard summary cards use `get_scan_summary` RPC — never queries raw scan table.

---

## 17. Compliance Readiness

### ISO
- Full unit traceability (raw material → finished product)
- Non-conformance records (QC_FAIL + defect codes + scan violations)
- Corrective action tracking (training flags on repeat defects, Alerts tab)
- Loss notes for scrapped/damaged/rejected units

### IND AS
- WIP: units between INW and RTD
- Finished goods: units at RTD
- Loss notes carry estimated_value field (filled by Vinay)

### Non-negotiable architectural constraints
- Records never deleted — void with reason only
- Every mutation logged with actor + timestamp + metadata
- Timestamps stored UTC, displayed IST
- UPC permanent — never reassigned, never reused

---

## 18. Integration Points

| System | Direction | What | Status |
|---|---|---|---|
| Unicommerce | Out | RTD handoff, channel routing | Manual currently |
| Biometric | In | Headcount / shift data | On-site, not connected |
| External UPC printer | Both | Batch gen → A3 print → receive | ✅ Live |
| TSC TE244 thermal printer | Out | Batch label at each line's PKG station | ✅ Live — polling v2.2 per-line atomic |
| Supabase | Core | All data | ✅ Live |
| Cloudflare Workers | API | Auth, logic, mutations | ✅ Live |
| Store system | Both | Returns handoff, loss notes, handover to production, procurement | ✅ Live |

---

## 19. Build Status

### Live & Confirmed Working ✅
- All scanner flows: INW, QC_PASS, QC_FAIL, WKS, PKG, PKG_OUT, RTO_IN
- PKG label printing — TSC TE244 via Supabase polling v2.2 (atomic claiming)
- Return system — store UI + production Returns Queue + RTD_RETURN dispatch path
- Dashboard — all 12 tabs live
- **Dispatch attribution** — SHARED line removed; per-line counts via pkg_scans.line ← April 2026
- **Hourly achievement chart** — battery-cell design, dispatched metric, pace indicator ← April 2026
- **Store procurement BY UNITS** — variant+color ordering, accumulated queue ← April 2026
- **Auth user creation** — createUser/resetPassword fixed (service key for apikey header) ← April 2026
- **Scans tab UPC search** — auto-pads digits to LOT-XXXXXXXX; summary cards display fix ← April 2026

### Open Issues 🔶

None currently open.

### Pending Build 🔲
- Repair run design session (before any build) — **next priority**
- Price master table — unit cost on procurement; item_type derivation from product master
- Operator performance view — per-operator daily output + trend
- Consolidated dispatch view — fresh RTD + RTD_RETURN combined
- Legacy UPC manual entry path (build when triggered)
- Daily / weekly / monthly reporting views
- Reconciliation module — Phase 4
- Audit module — Phase 5
- Assembly stations module — Phase 6
- Biometric integration
- Unicommerce reconciliation
- Dashboard tab access control (role-gating — deferred)
- PRODUCT_SUBVARIANTS data for Nitro/Dash/Fang/Atlas (Afshaan to update)

---

## 20. Open Design Questions

1. **Variant rework UPC:** Keep original UPC (preferred) or new UPC?
2. **Rework approval:** Who triggers and approves recall/rework orders?
3. **WIP valuation:** Material cost only or material + proportional labour?
4. **Unicommerce integration:** Formal API or reconciliation-based handoff?
5. **Biometric integration:** Headcount only or individual operator clock-in/out?
6. **Defect master ownership:** Who can add/modify defect codes post-seeding?
7. **QC_PASS timeout:** Should second scan time out after N seconds? Recommend yes.
8. **Channel barcode scanning:** Currently manual. Future: scan platform return labels directly.
9. **Legacy UPC gap:** Units dispatched pre-system have no `pkg_scans` record. Build when triggered.
10. **Consolidated dispatch view:** Fresh RTD + RTD_RETURN combined per product per day. Not yet built.
11. **Repair run schema:** Two contexts confirmed — inline (same-day, line-specific) vs repair run (L3 auxiliary). Pending full design session.
12. **Daily/weekly/monthly reporting:** Not yet built.
13. **`packed` vs `pending_rtd`:** Both in enum. When is it safe to remove `packed`?
14. **print_jobs retention:** Cleanup policy for old done/failed rows (suggest 30 days).
15. **All 15 devices APK vs PWA:** Staying on browser PWA viable. Decision deferred.
16. **Operator performance view:** Per-operator daily scan count, trend over time — data exists, view not built.
17. **Non-scanning operator allocation:** 20+ assemblers without scanners — deferred to Phase 6.
18. **Price master table:** For unit-level cost on procurement. item_type should be derived from product master, not hardcoded in BY UNITS mode.
19. **Hourly target split intelligence:** Currently equal (target/9 hrs). Future: weight by historical output pattern, account for startup ramp, lunch dip.

---

## 21. Session Start Protocol

When starting a new build session:
1. Ask Afshaan to share `LOT_BUILD.md`, `LOT_SYSTEM.md`, and any relevant HTML/worker files
2. Read all files before writing any code
3. Check Open Issues first — confirm floor status before building new features
4. Design before build — discuss architecture conversationally before writing instructions

---

*Update at end of every significant build or design session.*
