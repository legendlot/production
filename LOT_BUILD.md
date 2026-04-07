# Legend of Toys — Technical Build Document
**Version:** 1.9 | **Last Updated:** April 2026
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
line            TEXT
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

#### `print_jobs` — PKG label print queue ← updated April 2026
```
id           UUID PRIMARY KEY DEFAULT gen_random_uuid()
batch_label  TEXT NOT NULL
product      TEXT
model        TEXT
color        TEXT
channel      TEXT
packed_at    TIMESTAMPTZ
status       TEXT NOT NULL DEFAULT 'pending'   -- pending | printing | done | failed
line         TEXT                               -- L1 | L2 | L3 ← added April 2026 for per-line routing
created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
printed_at   TIMESTAMPTZ
error        TEXT
```
Index: `CREATE INDEX print_jobs_status_idx ON public.print_jobs (status, created_at);`

#### `devices` — confirmed columns (April 2026)
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

#### `operators` — confirmed columns (April 2026)
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

#### `operator_sessions` — Scanner login sessions ← new April 2026
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

**Session lifecycle:** Opens on QR card scan → Closes when next operator scans on same device (previous session gets logout_at set). No explicit logout. Shift attribution via timestamps (Regular: before 18:00, OT: 18:00-21:00, Double OT: after 21:00 IST).

Indexes: `operator_id`, `device_id`, `(device_id, login_at DESC)`

#### `return_shipments` (store schema) — updated April 2026
```
-- Status enum: open | partially_processed | fully_processed | handed_over | closed
handed_over_at  TIMESTAMPTZ
```

#### `store.production_runs` — confirmed columns (April 2026)
```
id           UUID PRIMARY KEY
run_no       TEXT
run_date     DATE
product      TEXT
line_no      TEXT                -- line the run is assigned to
shift        TEXT
status       TEXT
created_by   TEXT
released_at  TIMESTAMPTZ
completed_at TIMESTAMPTZ
notes        TEXT
created_at   TIMESTAMPTZ
updated_at   TIMESTAMPTZ
```

**Note:** `production_runs.id` is an integer in the store schema, NOT a UUID. Do not write it to `scans.plan_id` (which is UUID). Guard: `wksInPlanId = (typeof runId === 'string' && runId.includes('-')) ? runId : null`

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

### `get_scan_summary` — returns single row
Fields: `total, voided, INW, QC_PASS, QC_FAIL, WKS_IN, WKS_OUT, PKG, RTO_IN`
Note: `RTD_RETURN` not yet in RPC output.

### Cycle Time RPCs — shared design
- IQR fence: `GREATEST(Q3 + 2×GREATEST(IQR, 5), Q3 + 10)` minutes. Overnight cap: 480 minutes.
- Returns: `avg_mins_all`, split averages, `median_mins`, `units_measured`, `fastest_mins`, `slowest_normal_mins`, `outlier_count`, `outlier_max_mins`, `outlier_threshold_mins`

---

## 4. Worker Endpoints (GET actions)

| Action | Description |
|---|---|
| `getProductionDashboard` | Executive tab |
| `getLineView` | Lines tab |
| `getQCView` | QC tab — heatmap + FPY + repeat defects + cycle time ← cycle_time re-added April 2026 |
| `getCycleTimeSummary` | Reporting tab ← restored April 2026 |
| `getPlanVsActual` | Runs + Reporting tab |
| `getProductCodes` | UPC Generator |
| `getUpcBatches` | UPC Generator batch history |
| `getUpcBatch` | UPC Generator single batch |
| `getAllScans` | Scans + Corrections tabs |
| `getScanSummary` | Scans tab summary cards via RPC |
| `getViolations` | Alerts tab ← restored April 2026 |
| `getViolationSummary` | Alerts tab summary cards ← restored April 2026 |
| `getReturnQueue` | Returns tab — queries units with current_status=rto_in ← restored April 2026 |
| `getOperators` | Operators tab — all operators |
| `getOperatorSessions` | Operators tab — sessions by date/operator/line ← new April 2026 |
| `saveOperator` | Operators tab — create or update operator ← new April 2026 |
| `getPkgScans` | Scans tab — pkg_scans by date range |
| `getPkgScanLookup` | Print tab — pkg_scans by batch_label or car_upc with units join ← new April 2026 |

