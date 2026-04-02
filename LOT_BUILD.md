# Legend of Toys — Technical Build Document
**Version:** 1.1 | **Last Updated:** April 2026
**Purpose:** Technical reference for the LOT production operations system. Feed alongside LOT_SYSTEM.md when continuing development in a new chat session.

---

## 1. Stack

| Layer | Technology | Details |
|---|---|---|
| Database | Supabase (Postgres) | `jkxcnjabmrkteanzoofj.supabase.co` — Micro compute |
| API layer | Cloudflare Workers | `lotopsproxy.afshaan.workers.dev` — Worker v4 |
| Scanner PWA | Vanilla JS + ZXing | `scanner.legendoftoys.com` — GitHub Pages |
| Scanner APK | TWA (PWA Builder) | Wraps PWA |
| Dashboard | Vanilla JS | `dashboard.legendoftoys.com` — GitHub Pages |
| Camera/scanning | Native `getUserMedia` + ZXing | Replaced html5-qrcode (MIUI incompatibility) |
| Fonts | Tomorrow + JetBrains Mono | Google Fonts |

---

## 2. Supabase Schema

### Schema Separation
- **`store` schema** — inventory, procurement, GRN, issues, work orders, production runs
- **`public` schema** — scan tables, units, devices, operators, views, RPCs

Store schema API calls include `Accept-Profile: store` / `Content-Profile: store` headers.
Public schema RPC calls use `sbPublic()` helper — no profile headers.

---

### Store Schema Tables

| Table | Purpose |
|---|---|
| `stock_ledger` | Current stock per part_code |
| `material_current` | Parts master with unit_cost, bag_size, category |
| `bom_current` | BOM per product/variant — includes `has_remote` on product level |
| `grn_register` | Goods received notes |
| `issue_register` | Parts issued to production runs |
| `issue_receipts` | Production acknowledgement of parts received |
| `issue_receipt_lines` | Line items per receipt |
| `returns_log` | Market returns |
| `work_orders` | WOs for runs, parts requests, rework |
| `wo_parts_request` | Parts requested per WO |
| `production_runs` | Run header (product, date, status) |
| `line_flushes` | End-of-run material returns |
| `flush_lines` | Line items per flush |
| `flush_dispositions` | Disposition per flushed item |
| `flush_summary` | View |
| `manpower_log` | Daily headcount per shift |
| `manpower_activities` | Per-person activity log |
| `activity_log` | System-wide immutable audit trail |
| `roles` | Role definitions with permissions JSONB |
| `users_profile` | id, role, full_name, active, must_change_password |
| `shipments` | Inbound shipment headers |
| `shipping_marks` | Marks within a shipment |
| `receiving_lines` | Receiving line items |
| `bags` | Bag-level tracking for received goods |
| `forwarders` | Freight forwarder master |
| `vendors` | Vendor master |
| `purchase_orders` | PO headers |
| `po_lines` | PO line items |
| `po_revisions` | PO change history |
| `po_summary` | View |
| `quarantine_register` | Quarantined stock |

---

### Production Schema — New Tables Required (not yet built)

#### `upc_pool` — UPC sticker inventory
```sql
upc_id          TEXT PRIMARY KEY,           -- 'LOT-00000001'
product         TEXT NOT NULL,              -- 'Knox', 'Knox-Remote', 'Flare', etc.
batch_id        TEXT NOT NULL,              -- print batch reference
status          TEXT NOT NULL,              -- generated | printed | received | available | applied | damaged | unused
applied_to      TEXT,                       -- unit_id from `units` table, null until applied
created_at      TIMESTAMPTZ DEFAULT now(),
updated_at      TIMESTAMPTZ DEFAULT now()
```
Indexes: `product`, `batch_id`, `status`

#### `upc_batches` — Print batch records
```sql
batch_id        TEXT PRIMARY KEY,           -- 'B-001'
product         TEXT NOT NULL,
quantity        INT NOT NULL,
upc_from        TEXT NOT NULL,              -- first UPC in batch
upc_to          TEXT NOT NULL,              -- last UPC in batch (informational only — not used for range queries)
status          TEXT NOT NULL,              -- generated | sent_to_print | printed | received | fully_applied
generated_by    TEXT,
generated_at    TIMESTAMPTZ DEFAULT now(),
sent_at         TIMESTAMPTZ,
received_at     TIMESTAMPTZ,
notes           TEXT
```

