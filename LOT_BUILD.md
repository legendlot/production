# Legend of Toys — Technical Build Document
**Version:** 1.5 | **Last Updated:** April 2026
**Purpose:** Technical reference for the LOT production operations system. Feed alongside LOT_SYSTEM.md when continuing development in a new chat session.

---

## 1. Stack

| Layer | Technology | Details |
|---|---|---|
| Database | Supabase (Postgres) | `jkxcnjabmrkteanzoofj.supabase.co` — Micro compute |
| API layer | Cloudflare Workers | `lotopsproxy.afshaan.workers.dev` |
| Scanner PWA | Vanilla JS + ZXing | `scanner.legendoftoys.com` — GitHub Pages, repo `legendlot/production` |
| Scanner APK | TWA (PWA Builder) | Wraps PWA — camera permissions + assetlinks.json resolved |
| Dashboard | Vanilla JS | `dashboard.legendoftoys.com` — GitHub Pages, repo `legendlot/dashboard` |
| Store system | Vanilla JS | Separate app — store schema |
| Camera/scanning | Native `getUserMedia` + ZXing | Replaced html5-qrcode (MIUI incompatibility) |
| Print server | Node.js | `printserver.js` on PKG station laptop, port 3000 |
| Thermal printer | TSC TE244 | TSPL, USB, 50×25mm labels, `@thiagoelg/node-printer` |
| Fonts | Tomorrow + JetBrains Mono | Google Fonts |
| QR generation (dashboard) | QRCode.js from cdnjs | Used in UPC Generator print flow |

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

#### `scans` — All scan activity (immutable ledger)
```
id              UUID PRIMARY KEY
timestamp       TIMESTAMPTZ
upc             TEXT
ean             TEXT
activity        TEXT    -- INW | QC_PASS | QC_FAIL | WKS_IN | WKS_OUT | PKG | RTE | RTR | RTO_IN
line            TEXT
station         TEXT
device_id       UUID
operator_id     UUID
shift           TEXT
plan_id         TEXT
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

#### `scan_violations` — Wrong-sequence scan log
```
id                   UUID PRIMARY KEY DEFAULT gen_random_uuid()
timestamp            TIMESTAMPTZ DEFAULT now()
upc                  TEXT
device_code          TEXT
station              TEXT
operator_id          UUID
line                 TEXT
unit_status          TEXT          -- unit's status at time of violation
expected_status      TEXT          -- what status was required
error_message        TEXT NOT NULL -- full descriptive message shown to operator
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
product_seq     INT                 -- per-product sequential number
```

#### `upc_batches` — Print batch records
```
batch_id        TEXT PRIMARY KEY    -- 'B-001'
product         TEXT
product_code    TEXT
ean             TEXT
model           TEXT
color           TEXT
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
| `get_scan_summary` | Accurate scan counts bypassing row limits | `date` |
| `get_qc_cycle_time` | Avg time INW → QC_PASS/QC_FAIL | `p_date_from`, `p_date_to`, `p_line` |
| `next_seq` | Auto-increment named sequences | `seq_name` |

### `get_line_view` — notes
- Returns one row per line (fixed April 2026 — was returning one row per line×run)
- Groups scan counts by line only, joins most recent run for metadata
- Includes `first_scan_at` (timestamptz, UTC) — earliest scan on that line for the date
- Completion % based on `total_dispatched / target_qty`

### `get_qc_cycle_time` — notes
- Measures elapsed time from INW scan → first QC_PASS or QC_FAIL scan per unit
- Excludes units where gap > 480 minutes (prevents overnight distortion)
- Returns: `avg_mins_all`, `avg_mins_pass`, `avg_mins_fail`, `units_measured`, `fastest_mins`, `slowest_mins`

---

## 4. Worker Endpoints (GET actions)