## 4b. Worker Endpoints (POST actions)

| Action | Description |
|---|---|
| `postReturnHandover` | Store — marks shipment `handed_over` |
| `postPkgOut` | PKG_OUT scan — auto-routes RTE/RTR or RTD_RETURN. Inserts print_job row. |
| `generateUpcBatch` | Nulls model/color for remote batches |
| `postWksScan` | WKS scan — infers WKS_IN or WKS_OUT_PENDING. Uses device.line directly (per-line devices). Auto-associates active production run as plan_id. ← updated April 2026 |
| `postWksOut` | WKS outcome — REPAIRED or SCRAPPED. Uses device.line directly. Auto-associates active production run. Auto-creates scrap loss note. ← updated April 2026 |
| `postReprintJob` | Inserts new pending print_job row with line field for correct printer routing ← updated April 2026 |
| `postPkg` | PKG scan — pair verify, insert pkg_scans, insert print_jobs with `line: deviceLine` |

### SCANNER_ACTIONS (bypass JWT, use device auth)
```
scannerLogin, postScan, postWksScan, postWksOut, postQcPass, postQcFail, voidScanSelf, postPkg, postPkgOut, postReprintJob
```

---

## 5. Scanner Flows

### INW
Single scan. System identifies car vs remote from `upc_pool.product_code`. Unit created in `units` table.
**Critical:** Worker uses `product_code` (not `product` name) to look up `product_master`. Using `product` name with `LIMIT 1` returns first alphabetical match — caused B-014 Shadow Tarmac Black to be seeded as Asphalt Black. Fixed April 2026.

### QC_PASS
- `has_remote = true`: Two-scan sequence — car then remote. Pairing created in `unit_pairs`.
- `has_remote = false`: Single scan.
- Both: unit status → `qc_pass`.

### QC_FAIL
Single scan. Defect selection modal shown, filtered by component type. Parent + child rows in `qc_fail_events` + `qc_fail_defects`.

### WKS (Workshop) ← updated April 2026
Scanner calls `postWksScan` (NOT `postScan` with activity WKS — that caused enum violation).
1. Operator scans UPC → `postWksScan` called
2. If unit is `qc_fail` → WKS_IN recorded, unit → `in_repair`, loop_count+1, defects fetched
3. Line taken from `device.line` directly (WKS devices are now per-line: WKS-L1/L2/L3)
4. Active production run looked up by line+date and written to `plan_id` (UUID guard applied)
5. Scan same UPC again → WKS_OUT_PENDING returned
6. Operator taps REPAIRED → calls `postWksOut(outcome='repaired')` → unit → `repaired`
7. Operator taps CANNOT FIX → calls `postWksOut(outcome='scrapped')` → unit → `scrapped`, scrap loss note created

**Known issue:** WKS defects "No defects on record" — fix deployed April 2026, not yet confirmed on floor.

### PKG ← updated April 2026
Two-scan flow (car + remote):
1. Car scan → remote scan → pair verified
2. `pkg_scans` inserted
3. Car and remote `units.current_status` → `pending_rtd`
4. `print_jobs` row inserted with `status = 'pending'` and `line = deviceLine` (device is now PKG-L1/L2/L3)
5. Scanner overlay shows batch label + REPRINT button (always visible)

### PKG_OUT
Two paths auto-detected:
- **Fresh** (`pending_rtd`): `-E` → RTE, `-R` → RTR. Both units → `rtd`.
- **Return RTD** (`rtd` + `rtd_direct` in `handed_over` shipment): `RTD_RETURN` scan. Does NOT count in tally.

