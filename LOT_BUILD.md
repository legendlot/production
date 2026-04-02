# Legend of Toys — Technical Build Document
**Version:** 1.2 | **Last Updated:** April 2026
**Purpose:** Technical reference for the LOT production operations system. Feed alongside LOT_SYSTEM.md when continuing development in a new chat session.

---

## 1. Stack

| Layer | Technology | Details |
|---|---|---|
| Database | Supabase (Postgres) | `jkxcnjabmrkteanzoofj.supabase.co` — Micro compute |
| API layer | Cloudflare Workers | `lotopsproxy.afshaan.workers.dev` — Worker v4 |
| Scanner PWA | Vanilla JS + ZXing | `scanner.legendoftoys.com` — GitHub Pages, repo `legendlot/production` |
| Scanner APK | TWA (PWA Builder) | Wraps PWA — camera permissions + assetlinks.json resolved |
| Dashboard | Vanilla JS | `dashboard.legendoftoys.com` — GitHub Pages |
| Camera/scanning | Native `getUserMedia` + ZXing | Replaced html5-qrcode (MIUI incompatibility) |
| Fonts | Tomorrow + JetBrains Mono | Google Fonts |
| QR generation (dashboard) | QRCode.js from cdnjs | Used in UPC Generator print flow — renders off-screen, extracts data URLs, prints in new window |

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

### Production Schema — Current State (as of April 2026)

All tables below are live in the database. Columns confirmed against `information_schema` after migration fixes this session.

#### `units` — One row per physical unit
```
upc             TEXT PRIMARY KEY
ean             TEXT
sku             TEXT
product         TEXT
model           TEXT
color           TEXT
current_status  TEXT (enum)
loop_count      INT
is_fresh        BOOLEAN
created_at      TIMESTAMPTZ
updated_at      TIMESTAMPTZ
component_type  TEXT          -- 'car' | 'remote' — ADDED this session
paired_with     TEXT          -- references units(upc) — ADDED this session
production_run_id UUID        -- ADDED this session
```
> Note: PK is `upc` (TEXT), not `id` (UUID). `paired_with` references `units(upc)`.

#### `scans` — All scan activity
```
id              UUID PRIMARY KEY
timestamp       TIMESTAMPTZ
upc             TEXT
ean             TEXT
activity        TEXT          -- INW | QC_PASS | QC_FAIL | WKS_IN | WKS_OUT | RTO_IN
line            TEXT
station         TEXT
device_id       UUID
operator_id     UUID
shift           TEXT
plan_id         TEXT
batch_label     TEXT
notes           TEXT
raw_input       TEXT
loop_count      INT DEFAULT 1   -- ADDED this session
voided          BOOLEAN DEFAULT false -- ADDED this session
voided_by       UUID            -- ADDED this session
voided_at       TIMESTAMPTZ     -- ADDED this session
void_reason     TEXT            -- ADDED this session
superseded_by   UUID REFERENCES scans(id) -- ADDED this session
```

#### `upc_pool` — UPC sticker inventory
```
upc_id          TEXT PRIMARY KEY    -- 'LOT-00000001'
product         TEXT
batch_id        TEXT
status          TEXT                -- generated | available | applied | damaged | unused
applied_to      TEXT
created_at      TIMESTAMPTZ
updated_at      TIMESTAMPTZ
product_code    TEXT                -- 4-char code e.g. 'KNAK', or 5-char remote e.g. 'KNAKR'
product_seq     INT                 -- per-product sequential number (KNAK-1, KNAK-2...)
```

#### `upc_batches` — Print batch records
```
batch_id        TEXT PRIMARY KEY    -- 'B-001'
product         TEXT
quantity        INT
upc_from        TEXT
upc_to          TEXT
status          TEXT                -- generated | sent_to_print | printed | received | fully_applied
generated_by    TEXT
generated_at    TIMESTAMPTZ
sent_at         TIMESTAMPTZ
received_at     TIMESTAMPTZ
notes           TEXT
```