#### `units` — One row per physical unit (car or remote)
```sql
unit_id         TEXT PRIMARY KEY,           -- same as upc_id for simplicity, or separate surrogate key
upc_id          TEXT REFERENCES upc_pool,
product         TEXT NOT NULL,
component_type  TEXT NOT NULL,              -- 'car' | 'remote'
paired_with     TEXT REFERENCES units,      -- for cars: points to remote unit_id; for remotes: points to car unit_id
current_stage   TEXT NOT NULL,              -- inw | qc_pass | wks | rte | rtr | rto_in | converted | scrapped
loop_count      INT DEFAULT 0,
production_run_id TEXT,
created_at      TIMESTAMPTZ DEFAULT now(),
updated_at      TIMESTAMPTZ DEFAULT now()
```

#### `scan_events` — All scan activity
```sql
scan_event_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
unit_id         TEXT REFERENCES units,
scan_point      TEXT NOT NULL,              -- INW | QC_PASS | QC_FAIL | WKS_IN | WKS_OUT | RTE | RTR | RTO_IN
device_id       TEXT NOT NULL,
operator_id     TEXT NOT NULL,
line            TEXT,                       -- L1 | L2 | L3
product         TEXT,
loop_count      INT,
scanned_at      TIMESTAMPTZ DEFAULT now(),
voided          BOOLEAN DEFAULT false,
voided_by       TEXT,
voided_at       TIMESTAMPTZ,
void_reason     TEXT,
superseded_by   UUID REFERENCES scan_events -- for amendments: points to the correcting scan event
```
Indexes: `unit_id`, `scan_point`, `line`, `scanned_at`, `voided`
Partition by month at ~500K events/month

#### `qc_fail_events` — Parent table for QC failures
```sql
scan_event_id   UUID PRIMARY KEY REFERENCES scan_events,
unit_id         TEXT NOT NULL,
component_type  TEXT NOT NULL,              -- 'car' | 'remote'
line            TEXT,
operator_id     TEXT,
product         TEXT,
loop_count      INT,
failed_at       TIMESTAMPTZ DEFAULT now()
```

#### `qc_fail_defects` — Child table, one row per defect per failure
```sql
id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
scan_event_id   UUID REFERENCES qc_fail_events,
defect_code     TEXT NOT NULL,
severity        TEXT                        -- Critical | Major | Minor (denormalised from defect_master for history)
```
Index: `scan_event_id`, `defect_code`

#### `defect_master` — Defect code definitions
```sql
defect_code     TEXT PRIMARY KEY,           -- e.g. 'VIS-001', 'RMT-001'
category        TEXT NOT NULL,              -- Visual | Functional | Assembly | ...
description     TEXT NOT NULL,
component       TEXT NOT NULL,              -- 'car' | 'remote' | 'both'
product         TEXT,                       -- null = universal; specific product code = product-specific
severity        TEXT NOT NULL,              -- Critical | Major | Minor
training_flag   BOOLEAN DEFAULT false,      -- flag for corrective action tracking
active          BOOLEAN DEFAULT true,
created_at      TIMESTAMPTZ DEFAULT now()
```

#### `unit_pairs` — Pairing history
```sql
pair_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
car_unit_id     TEXT REFERENCES units,
remote_unit_id  TEXT REFERENCES units,
paired_at       TIMESTAMPTZ DEFAULT now(),
paired_by       TEXT,                       -- operator_id
scan_event_id   UUID REFERENCES scan_events,-- the QC_PASS event that created this pair
status          TEXT DEFAULT 'active'       -- active | broken (if pair broken due to rework)
```

#### `scan_amendments` — Tier 3 post-shift amendment log
```sql
amendment_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
original_event_id UUID REFERENCES scan_events,
amended_by      TEXT NOT NULL,
amended_at      TIMESTAMPTZ DEFAULT now(),
reason          TEXT NOT NULL,
original_value  JSONB,                      -- snapshot of original scan_event row
new_value       JSONB                       -- snapshot of amended scan_event row
```