| Action | Description |
|---|---|
| `getProductionDashboard` | Executive tab — summary + open runs + hourly chart |
| `getLineView` | Lines tab — per-line cards + operator table |
| `getQCView` | QC tab — FPY + defects + repeat failures + cycle time |
| `getPlanVsActual` | Runs tab + Reporting tab |
| `getProductCodes` | UPC Generator — product dropdown |
| `getUpcBatches` | UPC Generator — batch history |
| `getUpcBatch` | UPC Generator — single batch with rows for print |
| `getAllScans` | Scans + Corrections tabs |
| `getScanSummary` | Scans tab summary cards (RPC, bypasses row limit) |
| `getViolations` | Alerts tab |
| `getViolationSummary` | Alerts tab summary cards |
| `getReturnQueue` | Returns tab |

---

## 5. Scanner Flows

### INW
Single scan. System identifies car vs remote from `upc_pool.product_code`. Unit created in `units` table.

### QC_PASS
- `has_remote = true`: Two-scan sequence — car then remote. Pairing created in `unit_pairs`.
- `has_remote = false`: Single scan.
- Both: unit status → `qc_pass`.

### QC_FAIL
Single scan. Defect selection modal shown, filtered by component type. Parent + child rows in `qc_fail_events` + `qc_fail_defects`.

### WKS (Workshop)
System-inferred direction:
1. Operator scans UPC at WKS device
2. If unit is `qc_fail` → WKS_IN recorded, defects from last qc_fail_event shown on full-screen card, loop_count+1
3. Scan same UPC again → WKS_OUT_PENDING returned
4. Operator taps REPAIRED or CANNOT FIX — calls `postWksOut` with outcome
5. REPAIRED → unit `repaired`, eligible for QC; SCRAPPED → unit `scrapped`, scrap loss note auto-created

### PKG Print Flow
1. Successful PKG scan → `showPkgResult(data)` called
2. `firePrint(data)` fires async to `${cfg.printServer}/print`
3. Print server receives JSON, builds TSPL, sends to TSC TE244
4. Scanner shows "🖨 Label sent to printer" or "⚠ Printer offline" toast
5. Scan is NOT blocked if print fails

### Void + Status Rollback
When any scan is voided (Tier 1 or Tier 2), the worker:
1. Marks scan `voided = true`
2. Finds previous non-voided scan for same UPC
3. Updates `units.current_status` to match previous scan's activity
4. If no previous scan exists → rolls back to `inwarded`

---

## 6. Dashboard

**URL:** `dashboard.legendoftoys.com`
**Repo:** `legendlot/dashboard`

### Tabs
| Tab | Key Features |
|---|---|
| Executive | KPI cards, hourly bar chart (with count labels), production runs split Active/Upcoming |
| Lines | Per-line cards (locked to today), first scan time, operator output table |
| QC | QC cycle time cards, FPY by line, top defects grid, repeat failures table |
| Runs | Plan vs actual table with completion % |
| UPC Generator | Product select, car/remote toggle, batch history, print |
| Scans | Real-time feed, activity filter, UPC search, voided toggle. Summary cards via getScanSummary RPC. |
| Corrections | Tier 2 void + Tier 3 amend with modals |
| Alerts | Scan violations feed, acknowledge flow, summary cards, badge count on tab |
| Returns | Returns queue from store, ACTION button per unit, summary cards |
| Reporting | Date-range reporting — summary cards, daily output table, line breakdown table |

### Tab behaviour
- **Date bar hidden on:** UPC Generator, Scans, Corrections, Returns, Lines, Reporting
- **Lines tab** always loads today — locked, no date range
- **Reporting tab** uses date range from date bar

### Scroll Fix
All tab content panes have `#tab-X .table-wrap { flex:1; min-height:0; overflow-y:auto }` override. `setContentHeight()` called on tab switch and login.

### Known Schema Dependencies
All confirmed created:
- `get_executive_dashboard` RPC
- `get_open_runs` RPC
- `v_hourly_output` view
- `get_line_view` RPC ← updated April 2026 (line-level grouping + first_scan_at)
- `v_operator_output` view
- `get_plan_vs_actual` RPC
- `get_defect_heatmap` RPC
- `v_first_pass_yield` view
- `v_repeat_defects` view
- `v_dispatch_stock` view
- `v_repair_queue` view
- `v_daily_production` view
- `get_scan_summary` RPC
- `get_qc_cycle_time` RPC ← new April 2026

