# Legend of Toys — System Understanding Document
**Version:** 2.1 | **Last Updated:** April 2026
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth. At this trajectory, the system must be designed to handle ~120,000 units/month by end of Year 1 and potentially 700,000+ units/month by end of Year 2.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Google Sheets + Apps Script replaced. New integrated production operations system on Supabase + Cloudflare Workers + PWA is live and scanning on the factory floor. System confirmed working in live floor testing. PKG thermal label printing live via polling architecture v2.2. Per-line PKG and WKS devices deployed April 2026. Operator session tracking live April 2026.

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

**Why Mahesh reports to Afshaan and not Varun:** Deliberate structural decision. Mahesh cross-references inline QC data (Karthik's data) against actual audit findings. If he reported to Varun, there would be mixed incentives. His scope is expanding to cover stores and dispatch as well.

**Mahesh's system access:** Read-only across all data. Cannot amend, void, or modify any record — ever. This is a **permanent system constraint**, not a temporary restriction. Enforced at worker level.

**Operator profile:** Unskilled workers on the factory floor. Do not understand systems. Will make frequent, basic mistakes even after training. Any interface they touch must be **descriptive, visual, and foolproof** — assume the worst-case user at all times.

---

## 3. Products

LOT currently makes RC cars. Future product categories include die cast and DIY sets (no remotes). A finished RC car unit consists of:
- The car body (UPC sticker on the bottom)
- Remote control (UPC sticker — independent from car)
- Accessories (varies by SKU)
- Packaging tray + box (retail box or ecom box — physically different)
- Shrink wrap (applied by machine at end of packaging)

**SKUs:** 86 active SKUs across ~28 products with multiple model/color variants.

**`has_remote` flag:** Product master carries a boolean. RC cars = true. Die cast, DIY sets, future non-remote products = false. Drives scanner behaviour at QC_PASS and PKG (single scan vs two-scan pairing flow).

---

## 4. UPC System ✅ LOCKED

### What UPC is
A ~1.5cm × 1.5cm QR code sticker (metallic/shiny finish). Sent for external printing on A3 sheets. Applied to the bottom of the car / body of the remote. Primary tracking identifier throughout the production system.

### Format — LOCKED
**`LOT-00000001`** — 8 digits, zero-padded, globally sequential. One counter for all products. QR encodes this string. Never reassigned, never reused, never deleted.

### Display Code — LOCKED (updated April 2026)
Human-readable code printed below the QR: **`KNAK00000007`** (product_code + raw 8-digit LOT sequence number, no hyphens).

```
[QR CODE]
KNAK00000007
```

**Previous format `KNAK-7` (per-product seq) is deprecated** — was not globally unique across batches.

### Product Code System ✅ LOCKED
**Formula:** PP+M+C (2-char product + 1-char model + 1-char color)

**Color key:** K=Black, B=Blue, G=Green, R=Red, W=White, E=Grey, S=Silver, P=Purple, V=Purple2, M=Multi, N=Pink, X=Excavator, Y=Yellow, O=Orange, D=Desert

**Remote:** car code + 'R'. If car is KNAK (Knox Adventure Black), remote is KNAKR.

All 86 SKUs have unique 4-char codes seeded in `product_master.product_code`.

### Print Batch Workflow
1. Admin generates batch in dashboard — searchable dropdown, picks component (car/remote) + quantity
2. System assigns LOT- numbers + display codes, creates batch record
3. Print file sent to external printer (A3, **21×29 grid = 609 stickers/sheet**, no borders, 13mm cells)
4. Stickers received → admin clicks Received → all UPCs flip from `generated` → `available`
5. Stickers applied on floor → each UPC marked `applied` when scanned at INW
6. Damaged/unused stickers individually voided — never deleted, never reassigned

**Critical:** When a batch is generated with product_code `SHTK`, the worker must look up `product_master` using `product_code=eq.SHTK`, NOT by product name. Using product name with `LIMIT 1` returns the first alphabetical variant (e.g. Asphalt Black instead of Tarmac Black). Fixed April 2026 after B-014 incident.

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
| WKS | Workshop | 3 (one per line: WKS-L1/L2/L3) | System-inferred: qc_fail→WKS_IN, in_repair→WKS_OUT. Per-line April 2026. |
| PKG | Packaging station | 3 (one per line) | Two-scan (car + remote), channel toggle (E/R), prints batch label via Supabase polling |
| PKG_OUT | Dispatch Out | 1 shared | Scans batch label — auto-routes RTE/RTR (fresh) or RTD_RETURN (return) |
| RTO_IN | Returns | 1 shared | Two paths: intact → direct RTD, damaged → full production flow |

### Detailed Unit Journey

**Stage 1: Assembly → INW**
Car assembled, UPC sticker applied. Remote assembled (last station same line), UPC sticker applied. Each scanned independently at INW.

**Stage 2: QC**
- *has_remote = true:* Operator scans car → green prompt "📡 NOW SCAN REMOTE" → scans remote → pairing created at QC_PASS.
- *has_remote = false:* Single scan only.

**Stage 3: Workshop (if QC_FAIL)**
- Operator scans UPC at WKS device → scanner calls `postWksScan`
- `qc_fail` → WKS_IN recorded, unit → `in_repair`, defects shown, loop_count+1
- Line taken from device.line directly (WKS-L1/L2/L3)
- Active production run auto-associated as plan_id
- `in_repair` → WKS_OUT_PENDING — operator taps REPAIRED or CANNOT FIX
- REPAIRED → unit → `repaired`, eligible for QC again
- SCRAPPED → unit → `scrapped`, scrap loss note auto-created

**Two repair contexts (design confirmed, repair run not yet built):**
- **Inline repair:** Same day, same production run. Unit fails QC on L1, workshop on L1 fixes it. WKS-L1 device.
- **Repair run:** Spillover units that couldn't be fixed inline. Flushed to store at EOD. Issued to L3 (auxiliary line) on a later day for a dedicated repair run. Needs formal production run type — design session pending.

**Stage 4: Packaging (PKG)**
- Operator sets channel (ECOM / RETAIL) on device.
- Scans car UPC (must be qc_pass) → scans remote UPC → system verifies confirmed pair.
- System generates batch label: `LOT-XXXXXXXX-E` or `LOT-XXXXXXXX-R`.
- Print job inserted into `print_jobs` table (with `line` field) → line-specific print server polls and prints on that line's TSC TE244.
- Unit status → `pending_rtd`.

**Stage 5: Dispatch Out (PKG_OUT)**
- Operator scans batch label.
- **Fresh:** `-E` → RTE, `-R` → RTR. Both car and remote → `rtd`.
- **Return RTD:** Unit already `rtd` with active `rtd_direct` return in `handed_over` shipment → `RTD_RETURN`. Does NOT count in tally.

**Stage 6: Returns (RTO_IN)**
- Full return system built. See Section 11.

### Batch Label Format — LOCKED
**`LOT-XXXXXXXX-E`** (ecom) or **`LOT-XXXXXXXX-R`** (retail)

Visual print on label: Barcode · Batch label · Product name · Channel · Date packed — 50×25mm on TSC TE244.

---

## 6. Devices & Station Mapping ✅ LOCKED

Each device is permanently associated with one station and one line.

| Device Code | Station | Line |
|---|---|---|
| INW-L1/L2/L3 | INW | L1/L2/L3 |
| QCP-L1/L2/L3 | QC_PASS | L1/L2/L3 |
| QCF-L1/L2/L3 | QC_FAIL | L1/L2/L3 |
| WKS-L1/L2/L3 | WKS | L1/L2/L3 ← per-line April 2026 |
| PKG-L1/L2/L3 | PKG | L1/L2/L3 |
| PKG-OUT | PKG_OUT | SHARED |
| RTO | RTO_IN | SHARED |

Total: 19 devices (increased from 16 with per-line WKS). Scanner setup screen: operator selects Station + Line (2 taps). Device code auto-resolved from DB.

**Devices table columns:** id, device_code, label, line, station, is_active, created_at, last_seen, notes. No `activity` column.

---

## 7. Operators & Sessions ← new April 2026

### Operator Master (`operators` table)
- Name, role (enum), default line, QR code, active status
- Managed via dashboard Operators tab (Varun/Siddhant)
- QR code format: `LOT-OP-XXXXXXXX` (auto-generated on creation)
- Physical QR ID cards printed from dashboard → used for scanner login

### Operator Roles (enum)
`assembly | qc_inline | qc_audit | repair | packing | rtd | store | supervisor | line_manager | production_manager | admin`

### Session Tracking (`operator_sessions` table)
- Created automatically on every QR card scan login
- Closed when next operator scans on same device
- No explicit logout — shift attribution via timestamps
- Fields: operator_id, device_id, station, line, production_run_id, shift, login_at, logout_at

### Shift Time Boundaries (IST)
- **Regular:** 9:00 AM – 6:00 PM (Assembly/QC), 9:00 AM – 7:00 PM (Packing)
- **OT:** 6:00 PM – 9:00 PM
- **Double OT:** After 9:00 PM
- One-hour overlap (6–7pm) is intentional — PKG runs later than Assembly/QC for load balancing

### Station Allocation Philosophy
- QR card login = station allocation record. No separate allocation UI.
- Non-scanning operators (20+ assemblers without scanner access) are NOT tracked yet — deferred to Phase 6 when all stations get scanners.
- Manual select login on scanner does NOT create a session record.

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

**Dashboard tabs (April 2026):** Dashboard (formerly Executive), Lines, QC, UPC Generator, Scans, Corrections, Alerts, Returns, Reporting, Operators, Print. Runs tab removed — Plan vs Actual now on Dashboard tab.

---

## 9. Returns System

### Return Categories
| Code | Name | Definition |
|---|---|---|
| UDR | Undamaged Return | Box sealed, product untouched. Batch label scannable on outside. |
| CXR | Customer Return | Customer opened, returned for any reason. |
| BRV | Bulk Return — Vendor | Platform or trade partner returning unsold/excess stock in bulk. |

### Return Actions
- `rtd_direct` — UDR intact → PKG_OUT → `RTD_RETURN`
- `wks_repair` → WKS → repair → QC → PKG → RTD

### Return Flow
1. Store receives return shipment
2. Store does intake → inspection → disposition (3-stage UI)
3. Store hands over to production
4. Production — UDR: Scan batch label at PKG_OUT → `RTD_RETURN`. Does not count in RTE/RTR tally.
5. Production — Damaged: `wks_repair` → repair run (pending design)

**RTO_IN intact path:** ✅ Built April 2026. Scan batch label at PKG_OUT → worker detects rto_in status → RTD_RETURN scan written → units set to rtd. Counted separately from fresh RTE/RTR in tally.

---

## 10. Print System

### Architecture
- `print_jobs` table in public schema
- Print server (Node.js v2.2) on each line's PKG laptop
- Each server filters by its own line — no cross-line contamination
- **Atomic claiming (v2.2):** PATCH with `WHERE status=eq.pending` — if another server already claimed, returns empty array → skip. Prevents duplicate prints on simultaneous polls.

### Self-Service Reprints (April 2026)
Dashboard Print tab allows management to reprint any label:
- Single: enter batch label or car UPC (auto-prefixes LOT-)
- Bulk: paste multiple values
- Routes to correct line printer automatically via `print_jobs.line`

### When to use
- Label torn or lost
- Printer was offline during PKG scan
- Wrong label printed (product data corrected in DB after scan)

---

## 11. Key Technical Learnings (don't repeat these mistakes)

- The `unit_status` enum uses lowercase values (`rtd`, `in_repair`, `qc_pass`, etc.); the `activity_type` enum uses uppercase (`INW`, `QC_PASS`, `RTR`, etc.) — mixing these caused live bugs
- Supabase's default row limit (1000) silently caps query results; use Postgres RPCs to bypass for accurate counts
- WKS direction (IN/OUT) must be system-inferred from unit status, not operator-selected
- Worker endpoints must be in the `SCANNER_ACTIONS` array or auth will fail
- Void rollback is essential: voiding a scan must restore the unit's prior status
- PKG_OUT auto-routing from batch label suffix eliminates mode selection and prevents cross-channel errors
- Mahesh Reddy's read-only access must be enforced at the worker level, not just the UI
- Operator-facing error messages should be specific and descriptive, not generic
- `product_master` lookup must use `product_code`, not product name — name with LIMIT 1 returns first alphabetical match
- `production_runs.id` in store schema is an integer, NOT a UUID — do not write to `scans.plan_id` without UUID guard
- All RPCs and views that count QC_PASS/INW must JOIN units and filter component_type='car' — otherwise car+remote scans are both counted, doubling unit counts
- get_plan_vs_actual actuals CTE must join via units.upc not product_master.ean — ean is often NULL on scans
- v_operator_output grouping by shift caused duplicate rows per operator — shift removed from GROUP BY
- Upcoming runs filter must include 'Issued' status, not just 'Submitted' — runs get issued before their run date
- Print server race condition: two servers polling simultaneously can both see the same pending job in the 2-second window — use conditional PATCH to atomically claim

---

## 12. Print Server Setup

**On each line's PKG laptop:**
1. `config.json` — set `"line"` to `"L1"`, `"L2"`, or `"L3"`
2. `printserver.js` — identical file on all laptops (v2.2)
3. `npm install` (first time only)
4. Windows Startup shortcut to auto-start

**v2.2 deployed to:** L1 ✅ | L2 ⏳ needs deployment | L3 ⏳ needs deployment

---

## 13. Thermal Printer Setup

- **Model:** TSC TE244 (confirmed, on-site)
- **Label size:** 50mm × 25mm
- **Connection:** USB to Windows laptop at each line's PKG station
- **Print server:** Node.js (`printserver.js` v2.2) — polling Supabase, filtered by line, atomic claim
- **Config:** `config.json` — only `"line"` value differs between laptops (L1/L2/L3); all other config identical
- **Auto-start:** Windows Startup shortcut on each laptop
- **Label X position:** 24 dots (April 2026, shifted from 8). Not yet confirmed on floor.

---

## 14. Shift & Time

- **Regular shift:** 9:00 AM – 6:00 PM (Assembly/QC), 9:00 AM – 7:00 PM (Packing)
- **OT:** 6:00 PM – 9:00 PM
- **Double OT:** After 9:00 PM
- Biometric attendance exists on-site, not connected to system.
- Downtime tracking needed, not yet implemented.

---

## 15. Lines & Factory

- 3 lines: L1, L2, L3. Each runs one product at a time.
- Line switches happen mid-day when parts run out (parts availability = primary daily bottleneck).
- **New factory acquired, move pending** — system must support more lines, more scan points, more devices without structural changes. Device and line configuration fully dynamic via dashboard.

---

## 16. Scale

| Month | Units/Month | Scan Events/Month | upc_pool rows (cumulative) |
|---|---|---|---|
| Now | 20,000 | ~100,000 | ~20,000 |
| +6 | 50,000 | ~250,000 | ~220,000 |
| +12 | 124,000 | ~620,000 | ~920,000 |
| +18 | 308,000 | ~1,540,000 | ~3M |
| +24 | 763,000 | ~3,800,000 | ~10M |

Scan events is the largest table. Partition by month at ~500K events/month (~month 10). Dashboard summary cards use `get_scan_summary` RPC — never queries raw scan table. Supabase max rows set to 5000.

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
- Valuation method: open question
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
| TSC TE244 thermal printer | Out | Batch label at each line's PKG station (50×25mm) | ✅ Live — polling v2.2 per-line atomic |
| Supabase | Core | All data | ✅ Live |
| Cloudflare Workers | API | Auth, logic, mutations | ✅ Live |
| Store system | Both | Returns handoff, loss notes, handover to production | ✅ Live |

---

## 19. Build Status

### Live & Confirmed Working ✅
- Supabase DB — all tables, schema confirmed
- Product master — 86 SKUs with product codes
- Defect master — 35 codes, component filtering configured
- Scanner PWA (`scanner.legendoftoys.com`) — all scan flows working
- Worker (`lotopsproxy.afshaan.workers.dev`) — all endpoints live
- INW scan — car and remote, product_code-based lookup (confirmed April 2026)
- QC_PASS two-scan pairing flow
- QC_FAIL defect modal with component filtering
- WKS flow — per-line devices, device.line used directly, plan_id auto-association
- Repair loop — QC_FAIL → WKS → repaired → QC_PASS
- PKG scan — pair verify, status → `pending_rtd`, print job inserted with line
- PKG label printing — TSC TE244 via Supabase polling v2.2 (atomic claiming)
- PKG_OUT — RTE/RTR dispatch + RTD_RETURN path
- Scan violation logging + Alerts tab
- Station status controls
- Void rollback
- **Return system** — store UI + production Returns Queue tab + RTD_RETURN dispatch path ← intact path completed April 2026
- **Store return endpoints** — postReturnShipment, postReturnUnit, postReturnInspection, postReturnHandover, getReturnShipments, getReturnShipment ← built April 2026
- **Dashboard overhaul** ← April 2026:
  - Executive renamed to Dashboard; Runs tab removed; Plan vs Actual as cards on Dashboard
  - Date bar only on Dashboard tab; all other tabs default to today; Reporting has own internal date controls
  - Car/remote split throughout — Dashboard cards, Lines cards, Operator table, Scans summary cards
  - All RPCs fixed to use car-only unit counts (was double-counting car+remote)
  - Hourly chart shows grouped per-line bars (L1/L2/L3)
  - UPC search in Scans tab is all-time (no date restriction)
  - Per-tab date state independence
  - get_plan_vs_actual fixed (was joining on EAN, now joins on UPC via units)
  - Upcoming runs shows Issued future runs, not just Submitted
- Scan summary cards via `get_scan_summary` RPC
- Executive tab
- Lines tab
- QC tab — cycle time cards with IQR outlier detection restored April 2026
- Runs tab
- Reporting tab — all 3 cycle time segments, QC summary, product breakdown, daily, line
- UPC Generator
- Scans tab
- Corrections tab
- Alerts tab — getViolations + getViolationSummary restored April 2026
- Returns tab — getReturnQueue restored April 2026
- **Operators tab** — CRUD, sessions today, QR card print ← new April 2026
- **Print tab** — single + bulk reprint, auto-routing to correct line printer ← new April 2026
- **Operator sessions** — created on QR login, closed on next login same device ← new April 2026

### Open Issues 🔶

None currently open.

### Pending Build 🔲
- Repair run design session (before any build) — next priority
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
9. **Legacy UPC gap:** Units dispatched pre-system have no `pkg_scans` record. Design agreed — build when triggered.
10. **Consolidated dispatch view:** Fresh RTD + RTD_RETURN combined per product per day. Not yet built.
11. **Repair run schema:** Two contexts confirmed — inline (same-day, line-specific) vs repair run (L3 auxiliary, cross-line, formal run). Pending full design session.
12. **Daily/weekly/monthly reporting:** Not yet built.
13. **`packed` vs `pending_rtd`:** Both in enum. When is it safe to remove `packed`?
14. **print_jobs retention:** Cleanup policy for old done/failed rows (suggest 30 days).
15. **All 15 devices APK vs PWA:** With polling print solved, staying on browser PWA is viable for all. Decision deferred.
16. **Operator performance view:** Per-operator daily scan count, trend over time — data exists, view not built.
17. **Non-scanning operator allocation:** 20+ assemblers without scanners — deferred to Phase 6.

---

## 21. Session Start Protocol

When starting a new build session:
1. Ask Afshaan to share `LOT_BUILD.md`, `LOT_SYSTEM.md`, and any relevant HTML/worker files
2. Read all files before writing any code
3. Check Open Issues first — confirm floor status before building new features
4. Design before build — discuss architecture conversationally before writing instructions

---

*Update at end of every significant build or design session.*
