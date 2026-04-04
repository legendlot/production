# Legend of Toys — System Understanding Document
**Version:** 1.5 | **Last Updated:** April 2026
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth. At this trajectory, the system must be designed to handle ~120,000 units/month by end of Year 1 and potentially 700,000+ units/month by end of Year 2.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Google Sheets + Apps Script replaced. New integrated production operations system on Supabase + Cloudflare Workers + PWA is live and scanning on the factory floor. System confirmed working in live floor testing.

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

### Display Code — LOCKED
Human-readable code printed below the QR: **`KNAK-7`** (product_code + per-product sequence number).

- Product code: 4-char for cars (KNAK), 5-char for remotes (KNAKR = car code + R)
- Per-product sequence: independent counter per product_code — KNAK-1, KNAK-2... completely separate from FLBG-1, FLBG-2...
- Sticker shows only the display code — no LOT- number printed. QR still encodes LOT-XXXXXXXX.

```
[QR CODE]
KNAK-7
```

### Product Code System ✅ LOCKED
**Formula:** PP+M+C (2-char product + 1-char model + 1-char color)

**Color key:** K=Black, B=Blue, G=Green, R=Red, W=White, E=Grey, S=Silver, P=Purple, V=Purple2, M=Multi, N=Pink, X=Excavator, Y=Yellow, O=Orange, D=Desert

**Remote:** car code + 'R'. If car is KNAK (Knox Adventure Black), remote is KNAKR.

All 86 SKUs have unique 4-char codes seeded in `product_master.product_code`.

### Print Batch Workflow
1. Admin generates batch in dashboard — picks product + component (car/remote) + quantity
2. System assigns LOT- numbers + display codes, creates batch record
3. Print file sent to external printer (A3, 16×23 grid = 368 stickers/sheet)
4. Stickers received → admin clicks Received → all UPCs flip from `generated` → `available`
5. Stickers applied on floor → each UPC marked `applied` when scanned at INW
6. Damaged/unused stickers individually voided — never deleted, never reassigned

**Status lifecycle:** `generated` → `available` → `applied` | `damaged` | `unused`

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
| WKS | Workshop | 1 shared | System-inferred: qc_fail→WKS_IN, in_repair→WKS_OUT |
| PKG | Packaging station | 1 shared | Two-scan (car + remote), channel toggle (E/R), prints batch label |
| PKG_OUT | Dispatch Out | 1 shared | Scans batch label — auto-routes RTE or RTR from label suffix |
| RTO_IN | Returns | 1 shared | Two paths: intact → direct RTD, damaged → full production flow |

### Detailed Unit Journey

**Stage 1: Assembly → INW**
- Car assembled, UPC sticker applied. Remote assembled (last station same line), UPC sticker applied.
- Each scanned independently at INW. System identifies car vs remote from `upc_pool.product_code`.

**Stage 2: QC**
- *has_remote = true:* Operator scans car → green prompt "📡 NOW SCAN REMOTE" → scans remote → pairing created at QC_PASS. Failed component goes to QC_FAIL independently.
- *has_remote = false:* Single scan only.

**Stage 3: Workshop (if QC_FAIL)**
- Operator scans UPC at WKS device. System infers direction from unit status:
  - `qc_fail` → WKS_IN recorded, unit → `in_repair`, defects from QC_FAIL shown on screen, loop_count+1
  - `in_repair` → WKS_OUT_PENDING — operator taps REPAIRED or CANNOT FIX (SCRAP)
  - REPAIRED → unit → `repaired`, eligible for QC again
  - SCRAPPED → unit → `scrapped`, scrap loss note auto-created (pending Siddhant+ approval)

**Stage 4: Packaging (PKG)**
- Operator sets channel (ECOM / RETAIL) on device — prominent toggle.
- Scans car UPC (must be qc_pass) → scans remote UPC → system verifies confirmed pair from QC_PASS.
- System generates batch label: `LOT-XXXXXXXX-E` or `LOT-XXXXXXXX-R`.
- Label printed automatically on TSC TE244 thermal printer at station.
- Operator applies to outside of box → box sealed → accessories in → shrink wrap.
- Unit status → `pending_rtd`.