#### `qc_fail_events` — Parent table for QC failures
```
id              UUID PRIMARY KEY
scan_id         UUID
upc             TEXT
component_type  TEXT
line            TEXT
operator_id     UUID
product         TEXT
loop_count      INT
failed_at       TIMESTAMPTZ
```

#### `qc_fail_defects` — Child table, one row per defect per failure
```
id              UUID PRIMARY KEY
qc_fail_event_id UUID REFERENCES qc_fail_events
defect_code     TEXT
severity        TEXT
```

#### `defect_master` — Defect code definitions
```
id              UUID PRIMARY KEY
code            TEXT
category        TEXT
issue           TEXT
severity        TEXT
sub_issues      JSONB
is_active       BOOLEAN
created_at      TIMESTAMPTZ
component       TEXT            -- ADDED this session
product         TEXT            -- ADDED this session
training_flag   BOOLEAN DEFAULT false -- ADDED this session
```

#### `unit_pairs` — QC pairing records
```
id              UUID PRIMARY KEY
car_upc         TEXT
remote_upc      TEXT
paired_at       TIMESTAMPTZ
paired_by       UUID
scan_id         UUID
status          TEXT            -- active | broken
```

#### `scan_amendments` — Tier 3 amendment log
```
id              UUID PRIMARY KEY
original_scan_id UUID
amended_by      UUID
amended_at      TIMESTAMPTZ
reason          TEXT
original_value  JSONB
new_value       JSONB
```

#### `product_master` — Product/SKU reference
```
ean             TEXT PRIMARY KEY
sku             TEXT
product         TEXT
model           TEXT
color           TEXT
is_active       BOOLEAN
created_at      TIMESTAMPTZ
has_remote      BOOLEAN         -- true for RC cars
product_code    TEXT            -- 4-char code e.g. 'KNAK' (car), 'KNAKR' (remote = car code + 'R')
```
All 86 SKUs seeded with product codes.

#### `devices` — Device configuration
```
id              UUID PRIMARY KEY
device_code     TEXT            -- 'INW-L1', 'QCP-L1', etc.
label           TEXT
line            TEXT
station         TEXT            -- INW | QC_PASS | QC_FAIL | WKS | PKG | RTD | RTO_IN
is_active       BOOLEAN
created_at      TIMESTAMPTZ
last_seen       TIMESTAMPTZ     -- ADDED this session
notes           TEXT            -- ADDED this session
```
All 13 devices configured.

#### `operators` — Operator records
```
id              UUID PRIMARY KEY
name            TEXT
qr_code         TEXT            -- encoded in QR card e.g. 'OP-0001'
role            TEXT
reports_to      UUID
is_active       BOOLEAN
created_at      TIMESTAMPTZ
line            TEXT
qr_generated_at TIMESTAMPTZ
```
6 test operators seeded (OP-0001 through OP-0006).

---

### Production Schema — Views

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

---

## 3. Product Code System ✅ LOCKED

**Format:** 4-char alphanumeric — PP+M+C (2-char product prefix + 1-char model + 1-char color)

**Color key:** K=Black, B=Blue, G=Green, R=Red, W=White, E=Grey, S=Silver, P=Purple, V=Purple2, M=Multi, N=Pink, X=Excavator, Y=Yellow, O=Orange, D=Desert

**Remote code:** car code + 'R' (5 chars). e.g. KNAK (Knox Adventure Black car) → KNAKR (remote).

**Sticker display:** QR encodes `LOT-XXXXXXXX`, text below shows `KNAK-7` (product code + per-product sequential number). Human-readable and scannable.

**Per-product sequences:** Each product_code has its own counter in `upc_pool.product_seq`. KNAK-1, KNAK-2... independent of FLBG-1, FLBG-2... Numbers will get large over time — this is fine, operators match visually.

**Sample codes:**
- Knox Adventure Black = KNAK, remote = KNAKR
- Flare Race Grey = FLRE, remote = FLRER
- Bumble Base Blue = BMBB, remote = BMBBR