---

## 7. Print Server

**Location:** Windows laptop at PKG station
**Files:** `printserver.js`, `package.json`, `config.json`, `setup.bat`
**Port:** 3000
**Endpoints:** `GET /ping`, `GET /printers`, `POST /print`

### config.json
```json
{
  "port": 3000,
  "printer_name": "TSC TE244",
  "label_width_mm": 50,
  "label_height_mm": 25,
  "gap_mm": 2,
  "darkness": 8,
  "speed": 4
}
```

### Label layout (TSPL, 50×25mm @ 203 DPI)
```
BARCODE 8,3,"128",52,1,0,2,2,"LOT-XXXXXXXX-E"
TEXT 8,60,"2",0,1,1,"LOT-XXXXXXXX-E"
TEXT 8,82,"1",0,1,1,"Knox Adventure Black"
TEXT 8,96,"1",0,1,1,"ECOM  04-Apr-2026"
```

### Setup (one-time)
1. Install TSC TE244 driver from tscprinters.com
2. Connect USB
3. Run `setup.bat` — installs npm deps, creates startup shortcut
4. Run `start.bat` to start server
5. Visit `http://localhost:3000/printers` — confirm "TSC TE244" in list
6. Get laptop IP via `ipconfig` → enter in scanner setup screen

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
| get_qc_cycle_time RPC | Created in public schema | QC cycle time measurement INW → QC outcome |

---

## 9. Build Phases & Status

| Phase | Module | Status |
|---|---|---|
| 1 | DB + Scanner App | ✅ Complete (PKG pending printer physical setup) |
| 2 | Reporting Dashboard | ✅ Live (10 tabs) |
| 3 | Repair Module | ✅ Complete |
| 3b | Return System | ✅ Complete |
| 3c | Station Status Controls + Alerts | ✅ Complete |
| 3d | Cycle Time Analytics | 🔶 QC done — PKG + RTD pending |
| 3e | Reporting Tab | 🔶 Scaffold built — full build pending |
| 4 | Reconciliation | 🔲 Not started |
| 5 | Audit Module | 🔲 Not started |
| 6 | Assembly Stations | 🔲 Not started |

### Pending Build Items (prioritised)

**Next session:**
1. **PKG cycle time RPC + dashboard** — measure QC_PASS → PKG per unit. New RPC `get_pkg_cycle_time`, wire into QC tab or new Reporting section.
2. **RTD cycle time RPC + dashboard** — measure PKG → RTE/RTR per unit. New RPC `get_rtd_cycle_time`, same pattern.
3. **Full Reporting tab** — replace scaffold with proper views:
   - Summary cards (period totals)
   - Daily output table (already built)
   - Line breakdown table (already built)
   - Product breakdown — units by product/SKU for period
   - QC summary — FPY + top defects for period
   - Cycle time summary — all three cycle times side by side
   - Defect trend — defect counts by day

**Backlog:**
4. **Operator QR card print flow** — dashboard page to print physical ID cards
5. **Operator onboarding + station assignment** — enter name, assign station, print card
6. **Thermal printer physical setup** — install driver, connect USB, run print server
7. **RTO_IN two-path scanner flow** — intact UDR (scan batch label → RTD) vs damaged (→ WKS)
8. **UPC generator fix** — variant showing in remote entries
9. **Print sheet size + missing variant** — fix print layout
10. **Beep fix** — scanner audio not triggering consistently
11. **Reconciliation module** — Phase 4
12. **Audit module** — Mahesh's view — Phase 5

---

## 10. Cycle Time Design

Three cycle times tracked in the system:

| Segment | From | To | RPC | Status |
|---|---|---|---|---|
| QC Cycle Time | INW scan | QC_PASS or QC_FAIL scan | `get_qc_cycle_time` | ✅ Built |
| PKG Cycle Time | QC_PASS scan | PKG scan | `get_pkg_cycle_time` | 🔲 Pending |
| RTD Cycle Time | PKG scan | RTE or RTR scan | `get_rtd_cycle_time` | 🔲 Pending |

