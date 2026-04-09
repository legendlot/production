# Legend of Toys — Technical Build Document
**Version:** 2.4 | **Last Updated:** April 2026
**Purpose:** Technical reference for the LOT production operations system. Feed alongside LOT_SYSTEM.md when continuing development in a new chat session.

---

## 1. Stack

| Layer | Technology | Details |
|---|---|---|
| Database | Supabase (Postgres) | `jkxcnjabmrkteanzoofj.supabase.co` — Micro compute |
| API layer | Cloudflare Workers | `lotopsproxy.afshaan.workers.dev` |
| Scanner PWA | Vanilla JS + ZXing | `scanner.legendoftoys.com` — GitHub Pages, repo `legendlot/production` |
| Scanner APK | TWA (Bubblewrap) | Built via bubblewrap CLI — `app-release-signed.apk`. PKG station Redmi A5 uses APK. Keystore at `/Users/afshaansiddiqui/Documents/lot-scanner-apk/android.keystore` |
| Dashboard | Vanilla JS | `dashboard.legendoftoys.com` — GitHub Pages, repo `legendlot/dashboard` |
| Store system | Vanilla JS | Separate app — store schema |
| Camera/scanning | Native `getUserMedia` + ZXing | Replaced html5-qrcode (MIUI incompatibility) |
| Print server | Node.js v2.2 | `printserver.js` on each line's PKG station laptop — polls Supabase filtered by line. Atomic job claiming prevents cross-line duplicate prints. No HTTP server, no firewall rules needed. |
| Thermal printer | TSC TE244 | TSPL, USB, 50×25mm labels, `@thiagoelg/node-printer` |
| Fonts | Tomorrow + JetBrains Mono | Google Fonts |
| QR generation (dashboard) | QRCode.js from cdnjs | Used in UPC Generator print flow and Operators tab QR card print |

---

## 2. Supabase Schema

### Schema Separation
- **`store` schema** — inventory, procurement, GRN, issues, work orders, production runs, returns
- **`public` schema** — scan tables, units, devices, operators, views, RPCs

Store schema API calls include `Accept-Profile: store` / `Content-Profile: store` headers.
Public schema RPC calls use `sbPublic()` helper — no profile headers.

**Supabase max rows setting:** 5000 (changed from default 1000 — required for scan display table).

---

### Production Schema Tables (public)

#### `units` — One row per physical unit
```
upc             TEXT PRIMARY KEY
ean             TEXT                  -- nullable (remotes have no EAN)
sku             TEXT                  -- nullable
product         TEXT
model           TEXT                  -- nullable
color           TEXT                  -- nullable
current_status  TEXT (enum)           -- inwarded | qc_pass | qc_fail | in_repair | repaired
                                      -- pending_rtd | rtd | scrapped | rto_in
loop_count      INT
is_fresh        BOOLEAN
created_at      TIMESTAMPTZ
updated_at      TIMESTAMPTZ
component_type  TEXT                  -- 'car' | 'remote'
paired_with     TEXT                  -- references units(upc)
production_run_id UUID
```

**unit_status enum values (confirmed):** `inwarded | qc_pass | qc_fail | in_repair | repaired | pending_rtd | packed | rtd | scrapped | rto_in`
- `pending_rtd` — added April 2026. Set after PKG scan. Required for PKG_OUT validation.
- `packed` — legacy value, no longer written by system. Both coexist in enum.

#### `scans` — All scan activity (immutable ledger)
```
id              UUID PRIMARY KEY
timestamp       TIMESTAMPTZ
upc             TEXT
ean             TEXT
activity        TEXT    -- INW | QC_PASS | QC_FAIL | WKS_IN | WKS_OUT | PKG | RTE | RTR | RTO_IN | RTD_RETURN
line            TEXT
station         TEXT
device_id       UUID
operator_id     UUID
shift           TEXT
plan_id         TEXT    -- UUID of production run (store schema) — nullable
batch_label     TEXT
notes           TEXT
raw_input       TEXT
loop_count      INT DEFAULT 0
voided          BOOLEAN DEFAULT false
voided_by       UUID
voided_at       TIMESTAMPTZ
void_reason     TEXT
superseded_by   UUID REFERENCES scans(id)
```

**Note:** `RTD_RETURN` added to activity enum April 2026.
**Note on RTE/RTR line field:** PKG_OUT device is SHARED, so `scans.line` for RTE/RTR is unreliable. Always join via `pkg_scans.car_upc` to get the correct originating line.