### Print Flow — v2.2 Per-Line ← updated April 2026
1. PKG scan → Worker inserts `print_jobs` row with `status = pending`, `line = deviceLine`
2. Line-specific print server polls `WHERE status = 'pending' AND line = '$LINE'` every 2 seconds
3. **Atomic claim:** PATCH with `WHERE status = 'pending'` filter — if another server already claimed, Supabase returns empty array → server skips job silently. Prevents cross-line duplicate prints.
4. Prints → marks done/failed
5. REPRINT button → `postReprintJob` → new pending row inserted
6. Dashboard Print tab → `getPkgScanLookup` → `postReprintJob` → queued to correct line printer

### Operator Sessions ← new April 2026
Every QR card login via `scannerLogin`:
1. Closes any open session on that device (sets `logout_at`)
2. Looks up active production run for device's line
3. Derives shift from IST time (Regular <18:00, OT 18:00-21:00, Double OT >21:00)
4. Inserts new `operator_sessions` row
Manual select logins do NOT create session records (client-side only, no worker call).

### Void + Status Rollback
Voiding any scan rolls back `units.current_status` to previous non-voided scan's activity. If no previous scan → `inwarded`.

---

## 6. Dashboard

**URL:** `dashboard.legendoftoys.com` | **Repo:** `legendlot/dashboard`

### Tabs
| Tab | Key Features |
|---|---|
| Executive | KPI cards, hourly bar chart, runs split Active/Upcoming |
| Lines | Per-line cards (today only), first scan time, operator output table |
| QC | Cycle time (IQR outlier-aware), FPY by line, top defects, repeat failures |
| Runs | Plan vs actual |
| UPC Generator | Searchable dropdown, car/remote toggle, batch history, print |
| Scans | Real-time feed, activity filter, UPC search, voided toggle. Summary cards via getScanSummary RPC. Falls back to dashes on RPC failure. |
| Corrections | Tier 2 void + Tier 3 amend |
| Alerts | Scan violations, acknowledge, badge count |
| Returns | Units in rto_in status pending production action |
| Reporting | Date-range: all 3 cycle times, QC summary, product breakdown, daily, line |
| Operators | Full CRUD operator database, sessions today count, Print QR card ← new April 2026 |
| Print | Single + bulk batch label reprint by batch label or car UPC ← new April 2026 |

### setContentHeight fix ← April 2026
`setContentHeight` now only sets height on visible (non-hidden) tab panels and zeros out hidden ones. Previously all panels received the same height, pushing later tabs below the viewport.

### Operators Tab
- Add/Edit/Deactivate operators (admin + production_manager only)
- Filter by role, line, active status
- Sessions Today count from `operator_sessions`
- Print QR — generates QR from `operator.qr_code`, opens print window with card layout (name, role, line, QR, LOT logo)
- `saveOperator` GET action — creates new operator with auto-generated `LOT-OP-XXXXXXXX` QR code, or updates existing

### Print Tab
- Single mode: enter batch label or car UPC (auto-prefixes `LOT-` and zero-pads if needed)
- Bulk mode: paste multiple values one per line
- Shows match with product/line/channel before printing
- Individual Print button per row + Print All for bulk
- Queues to `print_jobs` via `postReprintJob` → routes to correct line printer automatically

---

## 7. Print Server — v2.2

**Location:** Windows laptop at each line's PKG station (one per line)
**Files:** `printserver.js` (identical on all laptops), `config.json` (only `line` differs)

### config.json — per-line (only `line` differs between laptops)
```json
{
  "line": "L1",
  "printer_name": "TSC TE244",
  "label_width_mm": 50,
  "label_height_mm": 25,
  "gap_mm": 2,
  "darkness": 8,
  "speed": 4,
  "poll_interval_ms": 2000,
  "supabase_url": "https://jkxcnjabmrkteanzoofj.supabase.co",
  "supabase_key": "sb_publishable_1Dd-r3h9Mou2Wqgn6t24Dw_lmWdBtLh"
}
```

### Poll + atomic claim (v2.2)
```
Query: status=eq.pending&line=eq.${LINE}&order=created_at.asc&limit=5
Claim: PATCH ?id=eq.${id}&status=eq.pending → { status: 'printing' }
       Returns representation — if empty array, another server claimed it → skip
```
v2.2 deployed to L1. **Needs deployment to L2 and L3 laptops.**

