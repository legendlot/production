# Legend of Toys — Technical Build Document
**Version:** 1.3 | **Last Updated:** April 2026
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
| QR generation (dashboard) | QRCode.js from cdnjs | Used in UPC Generator print flow |

---

## 2. Supabase Schema

### Schema Separation
- **`store` schema** — inventory, procurement, GRN, issues, work orders, production runs
- **`public` schema** — scan tables, units, devices, operators, views, RPCs

Store schema API calls include `Accept-Profile: store` / `Content-Profile: store` headers.
Public schema RPC calls use `sbPublic()` helper — no profile headers.

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
current_status  TEXT (enum)
loop_count      INT
is_fresh        BOOLEAN
created_at      TIMESTAMPTZ
updated_at      TIMESTAMPTZ
component_type  TEXT                  -- 'car' | 'remote'
paired_with     TEXT                  -- references units(upc)
production_run_id UUID
```

#### `scans` — All scan activity
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
rte_rtr_scan_id UUID                    -- set when PKG_OUT scan happens
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

#### `devices`
```
id              UUID PRIMARY KEY
device_code     TEXT UNIQUE
station         TEXT
line            TEXT
label           TEXT
is_active       BOOLEAN
last_seen       TIMESTAMPTZ
notes           TEXT
```

#### `operators`
```
id              UUID PRIMARY KEY
name            TEXT
qr_code         TEXT UNIQUE         -- 'OP-0001'
role            TEXT
line            TEXT
reports_to      UUID
is_active       BOOLEAN
qr_generated_at TIMESTAMPTZ
```

---

### Reporting Views (public)

| View/Function | Purpose | Status |
|---|---|---|
| `v_hourly_output` | QC pass units per hour by line | Live |
| `v_first_pass_yield` | FPY % by line, product, date | ✅ Created |
| `v_repeat_defects` | Units with multiple QC fails | ✅ Created |
| `v_dispatch_stock` | Units at RTD by product/SKU | Live |
| `v_repair_queue` | Units currently in WKS | Live |
| `v_daily_production` | Daily summary by line | Live |
| `v_operator_output` | Per-operator scan counts | Live |
| `get_executive_dashboard` | RPC — KPI summary | Live |
| `get_open_runs` | RPC — open production runs | Live |
| `get_line_view` | RPC — per-line stats | Live |
| `get_defect_heatmap` | RPC — defect counts by code/line | ✅ Created |
| `get_plan_vs_actual` | RPC — plan vs actual by date | Live |

---

## 3. Device Inventory (14 total)

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
| PKG | PKG | SHARED | Pair verify + prints batch label |
| PKG-OUT | PKG_OUT | SHARED | Scans batch label → RTE or RTR auto-routed |
| RTD | RTD | SHARED | Legacy — replaced by PKG-OUT |
| RTO | RTO_IN | SHARED | |

---

## 4. Cloudflare Worker v4

**URL:** `lotopsproxy.afshaan.workers.dev`
**Auth:** Supabase JWT on all dashboard endpoints. Scanner endpoints use device_code auth only.

### Scanner POST Endpoints (no JWT)
| Action | Description |
|---|---|
| `scannerLogin` | QR code → operator UUID |
| `postScan` | INW, WKS_IN, WKS_OUT, RTO_IN |
| `postQcPass` | QC_PASS — single or two-scan pairing |
| `postQcFail` | QC_FAIL — defect list |
| `postPkg` | PKG — pair verify + batch label generation |
| `postPkgOut` | PKG_OUT — batch label scan → RTE or RTR |
| `voidScanSelf` | Tier 1 operator self-void |

### Dashboard GET Endpoints (JWT)
| Action | Description |
|---|---|
| `getAllScans` | All scans for a date with operator + product join |
| `getPkgScans` | PKG scans by date/channel |
| `getProductCodes` | All active SKUs with product_code |
| `getOperators` | List operators |
| `getDevices` | List devices |
| `getDefectMaster` | Defect codes, filtered by component |
| `getUpcBatches` | Print batch history |
| `getUpcBatch` | Single batch + optional rows for print |
| `getScanHistory` | Full scan history for a UPC |
| `getPairingStatus` | Check car+remote pair |
| `getProductionDashboard` | Executive KPIs |
| `getPlanVsActual` | Plan vs actual by date range |
| `getLineView` | Per-line stats |
| `getQCView` | Defect heatmap + FPY + repeat defects |

### Dashboard POST Endpoints (JWT)
| Action | Description |
|---|---|
| `generateUpcBatch` | Create UPC batch |
| `receiveUpcBatch` | Mark received → UPCs flip to available |
| `voidUpc` | Mark UPC damaged/unused |
| `voidScan` | Tier 2 supervisor void |
| `amendScan` | Tier 3 manager amend |
| `createOperator` | Add operator + generate QR |
| `updateOperator` | Update operator |
| `configureDevice` | Update device station/line |

---

## 5. Scanner PWA

**URL:** `scanner.legendoftoys.com`
**Repo:** `legendlot/production`

### Station Setup
Operator selects station + line (2 taps). Device auto-resolved from DB. No manual entry.

### Supported Stations
| Station | Hint | Flow |
|---|---|---|
| Assembly (INW) | SCAN LOT UPC | Single scan |
| QC Pass | SCAN LOT UPC (CAR FIRST) | Two-scan pairing for RC cars |
| QC Fail | SCAN LOT UPC — DEFECT SELECT FOLLOWS | Single scan + defect modal |
| Workshop | SCAN LOT UPC | WKS_IN or WKS_OUT modal per scan |
| Packaging (PKG) | SCAN CAR UPC FIRST | Channel toggle + two-scan + result overlay |
| Dispatch Out (PKG_OUT) | SCAN BATCH LABEL — LOT-XXXXXXXX-E/R | Single scan, auto-routes RTE/RTR |
| RTO In | SCAN LOT UPC OR EAN | Single scan |

### PKG Flow Detail
1. Operator sets ECOM / RETAIL channel toggle (persisted to localStorage)
2. Scan car UPC → worker validates qc_pass
3. For RC cars: purple band appears "NOW SCAN REMOTE CONTROL"
4. Scan remote UPC → worker verifies confirmed pair
5. Full-screen result overlay shows batch label (`LOT-XXXXXXXX-E/R`) prominently
6. Print sent to thermal printer (pending hardware setup)
7. Operator taps "NEXT UNIT →" to clear and continue

### PKG_OUT Flow Detail
1. Operator scans batch label
2. System auto-detects channel from label suffix (-E or -R)
3. Cross-channel rejection: -E label at wrong station → hard error
4. Success: channel badge flashes (📦 ECOM or 🏪 RETAIL) + session tally updates

---

## 6. Dashboard

**URL:** `dashboard.legendoftoys.com`

### Tabs
| Tab | Key Features |
|---|---|
| Executive | KPI cards, hourly bar chart, open runs table |
| Lines | Per-line completion cards, operator output table |
| QC | FPY by line, top defects grid, repeat failures table |
| Runs | Plan vs actual table with completion % |
| UPC Generator | Product select, car/remote toggle, batch history, print |
| Scans | Real-time feed, activity filter, UPC search, voided toggle |
| Corrections | Tier 2 void + Tier 3 amend with modals |

### Known Schema Dependencies
Views and RPCs required by the dashboard — all confirmed created:
- `get_executive_dashboard` RPC
- `get_open_runs` RPC
- `v_hourly_output` view
- `get_line_view` RPC
- `v_operator_output` view
- `get_plan_vs_actual` RPC
- `get_defect_heatmap` RPC
- `v_first_pass_yield` view
- `v_repeat_defects` view
- `v_dispatch_stock` view
- `v_repair_queue` view
- `v_daily_production` view

---

## 7. Known DB Fixes Applied

| Fix | SQL | Reason |
|---|---|---|
| `units.ean` nullable | `ALTER TABLE public.units ALTER COLUMN ean DROP NOT NULL` | Remotes have no EAN |
| `units.sku` nullable | `ALTER TABLE public.units ALTER COLUMN sku DROP NOT NULL` | Remotes have no SKU |
| `units.model` nullable | `ALTER TABLE public.units ALTER COLUMN model DROP NOT NULL` | Remotes have no model |
| `units.color` nullable | `ALTER TABLE public.units ALTER COLUMN color DROP NOT NULL` | Remotes have no color |
| Defect component mapping | `UPDATE defect_master SET component = 'car' WHERE category::text LIKE 'Car-%'` etc. | Was null, broke filtering |
| Packaging defects inactive | `UPDATE defect_master SET is_active = false WHERE category::text = 'Packaging'` | Not applicable at QC stage |
| super_admin permissions | `UPDATE store.roles SET permissions = permissions \|\| '{"scan_void_supervisor":true,"scan_amend_manager":true}'` | Needed for corrections tab |

---

## 8. Build Phases & Status

| Phase | Module | Status |
|---|---|---|
| 1 | DB + Scanner App | ✅ Complete (PKG pending printer test) |
| 2 | Reporting Dashboard | ✅ Live |
| 3 | Repair Module | 🔲 Not started |
| 4 | Reconciliation | 🔲 Not started |
| 5 | Audit Module | 🔲 Not started |
| 6 | Assembly Stations | 🔲 Not started |

### Pending Build Items (prioritised)
1. **Station status controls** — each scan point validates previous unit status before accepting (e.g. PKG only accepts `qc_pass` units)
2. **Operator QR card print flow** — dashboard page to print physical ID cards
3. **Operator onboarding + station assignment** — enter name, assign station, print card
4. **Thermal printer + print server** — Node.js on dedicated laptop, ESC/POS, 60mm labels
5. **RTO_IN two-path flow** — intact → direct RTD, damaged → full production flow
6. **Return system design + build** — market returns, inspection, rework routing
7. **Reporting views** — any missing RPCs surfaced during dashboard use
8. **Reconciliation module** — Phase 4
9. **Audit module** — Mahesh's view — Phase 5

---

## 9. Technical Decisions Log

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
| Corrections onclick | Index into window._corrRows | Avoids HTML quoting issues with special chars in UPCs |
| Print approach | New browser window + embedded data URLs | canvas @media print unreliable; new window always works |

---

## 10. Open Technical Questions

1. **WKS device toggle:** Modal (current) vs system-inferred from unit state.
2. **Scan events partitioning:** Defer to month 10 (~500K events/month). Plan DDL now.
3. **QC_PASS timeout:** Should second scan time out after N seconds? Recommend yes.
4. **Rework module schema:** Minimum viable design pending business question resolution.
5. **Dashboard tab access control:** Deferred — role-gate when feature set is stable.
6. **Defect master ownership:** Who can add/modify defect codes after initial seeding?
7. **Thermal printer model:** 60mm ESC/POS confirmed. Specific model TBD after box label dimensions confirmed.

---

## 11. Environment Reference

> ⚠️ Do not commit to git.

- **Supabase URL:** `https://jkxcnjabmrkteanzoofj.supabase.co`
- **Supabase publishable key:** `sb_publishable_1Dd-r3h9Mou2Wqgn6t24Dw_lmWdBtLh`
- **Worker URL:** `https://lotopsproxy.afshaan.workers.dev`
- **Scanner:** `https://scanner.legendoftoys.com`
- **Dashboard:** `https://dashboard.legendoftoys.com`
- **GitHub repo:** `legendlot/production`

---

*Update at end of every build session. "Pending Build Items" and "Build Phases" are the source of truth for what to work on next.*