#### `scan_violations` — Wrong-sequence scan log
```
id                   UUID PRIMARY KEY DEFAULT gen_random_uuid()
timestamp            TIMESTAMPTZ DEFAULT now()
upc                  TEXT
device_code          TEXT
station              TEXT
operator_id          UUID
line                 TEXT
unit_status          TEXT
expected_status      TEXT
error_message        TEXT NOT NULL
acknowledged_by      UUID
acknowledged_at      TIMESTAMPTZ
acknowledgement_note TEXT
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
product_code    TEXT                -- 4-char e.g. 'KNAK', or 5-char remote e.g. 'KNAKR'
product_seq     INT                 -- per-product sequential number (legacy)
```

**INW status check:** `status` must be `available`. `applied` means already used on a prior unit — sticker was duplicated/reused on the floor. Fix: apply a fresh available sticker from the same batch.

#### `upc_batches` — Print batch records
```
batch_id        TEXT PRIMARY KEY    -- 'B-001'
product         TEXT
product_code    TEXT
ean             TEXT
model           TEXT                -- NULL for remote batches
color           TEXT                -- NULL for remote batches
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
category        TEXT (enum)         -- Car-Functional | Car-Visual | Remote-Functional | Remote-Visual | Packaging
issue           TEXT
severity        TEXT
sub_issues      JSONB
is_active       BOOLEAN
created_at      TIMESTAMPTZ
component       TEXT                -- 'car' | 'remote' | 'both'
product         TEXT
training_flag   BOOLEAN DEFAULT false
```

#### `unit_pairs` — QC pairing records
```
id              UUID PRIMARY KEY
car_upc         TEXT
remote_upc      TEXT
paired_at       TIMESTAMPTZ
paired_by       UUID
scan_id         UUID
status          TEXT                -- active | broken
```

#### `pkg_scans` — Packaging station records
```
id              UUID PRIMARY KEY
car_upc         TEXT NOT NULL
remote_upc      TEXT
batch_label     TEXT NOT NULL UNIQUE    -- e.g. LOT-00000042-E
channel         TEXT                    -- 'ecom' | 'retail'
operator_id     UUID
device_id       UUID
line            TEXT                    -- source of truth for which line dispatched a unit
packed_at       TIMESTAMPTZ DEFAULT NOW()
rte_rtr_scan_id UUID
print_sent      BOOLEAN DEFAULT FALSE
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

#### `print_jobs` — PKG label print queue
```
id           UUID PRIMARY KEY DEFAULT gen_random_uuid()
batch_label  TEXT NOT NULL
product      TEXT
model        TEXT
color        TEXT
channel      TEXT
packed_at    TIMESTAMPTZ
status       TEXT NOT NULL DEFAULT 'pending'   -- pending | printing | done | failed
line         TEXT                               -- L1 | L2 | L3
created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
printed_at   TIMESTAMPTZ
error        TEXT
```
Index: `CREATE INDEX print_jobs_status_idx ON public.print_jobs (status, created_at);`

#### `devices` — confirmed columns
```
id           UUID PRIMARY KEY
device_code  TEXT                -- e.g. INW-L1, PKG-L2, WKS-L1
label        TEXT
line         TEXT                -- L1 | L2 | L3 | SHARED
station      TEXT
is_active    BOOLEAN
created_at   TIMESTAMPTZ
last_seen    TIMESTAMPTZ
notes        TEXT
```
**Note:** No `activity` column exists. Activity is inferred from station/device_code in worker logic.

#### `operators` — confirmed columns
```
id              UUID PRIMARY KEY
name            TEXT
qr_code         TEXT
role            TEXT (enum)   -- assembly | qc_inline | qc_audit | repair | packing | rtd | store
                              -- supervisor | line_manager | production_manager | admin