**Stage 5: Dispatch Out (PKG_OUT)**
- Operator scans batch label.
- System auto-routes: `-E` suffix → RTE (ecom), `-R` suffix → RTR (retail).
- Cross-channel protection: `-E` label at RTR station → hard reject and vice versa.
- Both car and remote status → `rtd`.

**Stage 6: Returns (RTO_IN)**
- Full return system built. See Section 11.

### Batch Label Format — LOCKED
**`LOT-XXXXXXXX-E`** (ecom) or **`LOT-XXXXXXXX-R`** (retail)

Visual print on label: Barcode · Batch label · Product name · Channel · Date packed — 50×25mm on TSC TE244.

---

## 6. Devices & Station Mapping ✅ LOCKED

Each device is permanently associated with one station and one activity. The station IS the lock — operators cannot change scan type on a configured device.

| Device Code | Station | Activity | Line |
|---|---|---|---|
| INW-L1/L2/L3 | INW | INW | L1/L2/L3 |
| QCP-L1/L2/L3 | QC_PASS | QC_PASS | L1/L2/L3 |
| QCF-L1/L2/L3 | QC_FAIL | QC_FAIL | L1/L2/L3 |
| WKS | WKS | WKS (system-inferred IN/OUT) | SHARED |
| PKG | PKG | PKG (pair verify + print) | SHARED |
| PKG-OUT | PKG_OUT | RTE or RTR (auto from label) | SHARED |
| RTO | RTO_IN | RTO_IN | SHARED |

Total: 14 devices. Scanner setup screen: operator selects Station + Line (2 taps). Device code auto-resolved from DB.

**Print server URL** is configured per device in the scanner setup screen (field: Print Server URL). Defaults to `http://localhost:3000`. PKG station must have the laptop's local IP entered here.

---

## 7. Reporting & Dashboard ✅ LIVE

**URL:** `dashboard.legendoftoys.com`

### Dashboard Tabs
| Tab | Users | Content | Status |
|---|---|---|---|
| Executive | Afshaan, Vinay, Varun | Today KPIs, hourly output (with counts), runs split Active/Upcoming | ✅ Live |
| Lines | Siddhant, Kishan | Per-line cards (today only), first scan time, operator stats | ✅ Live |
| QC | Karthik, Mahesh | QC cycle time cards, FPY by line, defect heatmap, repeat failures | ✅ Live |
| Runs | All production_view | Plan vs actual | ✅ Live |
| UPC Generator | Afshaan, Varun | Generate batches, print stickers, receive | ✅ Live |
| Scans | All | Real-time scan feed, activity filter, UPC search | ✅ Live |
| Corrections | Siddhant upwards | Tier 2 void + Tier 3 amend | ✅ Live |
| Alerts | All (Kishan upwards to acknowledge) | Scan violations, badge count, acknowledge | ✅ Live |
| Returns | Production team | Returns queue handed off from store | ✅ Live |
| Reporting | Afshaan, Vinay, Varun | Date-range reporting — daily output, line breakdown | 🔶 Scaffold only |

**Lines tab is locked to today** — no date range. Historical line data lives in the Reporting tab.

Auto-refresh: 30 seconds. Scans tab summary uses `get_scan_summary` RPC — accurate regardless of row limits.

---

## 8. Scan Correction — Three Tiers ✅ BUILT

**Tier 1 — Operator (self-serve, no approval)**
- Available until unit receives its next scan in the flow
- `voided = true` on scan row, logged automatically
- Unit status rolled back to previous scan's status automatically
- Accessible from scanner app