#### `devices` — Device configuration
```sql
device_id       TEXT PRIMARY KEY,           -- e.g. 'DEV-001'
device_name     TEXT,
scan_point      TEXT NOT NULL,              -- INW | QC_PASS | QC_FAIL | WKS | RTE | RTR | RTO_IN
line            TEXT,                       -- L1 | L2 | L3 | null (shared)
active          BOOLEAN DEFAULT true,
last_seen       TIMESTAMPTZ,
notes           TEXT
```

#### `operators` — Operator records
```sql
operator_id     TEXT PRIMARY KEY,           -- encoded in QR card
full_name       TEXT NOT NULL,
line            TEXT,                       -- primary line assignment
role            TEXT,                       -- assembler | qc | packing | supervisor
active          BOOLEAN DEFAULT true,
qr_generated_at TIMESTAMPTZ,
created_at      TIMESTAMPTZ DEFAULT now()
```

---

### Production Schema — Existing Views

| View | Purpose |
|---|---|
| `v_hourly_output` | QC pass count per hour per line per date |
| `v_operator_output` | Per-operator stats (INW, QC pass, QC fail, pass rate) |
| `v_first_pass_yield` | FPY % per line per date |
| `v_repeat_defects` | Units with multiple QC_FAIL events + defect codes |
| `v_dispatch_stock` | Units at RTD (RTE + RTR) by product/SKU |
| `v_repair_queue` | Units in workshop (WKS_IN without WKS_OUT) |
| `v_daily_production` | Daily summary per line |

---

### Store Schema RPCs

| RPC | Purpose |
|---|---|
| `next_seq(seq_name, prefix)` | Sequence generator for all IDs |
| `bulk_update_stock_received(p_updates)` | Bulk add to stock |
| `bulk_update_stock_issued(p_updates)` | Bulk deduct from stock |

### Production Schema RPCs (existing)

| RPC | Purpose |
|---|---|
| `get_executive_dashboard(p_date)` | Today/WTD/MTD summary blob |
| `get_open_runs()` | Active production runs |
| `get_plan_vs_actual(p_date_from, p_date_to, p_line)` | Plan vs actual per run |
| `get_line_view(p_date, p_line)` | Per-line stats |
| `get_defect_heatmap(p_date_from, p_date_to, p_line)` | Defect counts by code/category |

---

## 3. Roles & Permissions

Permission keys on roles.permissions JSONB:
```
grn, stock_issue, receiving, reports, procurement_raise, procurement_approve,
line_flush_create, line_flush_verify, users_manage, reports_finance, production_view
```

**Scan amendment permissions (to be added):**
```
scan_void_supervisor    -- Tier 2: void scans within current shift
scan_amend_manager      -- Tier 3: post-shift amendments
```

**Mahesh's role:** `reports` only. No write permissions of any kind. Enforced at worker level.

---

## 4. Scan Architecture

### Scan Flow Per Point

**INW**
- Single scan
- System looks up UPC in `upc_pool` — identifies product + component_type (car/remote)
- Creates row in `units` (if first scan) and `scan_events`
- Marks UPC as `applied` in `upc_pool`

**QC_FAIL**
- Single scan — whichever component failed
- Creates `scan_events` row + `qc_fail_events` parent row
- Scanner shows component-filtered defect list (car or remote based on UPC lookup)
- Operator multi-selects → creates N rows in `qc_fail_defects`

**QC_PASS — `has_remote = false`**
- Single scan
- Creates `scan_events` row
- Updates `units.current_stage` to `qc_pass`

**QC_PASS — `has_remote = true`**
1. Operator scans car UPC → system confirms it's a car, shows product name
2. Scanner prompts: "Now scan remote"
3. Operator scans remote UPC → system confirms it's the correct remote type for this product
4. System creates:
   - `scan_events` row for car (QC_PASS)
   - `scan_events` row for remote (QC_PASS)
   - `unit_pairs` row linking car + remote
   - Updates `units.paired_with` on both rows
   - Updates `units.current_stage` = `qc_pass` on both

**RTE / RTR**
- Operator scans car UPC + remote UPC (pair verification)
- System checks `unit_pairs` — are these a confirmed pair?
- If yes: creates `scan_events` row (RTE or RTR), updates `units.current_stage`
- If no: scanner blocks, shows error

