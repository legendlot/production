# Legend of Toys — System Understanding Document
**Version:** 1.8 | **Last Updated:** April 2026
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth. At this trajectory, the system must be designed to handle ~120,000 units/month by end of Year 1 and potentially 700,000+ units/month by end of Year 2.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Google Sheets + Apps Script replaced. New integrated production operations system on Supabase + Cloudflare Workers + PWA is live and scanning on the factory floor. System confirmed working in live floor testing. PKG thermal label printing live via polling architecture. Per-line PKG printers deployed April 2026.

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
- `in_repair` → WKS_OUT_PENDING — operator taps REPAIRED or CANNOT FIX
- REPAIRED → unit → `repaired`, eligible for QC again
- SCRAPPED → unit → `scrapped`, scrap loss note auto-created

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
| WKS | WKS | SHARED |
| PKG-L1/L2/L3 | PKG | L1/L2/L3 |
| PKG-OUT | PKG_OUT | SHARED |
| RTO | RTO_IN | SHARED |

Total: 16 devices. Scanner setup screen: operator selects Station + Line (2 taps). Device code auto-resolved from DB.

**Devices table columns:** id, device_code, label, line, station, is_active, created_at, last_seen, notes. No `activity` column.

**Print routing:** Each PKG device (PKG-L1/L2/L3) writes its line into `print_jobs.line`. Each line's print server polls only for its own line's jobs — no cross-line print collisions.

---

## 7. Reporting & Dashboard ✅ LIVE

**URL:** `dashboard.legendoftoys.com`

### Dashboard Tabs
| Tab | Users | Content | Status |
|---|---|---|---|
| Executive | Afshaan, Vinay, Varun | Today KPIs, hourly output (with counts), runs split Active/Upcoming | ✅ Live |
| Lines | Siddhant, Kishan | Per-line cards (today only), first scan time, operator stats | ✅ Live |
| QC | Karthik, Mahesh | QC cycle time (IQR outlier-aware), median, FPY by line, defect heatmap, repeat failures | ✅ Live |
| Runs | All production_view | Plan vs actual | ✅ Live |
| UPC Generator | Afshaan, Varun | Generate batches (searchable dropdown), print stickers, receive | ✅ Live |
| Scans | All | Real-time scan feed, activity filter, UPC search. Summary cards: TOTAL/INW/QC PASS/QC FAIL/WKS IN/WKS OUT/PKG/RTO IN/VOIDED via RPC. | ✅ Live |
| Corrections | Siddhant upwards | Tier 2 void + Tier 3 amend | ✅ Live |
| Alerts | All (Kishan upwards to acknowledge) | Scan violations, badge count, acknowledge | ✅ Live |
| Returns | Production team | Returns queue handed off from store | ✅ Live |
| Reporting | Afshaan, Vinay, Varun | Date-range: cycle time summary, QC summary, product breakdown, daily output, line breakdown | ✅ Live |

Auto-refresh: 30 seconds. Scans tab summary uses `get_scan_summary` RPC — accurate regardless of row limits.

---

## 8. Scan Correction — Three Tiers ✅ BUILT

**Tier 1 — Operator (self-serve, no approval)**
- Available until unit receives its next scan in the flow
- `voided = true` on scan row, unit status rolled back automatically

**Tier 2 — Supervisor within shift (Kishan / Karthik)**
- Can void any scan from the current shift
- Mandatory reason required, unit status rolled back automatically
- Permission: `scan_void_supervisor`

**Tier 3 — Post-shift amendment (Siddhant / Varun / Afshaan)**
- Can amend line, operator_id, or notes on any scan after shift close
- Mandatory reason, immutable audit trail in `scan_amendments`
- Permission: `scan_amend_manager`

**Mahesh:** Read-only. No amendment access at any tier, ever.

---

## 9. Defect Master ✅ CONFIGURED

35 active defect codes across 5 categories:
- **Car-Functional** (CF-01 to CF-09) — component = `car`
- **Car-Visual** (CV-01 to CV-08) — component = `car`
- **Remote-Functional** (RF-01 to RF-08) — component = `remote`
- **Remote-Visual** (RV-01 to RV-04) — component = `remote`
- **Packaging** (PK-01 to PK-05) — component = `both`, currently inactive

Scanner filters defect list by `component_type` of unit being scanned.

---

## 10. Station Status Controls & Violations ✅ BUILT