### Label layout (TSPL, 50×25mm @ 203 DPI)
```
BARCODE 24,3,"128",52,1,0,2,2,"LOT-XXXXXXXX-E"
TEXT 24,60,"2",0,1,1,"LOT-XXXXXXXX-E"
TEXT 24,82,"1",0,1,1,"Knox Adventure Black"
TEXT 24,96,"1",0,1,1,"ECOM  06-Apr-2026"
```
X position = 24 dots. Not yet confirmed on floor.

---

## 8. Known DB Fixes Applied

| Fix | SQL | Reason |
|---|---|---|
| `units.ean` nullable | `ALTER TABLE public.units ALTER COLUMN ean DROP NOT NULL` | Remotes have no EAN |
| `units.sku` nullable | `ALTER TABLE public.units ALTER COLUMN sku DROP NOT NULL` | Remotes have no SKU |
| `units.model` nullable | `ALTER TABLE public.units ALTER COLUMN model DROP NOT NULL` | Remotes have no model |
| `units.color` nullable | `ALTER TABLE public.units ALTER COLUMN color DROP NOT NULL` | Remotes have no color |
| Defect component mapping | `UPDATE defect_master SET component = 'car' WHERE category::text LIKE 'Car-%'` etc. | Was null, broke filtering |
| Packaging defects inactive | `UPDATE defect_master SET is_active = false WHERE category::text = 'Packaging'` | Not applicable at QC stage |
| super_admin permissions | Added `scan_void_supervisor`, `scan_amend_manager`, `loss_note_approve`, `channel_manage` | Various features |
| Supabase max rows | Settings → API → Data API → Max Rows → 5000 | Default 1000 was capping scan display |
| Return sequences | `INSERT INTO store.sequences VALUES ('rs',0),('ru',0),('ln',0)` | Return system IDs |
| get_scan_summary RPC | Created in public schema | Accurate scan counts bypassing row limits |
| get_line_view fix | DROP + recreate with line-level grouping + first_scan_at | Was returning two rows per line when run existed |
| get_qc_cycle_time RPC | Dropped + recreated with IQR outlier detection, new return fields | April 2026 |
| get_pkg_cycle_time RPC | Created in public schema | PKG cycle time measurement |
| get_rtd_cycle_time RPC | Created in public schema | RTD cycle time measurement |
| RTD_RETURN activity enum | `ALTER TYPE public.activity_type ADD VALUE IF NOT EXISTS 'RTD_RETURN'` | Return RTD distinct from RTE/RTR |
| return_shipments.handed_over_at | `ALTER TABLE store.return_shipments ADD COLUMN IF NOT EXISTS handed_over_at TIMESTAMPTZ` | Handover timestamp |
| return_shipments status enum | Added `handed_over` value | Store handover flow |
| upc_batches remote model/color | Worker nulls model/color for 5-char product_code batches | Remotes are product-level not variant-level |
| `pending_rtd` unit_status enum | `ALTER TYPE unit_status ADD VALUE IF NOT EXISTS 'pending_rtd'` | Worker used pending_rtd but enum only had `packed` |
| print_jobs table | Created in public schema with status index | Print polling architecture April 2026 |
| print_jobs.line column | `ALTER TABLE public.print_jobs ADD COLUMN IF NOT EXISTS line TEXT` | Per-line print routing April 2026 |
| PKG device per-line | `UPDATE public.devices SET device_code='PKG-L1', line='L1', label='PKG Line 1' WHERE device_code='PKG'` | PKG was SHARED — now per-line |
| PKG-L2/L3 devices | `INSERT INTO public.devices (device_code, station, line, label, is_active) VALUES ('PKG-L2','PKG','L2','PKG Line 2',true),('PKG-L3','PKG','L3','PKG Line 3',true)` | New per-line PKG devices |
| WKS device per-line | `UPDATE public.devices SET device_code='WKS-L1', line='L1', label='Workshop Line 1' WHERE device_code='WKS' AND station='WKS'` | WKS was SHARED — now per-line |
| WKS-L2/L3 devices | `INSERT INTO public.devices (device_code, station, line, label, is_active) VALUES ('WKS-L2','WKS','L2','Workshop Line 2',true),('WKS-L3','WKS','L3','Workshop Line 3',true)` | New per-line WKS devices |
| operator_sessions table | Created April 2026 — migrated from old schema | Scanner login session tracking |
| B-014 product seeding bug | Worker used `product` name for `product_master` lookup — `LIMIT 1` returned first alphabetical match (Asphalt Black). Fixed to use `product_code`. | All Shadow Tarmac Black units in B-014 range seeded as Asphalt Black |
| B-014 units data fix | `UPDATE public.units SET sku='shadow-tarmac-black', model='Tarmac', ean='5949998500299' WHERE upc BETWEEN 'LOT-00002615' AND 'LOT-00003214' AND sku='shadow-asphalt-black'` | Run twice — initial + gap period |
| 18 B-014 batch labels reprinted | INSERT into print_jobs for all B-014 pkg_scans | Labels had wrong EAN from seeding bug |