**Pattern for all cycle time RPCs:**
- Join scans on UPC, pair earlier scan with later scan
- Exclude gaps > 480 minutes (one shift) to avoid overnight distortion
- Return: `avg_mins_all`, `avg_mins_pass` (or relevant split), `units_measured`, `fastest_mins`, `slowest_mins`
- Display in dashboard: "14.2 min" for < 60 min, "1h 23m" for ≥ 60 min

---

## 11. Technical Decisions Log

| Decision | Choice | Reason |
|---|---|---|
| Camera library | Native getUserMedia + ZXing | html5-qrcode fails silently on MIUI |
| Scan at PKG_OUT | Batch label not UPC | UPC inaccessible inside sealed box |
| Batch label format | LOT-XXXXXXXX-E/R | Car UPC + channel — no new identifier pool needed |
| Channel at PKG | Toggle on device screen | Decided before run, sequential within day |
| Channel validation | Encoded in label suffix | Prevents wrong-channel scans without mode selection |
| Schema separation | store vs public | Legacy origin; RPCs must not include profile headers |
| RTD definition | RTE + RTR (via PKG_OUT) | Not a physical scan point |
| Operator login | QR card scan only | Operators can't type reliably |
| UPC format | LOT-XXXXXXXX (8 digit, global sequential) | Simple, no collision risk, scalable |
| Display code | KNAK-7 (product_code + per-product seq) | Human readable, operator can match sticker to error |
| QR encodes | LOT- number (not display code) | Permanent anchor; display code is derived |
| Remote UPC | Independent from car, same pool | Remote travels separately, needs own scan history |
| Remote pairing | Created at QC_PASS only | No tentative pair state — cleaner model |
| QC_FAIL storage | Parent + child rows | Enables defect-level and unit-level queries |
| Defect filtering | By component type at scan time | Shorter list, no wrong-component codes |
| Scan correction | Tier 1/2/3 model | Prevent first, correct second; Mahesh read-only always |
| Void mechanism | voided flag on scans row | Records never deleted; audit trail always complete |
| Void rollback | Unit status rolled back on void | Prevents units getting stuck after incorrect voids |
| WKS flow | System-inferred from unit status | No manual IN/OUT modal — eliminates operator error |
| WKS line tracking | Pulled from original INW scan | WKS device is SHARED with no line assignment |
| Print approach | New browser window + embedded data URLs | canvas @media print unreliable; new window always works |
| PKG label print | Fire-and-forget to local print server | Scan not blocked if printer offline |
| Scan summary | get_scan_summary RPC not row query | Bypasses Supabase row limits — always accurate |
| Return categories | UDR / CXR / BRV | Clear, unambiguous, future-proof vs RTO/RTV |
| Return processing | Unit-by-unit within shipments | Matches physical workflow |
| Loss note auto-create | WKS_OUT scrapped triggers scrap note | Every financial loss has a formal document |
| Station status controls | logViolation on every rejection | Permanent audit trail + real-time alerts |
| Lines tab date lock | Always today, no range | Line cards are operational live view — historical data belongs in Reporting tab |
| Cycle time cap | 480 minutes per segment | Prevents overnight gaps distorting averages |

---

## 12. Open Technical Questions

1. **Scan events partitioning:** Defer to month 10 (~500K events/month). Plan DDL now.
2. **QC_PASS timeout:** Should second scan time out after N seconds? Recommend yes.
3. **Rework module schema:** Minimum viable design pending business question resolution.
4. **Dashboard tab access control:** Deferred — role-gate when feature set is stable.
5. **Defect master ownership:** Who can add/modify defect codes after initial seeding?
6. **Channel barcode scanning:** Future — scan platform return labels to auto-fill channel/return ID.

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

---

*Update at end of every build session. "Pending Build Items" and "Build Phases" are the source of truth for what to work on next.*