**Tier 2 — Supervisor within shift (Kishan / Karthik)**
- Can void any scan from the current shift
- Mandatory reason required
- Unit status rolled back automatically on void
- Permission: `scan_void_supervisor` on role
- Accessible from Corrections tab on dashboard

**Tier 3 — Post-shift amendment (Siddhant / Varun / Afshaan)**
- Can amend line, operator_id, or notes on any scan after shift close
- Mandatory reason, immutable audit trail in `scan_amendments`
- Permission: `scan_amend_manager` on role
- Accessible from Corrections tab on dashboard

**Mahesh:** Read-only. No amendment access at any tier, ever.

**Important:** Voiding a scan now automatically rolls back `units.current_status` to match the previous non-voided scan. This prevents units getting stuck after incorrect voids.

---

## 9. Defect Master ✅ CONFIGURED

35 active defect codes across 5 categories:
- **Car-Functional** (CF-01 to CF-09) — component = `car`
- **Car-Visual** (CV-01 to CV-08) — component = `car`
- **Remote-Functional** (RF-01 to RF-08) — component = `remote`
- **Remote-Visual** (RV-01 to RV-04) — component = `remote`
- **Packaging** (PK-01 to PK-05) — component = `both`, currently inactive

Scanner filters defect list by `component_type` of unit being scanned — car scans show only car defects, remote scans show only remote defects.

---

## 10. Station Status Controls & Violations ✅ BUILT

Every scan point validates the unit's previous status before accepting. Wrong-sequence scans are:
1. Hard-rejected on the scanner with a specific, descriptive error message
2. Logged to `scan_violations` table with full context
3. Visible immediately on the Alerts tab of the dashboard

**Validation rules:**
- INW — rejects if UPC has been scanned before (already in system)
- QC_PASS — rejects unless status is `inwarded` or `repaired`
- QC_FAIL — rejects unless status is `inwarded` or `repaired`
- WKS — rejects unless status is `qc_fail` or `in_repair`
- PKG — rejects unless status is `qc_pass`

**Alerts tab:** Permanent log of all violations. Supervisors (Kishan upwards) can acknowledge with optional note. Mahesh has read-only access. Acknowledged rows remain visible but dimmed. Badge on tab shows unacknowledged count.

---

## 11. Return System ✅ BUILT

### Return Categories
| Code | Name | Definition |
|---|---|---|
| UDR | Undamaged Return | Box sealed, product untouched. Batch label scannable on outside. |
| CXR | Customer Return | Customer opened, returned for any reason. |
| BRV | Bulk Return — Vendor | Platform or trade partner returning unsold/excess stock in bulk. |

### Return Flow
1. **Store — Intake:** Receive shipment, log each unit (RS-XXXX → RU-XXXX)
2. **Store — Inspection:** Open return, record box condition, product condition, UPC if readable, disposition
3. **Disposition options:**
   - `rtd_direct` — UDR intact → production scans at RTO_IN → RTD
   - `wks_repair` → production sends to WKS → repairs → QC → PKG → RTD
   - `loss_damage` → damage note created (LN-XXXX), Siddhant+ approval required
   - `loss_rejection` → rejection note created, Siddhant+ approval required
4. **Production — Returns Queue tab:** Shows all units handed off from store. ACTION button marks unit as actioned.
5. **Auto-linking:** When production scans a return unit at RTO_IN or WKS, `return_production_links` record closes automatically.

### Loss Notes
Three types: `damage`, `rejection`, `scrap`. All require Siddhant / Varun / Afshaan / Vinay approval (`loss_note_approve` permission). Scrap notes auto-created when WKS_OUT → scrapped. Permanent log, never deleted.

### Channel Master (`store.channels`)
Dynamic table of sales channels with platform, fulfilment model, return behaviour (segregated/unsegregated). Managed by Varun/Vinay/Afshaan (`channel_manage` permission).

---

## 12. Cycle Time Analytics 🔶 PARTIAL

Three cycle time segments tracked across the production flow:

| Segment | Measures | From → To | RPC | Status |
|---|---|---|---|---|
| QC Cycle Time | Time in assembly + QC queue | INW → QC_PASS or QC_FAIL | `get_qc_cycle_time` | ✅ Built |
| PKG Cycle Time | Time from QC pass to packaging | QC_PASS → PKG | `get_pkg_cycle_time` | 🔲 Pending |
| RTD Cycle Time | Time from packaging to dispatch | PKG → RTE or RTR | `get_rtd_cycle_time` | 🔲 Pending |

**Design principles:**
- All cycle time RPCs exclude gaps > 480 minutes to avoid overnight distortion
- Display format: "14.2 min" for < 60 min, "1h 23m" for ≥ 60 min
- Each RPC returns: `avg_mins_all`, split averages by outcome, `units_measured`, `fastest_mins`, `slowest_mins`
- QC cycle time displayed on QC tab. PKG and RTD cycle times will be added to Reporting tab when built.

---

## 13. Rework & Reverse Flow Scenarios

**Core principle:** Original records preserved. Rework captured as distinct event. Final state drives reports. Lineage always queryable.

### Scenario A: Variant Rework
**Example:** Flare Burnout Grey → convert to Flare Race Grey.
- **Open question:** keep original UPC or issue new one?

### Scenario B: Post-Dispatch Part Replacement
- RTD stock: pulled back via internal rework order
- Post-dispatch: RTO_IN → rework order

### Scenario C: Workshop Repair ✅ IMPLEMENTED
Standard QC_FAIL → WKS (system-inferred WKS_IN) → WKS (REPAIRED or SCRAPPED) → re-QC or end. Loop count tracks cycles.

### Scenario D: Packaging Rework
Original RTE/RTR voided via Tier 2/3; new scan created and linked.

---

## 14. Open Design Questions

1. **Variant rework UPC:** Keep original UPC (preferred — history carries over) or new UPC?
2. **Rework approval:** Who triggers and approves recall/rework orders?
3. **WIP valuation:** Material cost only or material + proportional labour?
4. **Unicommerce integration:** Formal API or reconciliation-based handoff?
5. **Biometric integration:** Headcount only or individual operator clock-in/out?
6. **Defect master ownership:** Who can add/modify defect codes post-seeding?
7. **RTO_IN two-path:** Intact UDR with batch label → RTD direct path not yet wired in scanner.
8. **QC_PASS timeout:** Should second scan time out after N seconds? Recommend yes.
9. **Channel barcode scanning:** Currently manual entry. Future: scan platform return labels directly.

---

## 15. Thermal Printer Setup

- **Model:** TSC TE244 (confirmed, on-site)
- **Label size:** 50mm × 25mm
- **Connection:** USB to Windows laptop at PKG station
- **Print server:** Node.js (`printserver.js`) running on laptop, port 3000
- **Protocol:** TSPL commands via Windows printer driver (raw printing via `@thiagoelg/node-printer`)
- **Auto-start:** Windows Startup shortcut created by `setup.bat`
- **Scanner config:** Print Server URL field in setup screen per device
- **Status:** Print server code built. Pending driver install + USB connection on floor.

---

## 16. Shift & Time

- One shift: 9:00 AM – 6:00 PM. OT possible.
- Biometric attendance exists on-site, not connected.
- Downtime tracking needed, not yet implemented.

---

## 17. Lines & Factory

- 3 lines: L1, L2, L3. Each runs one product at a time.
- Line switches happen mid-day when parts run out (parts availability = primary daily bottleneck).
- **New factory acquired, move pending** — system must support more lines, more scan points, more devices without structural changes. Device and line configuration fully dynamic via dashboard.

---

## 18. Scale

| Month | Units/Month | Scan Events/Month | upc_pool rows (cumulative) |
|---|---|---|---|
| Now | 20,000 | ~100,000 | ~20,000 |
| +6 | 50,000 | ~250,000 | ~220,000 |
| +12 | 124,000 | ~620,000 | ~920,000 |
| +18 | 308,000 | ~1,540,000 | ~3M |
| +24 | 763,000 | ~3,800,000 | ~10M |

