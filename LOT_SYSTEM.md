# Legend of Toys — System Understanding Document
**Version:** 1.3 | **Last Updated:** April 2026
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth. At this trajectory, the system must be designed to handle ~120,000 units/month by end of Year 1 and potentially 700,000+ units/month by end of Year 2.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Google Sheets + Apps Script replaced. New integrated production operations system on Supabase + Cloudflare Workers + PWA is live and scanning on the factory floor.

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
| WKS_IN | Workshop Entry | 1 shared | |
| WKS_OUT | Workshop Exit | 1 shared | |
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
- WKS_IN → repair → WKS_OUT → re-enters QC. Loop count incremented.

**Stage 4: Packaging (PKG)**
- Operator sets channel (ECOM / RETAIL) on device — prominent toggle.
- Scans car UPC (must be qc_pass) → scans remote UPC → system verifies confirmed pair from QC_PASS.
- System generates batch label: `LOT-XXXXXXXX-E` or `LOT-XXXXXXXX-R`.
- Label printed on thermal printer at station. Operator applies to outside of box.
- Box sealed → accessories in → shrink wrap.
- Unit status → `pending_rtd`.

**Stage 5: Dispatch Out (PKG_OUT)**
- Operator scans batch label.
- System auto-routes: `-E` suffix → RTE (ecom), `-R` suffix → RTR (retail).
- Cross-channel protection: `-E` label at RTR station → hard reject and vice versa.
- Both car and remote status → `rtd`.

**Stage 6: Returns (RTO_IN)**
- Intact/untouched returns → direct to RTD, bypasses production flow.
- Damaged returns → full production flow from INW.
- All RTO activity visible in Mahesh's audit view.

### Batch Label Format — LOCKED
**`LOT-XXXXXXXX-E`** (ecom) or **`LOT-XXXXXXXX-R`** (retail)

Visual print on label: Product · Model · Color · Channel · Date packed

---

## 6. Devices & Station Mapping ✅ LOCKED

Each device is permanently associated with one station and one activity. The station IS the lock — operators cannot change scan type on a configured device.

| Device Code | Station | Activity | Line |
|---|---|---|---|
| INW-L1/L2/L3 | INW | INW | L1/L2/L3 |
| QCP-L1/L2/L3 | QC_PASS | QC_PASS | L1/L2/L3 |
| QCF-L1/L2/L3 | QC_FAIL | QC_FAIL | L1/L2/L3 |
| WKS | WKS | WKS (modal per scan) | SHARED |
| PKG | PKG | PKG (pair verify + print) | SHARED |
| PKG-OUT | PKG_OUT | RTE or RTR (auto from label) | SHARED |
| RTO | RTO_IN | RTO_IN | SHARED |

Total: 14 devices. Scanner setup screen: operator selects Station + Line (2 taps). Device code auto-resolved from DB.

---

## 7. Reporting & Dashboard ✅ LIVE

**URL:** `dashboard.legendoftoys.com`

### Dashboard Tabs
| Tab | Users | Content | Status |
|---|---|---|---|
| Executive | Afshaan, Vinay, Varun | Today KPIs, hourly output, open runs | ✅ Live |
| Lines | Siddhant, Kishan | Per-line cards, operator stats | ✅ Live |
| QC | Karthik, Mahesh | FPY by line, defect heatmap, repeat failures | ✅ Live |
| Runs | All production_view | Plan vs actual | ✅ Live |
| UPC Generator | Afshaan, Varun | Generate batches, print stickers, receive | ✅ Live |
| Scans | All | Real-time scan feed, activity filter, UPC search | ✅ Live |
| Corrections | Siddhant upwards | Tier 2 void + Tier 3 amend | ✅ Live |

Auto-refresh: 30 seconds.

---

## 8. Scan Correction — Three Tiers ✅ BUILT

**Tier 1 — Operator (self-serve, no approval)**
- Available until unit receives its next scan in the flow
- `voided = true` on scan row, logged automatically
- Accessible from scanner app

**Tier 2 — Supervisor within shift (Kishan / Karthik)**
- Can void any scan from the current shift
- Mandatory reason required
- Permission: `scan_void_supervisor` on role
- Accessible from Corrections tab on dashboard

**Tier 3 — Post-shift amendment (Siddhant / Varun / Afshaan)**
- Can amend line, operator_id, or notes on any scan after shift close
- Mandatory reason, immutable audit trail in `scan_amendments`
- Permission: `scan_amend_manager` on role
- Accessible from Corrections tab on dashboard

**Mahesh:** Read-only. No amendment access at any tier, ever.

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

## 10. Rework & Reverse Flow Scenarios

**Core principle:** Original records preserved. Rework captured as distinct event. Final state drives reports. Lineage always queryable.

### Scenario A: Variant Rework
**Example:** Flare Burnout Grey → convert to Flare Race Grey.
- **Open question:** keep original UPC or issue new one?

### Scenario B: Post-Dispatch Part Replacement
- RTD stock: pulled back via internal rework order
- Post-dispatch: RTO_IN → rework order