**Batch label scan at RTE/RTR:** The batch label (`EAN-YYYYMMDD-NNN`) is scanned at the shrink wrap machine, not the UPC. Batch label links back to the QC_PASS event. This prevents duplicate dispatch scans.

---

## 5. Scan Correction — Technical Implementation

### Tier 1 — Operator undo
- `scan_events` row remains, `voided = true`, `voided_by = operator_id`, `void_reason = 'operator_undo'`
- Available until unit's next scan_event is created
- Enforced: check `scan_events WHERE unit_id = ? AND voided = false ORDER BY scanned_at DESC` — if latest scan for this unit is newer than the scan being undone, undo is blocked

### Tier 2 — Supervisor void
- Same as Tier 1 but mandatory `void_reason` text
- Permission check: `scan_void_supervisor`
- Restricted to scans within current shift (`scanned_at >= shift_start`)

### Tier 3 — Post-shift amendment
- `scan_events` row updated directly (or voided + new row created)
- Row created in `scan_amendments` with full before/after snapshot
- Permission check: `scan_amend_manager`
- No time restriction

---

## 6. Scanner PWA

**URL:** `scanner.legendoftoys.com`
**Repo:** `legendlot/production` (GitHub Pages)
**Tech:** Vanilla JS, native `getUserMedia`, ZXing (WASM)

### Key Technical Decisions (do not reverse)
- `html5-qrcode` → fails silently on MIUI/Xiaomi — replaced with native `getUserMedia` + ZXing
- Android requires user gesture before camera prompt → tap-to-start overlay
- GitHub Pages won't serve `.well-known/` without Jekyll `_config.yml` include directive
- APK via PWA Builder (TWA) — camera permissions + assetlinks.json resolved

### Current Scanner Screens
1. **Login** — operator scans QR card, no typing
2. **Device setup** — scan point + line selection (first boot or reconfiguration)
3. **Scan screen** — live camera, shows scan point + operator name, writes to Supabase on scan

### Scanner UX Principles (non-negotiable)
- No text input by operators ever
- Every successful scan: product name shown prominently + success sound/vibration
- Every failed/blocked scan: clear error + fail sound
- Device pre-configured to scan point — operator never chooses scan type
- QR card login only

### QC_PASS Flow Change Required
Current: single scan → write to DB.
Required: guided two-step flow (car → prompt → remote → confirm → write pair + two scan events).
Triggered only when `has_remote = true` on the scanned product.

---

## 7. Dashboard

**URL:** `dashboard.legendoftoys.com`
**Tech:** Vanilla JS single-file HTML
**Auth:** JWT in memory (not localStorage)

### Design System
```
Colors:
  --yellow: #F2CD1A    primary accent, LOT brand
  --blue:   #213CE2
  --red:    #DE2A2A
  --green:  #22c55e
  --orange: #f59e0b
  --bg:     #080808
  --s1–s4:  surface layers #0f0f0f → #252525
  --b1–b3:  border layers #2a2a2a → #444
  --text:   #f0f0f0
  --t2:     #999, --t3: #555

Fonts:
  --head: 'Tomorrow' (headings, numbers)
  --mono: 'JetBrains Mono' (data, tables)
```

### Tabs & Users
| Tab | Primary Users | Content |
|---|---|---|
| Executive | Afshaan, Vinay, Varun | Today KPIs, hourly chart, open runs |
| Lines | Siddhant, Kishan | Per-line cards, operator table |
| QC | Karthik, Mahesh | FPY, defect heatmap, repeat failures |
| Runs | All production_view | Plan vs actual |

Auto-refresh: 30 seconds. Green dot = live, orange = fetching.
Tab access control: deferred. All tabs visible to all roles currently.

---

## 8. Cloudflare Worker v4

**URL:** `lotopsproxy.afshaan.workers.dev`
**Auth:** Supabase JWT on every request (except login)

### Production Dashboard GET Endpoints
| Action | Description |
|---|---|
| `getProductionDashboard` | Executive summary: today/WTD/MTD + open runs + hourly chart |
| `getPlanVsActual` | Plan vs actual by date range, optional line |
| `getLineView` | Per-line stats + hourly + operator table |
| `getQCView` | Defect heatmap + FPY + repeat defects |
| `getDispatchStock` | RTD stock by product |
| `getRepairQueue` | Units in workshop |
| `getDailyProduction` | Daily production summary |