---

## 9. Build Phases & Status

| Phase | Module | Status |
|---|---|---|
| 1 | DB + Scanner App | ✅ Complete |
| 1b | PKG Print System | ✅ Complete — polling v2.2 per-line atomic (April 2026) |
| 2 | Reporting Dashboard | ✅ Live (12 tabs including Operators + Print) |
| 3 | Repair Module | ✅ Complete |
| 3b | Return System | ✅ Complete (incl. handover + RTD_RETURN path) |
| 3c | Station Status Controls + Alerts | ✅ Complete |
| 3d | Cycle Time Analytics | ✅ Complete (all 3 RPCs with IQR outlier detection) |
| 3e | Reporting Tab | ✅ Complete |
| 3f | Operator Management | ✅ Complete — Operators tab + sessions (April 2026) |
| 3g | Print Tab | ✅ Complete — self-service reprint (April 2026) |
| 4 | Reconciliation | 🔲 Not started |
| 5 | Audit Module | 🔲 Not started |
| 6 | Assembly Stations | 🔲 Not started |

### Open Issues — fix before building new features

1. **WKS defects "No defects on record"** — fix deployed April 2026. **Not confirmed working on floor.**
2. **Label X position** — shifted to 24 dots. **Not confirmed on floor.**
3. **Print server v2.2** — deployed to L1. **Needs deployment to L2 and L3 laptops.**
4. **WKS line fix** — WKS per-line devices deployed April 2026. **Not yet confirmed on floor.**

### Pending Build Items (prioritised)

**Next session:**
1. **RTO_IN intact path** — scanner side only (scan batch label → RTD direct; damaged path already wired)
2. **Repair run design session** — full design before any build

