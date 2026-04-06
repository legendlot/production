# Legend of Toys — Technical Build Document
**Version:** 1.7 | **Last Updated:** April 2026
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
| Print server | Node.js v2.0 | `printserver.js` on PKG station laptop — polls Supabase. No HTTP server, no firewall rules needed. |
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

#### `print_jobs` — PKG label print queue ← new April 2026
```
id           UUID PRIMARY KEY DEFAULT gen_random_uuid()
batch_label  TEXT NOT NULL
product      TEXT
model        TEXT
color        TEXT
channel      TEXT
packed_at    TIMESTAMPTZ
status       TEXT NOT NULL DEFAULT 'pending'   -- pending | printing | done | failed
created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
printed_at   TIMESTAMPTZ
error        TEXT
```
Index: `CREATE INDEX print_jobs_status_idx ON public.print_jobs (status, created_at);`

#### `return_shipments` (store schema) — updated April 2026
```
-- Status enum: open | partially_processed | fully_processed | handed_over | closed
handed_over_at  TIMESTAMPTZ
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

### `get_scan_summary` — returns single row
Fields: `total, voided, INW, QC_PASS, QC_FAIL, WKS_IN, WKS_OUT, PKG, RTO_IN`
Note: `RTD_RETURN` not yet in RPC output.

### Cycle Time RPCs — shared design
- IQR fence: `GREATEST(Q3 + 2×IQR, Q3 + 10)` with min IQR floor of 5 min
- Overnight cap: 480 minutes
- Returns: `avg_mins_all`, split averages, `median_mins`, `units_measured`, `fastest_mins`, `slowest_normal_mins`, `outlier_count`, `outlier_max_mins`, `outlier_threshold_mins`

---

## 4. Worker Endpoints (GET actions)

| Action | Description |
|---|---|
| `getProductionDashboard` | Executive tab |
| `getLineView` | Lines tab |
| `getQCView` | QC tab |
| `getCycleTimeSummary` | Reporting tab |
| `getPlanVsActual` | Runs + Reporting tab |
| `getProductCodes` | UPC Generator |
| `getUpcBatches` | UPC Generator batch history |
| `getUpcBatch` | UPC Generator single batch |
| `getAllScans` | Scans + Corrections tabs |
| `getScanSummary` | Scans tab summary cards via RPC ← handler added April 2026 |
| `getViolations` | Alerts tab |
| `getViolationSummary` | Alerts tab summary cards |
| `getReturnQueue` | Returns tab |

## 4b. Worker Endpoints (POST actions)

| Action | Description |
|---|---|
| `postReturnHandover` | Store — marks shipment `handed_over` |
| `postPkgOut` | PKG_OUT scan — auto-routes RTE/RTR or RTD_RETURN. Inserts print_job row. |
| `generateUpcBatch` | Nulls model/color for remote batches |
| `postWksScan` | WKS scan — infers WKS_IN or WKS_OUT_PENDING. Fetches defects. Returns effective line from INW scan. ← new April 2026 |
| `postWksOut` | WKS outcome — REPAIRED or SCRAPPED. Auto-creates scrap loss note. ← new April 2026 |
| `postReprintJob` | Inserts new pending print_job row for reprint ← new April 2026 |

### SCANNER_ACTIONS (bypass JWT, use device auth)
```
scannerLogin, postScan, postWksScan, postWksOut, postQcPass, postQcFail, voidScanSelf, postPkg, postPkgOut, postReprintJob
```

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

### WKS (Workshop) ← updated April 2026
Scanner calls `postWksScan` (NOT `postScan` with activity WKS — that caused enum violation).
1. Operator scans UPC → `postWksScan` called
2. If unit is `qc_fail` → WKS_IN recorded, unit → `in_repair`, loop_count+1, defects fetched, effective line pulled from original INW scan
3. Scan same UPC again → WKS_OUT_PENDING returned
4. Operator taps REPAIRED → calls `postWksOut(outcome='repaired')` → unit → `repaired`
5. Operator taps CANNOT FIX → calls `postWksOut(outcome='scrapped')` → unit → `scrapped`, scrap loss note created

**Known issue:** WKS_IN sometimes shows line SHARED and "No defects on record". Fixes deployed April 2026, not yet confirmed working.

### PKG ← updated April 2026
Two-scan flow (car + remote):
1. Car scan → remote scan → pair verified
2. `pkg_scans` inserted
3. Car and remote `units.current_status` → `pending_rtd`
4. `print_jobs` row inserted (`status = 'pending'`)
5. Scanner overlay shows batch label + REPRINT button (always visible)

**Known issue:** PKG scan records show line SHARED. Fix pending.

### PKG_OUT
Two paths auto-detected:
- **Fresh** (`pending_rtd`): `-E` → RTE, `-R` → RTR. Both units → `rtd`.
- **Return RTD** (`rtd` + `rtd_direct` in `handed_over` shipment): `RTD_RETURN` scan. Does NOT count in tally.

### Print Flow — v2.0 Polling ← new April 2026
1. PKG scan → Worker inserts `print_jobs` row (`status = pending`)
2. Print server polls every 2 seconds → picks up pending row → prints → marks done/failed
3. REPRINT button → `postReprintJob` → new pending row inserted
4. No phone→laptop connection. Works on any device, any network.

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
| Scans | Real-time feed, activity filter, UPC search, voided toggle. Summary cards: TOTAL/INW/QC PASS/QC FAIL/WKS IN/WKS OUT/PKG/RTO IN/VOIDED via getScanSummary RPC. Falls back to dashes (not row count) on RPC failure. ← fixed April 2026 |
| Corrections | Tier 2 void + Tier 3 amend |
| Alerts | Scan violations, acknowledge, badge count |
| Returns | Returns queue from store |
| Reporting | Date-range: all 3 cycle times, QC summary, product breakdown, daily, line |

---

## 7. Print Server — v2.0

**Location:** Windows laptop at PKG station
**Files:** `printserver.js`, `config.json`

### config.json
```json
{
  "port": 3000,
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

### Label layout (TSPL, 50×25mm @ 203 DPI) — updated April 2026
```
BARCODE 24,3,"128",52,1,0,2,2,"LOT-XXXXXXXX-E"
TEXT 24,60,"2",0,1,1,"LOT-XXXXXXXX-E"
TEXT 24,82,"1",0,1,1,"Knox Adventure Black"
TEXT 24,96,"1",0,1,1,"ECOM  06-Apr-2026"
```
X position = 24 dots (shifted from 8 in April 2026). May need further tuning on factory floor.

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
| `pending_rtd` unit_status enum | `ALTER TYPE unit_status ADD VALUE IF NOT EXISTS 'pending_rtd'` | Worker used pending_rtd but enum only had `packed` — silent update failures on all PKG scans. Fixed April 2026. |
| print_jobs table | Created in public schema with status index | Print polling architecture April 2026 |

---

## 9. Build Phases & Status

| Phase | Module | Status |
|---|---|---|
| 1 | DB + Scanner App | ✅ Complete |
| 1b | PKG Print System | ✅ Complete — polling v2.0 (April 2026) |
| 2 | Reporting Dashboard | ✅ Live (10 tabs, full Reporting tab) |
| 3 | Repair Module | ✅ Complete |
| 3b | Return System | ✅ Complete (incl. handover + RTD_RETURN path) |
| 3c | Station Status Controls + Alerts | ✅ Complete |
| 3d | Cycle Time Analytics | ✅ Complete (all 3 RPCs with IQR outlier detection) |
| 3e | Reporting Tab | ✅ Complete |
| 4 | Reconciliation | 🔲 Not started |
| 5 | Audit Module | 🔲 Not started |
| 6 | Assembly Stations | 🔲 Not started |

### Open Issues — fix before building new features

1. **WKS_IN line shows SHARED** — Should show original INW line. `postWksScan` fix deployed April 2026 (pulls from INW scan). Not confirmed working.
2. **WKS defects "No defects on record"** — `fetchDefects` fixed April 2026 (individual queries per defect code, correct queryPublic return shape). Not confirmed working.
3. **PKG scan line shows SHARED** — `postPkg` handler needs effectiveLine from INW scan, same fix as WKS_IN. Not yet done.
4. **Production run line info missing** — Runs don't show line in store view or production run view. Store index file needed. Not started.
5. **Label X position** — Shifted to 24 dots (was 8). Printing but final centering not confirmed on floor.

### Pending Build Items (prioritised)

**Next session (after open issues resolved):**
1. **Operator QR card print flow** — dashboard page to generate + print physical ID cards
2. **Operator onboarding + station assignment** — enter name, assign station, print card
3. **Production run line info** — store + dashboard fix (issue #4 above)

**Backlog:**
4. **Consolidated dispatch view** — fresh RTD + RTD_RETURN combined per product per day
5. **Repair run** — new production run type for damaged returns. Pending design session.
6. **Legacy UPC manual entry** — for pre-system units returning. Build when triggered.
7. **Daily / weekly / monthly reporting views**
8. **Reconciliation module** — Phase 4
9. **Audit module** — Phase 5
10. **Assembly stations module** — Phase 6
11. **Dashboard tab RBAC** — deferred until feature set stable
12. **Biometric integration**
13. **Unicommerce reconciliation**
14. **APK rollout to all 15 devices** — currently only PKG station. With polling print, all devices can stay on browser PWA. Decision deferred.

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
| WKS worker action | `postWksScan` separate from `postScan` | `postScan` inserts raw activity to DB — WKS needs direction inference before insert. Raw `WKS` is not a valid activity_type enum value. |
| WKS line tracking | Pulled from original INW scan | WKS device is SHARED with no line assignment |
| Print approach | Polling Supabase print_jobs table | Chrome Android blocks local network fetch from HTTPS PWA. Polling is network-agnostic, works on all devices without any per-device config. |
| PKG label print | Non-blocking via print_jobs | Scan not blocked if printer offline. REPRINT button always available. |
| Scan summary | get_scan_summary RPC not row query | Bypasses Supabase row limits — always accurate |
| Scan summary fallback | Dashes not row-count | allScans capped at 500 display rows — counting from it gives wrong numbers |
| Return categories | UDR / CXR / BRV | Clear, unambiguous, future-proof |
| RTD_RETURN activity | Distinct from RTE/RTR | Return RTD must not inflate dispatch counts |
| Loss note auto-create | WKS_OUT scrapped triggers scrap note | Every financial loss has a formal document |
| Scanner APK | Bubblewrap TWA | PWA Builder was unresponsive. Bubblewrap CLI builds directly from manifest. Package: com.legendoftoys.scanner |
| `packed` vs `pending_rtd` | Both in enum, `pending_rtd` is active | `packed` is legacy unused. `pending_rtd` added April 2026 — required for PKG→PKG_OUT status validation. |

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
9. **Repair run schema:** Pending design session.
10. **Daily/weekly/monthly reporting:** Not yet designed or built.
11. **`packed` vs `pending_rtd` cleanup:** Both in enum. Remove `packed` when safe.
12. **print_jobs retention:** Table grows indefinitely. Add cleanup for done/failed rows older than 30 days.
13. **All 15 devices APK vs PWA:** With polling print, all can stay on browser PWA. Decision deferred.

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