reports_to      UUID
is_active       BOOLEAN
created_at      TIMESTAMPTZ
line            TEXT          -- default line (not fixed assignment)
qr_generated_at TIMESTAMPTZ
```

#### `operator_sessions` — Scanner login sessions
```
id                UUID PRIMARY KEY DEFAULT gen_random_uuid()
operator_id       UUID NOT NULL REFERENCES public.operators(id)
device_id         UUID NOT NULL REFERENCES public.devices(id)
station           TEXT NOT NULL
line              TEXT NOT NULL
production_run_id UUID          -- active run at login time, nullable
shift             TEXT          -- Regular | OT | Double OT
login_at          TIMESTAMPTZ NOT NULL DEFAULT now()
logout_at         TIMESTAMPTZ   -- null until next operator logs in on same device
created_at        TIMESTAMPTZ DEFAULT now()
```

**Session lifecycle:** Opens on QR card scan → Closes when next operator scans on same device. No explicit logout. Shift attribution via timestamps (Regular: before 18:00, OT: 18:00-21:00, Double OT: after 21:00 IST).

Indexes: `operator_id`, `device_id`, `(device_id, login_at DESC)`

#### `store.production_runs` — confirmed columns
```
id           UUID PRIMARY KEY
run_no       TEXT
run_date     DATE
product      TEXT
line_no      TEXT
shift        TEXT
status       TEXT
created_by   TEXT
released_at  TIMESTAMPTZ
completed_at TIMESTAMPTZ
notes        TEXT
created_at   TIMESTAMPTZ
updated_at   TIMESTAMPTZ
```

**Note:** `production_runs.id` is an integer in the store schema, NOT a UUID. Guard: `wksInPlanId = (typeof runId === 'string' && runId.includes('-')) ? runId : null`

#### `store.po_lines` — key columns
```
po_number       TEXT
line_no         INT
product         TEXT
variant         TEXT        -- model-level variant
color           TEXT        -- color sub-variant ← added April 2026 for BY UNITS mode, nullable
item_type       TEXT
description     TEXT
part_code       TEXT
qty_ordered     NUMERIC
qty_received    NUMERIC
unit            TEXT
unit_price      NUMERIC
```

---

## 3. RPCs (public schema)

| RPC | Purpose | Parameters |
|---|---|---|
| `get_executive_dashboard` | KPI summary cards | `p_date` |
| `get_open_runs` | Active + upcoming production runs | none |
| `get_line_view` | Per-line scan counts + run info + first_scan_at | `p_date`, `p_line` |
| `get_defect_heatmap` | Top defects by code | `p_date_from`, `p_date_to`, `p_line` |
| `v_first_pass_yield` | FPY by line/date (view) | — |
| `v_repeat_defects` | Units with multiple failures (view) | — |
| `get_plan_vs_actual` | Run targets vs actuals | `p_date_from`, `p_date_to`, `p_line` |
| `get_scan_summary` | Accurate scan counts bypassing row limits | `p_date` |
| `get_qc_cycle_time` | Avg time INW → QC_PASS/QC_FAIL with IQR outlier detection | `p_date_from`, `p_date_to`, `p_line` |
| `get_pkg_cycle_time` | Avg time QC_PASS → PKG with IQR outlier detection | `p_date_from`, `p_date_to`, `p_line` |
| `get_rtd_cycle_time` | Avg time PKG → RTE/RTR with IQR outlier detection | `p_date_from`, `p_date_to`, `p_line` |
| `next_seq` | Auto-increment named sequences | `seq_name` |
| `get_line_car_remote_split` | Per-line car/remote counts for INW and QC_PASS | `p_date` |
| `get_dispatch_by_pkg_line` | RTE/RTR counts attributed via pkg_scans.line | `p_date` ← new April 2026 |
| `get_hourly_dispatch_by_line` | Dispatched units per hour per line via pkg_scans join | `p_date` ← new April 2026 |
| `get_defect_breakdown` | Per-product/component/severity/defect counts for QC breakdown view | `p_date_from`, `p_date_to`, `p_line` ← new April 2026 |

### `get_scan_summary` — returns single row
Fields: `total, voided, INW, QC_PASS, QC_FAIL, WKS_IN, WKS_OUT, PKG, RTO_IN, RTD_RETURN`

### Cycle Time RPCs — shared design
- IQR fence: `GREATEST(Q3 + 2×GREATEST(IQR, 5), Q3 + 10)` minutes. Overnight cap: 480 minutes.
- Returns: `avg_mins_all`, split averages, `median_mins`, `units_measured`, `fastest_mins`, `slowest_normal_mins`, `outlier_count`, `outlier_max_mins`, `outlier_threshold_mins`

### `get_dispatch_by_pkg_line` — design note
Joins `scans` → `pkg_scans` on `car_upc` + filters `component_type = 'car'` to attribute RTE/RTR to the line where the unit was **packaged**. This is the canonical way to get per-line dispatch counts. Used by `getLineView` and `getPlanVsActual` worker endpoints to override the broken `scans.line` SHARED attribution.

### `get_hourly_dispatch_by_line` — design note
Same join as above, grouped by `EXTRACT(HOUR FROM s.timestamp AT TIME ZONE 'Asia/Kolkata')` and `ps.line`. Powers the hourly battery-cell chart on the Dashboard tab.

---

## 4. Worker Endpoints (GET actions)

| Action | Description |
|---|---|
| `getProductionDashboard` | Executive tab — includes `hourly_dispatch` from `get_hourly_dispatch_by_line` ← updated April 2026 |
| `getLineView` | Lines tab — merges corrected dispatch via `get_dispatch_by_pkg_line`, filters SHARED ← updated April 2026 |
| `getQCView` | QC tab — heatmap + FPY + repeat defects + cycle time |
| `getCycleTimeSummary` | Reporting tab |
| `getPlanVsActual` | Runs + Reporting — merges corrected dispatch for single-day calls ← updated April 2026 |
| `getProductCodes` | UPC Generator |
| `getUpcBatches` | UPC Generator batch history |
| `getUpcBatch` | UPC Generator single batch |
| `getAllScans` | Scans + Corrections tabs |
| `getScanSummary` | Scans tab summary cards via RPC |
| `getScansByUpc` | Scans tab UPC search — auto-pads digits to LOT-XXXXXXXX ← updated April 2026 |
| `getViolations` | Alerts tab |
| `getViolationSummary` | Alerts tab summary cards |
| `getReturnQueue` | Returns tab |
| `getOperators` | Operators tab |
| `getOperatorSessions` | Operators tab — sessions by date/operator/line |
| `saveOperator` | Operators tab — create or update operator |
| `getPkgScans` | Scans tab — pkg_scans by date range |
| `getPkgScanLookup` | Print tab — pkg_scans by batch_label or car_upc with units join |

## 4b. Worker Endpoints (POST actions)

| Action | Description |
|---|---|
| `postPkgOut` | PKG_OUT scan — uses `pkgScan.line` as effectiveLine ← updated April 2026 |
| `generateUpcBatch` | Nulls model/color for remote batches |
| `postWksScan` | WKS scan — infers WKS_IN or WKS_OUT_PENDING |
| `postWksOut` | WKS outcome — REPAIRED or SCRAPPED |
| `postReprintJob` | Inserts new pending print_job row |
| `postPkg` | PKG scan — pair verify, insert pkg_scans, insert print_jobs |
| `postReturnShipment` | Store — creates return shipment record |
| `postReturnUnit` | Store — logs return unit |
| `postReturnInspection` | Store — records inspection + disposition |
| `postReturnHandover` | Store — marks shipment handed_over, sets units → rto_in |
| `getReturnShipments` | Store — list return shipments |
| `getReturnShipment` | Store — single shipment + all return units |
| `createUser` | Store admin — uses `SUPABASE_SERVICE_KEY` for apikey header ← fixed April 2026 |
| `resetPassword` | Store admin — same fix ← fixed April 2026 |

### SCANNER_ACTIONS (bypass JWT, use device auth)
```
scannerLogin, postScan, postWksScan, postWksOut, postQcPass, postQcFail, voidScanSelf, postPkg, postPkgOut, postReprintJob
```

---

## 5. Scanner Flows

### INW
Single scan. System identifies car vs remote from `upc_pool.product_code`. Unit created in `units` table. Worker uses `product_code` (not `product` name) to look up `product_master`.

**UPC must be `available`.** Status `applied` = sticker already used on a prior unit (duplicate/reused on floor). Fix: apply a fresh available sticker from the same batch.

### QC_PASS
- `has_remote = true`: Two-scan sequence — car then remote. Pairing created in `unit_pairs`.
- `has_remote = false`: Single scan.
- Both: unit status → `qc_pass`.

### QC_FAIL
Single scan. Defect selection modal shown, filtered by component type. Parent + child rows in `qc_fail_events` + `qc_fail_defects`.

### WKS (Workshop)
Scanner calls `postWksScan`. Direction (IN/OUT) inferred from unit status. Line from `device.line` directly (WKS-L1/L2/L3). UUID guard on plan_id.

### PKG
Two-scan flow (car + remote). Status → `pending_rtd`. `print_jobs` row inserted with line.

### PKG_OUT ← updated April 2026
- **Fresh** (`pending_rtd`): `-E` → RTE, `-R` → RTR. Both units → `rtd`.
- **Return RTD**: `RTD_RETURN` scan.
- **Line attribution:** `effectiveLine = pkgScan.line || d.line || device.line` — uses PKG scan's line (L1/L2/L3), not PKG_OUT device line (SHARED).

### Print Flow — v2.2 Per-Line
Poll → atomic claim → print → done/failed. REPRINT via print_jobs. v2.2 on L1 and L2. **L3 not yet set up.**

### Operator Sessions
Every QR login: closes prior session on device, derives shift from IST, inserts new session row.

### Void + Status Rollback
Voiding rolls back `units.current_status` to previous non-voided scan's activity.

---

## 6. Dashboard

**URL:** `dashboard.legendoftoys.com` | **Repo:** `legendlot/dashboard`

### Tabs
| Tab | Key Features |
|---|---|
| Executive | KPI cards, hourly battery-cell dispatch chart, PVA cards, runs table |
| Lines | Per-line cards (today only). SHARED card filtered out. Dispatch numbers now correctly attributed. |
| QC | Cycle time, FPY by line, top defects, repeat failures |
| Runs | Plan vs actual |
| UPC Generator | Searchable dropdown, car/remote toggle, batch history, print |
| Scans | Real-time feed, activity filter, UPC search (auto-pads digits), voided toggle. Summary cards fixed. |
| Corrections | Tier 2 void + Tier 3 amend |
| Alerts | Scan violations, acknowledge, badge count |
| Returns | Units in rto_in status |
| Reporting | Date-range cycle times, QC summary, product breakdown, daily, line |
| Operators | Full CRUD, sessions today, QR card print |
| Print | Single + bulk batch label reprint |

### Hourly Chart — Battery Cell Design ← new April 2026
- One row per active line (SHARED filtered out)
- Each cell = a battery: container height = hourly target, fill = actual dispatched that hour
- Colours: green (≥ target), orange (70–99%), red (<70%), dimmed (future), line colour (current partial hour)
- Metric: **dispatched units** (RTE+RTR) from `get_hourly_dispatch_by_line` via pkg_scans.line
- Header: target/hr rate, total dispatched, pace status (X ahead / X behind)
- Hourly target = `target_qty / 9` (equal split, 9am–6pm). Future: weighted by output pattern.

### Dispatch Attribution Fix ← April 2026
PKG_OUT device line = SHARED. Three data paths fixed:
1. `postPkgOut` — writes `pkgScan.line` to future RTE/RTR scans
2. `getLineView` — overrides rtr/rte/completion_pct via `get_dispatch_by_pkg_line`
3. `getPlanVsActual` — overrides actual_rtr/rte/completion_pct/gap for single-day calls
4. `renderLineCards` — filters SHARED line

### Store Procurement — BY UNITS Mode ← new April 2026
Third line item mode. Product → model+color grid with qty inputs → accumulated queue → submitted as po_lines with product/variant/color. `store.po_lines.color` column added. item_type defaults to 'RC Car'. Price left blank for future price master.

---

## 7. Print Server — v2.2

**Location:** Windows laptop at each line's PKG station.
**Files:** `printserver.js` (identical), `config.json` (only `line` differs: L1/L2/L3).

v2.2 deployed to L1 and L2. **L3 not yet set up.**

---

## 8. Known DB Fixes Applied

| Fix | SQL | Reason |
|---|---|---|
| `units.ean/sku/model/color` nullable | ALTER TABLE DROP NOT NULL | Remotes have no EAN/SKU/model/color |
| Defect component mapping | UPDATE defect_master SET component | Was null, broke filtering |
| Supabase max rows | Settings → API → Max Rows → 5000 | Default 1000 capped scan display |
| Return sequences | INSERT INTO store.sequences | Return system IDs |
| `get_scan_summary` RPC | Created + updated with RTD_RETURN + car/remote split | Accurate counts + split |
| get_line_view RPC | DROP + recreate multiple times | Line grouping, first_scan_at, car-only counts |
| Cycle time RPCs | Created (QC, PKG, RTD) with IQR outlier detection | April 2026 |
| RTD_RETURN activity enum | ALTER TYPE activity_type ADD VALUE | Return RTD path |
| return_shipments | created + handed_over_at + status enum | Store return system |
| `pending_rtd` unit_status enum | ALTER TYPE unit_status ADD VALUE | PKG flow |
| print_jobs table + line column | Created + line column added | Per-line print routing |
| PKG/WKS devices per-line | UPDATE + INSERT devices | Per-line routing |
| operator_sessions table | Created April 2026 | Session tracking |
| B-014 units data fix | UPDATE units SET sku/model/ean WHERE upc BETWEEN ... | Product seeding bug |
| get_executive_dashboard RPC | DROP + recreate car-only counts | Was double-counting |
| get_plan_vs_actual RPC | DROP + recreate joins via units.upc | EAN join returned 0 actuals |
| get_line_car_remote_split RPC | Created | Car/remote split for Lines tab |
| v_operator_output view | DROP + recreate, shift removed from GROUP BY | Duplicate rows per shift |
| **get_dispatch_by_pkg_line RPC** | Created April 2026 | PKG_OUT SHARED line attribution bug |
| **get_hourly_dispatch_by_line RPC** | Created April 2026 | Hourly battery chart |
| **store.po_lines.color column** | `ALTER TABLE store.po_lines ADD COLUMN IF NOT EXISTS color TEXT` | BY UNITS procurement mode |
| **Auth admin apikey fix** | `SUPABASE_SERVICE_KEY` used for apikey in createUser/resetPassword/getUsers | Publishable key rejected by Auth Admin API |
| **get_line_car_remote_split RPC** | Added `QC_FAIL` to activity filter (April 2026) | QC Fail car/remote split on dashboard + lines tab |
| **get_executive_dashboard RPC** | PKG/RTR/RTE/QC_FAIL now filter `component_type = 'car'`; `voided = false` added to all counts (April 2026) | Was double-counting cars+remotes for dispatch/pkg metrics |
| **get_defect_breakdown RPC** | Created April 2026 — returns product/component/severity/defect breakdown | Powers QC tab product breakdown collapsible view |
| **get_line_view RPC** | `component_type = 'car'` → `IS DISTINCT FROM 'remote'` on INW/QC_PASS/QC_FAIL/PKG counts — **PENDING, not yet applied** | Silently drops scans where units.component_type is NULL |
| **store.stock_ledger orphan** | Hard deleted id=7 (HW-SC-M08-23) | Phantom row with 0 received, 46,800 issued, null cost — driving false reorder flag in reorder_flags view |

---

## 9. Build Phases & Status

| Phase | Module | Status |
|---|---|---|
| 1 | DB + Scanner App | ✅ Complete |
| 1b | PKG Print System | ✅ Complete — polling v2.2 per-line atomic |
| 2 | Reporting Dashboard | ✅ Live (12 tabs) |
| 3 | Repair Module | ✅ Complete |
| 3b | Return System | ✅ Complete |
| 3c | Station Status Controls + Alerts | ✅ Complete |
| 3d | Cycle Time Analytics | ✅ Complete |
| 3e | Reporting Tab | ✅ Complete |
| 3f | Operator Management | ✅ Complete |
| 3g | Print Tab | ✅ Complete |
| 3h | RTO_IN Intact Path + Store Returns | ✅ Complete |
| 3i | Dashboard Overhaul | ✅ Complete |
| 3j | Dispatch Attribution Fix | ✅ Complete — SHARED line removed, pkg_scans.line used (April 2026) |
| 3k | Hourly Achievement Chart | ✅ Complete — battery-cell, dispatched metric, per-line (April 2026) |
| 3l | Store Procurement BY UNITS | ✅ Complete — variant+color ordering mode (April 2026) |
| 3m | QC Tab Overhaul | ✅ Complete — cycle time by line, defects by line/functional/visual, product breakdown collapsible (April 2026) |
| 3n | Alerts Tab Fix | ✅ Complete — violation logging wired to scanner flows, date filter fixed, summary fields fixed, alerts date picker added (April 2026) |
| 3o | Dashboard Exec Cards Fix | ✅ Complete — QC Fail car/remote split, dispatch double-count fixed (April 2026) |
| 3p | Store Procurement Fixes | ✅ Complete — init race condition fixed, BOM metal parts filter fixed, variant filter fixed, deduplication on add (April 2026) |
| 3q | FBU/CKD/SKD Schema | 🔶 Schema only — product_master + po_lines columns added, remote rows seeded (23 products), receive_format backfilled. UI + GRN integration built but pending test. (April 2026) |
| 3r | FBU/CKD/SKD Store Frontend | 🧪 Built, pending test — BY UNITS BOM explosion for CKD, FBU GRN panel, FBU stock view toggle, issue_mode selector per variant row (FBU products only), issue queue FBU lines section, issueAgainstRun FBU deduction (April 2026) |
| 3s | QC_PASS / PKG product_master fix | ✅ Deployed — added `component_type=eq.car` filter to postQcPass, postPkg, getProductCodes; prevents remote rows being returned instead of car rows (April 2026) |
| 4 | Reconciliation | 🔲 Not started |
| 5 | Audit Module | 🔲 Not started |
| 6 | Assembly Stations | 🔲 Not started |

### Open Issues — fix before building new features

None currently. ✅

### Pending Test — built but not yet verified on floor

| Item | What to test |
|---|---|
| **FBU/CKD/SKD Store Frontend (3r)** | BY UNITS CKD → BOM explosion on PO save; BY UNITS FBU → unit-level po_lines; FBU GRN panel submits and increments fbu_stock; stock view FBU toggle shows fbu_stock; issue_mode selector appears only for FBU products (Dash/Nitro) on production runs; issue queue FBU section present for Dash/Nitro runs; issueAgainstRun deducts fbu_stock and logs to fbu_issue_register |
| **QC_PASS fix (3s)** | Knox (and any has_remote=true product) scanned at QC_PASS → remote prompt appears correctly; product name in toast is correct |

### Pending Build Items (prioritised)

**Next up:**
1. **Dashboard nav bar consolidation** — 11 tabs → grouped dropdowns; design session in progress
2. **Repair run design session** — full design before any build

**Dashboard backlog:**
3. **Dashboard day view for production** — detailed per-line, per-hour breakdown for a single selected day
4. **Channel allocation** — dispatch system + live stock view showing ecom vs retail allocation and split
5. **Consolidated dispatch view** — fresh RTD + RTD_RETURN combined per product per day

**Scanner backlog:**
6. **Print EAN alongside batch code** — add EAN to PKG thermal label (50×25mm TSC TE244)
7. **Test scanner mode** — scan any UPC → display current unit status and stage history; diagnostic tool for floor staff

**Store backlog:**
8. **GRN receiving template** — CKD BOM explosion vs FBU unit count path (schema + frontend done; GRN template not yet built)
9. **Price master module** — unit cost tracking with history; BOM dynamically calculates PO value from price charts

**General backlog:**
10. **Legacy UPC manual entry** — build when triggered
11. **Daily / weekly / monthly reporting views**
12. **Reconciliation module** — Phase 4
13. **Audit module** — Phase 5
14. **Assembly stations module** — Phase 6
15. **Dashboard tab RBAC** — deferred
16. **Biometric integration**
17. **Unicommerce reconciliation**
18. **APK rollout to all 15 devices** — deferred
19. **Dash/Nitro QR code problem** — cars too small to stick QR label; needs separate design session
20. **store.material_master name inconsistencies** — `HW-TM-POS` and `UNV-CB-2PIN-01` have two different names; rename pending
21. **Old para items with `(old)` suffix** — still active in Bumble, Flare, Ghost, Shadow BOM; deactivation pending Afshaan confirmation

---

## 10. Cycle Time Design

| Segment | From | To | RPC | Status |
|---|---|---|---|---|
| QC Cycle Time | INW | QC_PASS or QC_FAIL | `get_qc_cycle_time` | ✅ Built |
| PKG Cycle Time | QC_PASS | PKG | `get_pkg_cycle_time` | ✅ Built |
| RTD Cycle Time | PKG | RTE or RTR | `get_rtd_cycle_time` | ✅ Built |

IQR fence: `GREATEST(Q3 + 2×GREATEST(IQR, 5), Q3 + 10)` minutes. Overnight cap: 480 minutes.

---

## 11. Technical Decisions Log

| Decision | Choice | Reason |
|---|---|---|
| Camera library | Native getUserMedia + ZXing | html5-qrcode fails silently on MIUI |
| Scan at PKG_OUT | Batch label not UPC | UPC inaccessible inside sealed box |
| Batch label format | LOT-XXXXXXXX-E/R | Car UPC + channel — no new identifier pool needed |
| Channel at PKG | Toggle on device screen | Decided before run |
| Schema separation | store vs public | Legacy origin; RPCs must not include profile headers |
| RTD definition | RTE + RTR (via PKG_OUT) | Not a physical scan point |
| Operator login | QR card scan only | Operators can't type reliably |
| UPC format | LOT-XXXXXXXX (8 digit, global sequential) | Simple, no collision risk, scalable |
| Display code | KNAK00000007 (product_code + raw LOT seq, no hyphens) | Globally unique, human-readable |
| QR encodes | LOT- number (not display code) | Permanent anchor |
| Remote UPC | Independent from car, same pool | Remote travels separately |
| Remote pairing | Created at QC_PASS only | No tentative pair state |
| QC_FAIL storage | Parent + child rows | Enables defect-level and unit-level queries |
| Defect filtering | By component type at scan time | Shorter list |
| Scan correction | Tier 1/2/3 model | Prevent first, correct second; Mahesh read-only always |
| Void mechanism | voided flag on scans row | Records never deleted |
| Void rollback | Unit status rolled back on void | Prevents stuck units |
| WKS flow | System-inferred from unit status | Eliminates operator error |
| WKS worker action | `postWksScan` separate from `postScan` | Raw WKS not a valid activity_type enum |
| WKS line tracking | device.line directly (per-line devices) | No INW lookup needed |
| WKS plan_id | UUID guard before writing | store schema production_runs.id is integer not UUID |
| Print approach | Polling Supabase print_jobs | Chrome Android blocks local network fetch from HTTPS PWA |
| PKG label print | Non-blocking via print_jobs | Scan not blocked if printer offline |
| PKG/WKS per-line devices | PKG-L1/L2/L3, WKS-L1/L2/L3 | Per-line routing |
| Print server v2.2 | Atomic job claiming via conditional PATCH | Race condition fix |
| Operator sessions | Created on QR login, closed on next login same device | No explicit logout needed |
| Repair run | Design session required before build | Too many unknowns |
| `packed` vs `pending_rtd` | Both in enum, `pending_rtd` is active | `packed` is legacy |
| **Dispatch line attribution** | `pkgScan.line` not `device.line` in postPkgOut | PKG_OUT is SHARED — join pkg_scans for correct line |
| **Hourly chart metric** | Dispatched (RTE+RTR) not QC Pass | QC Pass overcounts remotes; dispatched = actual output |
| **BY UNITS item_type** | Defaults to 'RC Car' | Future: derive from product master |
| **Auth Admin API key** | SUPABASE_SERVICE_KEY for both apikey + Authorization | Publishable key rejected by Supabase Auth Admin endpoints |
| **UPC search auto-pad** | Digits → LOT-XXXXXXXX in worker | Operators type short numbers like "4267", not full format |
| **scanSummaryCards restore** | `style.display = 'grid'` not `''` | Element has inline grid style; `''` removes display, falls back to block |
| **QC Fail headline** | Total (car+remote) as headline, car·remote breakdown as sub-text | Consistent with how dispatched shows RTR·RTE breakdown |
| **get_line_view car counts** | `IS DISTINCT FROM 'remote'` preferred over `= 'car'` | Prevents silent drop of scans where units.component_type is NULL |
| **Violation logging** | Fire-and-forget `.catch(() => {})` inserts to scan_violations | Scanner response not blocked if violation log fails |
| **FBU/CKD/SKD format** | PO-level `receive_format` override; product master gets `car_receive_format`/`remote_receive_format` defaults | Format drives PO creation UI and GRN receiving template — not production flow |
| **BOM group filter** | `cats` filters `part_category`, `types` filters `part_type` — both supported per group | Metal Parts uses `part_type IN ('Metal','Hardware')` since metal spans Car/Remote/Fastener categories |
| **product_master remote rows** | One row per product (not per variant); model + color = NULL; product_code = PPXXR; linked_product_code = PP prefix | Remotes are product-level, not SKU-level — all Flare variants share one remote |
| **product_master component_type filter** | All `postQcPass`, `postPkg`, `getProductCodes` lookups filter `component_type=eq.car` | Remote rows now in product_master; without filter, `limit=1` could return remote row (has_remote=false) |
| **FBU stock table** | `store.fbu_stock` separate from `stock_ledger` | stock_ledger is component-level; FBU units are a different stock type; both coexist and show separately in UI |
| **issue_mode per WO** | `work_orders.issue_mode = 'components' | 'fbu'`; toggle only shown for FBU products | Per-line granularity; default components; FBU toggle only visible when product.receive_format = 'FBU' |

---

## 12. Open Technical Questions

1. **Scan events partitioning:** Defer to month 10 (~500K events/month).
2. **QC_PASS timeout:** Should second scan time out after N seconds? Recommend yes.
3. **Rework module schema:** Pending design session.
4. **Dashboard tab access control:** Deferred.
5. **Defect master ownership:** Who can add/modify defect codes post-seeding?
6. **Channel barcode scanning:** Future — scan platform return labels.
7. **Legacy UPC gap:** Build when triggered.
8. **Consolidated dispatch view:** Not yet built.
9. **Repair run schema:** Two contexts — inline vs L3 auxiliary. Pending design session.
10. **Daily/weekly/monthly reporting:** Not yet designed.
11. **`packed` vs `pending_rtd` cleanup:** Remove `packed` when safe.
12. **print_jobs retention:** Add cleanup for done/failed rows older than 30 days.
13. **All 15 devices APK vs PWA:** Deferred.
14. **Operator performance view:** Data available, view not built.
15. **Shift time boundaries:** Regular 9am-7pm (PKG), 9am-6pm (Assembly/QC). OT 6pm-9pm. Double OT after 9pm IST.
16. **Price master table:** For unit-level cost on procurement POs. item_type auto-derived from product master.
17. **Hourly target split intelligence:** Currently equal (target/9 hrs). Future: weight by historical pattern.
18. **PRODUCT_VARIANTS / PRODUCT_SUBVARIANTS data:** Nitro, Dash, Fang, Atlas need base variant + colors for BY UNITS mode to render correctly.

---

## 13. Environment Reference

> ⚠️ Do not commit to git.

- **Supabase URL:** `https://jkxcnjabmrkteanzoofj.supabase.co`
- **Supabase publishable key:** `sb_publishable_1Dd-r3h9Mou2Wqgn6t24Dw_lmWdBtLh`
- **Worker URL:** `https://lotopsproxy.afshaan.workers.dev`
- **Scanner:** `https://scanner.legendoftoys.com`
- **Dashboard:** `https://dashboard.legendoftoys.com`
- **Scanner repo:** `legendlot/production`
- **Dashboard repo:** `legendlot/dashboard`
- **APK keystore:** `/Users/afshaansiddiqui/Documents/lot-scanner-apk/android.keystore`
- **APK package ID:** `com.legendoftoys.scanner`

---

*Update at end of every build session. "Open Issues" and "Pending Build Items" are the source of truth for what to work on next.*