All 86 SKUs mapped and seeded in `product_master.product_code`.

---

## 4. Roles & Permissions

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

**Mahesh's role:** `reports` only. No write permissions of any kind. Enforced at worker level. Permanent constraint.

---

## 5. Scan Architecture

### Scan Flow Per Point

**INW**
- Single scan on LOT-XXXXXXXX UPC
- Looks up `upc_pool` — must be status `available`
- Determines `component_type` from `upc_pool.product_code` (ends with 'R' = remote)
- Creates/updates row in `units`, creates row in `scans`
- Marks UPC as `applied` in `upc_pool`
- Returns `display_code` (e.g. KNAK-7) for operator feedback

**QC_FAIL**
- Single scan — whichever component failed
- Creates `scans` row + `qc_fail_events` parent row
- Scanner shows component-filtered defect list (filtered by `defect_master.component`)
- Operator multi-selects → creates N rows in `qc_fail_defects`

**QC_PASS — `has_remote = false`**
- Single scan, creates `scans` row, updates `units.current_status`

**QC_PASS — `has_remote = true`**
1. Operator scans car UPC → system confirms car, shows product name
2. Scanner shows green band: "📡 NOW SCAN REMOTE CONTROL"
3. Operator scans remote UPC → system confirms correct remote type (product_code ends with 'R', car code matches)
4. System creates: scan row for car + scan row for remote + `unit_pairs` row + updates `units.paired_with` on both
5. Error on wrong remote: "Wrong remote — expected KNAKR, got FLRER"

**WKS**
- Per-scan modal: operator chooses WKS_IN or WKS_OUT
- Single scan, creates `scans` row

**RTO_IN**
- Accepts LOT UPC or EAN (13-digit)

---

## 6. Scan Correction

### Tier 1 — Operator (self-serve)
- `voided = true` on scan row, available until unit's next scan in flow

### Tier 2 — Supervisor void (within shift)
- Same as Tier 1 + mandatory reason, permission: `scan_void_supervisor`

### Tier 3 — Post-shift amendment
- Scan row voided + new row created + `scan_amendments` row with full before/after snapshot
- Permission: `scan_amend_manager`, no time restriction

---

## 7. Scanner PWA ✅ LIVE

**URL:** `scanner.legendoftoys.com`
**Repo:** `legendlot/production` (GitHub Pages)
**Tech:** Vanilla JS, native `getUserMedia`, ZXing (WASM)

### Screens
1. **Login** — operator scans QR card (OP-XXXX format), or manual name select fallback
2. **Device setup** — simplified: Station + Line only (2 taps). Device code auto-resolved from DB lookup on launch. No URL/key fields, no activities, no lock/PIN.
3. **Scan screen** — live camera, station label in top bar (e.g. "ASSEMBLY · L1"), operator name, shift selector, scan log feed

### Station → Activity Mapping (locked, no UI selection needed)
| Station label | DB station code | Activity |
|---|---|---|
| Assembly | INW | INW |
| QC Pass | QC_PASS | QC_PASS |
| QC Fail | QC_FAIL | QC_FAIL |
| Workshop | WKS | WKS (modal per scan) |
| RTO In | RTO_IN | RTO_IN |

### Device Auto-Resolution
On "Launch Scanner": `sbGet('devices', 'station=eq.INW&line=eq.L1&is_active=eq.true')` → sets `cfg.deviceCode` automatically. If no device found, shows error in setup screen.

### cfg Object (saved to localStorage)
```javascript
{
  url:          DEFAULT_SUPABASE_URL,  // hardcoded, not user-editable
  key:          DEFAULT_SUPABASE_KEY,  // hardcoded, not user-editable
  stationCode:  'INW',                 // DB station code
  stationLabel: 'Assembly',            // display name
  line:         'L1',
  deviceCode:   'INW-L1',              // auto-resolved from DB
  activity:     'INW',                 // derived from stationCode
  shift:        'Regular',
  camFacing:    'environment',
  torchEnabled: false
}
```