### Scenario C: Workshop Repair
Standard QC_FAIL → WKS_IN → WKS_OUT → re-QC. Loop count tracks cycles. ✅ Implemented.

### Scenario D: Packaging Rework
Original RTE/RTR voided via Tier 2/3; new scan created and linked.

---

## 11. Open Design Questions

1. **Variant rework UPC:** Keep original UPC (preferred — history carries over) or new UPC?
2. **Rework approval:** Who triggers and approves recall/rework orders?
3. **WIP valuation:** Material cost only or material + proportional labour?
4. **Unicommerce integration:** Formal API or reconciliation-based handoff?
5. **Biometric integration:** Headcount only or individual operator clock-in/out?
6. **Defect master ownership:** Who can add/modify defect codes post-seeding?
7. **WKS device mode:** Modal per scan (current) vs system-inferred from unit state.
8. **Return system:** Full design pending — market returns, RTO processing, direct-to-RTD credits.
9. **Thermal printer spec:** 60mm ESC/POS, dedicated laptop print server. Printer model TBD once box label dimensions confirmed.
10. **Box label dimensions:** Need available space on box to finalise sticker size and printer spec.

---

## 12. Shift & Time

- One shift: 9:00 AM – 6:00 PM. OT possible.
- Biometric attendance exists on-site, not connected.
- Downtime tracking needed, not yet implemented.

---

## 13. Lines & Factory

- 3 lines: L1, L2, L3. Each runs one product at a time.
- Line switches happen mid-day when parts run out (parts availability = primary daily bottleneck).
- **New factory acquired, move pending** — system must support more lines, more scan points, more devices without structural changes. Device and line configuration fully dynamic via dashboard.

---

## 14. Scale

| Month | Units/Month | Scan Events/Month | upc_pool rows (cumulative) |
|---|---|---|---|
| Now | 20,000 | ~100,000 | ~20,000 |
| +6 | 50,000 | ~250,000 | ~220,000 |
| +12 | 124,000 | ~620,000 | ~920,000 |
| +18 | 308,000 | ~1,540,000 | ~3M |
| +24 | 763,000 | ~3,800,000 | ~10M |

Scan events is the largest table. Partition by month at ~500K events/month (~month 10). Dashboard views must pre-aggregate — never query raw scan table for reporting.

---

## 15. Compliance Readiness

### ISO
- Full unit traceability (raw material → finished product)
- Non-conformance records (QC_FAIL + defect codes)
- Corrective action tracking (training flags on repeat defects)
- BOM version history

### IND AS
- WIP: units between INW and RTD
- Finished goods: units at RTD
- Valuation method: open question

### Non-negotiable architectural constraints
- Records never deleted — void with reason only
- Every mutation logged with actor + timestamp + metadata
- Timestamps stored UTC, displayed IST
- UPC permanent — never reassigned, never reused
- BOM versions are historical

---

## 16. Integration Points

| System | Direction | What | Status |
|---|---|---|---|
| Unicommerce | Out | RTD handoff, channel routing | Manual currently |
| Biometric | In | Headcount / shift data | On-site, not connected |
| External UPC printer | Both | Batch gen → A3 print → receive | ✅ Live |
| Thermal label printer | Out | Batch label at PKG station | Pending printer purchase |
| Supabase | Core | All data | ✅ Live |
| Cloudflare Workers | API | Auth, logic, mutations | ✅ Live |

---

## 17. Build Status

### Live ✅
- Supabase DB — all tables, schema confirmed
- Product master — 86 SKUs with product codes
- Defect master — 35 codes, component filtering configured
- Scanner PWA (`scanner.legendoftoys.com`) — all scan flows working
- Worker v4 (`lotopsproxy.afshaan.workers.dev`) — all endpoints live
- Dashboard (`dashboard.legendoftoys.com`) — all 7 tabs live
- UPC batch generation + A3 print workflow
- QC views — FPY, defect heatmap, repeat failures
- Scan correction — Tier 2 void + Tier 3 amend

### Confirmed Working in Testing ✅
- INW scan — car and remote
- QC_PASS two-scan pairing flow
- QC_FAIL defect modal with component filtering
- Duplicate scan rejection
- Scan void (Tier 2) from dashboard
- Executive, Lines, QC, Scans, Corrections dashboard tabs

### Pending Testing 🔶
- PKG pair verification + batch label flow (needs thermal printer)
- PKG_OUT dispatch scan
- WKS_IN / WKS_OUT

### Pending Build 🔲
- Station status controls — each scan point validates previous unit status
- Operator QR card print flow — dashboard page to print physical ID cards
- Operator onboarding + station assignment flow
- Thermal printer + Node.js print server
- RTO_IN two-path flow (intact vs damaged)
- Return system design + build
- Reconciliation module
- Audit module (Mahesh view)
- Assembly stations module
- Biometric integration
- Unicommerce reconciliation
- Dashboard tab access control (role-gating)

---

*Update at end of every significant build or design session.*