### Store GET Endpoints (existing — unchanged)
getManpower, getActivityLog, getRoles, getUsers, getStock, getMaterials, getBOM, getGRN, getGRNSummary, getIssues, getReturns, getRecentWOs, getWOParts, getWorkOrders, getDashboard, calcKit, getReportSummary, downloadReport, getShipments, getShipment, getBags, getForwarders, getForwarder, getVendors, getVendor, getFlushes, getFlush, getQuarantine, getPOs, getPO, getPendingInward, getProductionRuns, getProductionRun, getIssueReceipts

### Store POST Endpoints (existing — unchanged)
postGRN, updateWorkOrder, postWorkOrder, postIssue, postReturn, postStockCount, postShipment, postShippingMark, updateShippingMark, postReceivingLine, updateReceivingLine, generateBags, raiseGRNFromReceiving, updateShipmentStatus, postForwarder, updateForwarder, postVendor, updateVendor, postFlush, verifyFlush, postPO, approvePO, updatePOStatus, amendPO, cancelPO, updatePOLineReceived, postManpower, createRole, updateRole, deleteRole, createUser, updateUser, resetPassword, changeMyPassword, createProductionRun, submitProductionRun, cancelProductionRun, rejectProductionRun, issueAgainstRun, postIssueReceipt, reappealIssueReceipt, postShortIssue, forceResolveReceipt

### New Endpoints Required (production scan system)
```
GET:
  getOperators           -- list all operators
  getDevices             -- list all configured devices
  getUpcBatches          -- list UPC print batches
  getUpcBatch            -- single batch detail
  getScanHistory         -- scan history for a unit (by UPC)
  getRepairQueue         -- units in workshop (existing view)
  getPairingStatus       -- check if car+remote are a valid pair

POST:
  postScan               -- write a scan event (INW, QC_FAIL, WKS_IN, WKS_OUT, RTE, RTR, RTO_IN)
  postQcPass             -- QC_PASS with optional pairing (has_remote flow)
  voidScan               -- Tier 1/2 scan void
  amendScan              -- Tier 3 post-shift amendment
  generateUpcBatch       -- create a print batch, assign sequential UPCs
  receiveUpcBatch        -- mark batch as received from printer
  voidUpc                -- mark individual UPC as damaged/unused
  createOperator         -- add operator record
  updateOperator         -- update operator
  configureDevice        -- set scan_point + line for a device
```

---

## 9. Device Inventory (13 total)

| # | Device ID | Scan Point | Line | Notes |
|---|---|---|---|---|
| 1 | DEV-001 | INW | L1 | |
| 2 | DEV-002 | INW | L2 | |
| 3 | DEV-003 | INW | L3 | |
| 4 | DEV-004 | QC_PASS | L1 | Two-scan flow for RC cars |
| 5 | DEV-005 | QC_PASS | L2 | Two-scan flow for RC cars |
| 6 | DEV-006 | QC_PASS | L3 | Two-scan flow for RC cars |
| 7 | DEV-007 | QC_FAIL | L1 | Multi-defect, component-filtered list |
| 8 | DEV-008 | QC_FAIL | L2 | Multi-defect, component-filtered list |
| 9 | DEV-009 | QC_FAIL | L3 | Multi-defect, component-filtered list |
| 10 | DEV-010 | WKS | Shared | WKS_IN + WKS_OUT (toggle or inferred) |
| 11 | DEV-011 | RTE | Shared | Scans batch label; verifies pair |
| 12 | DEV-012 | RTR | Shared | Scans batch label; verifies pair |
| 13 | DEV-013 | RTO_IN | Shared | |

---

## 10. Build Phases & Status