**Backlog:**
3. **Consolidated dispatch view** — fresh RTD + RTD_RETURN combined per product per day
4. **Legacy UPC manual entry** — for pre-system units returning. Build when triggered.
5. **Daily / weekly / monthly reporting views**
6. **Reconciliation module** — Phase 4
7. **Audit module** — Phase 5
8. **Assembly stations module** — Phase 6
9. **Dashboard tab RBAC** — deferred until feature set stable
10. **Biometric integration**
11. **Unicommerce reconciliation**
12. **APK rollout to all 15 devices** — decision deferred

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
| Channel at PKG | Toggle on device screen | Decided before run, sequential within day |
| Schema separation | store vs public | Legacy origin; RPCs must not include profile headers |
| RTD definition | RTE + RTR (via PKG_OUT) | Not a physical scan point |
| Operator login | QR card scan only | Operators can't type reliably |
| UPC format | LOT-XXXXXXXX (8 digit, global sequential) | Simple, no collision risk, scalable |
| Display code | KNAK00000007 (product_code + raw LOT seq, no hyphens) | Globally unique, human-readable |
| QR encodes | LOT- number (not display code) | Permanent anchor; display code is derived |
| Remote UPC | Independent from car, same pool | Remote travels separately, needs own scan history |
| Remote pairing | Created at QC_PASS only | No tentative pair state — cleaner model |
| QC_FAIL storage | Parent + child rows | Enables defect-level and unit-level queries |
| Defect filtering | By component type at scan time | Shorter list, no wrong-component codes |
| Scan correction | Tier 1/2/3 model | Prevent first, correct second; Mahesh read-only always |
| Void mechanism | voided flag on scans row | Records never deleted; audit trail always complete |
| Void rollback | Unit status rolled back on void | Prevents units getting stuck after incorrect voids |
| WKS flow | System-inferred from unit status | No manual IN/OUT modal — eliminates operator error |
| WKS worker action | `postWksScan` separate from `postScan` | Raw `WKS` is not a valid activity_type enum value |
| WKS line tracking | device.line directly (per-line devices) | WKS-L1/L2/L3 devices deployed April 2026 — no INW lookup needed |
| WKS plan_id | UUID guard before writing to scans | store schema production_runs.id is integer not UUID |
| Print approach | Polling Supabase print_jobs table | Chrome Android blocks local network fetch from HTTPS PWA |
| PKG label print | Non-blocking via print_jobs | Scan not blocked if printer offline. REPRINT always available. |
| PKG per-line devices | PKG-L1/L2/L3 | Multiple printers need routing. print_jobs.line + per-line poll prevents cross-line collisions. |
| WKS per-line devices | WKS-L1/L2/L3 | Inline workshop is line-specific. Repair run (L3 auxiliary) is separate future system. |
| Print server v2.2 | Atomic job claiming via conditional PATCH | v2.1 had race condition — both L1+L2 servers could claim same job in 2-second poll window |
| Print tab | Self-service reprint on dashboard | Reduces dependency on Afshaan for manual SQL reprints |
| Operator sessions | Created on QR card login, closed on next login same device | No explicit logout needed; shift derived from timestamps |
| Operator allocation | Scanner QR login IS the station allocation | No separate allocation UI for now; non-scanning operators deferred to Phase 6 |
| Repair run | Design session required before build | Touches production runs, store issuance, unit assignment, aging — too many unknowns |
| `packed` vs `pending_rtd` | Both in enum, `pending_rtd` is active | `packed` is legacy unused. `pending_rtd` added April 2026 |
| getReturnQueue | Queries units with current_status=rto_in | Temporary — proper return_category + action routing pending RTO_IN intact path build |

---

## 12. Open Technical Questions

1. **Scan events partitioning:** Defer to month 10 (~500K events/month). Plan DDL now.
2. **QC_PASS timeout:** Should second scan time out after N seconds? Recommend yes.
3. **Rework module schema:** Pending design session.
4. **Dashboard tab access control:** Deferred — role-gate when feature set stable.
5. **Defect master ownership:** Who can add/modify defect codes after initial seeding?
6. **Channel barcode scanning:** Future — scan platform return labels to auto-fill channel/return ID.
7. **Legacy UPC gap:** Design agreed, build deferred. Trigger = first time volume makes manual handling unworkable.
8. **Consolidated dispatch view:** Fresh RTD + RTD_RETURN combined — not yet built.
9. **Repair run schema:** Pending design session. Two contexts: inline (same day, line-specific) vs repair run (L3 auxiliary, cross-line, formal production run).
10. **Daily/weekly/monthly reporting:** Not yet designed or built.
11. **`packed` vs `pending_rtd` cleanup:** Both in enum. Remove `packed` when safe.
12. **print_jobs retention:** Table grows indefinitely. Add cleanup for done/failed rows older than 30 days.
13. **All 15 devices APK vs PWA:** With polling print, all can stay on browser PWA. Decision deferred.
14. **Operator performance view:** Per-operator daily scan count + trend. Data is available (scans + sessions); view not yet built.
15. **Shift time boundaries:** Regular 9am-7pm (PKG), 9am-6pm (Assembly/QC). OT 6pm-9pm. Double OT after 9pm IST. One-hour overlap intentional for PKG load balancing.

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