Every scan point validates the unit's previous status before accepting. Wrong-sequence scans are:
1. Hard-rejected on the scanner with a specific, descriptive error message
2. Logged to `scan_violations` table with full context
3. Visible immediately on the Alerts tab

**Validation rules:**
- INW — rejects if UPC has been scanned before
- QC_PASS — rejects unless status is `inwarded` or `repaired`
- QC_FAIL — rejects unless status is `inwarded` or `repaired`
- WKS — rejects unless status is `qc_fail` or `in_repair`
- PKG — rejects unless status is `qc_pass`
- PKG_OUT — rejects unless status is `pending_rtd` (or `rtd` for return RTD path)

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
2. **Store — Inspection:** Record condition, UPC if readable, disposition
3. **Disposition options:**
   - `rtd_direct` — UDR intact → PKG_OUT → `RTD_RETURN`
   - `wks_repair` → WKS → repair → QC → PKG → RTD
   - `loss_damage` → damage note (LN-XXXX)
   - `loss_rejection` → rejection note
4. **Handover:** "HAND OVER TO PRODUCTION" button → shipment → `handed_over`
5. **Production — UDR:** Scan batch label at PKG_OUT → `RTD_RETURN`. Does not count in RTE/RTR tally.
6. **Production — Damaged:** `wks_repair` → repair run (pending design)

### Loss Notes
Three types: `damage`, `rejection`, `scrap`. Scrap notes auto-created on WKS_OUT scrapped. Require Siddhant/Varun/Afshaan/Vinay approval.

---

## 12. Cycle Time Analytics ✅ BUILT

| Segment | Measures | From → To | RPC | Status |
|---|---|---|---|---|
| QC Cycle Time | Assembly + QC queue time | INW → QC_PASS or QC_FAIL | `get_qc_cycle_time` | ✅ Built |
| PKG Cycle Time | QC pass to packaging | QC_PASS → PKG | `get_pkg_cycle_time` | ✅ Built |
| RTD Cycle Time | Packaging to dispatch | PKG → RTE or RTR | `get_rtd_cycle_time` | ✅ Built |

IQR fence = Q3 + 2×IQR (min IQR floor 5 min, hard floor Q3 + 10 min). Overnight cap: 480 minutes.

---

## 13. Rework & Reverse Flow Scenarios

### Scenario C: Workshop Repair ✅ IMPLEMENTED
QC_FAIL → WKS_IN (system-inferred) → WKS_OUT (REPAIRED or SCRAPPED) → re-QC or end. Loop count tracks cycles. Scanner calls `postWksScan` and `postWksOut` (not `postScan`).

### Scenario E: Repair Run (damaged returns) 🔲 PENDING DESIGN
Damaged return units go through a repair run — batch-based, plannable. Design and build deferred.

---

## 14. PKG Label Print System ✅ BUILT — v2.1 Per-Line (April 2026)

### Architecture
One print server per line, running on that line's dedicated Windows laptop at the PKG station. Each server polls Supabase `print_jobs` table every 2 seconds, filtered to its own line only. No HTTP server. No direct phone→laptop connection.

### Flow
1. PKG scan succeeds → Worker inserts `print_jobs` row with `status = pending` and `line = deviceLine`
2. Scanner overlay shows batch label + REPRINT button
3. Line-specific print server detects its pending row → marks `printing` → builds TSPL → fires to that line's TSC TE244 → marks `done` or `failed`
4. REPRINT button → `postReprintJob` action → new pending row

### Why not direct push
Chrome Android blocks `fetch()` from HTTPS PWA (`scanner.legendoftoys.com`) to local HTTP/HTTPS print server. This is a browser-level restriction (mixed content policy) that cannot be bypassed by cert acceptance in a separate tab. iPhone/Safari works but Android Chrome does not. APK (TWA) also blocked. Polling via Supabase permanently solves this for all devices.

### Why factory WiFi doesn't matter
Print server only calls outbound to Supabase. It does not talk to scanner devices. Different WiFi network, different physical location — makes no difference as long as the laptop has internet.

### Scanner devices
- **15 Redmi A5** — factory floor scanners, Chrome Android, browser PWA
- **PKG station Redmi A5 (per line)** — APK installed (com.legendoftoys.scanner) via Bubblewrap TWA. With polling print, APK vs browser doesn't affect printing — both work.

---

## 15. Thermal Printer Setup