| Phase | Module | Status |
|---|---|---|
| 1 | DB + Scanner App | ✅ Largely complete |
| 2 | Reporting Dashboard | ✅ Live |
| 3 | Repair Module | 🔲 Not started |
| 4 | Reconciliation | 🔲 Not started |
| 5 | Audit Module | 🔲 Not started |
| 6 | Assembly Stations | 🔲 Not started |
| — | Operator seeding + QR cards | 🔲 Next up |
| — | Device configuration (all 13) | 🔲 Next up |
| — | UPC generation + print workflow | 🔲 Design locked, build pending |
| — | `has_remote` flag + QC_PASS pairing flow | 🔲 Design locked, build pending |
| — | QC_FAIL schema (parent/child) | 🔲 Design locked, build pending |
| — | Scan correction UI (3 tiers) | 🔲 Design locked, build pending |
| — | Packing pair verification | 🔲 Design locked, build pending |
| — | Rework module | 🔲 Partial design, open questions remain |
| — | Dashboard tab access control | 🔲 Deferred |

---

## 11. Technical Decisions Log

| Decision | Choice | Reason |
|---|---|---|
| Camera library | Native getUserMedia + ZXing | html5-qrcode fails silently on MIUI |
| Scan at RTE/RTR | Batch label not UPC | Prevents duplicate dispatch scans |
| Schema separation | store vs public | Legacy origin; RPCs must not include profile headers |
| RTD definition | Calculated (RTE + RTR) | Not a physical scan point |
| Operator login | QR card scan only | Operators can't type reliably |
| UPC format | LOT-00000001 (8 digit global sequential) | Simple, no collision risk, scalable |
| UPC storage | Individual row per UPC in upc_pool | Enables per-unit tracking, product queries, voiding |
| Remote UPC | Independent from car, same pool | Remote travels separately, needs own scan history |
| Remote pairing | Created at QC_PASS, not before | No tentative pair state — cleaner model |
| QC_FAIL storage | Parent + child rows | Enables both defect-level and unit-level queries |
| Defect list filtering | By component type at scan time | Shorter list, no wrong-component defect codes |
| Scan correction | Tier 1/2/3 model | Prevent first, correct second; Mahesh read-only always |
| Void mechanism | voided flag on scan_events row | Records never deleted; audit trail always complete |
| DB hosting | Supabase Micro | Upgraded for compute |
| API layer | Cloudflare Workers | Edge, cheap, fast |
| Frontend hosting | GitHub Pages | Free, static PWA |
| Token storage | Memory only | Security; session expires on tab close |
| Mahesh access | Read-only permanently | Audit independence — cannot be changed per role |

---

## 12. Open Technical Questions

1. **WKS device mode:** Manual toggle (operator switches between WKS_IN and WKS_OUT) vs system-inferred (system checks unit's current stage and determines correct scan type). Inferred is smarter but has edge cases if unit state is wrong.

2. **Scan events partitioning:** Implement month-based partitioning now (future-proof) or after volume warrants it (~month 10)? Recommend planning the DDL now even if not deploying partitions yet.

3. **`units` table primary key:** Use `upc_id` directly as primary key (simpler joins) or a separate surrogate UUID (more flexible if UPC ever needs to be corrected)? Given UPC is permanent and never reassigned, `upc_id` as PK is fine.

4. **QC_PASS scanner flow (two-step):** Should the second scan (remote) time out if the operator doesn't scan within N seconds? If yes, what happens — cancel the flow or hold? Timeout prevents a stuck scanner if operator walks away mid-flow.

5. **Rework module schema:** Minimum viable: `rework_orders` table linked to original `scan_events`, consumes parts from store, produces re-inspected unit. Full design pending resolution of open business questions.

6. **`product_master` table:** Currently `bom_current` carries product info. A dedicated `product_master` table with `has_remote`, `product_code`, `category` (RC car / die cast / DIY), `active` would be cleaner — especially as new product categories launch.

---

## 13. Environment Reference

> ⚠️ Do not commit to git.

- **Supabase URL:** `https://jkxcnjabmrkteanzoofj.supabase.co`
- **Supabase publishable key:** `sb_publishable_1Dd-r3h9Mou2Wqgn6t24Dw_lmWdBtLh`
- **Worker URL:** `https://lotopsproxy.afshaan.workers.dev`
- **Scanner:** `https://scanner.legendoftoys.com`
- **Dashboard:** `https://dashboard.legendoftoys.com`
- **GitHub repo:** `legendlot/production`

---

*Update at end of every build session. "Open Technical Questions" and "Build Phases" are the source of truth for what to work on next.*