### Key Technical Decisions (do not reverse)
- `html5-qrcode` → fails silently on MIUI/Xiaomi — replaced with native `getUserMedia` + ZXing
- Android requires user gesture before camera prompt → tap-to-start overlay
- GitHub Pages won't serve `.well-known/` without Jekyll `_config.yml` include directive
- APK via PWA Builder (TWA) — camera permissions + assetlinks.json resolved
- Success feedback fires AFTER worker confirms — not optimistically
- Offline queue stores `{action, data}` and replays via worker on reconnect

### Scan Feedback Ordering (important)
```
onScan → processBarcode → submitScanWorker → if ok: flash green + beep + log
                                            → if err: showReject(title, message)
```

### QC_PASS Two-Scan Flow
- `pendingQcPassCar` stores first scan UPC
- Green band shown: "📡 NOW SCAN REMOTE CONTROL"
- Second scan triggers `doQcPassRemote()` → calls `postQcPass` worker endpoint
- `cancelQcPassFlow()` cancels and resets state

---

## 8. Dashboard ✅ LIVE

**URL:** `dashboard.legendoftoys.com`
**Tech:** Vanilla JS single-file HTML
**Auth:** JWT in memory (not localStorage)

### Design System
```
Colors:
  --yellow: #F2CD1A    primary accent
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
| UPC Generator | Afshaan, Varun | Batch generation, print, receive |

Auto-refresh: 30 seconds. Date bar hidden on UPC Generator tab.

### UPC Generator Tab
- Product dropdown (from `getProductCodes` worker endpoint)
- Car / Remote toggle (shown only when `has_remote = true`)
- Quantity input (1–10,000)
- Generate → calls `generateUpcBatch` → shows "Batch B-001 · 500 stickers · KNAK-1 to KNAK-500 · 2 A3 sheets"
- Print → fetches batch rows with `getUpcBatch?include_rows=true` → generates QR codes off-screen → extracts canvas data URLs → opens new browser tab with embedded images → auto-prints
- Print layout: A3 portrait, 16 cols × 23 rows = 368 stickers per sheet, 15mm × 15mm stickers
- Batch history table: Print / Sent / Received actions

### Print Architecture (important)
The print flow opens a **new browser tab** with standalone HTML. This is intentional — `@media print` + `display:none` approaches failed because canvas elements don't render inside hidden containers. New window approach is reliable.
```
printBatch(batchId)
  → fetch rows (getUpcBatch?include_rows=true)
  → generateQrDataUrls(rows)      // renders QR into off-screen container, extracts PNG data URLs
  → openPrintWindow(batch, rows, qrUrls)  // new tab, embedded <img src="data:...">, auto-prints