- **Model:** TSC TE244 (confirmed, on-site)
- **Label size:** 50mm × 25mm
- **Connection:** USB to Windows laptop at each line's PKG station
- **Print server:** Node.js (`printserver.js` v2.1) — polling Supabase, filtered by line, no HTTP server
- **Config:** `config.json` — only `"line"` value differs between laptops (L1/L2/L3); all other config identical
- **Auto-start:** Windows Startup shortcut on each laptop
- **Label X position:** 24 dots (April 2026, shifted from 8). May need further tuning.

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

---

## 20. Integration Points

| System | Direction | What | Status |
|---|---|---|---|
| Unicommerce | Out | RTD handoff, channel routing | Manual currently |
| Biometric | In | Headcount / shift data | On-site, not connected |
| External UPC printer | Both | Batch gen → A3 print → receive | ✅ Live |
| TSC TE244 thermal printer | Out | Batch label at each line's PKG station (50×25mm) | ✅ Live — polling v2.1 per-line |
| Supabase | Core | All data | ✅ Live |
| Cloudflare Workers | API | Auth, logic, mutations | ✅ Live |
| Store system | Both | Returns handoff, loss notes, handover to production | ✅ Live |

---

## 21. Build Status

### Live & Confirmed Working ✅
- Supabase DB — all tables, schema confirmed
- Product master — 86 SKUs with product codes
- Defect master — 35 codes, component filtering configured
- Scanner PWA (`scanner.legendoftoys.com`) — all scan flows working
- Worker (`lotopsproxy.afshaan.workers.dev`) — all endpoints live
- INW scan — car and remote (confirmed on floor)
- QC_PASS two-scan pairing flow (confirmed on floor)
- QC_FAIL defect modal with component filtering (confirmed on floor)
- WKS flow — `postWksScan` + `postWksOut` (confirmed working April 2026)
- Repair loop — QC_FAIL → WKS → repaired → QC_PASS (confirmed on floor)
- PKG scan — pair verify, status → `pending_rtd`, print job inserted with line
- PKG label printing — TSC TE244 via Supabase polling (confirmed April 2026)
- PKG_OUT — RTE/RTR dispatch + RTD_RETURN path
- Scan violation logging + Alerts tab
- Station status controls
- Void rollback — unit status rolled back on scan void
- Return system — store UI + production Returns Queue tab
- Return handover — HAND OVER TO PRODUCTION button
- RTD_RETURN scan path
- Return auto-linking
- Scan summary cards via `get_scan_summary` RPC (accurate, worker handler added April 2026)
- Executive tab — hourly bar chart with count labels, runs split Active Today / Upcoming
- Lines tab — locked to today, first scan time per line, dispatch-based completion %
- QC tab — cycle time cards with IQR outlier detection
- Reporting tab — all 3 cycle time segments, QC summary, product breakdown, daily output, line breakdown
- UPC generator — searchable dropdown, remote variant suppressed
- Print sheet — 21×29 grid, 609 stickers/A3
- Production run LINE column — store issue queue + production runs table (April 2026)
- Per-line PKG devices — PKG-L1/L2/L3, `print_jobs.line` routing, printserver v2.1 (April 2026)

### Open Issues 🔶
- WKS_IN line shows SHARED (fix deployed April 2026, **not confirmed on floor**)
- WKS defects "No defects on record" (fix deployed April 2026, **not confirmed on floor**)
- Label X position final tuning (shifted to 24 dots, **not confirmed on floor**)

### Pending Build 🔲
- Operator QR card print flow
- Operator onboarding + station assignment flow
- Consolidated dispatch view — fresh RTD + RTD_RETURN combined
- Repair run — new production run type for damaged returns (design pending)
- Legacy UPC manual entry path (build when triggered)
- Daily / weekly / monthly reporting views
- Reconciliation module — Phase 4
- Audit module — Mahesh's dedicated view — Phase 5
- Assembly stations module — Phase 6
- Biometric integration
- Unicommerce reconciliation
- Dashboard tab access control (role-gating — deferred)

---

## 22. Open Design Questions

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
11. **Repair run schema:** New production run type for damaged returns. Pending design session.
12. **Daily/weekly/monthly reporting:** Not yet built.
13. **`packed` vs `pending_rtd`:** Both in enum. When is it safe to remove `packed`?
14. **print_jobs retention:** Cleanup policy for old done/failed rows.
15. **All 15 devices APK vs PWA:** With polling print solved, staying on browser PWA is viable for all. Decision deferred.

---

*Update at end of every significant build or design session.*