Scan events is the largest table. Partition by month at ~500K events/month (~month 10). Dashboard summary cards use `get_scan_summary` RPC — never queries raw scan table. Supabase max rows set to 5000. getAllScans display limit 2000.

---

## 19. Compliance Readiness

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
- BOM versions are historical

---

## 20. Integration Points

| System | Direction | What | Status |
|---|---|---|---|
| Unicommerce | Out | RTD handoff, channel routing | Manual currently |
| Biometric | In | Headcount / shift data | On-site, not connected |
| External UPC printer | Both | Batch gen → A3 print → receive | ✅ Live |
| TSC TE244 thermal printer | Out | Batch label at PKG station (50×25mm) | Code built, pending hardware setup |
| Supabase | Core | All data | ✅ Live |
| Cloudflare Workers | API | Auth, logic, mutations | ✅ Live |
| Store system | Both | Returns handoff, loss notes | ✅ Live |

---

## 21. Build Status

### Live & Confirmed Working ✅
- Supabase DB — all tables, schema confirmed
- Product master — 86 SKUs with product codes
- Defect master — 35 codes, component filtering configured
- Scanner PWA (`scanner.legendoftoys.com`) — all scan flows working
- Worker (`lotopsproxy.afshaan.workers.dev`) — all endpoints live
- Dashboard (`dashboard.legendoftoys.com`) — 10 tabs live
- INW scan — car and remote (confirmed on floor)
- QC_PASS two-scan pairing flow (confirmed on floor)
- QC_FAIL defect modal with component filtering (confirmed on floor)
- WKS system-inferred flow — WKS_IN (defect display) + WKS_OUT (REPAIRED/SCRAP) (confirmed on floor)
- Repair loop — QC_FAIL → WKS → repaired → QC_PASS (confirmed on floor)
- Scan violation logging + Alerts tab
- Station status controls (INW, QC_PASS, QC_FAIL, WKS, PKG validation)
- Void rollback — unit status rolled back on scan void
- Return system — store UI (shipments, inspection, loss notes, channels) + production Returns Queue tab
- Return auto-linking — WKS_IN, WKS_OUT, RTO_IN close return_production_links automatically
- Scan summary cards via `get_scan_summary` RPC (accurate, no row limit issue)
- Executive tab — hourly bar chart with count labels, runs split Active Today / Upcoming
- Lines tab — locked to today, first scan time per line, dispatch-based completion %
- QC tab — QC cycle time cards (avg, pass, fail, fastest, slowest)
- Reporting tab — scaffold with summary cards, daily output, line breakdown

### Pending Testing 🔶
- PKG pair verification + batch label print (thermal printer not yet physically connected)
- PKG_OUT dispatch scan

### Pending Build 🔲
- **PKG cycle time** — RPC `get_pkg_cycle_time` (QC_PASS → PKG) + dashboard display
- **RTD cycle time** — RPC `get_rtd_cycle_time` (PKG → RTE/RTR) + dashboard display
- **Full Reporting tab** — product breakdown, QC summary, cycle time summary, defect trend
- Operator QR card print flow — dashboard page to print physical ID cards
- Operator onboarding + station assignment flow
- Thermal printer physical setup (driver install, USB connection, print server running)
- RTO_IN two-path flow — scanner side (intact UDR with batch label → RTD direct)
- Reconciliation module — Phase 4
- Audit module — Mahesh's dedicated view — Phase 5
- Assembly stations module — Phase 6
- Biometric integration
- Unicommerce reconciliation
- Dashboard tab access control (role-gating — deferred)
- UPC generator fix — variant showing in remote entries
- Print sheet size fix + missing variant on print
- Beep fix on scanner

---

*Update at end of every significant build or design session.*