```

---

## 9. Cloudflare Worker v4 ✅ LIVE

**URL:** `lotopsproxy.afshaan.workers.dev`
**Auth:** Supabase JWT on every request except `scannerLogin`

### Scanner POST Endpoints (no JWT — device_code auth)
| Action | Description |
|---|---|
| `scannerLogin` | Resolves QR code (OP-XXXX) → operator UUID + name |
| `postScan` | INW, WKS_IN, WKS_OUT, RTO_IN scans |
| `postQcPass` | QC_PASS — single scan or two-scan car+remote pairing |
| `postQcFail` | QC_FAIL — with parent event + defect list |
| `voidScanSelf` | Tier 1 operator self-void |

### Dashboard GET Endpoints (JWT required)
| Action | Description |
|---|---|
| `getProductCodes` | All active SKUs with product_code, has_remote |
| `getOperators` | List all operators |
| `getDevices` | List all devices |
| `getDefectMaster` | Defect codes, filtered by is_active |
| `getUpcBatches` | List print batches |
| `getUpcBatch` | Single batch — supports `?include_rows=true` for print |
| `getScanHistory` | Scan history for a UPC |
| `getPairingStatus` | Check if car+remote are a valid pair |
| `getProductionDashboard` | Executive summary |
| `getPlanVsActual` | Plan vs actual by date range |
| `getLineView` | Per-line stats + operator table |
| `getQCView` | Defect heatmap + FPY + repeat defects |

### Dashboard POST Endpoints (JWT required)
| Action | Description |
|---|---|
| `generateUpcBatch` | Create batch — takes `product_code` (4/5 char), assigns `product_seq` per-product |
| `receiveUpcBatch` | Mark batch received → all UPCs flip to `available` |
| `voidUpc` | Mark individual UPC as damaged/unused |
| `voidScan` | Tier 2 supervisor void |
| `amendScan` | Tier 3 post-shift amendment |
| `createOperator` | Add operator + generate QR code |
| `updateOperator` | Update operator record |
| `configureDevice` | Update device station/line |

### generateUpcBatch Logic
- Input: `{ product_code, quantity, notes }`
- `product_code` is 4-char (car) or 5-char ending in 'R' (remote)
- Looks up `product_master` by car code to get product name
- Finds `MAX(product_seq)` for this product_code in `upc_pool` → starts from +1
- Finds `MAX(upc_id)` globally → assigns next LOT- numbers
- Inserts in chunks of 500 to avoid payload limits

### Store GET/POST Endpoints (existing — unchanged)
All original store endpoints unchanged. See v1.1 for full list.

---

## 10. Device Inventory (13 total, all configured)

| Device Code | Station | Line | Notes |
|---|---|---|---|
| INW-L1 | INW | L1 | |
| INW-L2 | INW | L2 | |
| INW-L3 | INW | L3 | |
| QCP-L1 | QC_PASS | L1 | Two-scan flow for RC cars |
| QCP-L2 | QC_PASS | L2 | |
| QCP-L3 | QC_PASS | L3 | |
| QCF-L1 | QC_FAIL | L1 | Component-filtered defect list |
| QCF-L2 | QC_FAIL | L2 | |
| QCF-L3 | QC_FAIL | L3 | |
| WKS | WKS | SHARED | WKS_IN + WKS_OUT modal per scan |
| PKG | PKG | SHARED | Future |
| RTD | RTD | SHARED | Future |
| RTO | RTO_IN | SHARED | |

---

## 11. Build Phases & Status

| Phase | Module | Status |
|---|---|---|
| 1 | DB + Scanner App | ✅ **Complete** |
| 2 | Reporting Dashboard | ✅ **Live** |
| 3 | Repair Module | 🔲 Not started |
| 4 | Reconciliation | 🔲 Not started |
| 5 | Audit Module | 🔲 Not started |
| 6 | Assembly Stations | 🔲 Not started |

### Phase 1 Detailed Status
| Item | Status |
|---|---|
| DB schema — all tables | ✅ Live (fixed missing columns this session) |
| 86 SKUs in product_master | ✅ |
| Product code system (4-char + R for remote) | ✅ Seeded |
| 6 test operators seeded | ✅ |
| 13 devices configured | ✅ |
| UPC generation + print (dashboard) | ✅ Live — A3, 16×23 grid, 368/sheet |
| Batch receive workflow | ✅ Live |
| Scanner operator login (QR + manual) | ✅ Live |
| Scanner simplified setup (station+line only) | ✅ Live |
| INW scan → DB | ✅ **Confirmed working this session** |
| QC_PASS two-scan pairing flow | ✅ Built, not yet tested end-to-end |
| QC_FAIL defect modal | ✅ Built, not yet tested end-to-end |
| WKS modal | ✅ Built, not yet tested |
| Offline queue | ✅ Built |
| Scan correction UI | ✅ Worker endpoints done, dashboard UI pending |
| Packing pair verification | 🔲 Not started |
| RTE/RTR scan flow | 🔲 Not started |
| Operator QR card printing | 🔲 Not started |

---

## 12. Next Session — Where to Pick Up

**Immediate tests to run (scanner is working, test these flows):**
1. QC_PASS — scan a car UPC, then its remote → confirm pairing in `unit_pairs` table
2. QC_FAIL — scan a unit, select defects → confirm rows in `qc_fail_events` + `qc_fail_defects`
3. Duplicate scan rejection — scan same UPC twice → should get DUPLICATE SCAN screen

**Then build:**
- Scan correction UI on dashboard (Tier 2 void + Tier 3 amend)
- Operator QR card generation + print flow
- RTE/RTR scan flow (scans batch label, not UPC)
- Packing pair verification

---

## 13. Technical Decisions Log

| Decision | Choice | Reason |
|---|---|---|
| Camera library | Native getUserMedia + ZXing | html5-qrcode fails silently on MIUI |
| Scan at RTE/RTR | Batch label not UPC | Prevents duplicate dispatch scans |
| Schema separation | store vs public | Legacy origin; RPCs must not include profile headers |
| RTD definition | Calculated (RTE + RTR) | Not a physical scan point |
| Operator login | QR card scan only | Operators can't type reliably |
| UPC format | LOT-XXXXXXXX (8 digit, global sequential) | Simple, no collision risk, scalable |
| Display code | KNAK-7 (product_code + per-product seq) | Human readable, scannable on floor |
| QR encodes | LOT- number (not display code) | Permanent anchor; display code is derived |
| Product codes | 4-char PP+M+C, remote = car code + R | Unique per variant/color, operator-readable |
| Per-product sequence | Independent counter per product_code | Short readable numbers; car/remote independent |
| Remote UPC | Independent from car, same pool | Remote travels separately, needs own scan history |
| Remote pairing | Created at QC_PASS only | No tentative pair state — cleaner model |
| QC_FAIL storage | Parent + child rows | Enables both defect-level and unit-level queries |
| Defect list filtering | By component type at scan time | Shorter list, no wrong-component codes |
| Scan correction | Tier 1/2/3 model | Prevent first, correct second; Mahesh read-only always |
| Void mechanism | voided flag on scans row | Records never deleted; audit trail always complete |
| Scanner setup | Station + line only | Device auto-resolved from DB; no manual entry |
| Lock mechanism | Station definition = lock | No PIN needed; each station = one activity |
| Print approach | New browser window with embedded data URLs | canvas @media print unreliable; new window always works |
| Scanner feedback | After worker confirms | Not optimistic — prevents false success signals |
| Token storage | Memory only | Security; session expires on tab close |
| Mahesh access | Read-only permanently | Audit independence — cannot be changed per role |

---

## 14. Open Technical Questions

1. **WKS device toggle:** Operator manually chooses WKS_IN vs WKS_OUT per scan via modal (current). Could be system-inferred from unit's current state — smarter but has edge cases.

2. **Scan events partitioning:** Defer to month 10 (~500K events/month). Plan DDL now even if not deploying yet.

3. **QC_PASS timeout:** Should second scan (remote) time out after N seconds? If operator walks away mid-flow, scanner is stuck. Recommend yes — configurable timeout with explicit cancel.

4. **Rework module schema:** Minimum viable: `rework_orders` table linked to original scan, consumes parts from store. Full design pending resolution of open business questions.

5. **Dashboard tab access control:** Deferred — role-gate later when feature set is stable.

6. **Defect master ownership:** Who can add/modify defect codes after initial seeding?

---

## 15. Environment Reference

> ⚠️ Do not commit to git.

- **Supabase URL:** `https://jkxcnjabmrkteanzoofj.supabase.co`
- **Supabase publishable key:** `sb_publishable_1Dd-r3h9Mou2Wqgn6t24Dw_lmWdBtLh`
- **Worker URL:** `https://lotopsproxy.afshaan.workers.dev`
- **Scanner:** `https://scanner.legendoftoys.com`
- **Dashboard:** `https://dashboard.legendoftoys.com`
- **GitHub repo:** `legendlot/production`

---

*Update at end of every build session. "Next Session" and "Build Phases" are the source of truth for what to work on next.*
