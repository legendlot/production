# Legend of Toys — Technical Build Document
**Version:** 2.7 | **Last Updated:** April 2026
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
| Store system | Vanilla JS | Separate app — store schema. Repo: `legendlot/Stores`. Hosted at `store.legendoftoys.com` via GitHub Pages + Cloudflare custom domain. |
| Camera/scanning | Native `getUserMedia` + ZXing | Replaced html5-qrcode (MIUI incompatibility) |
| Print server | Node.js v2.2 | `printserver.js` on each line's PKG station laptop — polls Supabase filtered by line. Atomic job claiming prevents cross-line duplicate prints. No HTTP server, no firewall rules needed. |
| Thermal printer | TSC TE244 | TSPL, USB, 50×25mm labels, `@thiagoelg/node-printer` |
| Fonts | Tomorrow + JetBrains Mono | Google Fonts |
| QR generation (dashboard) | QRCode.js from cdnjs | Used in UPC Generator print flow and Operators tab QR card print |

---

## 2. Supabase Schema

### Schema Separation
- **`store` schema** — inventory, procurement, GRN, issues, work orders, production runs, returns, receiving
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

**INW status check:** `status` must be `available`. `applied` means already used on a prior unit — sticker was duplicated/reused on the floor.

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

#### `dispatch_channels` — Channel master
```
id                UUID PRIMARY KEY DEFAULT gen_random_uuid()
name              TEXT NOT NULL
type              TEXT NOT NULL CHECK (type IN ('ecom','retail','other'))
fulfillment_model TEXT NOT NULL CHECK (fulfillment_model IN ('unit','bulk'))
is_sale           BOOLEAN NOT NULL DEFAULT true
is_active         BOOLEAN NOT NULL DEFAULT true
created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
```
Seeded with 17 channels (April 2026). Managed via dashboard Dispatch → Channel Master.

#### `dispatch_shipments` — Shipment grouping
```
id                    UUID PRIMARY KEY DEFAULT gen_random_uuid()
shipment_no           TEXT UNIQUE NOT NULL  -- SHP-0001 via shp_seq sequence
channel_id            UUID REFERENCES dispatch_channels
destination_warehouse TEXT                  -- nullable, bulk channels
scheduled_date        DATE
status                TEXT DEFAULT 'draft'  -- draft | ready | shipped
shipped_at            TIMESTAMPTZ
notes                 TEXT
created_by            UUID
created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
```

#### `dispatch_allocations` — Unit-level channel allocation
```
id           UUID PRIMARY KEY DEFAULT gen_random_uuid()
car_upc      TEXT NOT NULL UNIQUE       -- one allocation per unit
batch_label  TEXT NOT NULL
channel_id   UUID REFERENCES dispatch_channels
shipment_id  UUID REFERENCES dispatch_shipments  -- nullable
allocated_by UUID
allocated_at TIMESTAMPTZ NOT NULL DEFAULT now()
shipped_at   TIMESTAMPTZ                -- stamped on DOUT scan or markShipmentShipped
override     BOOLEAN NOT NULL DEFAULT false
created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
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

#### `operators` — confirmed columns
```
id              UUID PRIMARY KEY
name            TEXT
qr_code         TEXT
role            TEXT (enum)
reports_to      UUID
is_active       BOOLEAN
created_at      TIMESTAMPTZ
line            TEXT
qr_generated_at TIMESTAMPTZ
```

#### `operator_sessions` — Scanner login sessions
```
id                UUID PRIMARY KEY DEFAULT gen_random_uuid()
operator_id       UUID NOT NULL REFERENCES public.operators(id)
device_id         UUID NOT NULL REFERENCES public.devices(id)
station           TEXT NOT NULL
line              TEXT NOT NULL
production_run_id UUID
shift             TEXT
login_at          TIMESTAMPTZ NOT NULL DEFAULT now()
logout_at         TIMESTAMPTZ
created_at        TIMESTAMPTZ DEFAULT now()
```

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

#### `store.po_lines` — key columns
```
po_number       TEXT
line_no         INT
product         TEXT
variant         TEXT
color           TEXT                -- added April 2026 for BY UNITS mode, nullable
item_type       TEXT
description     TEXT
part_code       TEXT
qty_ordered     NUMERIC
qty_received    NUMERIC
unit            TEXT
unit_price      NUMERIC
receive_format  TEXT                -- 'FBU' | 'CKD' | 'SKD' | null
```

#### `store.shipments` — Receiving shipments
```
shipment_id           TEXT PRIMARY KEY    -- SHP-001, SHP-002...
supplier              TEXT
po_reference          TEXT                -- nullable
origin                TEXT DEFAULT 'China'
arrival_date          DATE
total_boxes_expected  INTEGER DEFAULT 0
total_boxes_received  INTEGER DEFAULT 0
total_weight_expected NUMERIC
total_weight_actual   NUMERIC
receive_format        TEXT DEFAULT 'parts'  -- 'parts' | 'fbu' ← added April 2026
status                TEXT DEFAULT 'Arriving'
created_by            TEXT
notes                 TEXT
created_at            TIMESTAMPTZ
updated_at            TIMESTAMPTZ
```

#### `store.shipping_marks` — Per-box marks within a shipment
```
mark_id              TEXT PRIMARY KEY    -- MRK-001...
shipment_id          TEXT
mark_code            TEXT
box_count_expected   INTEGER DEFAULT 1
box_count_received   INTEGER DEFAULT 0
weight_expected      NUMERIC
weight_actual        NUMERIC
status               TEXT DEFAULT 'Pending'  -- Received | Pending | Damaged | Missing
notes                TEXT
created_at           TIMESTAMPTZ
```

#### `store.receiving_lines` — One row per expected SKU per shipment
```
id              BIGSERIAL PRIMARY KEY
line_id         TEXT                -- RCV-001...
shipment_id     TEXT
mark_id         TEXT                -- nullable (line level, not entry level)
part_code       TEXT
part_name       TEXT
product         TEXT
variant         TEXT                -- added April 2026
color           TEXT                -- added April 2026
line_type       TEXT DEFAULT 'parts'  -- 'parts' | 'fbu' | 'unexpected' ← added April 2026
qty_expected    INTEGER DEFAULT 0
qty_counted     INTEGER DEFAULT 0   -- maintained as sum of receiving_entries.qty
bags_of         INTEGER DEFAULT 25
bag_count       INTEGER
grn_no          TEXT
counted_by      TEXT
session_date    DATE
status          TEXT DEFAULT 'In Progress'  -- In Progress | Counted | GRN Raised
notes           TEXT
created_at      TIMESTAMPTZ
updated_at      TIMESTAMPTZ
```

#### `store.receiving_entries` — Per-box per-SKU counts ← new April 2026
```
id          BIGSERIAL PRIMARY KEY
entry_id    TEXT UNIQUE NOT NULL    -- ENT-001...
line_id     TEXT NOT NULL           -- references receiving_lines.line_id
shipment_id TEXT NOT NULL
mark_id     TEXT                    -- which box this entry is from
qty         INTEGER NOT NULL DEFAULT 0
condition   TEXT NOT NULL DEFAULT 'OK'  -- OK | Damaged
notes       TEXT
logged_by   TEXT
created_at  TIMESTAMPTZ DEFAULT now()
```

#### `store.shipment_progress` — View
Columns: `shipment_id, supplier, arrival_date, status, receive_format, total_boxes_expected, total_boxes_received, marks_total, marks_received, parts_total, parts_grn_raised, parts_counted, parts_in_progress`
Note: Recreated April 2026 (DROP + CREATE) to add `receive_format`. Grants re-applied after recreation.

#### `store.sequences` — Named sequence counters
```
name        TEXT PRIMARY KEY
current_val INTEGER
```
Seeded sequences include: `grn, wo, iss, ret, rs, ru, shp, mrk, rcv, bag, fl, po, run, ent`
**`ent` sequence** added April 2026 for `receiving_entries.entry_id`.

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
| `get_dispatch_by_pkg_line` | RTE/RTR counts attributed via pkg_scans.line | `p_date` |
| `get_hourly_dispatch_by_line` | Dispatched units per hour per line via pkg_scans join | `p_date` |
| `get_defect_breakdown` | Per-product/component/severity/defect counts | `p_date_from`, `p_date_to`, `p_line` |
| `get_takt_time` | Inter-scan gap IQR analysis per station per line | `p_date_from`, `p_date_to`, `p_line` |
| `get_first_unit_timeline` | First scan per station per line per day | `p_date_from`, `p_date_to`, `p_line` |
| `get_takt_by_run` | Takt grouped by date+line+product joined to production_runs | `p_date_from`, `p_date_to`, `p_line` |

---

## 4. Worker Endpoints (GET actions)

| Action | Description |
|---|---|
| `getProductionDashboard` | Executive tab |
| `getLineView` | Lines tab |
| `getQCView` | QC tab |
| `getCycleTimeSummary` | Reporting tab |
| `getPlanVsActual` | Runs + Reporting |
| `getProductCodes` | UPC Generator |
| `getUpcBatches` | UPC Generator batch history |
| `getUpcBatch` | UPC Generator single batch |
| `getAllScans` | Scans + Corrections tabs |
| `getScanSummary` | Scans tab summary cards via RPC |
| `getScansByUpc` | Scans tab UPC search |
| `getViolations` | Alerts tab |
| `getViolationSummary` | Alerts tab summary cards |
| `getReturnQueue` | Returns tab |
| `getOperators` | Operators tab |
| `getOperatorSessions` | Operators tab |
| `saveOperator` | Operators tab — create or update |
| `getPkgScans` | Scans tab — pkg_scans by date range |
| `getPkgScanLookup` | Print tab |
| `getShipments` | Receiving — active shipments via `shipment_progress` view |
| `getShipment` | Receiving — single shipment + marks + lines |
| `getReceivingEntries` | Receiving — entries for a line_id |
| `getUpcomingShipments` | Receiving — POs pending inward without a shipment yet |
| `getProductReceiveFormats` | Store — product → receive_format map |
| `getPendingInward` | Store — POs awaiting inward (includes outstanding_lines JSON) |

## 4b. Worker Endpoints (POST actions)

| Action | Description |
|---|---|
| `postPkgOut` | PKG_OUT scan |
| `generateUpcBatch` | UPC batch generation |
| `postWksScan` | WKS scan |
| `postWksOut` | WKS outcome |
| `postReprintJob` | Reprint label |
| `postPkg` | PKG scan |
| `postReturnShipment` | Store — creates return shipment |
| `postReturnUnit` | Store — logs return unit |
| `postReturnInspection` | Store — records inspection |
| `postReturnHandover` | Store — marks shipment handed_over |
| `createUser` | Store admin |
| `resetPassword` | Store admin |
| `postShipment` | Receiving — creates shipment + auto-populates lines from PO |
| `postShippingMark` | Receiving — adds single mark |
| `postMarkRange` | Receiving — bulk creates marks from range spec ← new April 2026 |
| `postReceivingLine` | Receiving — adds a line (parts or FBU) |
| `postReceivingEntry` | Receiving — adds a single entry to a line |
| `postBoxIntake` | Receiving — bulk entries from box intake form ← new April 2026 |
| `updateReceivingLine` | Receiving — updates line status/qty |
| `generateBags` | Receiving — generates bag records for parts line |
| `raiseGRNFromReceiving` | Receiving — raises GRN, branches FBU vs parts |
| `updateShipmentStatus` | Receiving — updates shipment status |

### SCANNER_ACTIONS (bypass JWT, use device auth)
```
scannerLogin, postScan, postWksScan, postWksOut, postQcPass, postQcFail, voidScanSelf, postPkg, postPkgOut, postReprintJob
```

---

## 5. Scanner Flows

### INW
Single scan. System identifies car vs remote from `upc_pool.product_code`. Unit created in `units` table. Worker uses `product_code` (not `product` name) to look up `product_master`.

**UPC must be `available`.** Status `applied` = sticker already used on a prior unit.

### QC_PASS
- `has_remote = true`: Two-scan sequence — car then remote. Pairing created in `unit_pairs`.
- `has_remote = false`: Single scan.

### QC_FAIL
Single scan. Defect selection modal shown, filtered by component type.

### WKS (Workshop)
Scanner calls `postWksScan`. Direction (IN/OUT) inferred from unit status.

### PKG
Two-scan flow (car + remote). Status → `pending_rtd`. `print_jobs` row inserted with line.

### PKG_OUT
- **Fresh** (`pending_rtd`): `-E` → RTE, `-R` → RTR. Both units → `rtd`.
- **Return RTD**: `RTD_RETURN` scan.
- **Line attribution:** `effectiveLine = pkgScan.line || d.line || device.line`

### Print Flow — v2.2 Per-Line
Poll → atomic claim → print → done/failed. v2.2 on L1 and L2. **L3 not yet set up.**

---

## 6. Dashboard
**URL:** `dashboard.legendoftoys.com` | **Repo:** `legendlot/dashboard`

### Tabs
| Tab | Key Features |
|---|---|
| Executive | KPI cards, hourly battery-cell dispatch chart, PVA cards, runs table |
| Lines | Per-line cards (today only). SHARED filtered. |
| QC | Cycle time, FPY, top defects, repeat failures |
| Runs | Plan vs actual |
| UPC Generator | Searchable dropdown, car/remote toggle, batch history, print |
| Scans | Real-time feed, activity filter, UPC search, voided toggle |
| Corrections | Tier 2 void + Tier 3 amend |
| Alerts | Scan violations, acknowledge, badge count |
| Returns | Units in rto_in status |
| Reporting | Section-first: Production/Cycle Time/Defects/Throughput/Downloads |
| Operators | Full CRUD, sessions today, QR card print |
| Print | Single + bulk batch label reprint |

---

## 7. Store System
**URL:** `store.legendoftoys.com` | **Repo:** `legendlot/Stores`

### Key modules
- **Overview** — dashboard KPIs
- **Inventory** — stock ledger, FBU stock toggle, reorder flags, bags
- **Procurement** — PO creation (FROM BOM / MANUAL / BY UNITS modes), vendor/forwarder management
- **Receiving** — inbound shipment management (see Section 7a)
- **Production** — production runs, work orders, issue queue
- **Store** — GRN entry, issue management
- **Returns** — return shipments, inspection, disposition
- **Library** — BOM management, material master

### 7a. Receiving System ← major redesign April 2026

#### Flow
```
Create shipment (linked to PO) → auto-populated SKU lines from PO
→ Add shipping marks (RANGE or SINGLE)
→ Open box per mark → BOX INTAKE form (PO-linked SKU grid: OK qty + Damaged qty per SKU)
→ Unexpected items via escape hatch
→ Reconciliation panel (auto-computed: Matched/Short/Over/Has Damage/Pending per SKU)
→ Box Contents panel (per-mark SKU breakdown)
→ Raise GRN (branches FBU → fbu_grn_register + fbu_stock; Parts → grn_register + stock_ledger)
```

#### Key design principles
- **Box-first**: primary navigation is by mark/box, not by SKU
- **Short is derived**: expected − total found, never manually entered
- **Split disposition**: OK qty + Damaged qty per SKU per box (two entries created)
- **PO pre-population**: shipment creation auto-inserts receiving_lines from PO outstanding lines
- **Upcoming POs**: receiving list shows POs without shipments as "upcoming" with + CREATE SHIPMENT

#### Mark range entry
- Prefix + From + To + Skip (exceptions) → bulk generates marks
- e.g. prefix=`da-Kai-`, from=1, to=15, skip=8 → 14 marks

#### Reconciliation states (auto-derived)
- ✅ Matched — total counted = expected
- 🟠 Has Damage — counted = expected but some damaged
- 🔴 Short — counted < expected
- 🟡 Over — counted > expected
- ⚪ Pending — nothing counted yet

#### Parts vs FBU receiving
Identical flow. Parts lines have part_code + part_name. FBU lines have product + variant + color. `line_type` column distinguishes them. GRN path branches on `line_type`.

### 7b. PO order type auto-detection
`detectAndSetOrderType()` fires when lines are added. Maps item_type → order type:
- RC Car / Construction Toy → Product (locks immediately for BY UNITS)
- Part / Tie Screw → Component
- Ecom/Retail Packaging / Tray / Shrink Wrap → Packaging
- Comic / Manual / Licence Card / Stickers → Para
- Other → Consumable
- Tools/Machines → Tools/Machines

Yellow border + "auto-detected" label shown when auto-set. User can still override manually.

### 7c. BY UNITS mode — per-product format override
`poUnitsFormatOverride` map: product → 'FBU'|'CKD'. CKD|FBU toggle shown per product in grid. Default from `receiveFormatCache`. Override shown with ★ and "(overridden)" note. Queue shows Format column. Reset on PO clear.

---

## 8. Schema Changes Log

| Change | SQL | Purpose |
|---|---|---|
| `store.shipments.receive_format` | `ALTER TABLE store.shipments ADD COLUMN IF NOT EXISTS receive_format TEXT DEFAULT 'parts'` | FBU vs parts receiving |
| `store.receiving_lines.variant/color/line_type` | `ALTER TABLE store.receiving_lines ADD COLUMN IF NOT EXISTS variant TEXT; ... color TEXT; ... line_type TEXT DEFAULT 'parts'` | FBU line support |
| `store.receiving_entries` | `CREATE TABLE store.receiving_entries (...)` + GRANT | Per-box per-SKU entry table |
| `store.sequences 'ent'` | `INSERT INTO store.sequences (name, current_val) VALUES ('ent', 0)` | entry_id sequence |
| `store.shipment_progress` view | DROP + CREATE to add `receive_format` column + re-GRANT | FBU badge in shipments list |
| **store.po_lines.color** | `ALTER TABLE store.po_lines ADD COLUMN IF NOT EXISTS color TEXT` | BY UNITS color ordering |
| **store.po_lines.receive_format** | `ALTER TABLE store.po_lines ADD COLUMN IF NOT EXISTS receive_format TEXT` | PO-level FBU override |
| **store.fbu_stock + fbu_grn_register + fbu_issue_register** | Created April 2026 | FBU stock tracking |
| **work_orders.issue_mode** | `ALTER TABLE store.work_orders ADD COLUMN IF NOT EXISTS issue_mode TEXT DEFAULT 'components'` | Per-WO FBU vs components |
| **update_fbu_stock_received / update_fbu_stock_issued RPCs** | Created April 2026 (store schema) | FBU stock increment/decrement |
| **product_master: component_type, receive_format, linked_product_code** | ALTER TABLE + backfill + 23 remote rows seeded | FBU/CKD procurement format |
| **Dispatch system** | dispatch_channels, dispatch_shipments, dispatch_allocations + enums | Full dispatch flow |

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
| 3j | Dispatch Attribution Fix | ✅ Complete |
| 3k | Hourly Achievement Chart | ✅ Complete |
| 3l | Store Procurement BY UNITS | ✅ Complete — variant+color + per-product FBU/CKD override |
| 3m | QC Tab Overhaul | ✅ Complete |
| 3n | Alerts Tab Fix | ✅ Complete |
| 3o | Dashboard Exec Cards Fix | ✅ Complete |
| 3p | Store Procurement Fixes | ✅ Complete |
| 3q | FBU/CKD/SKD Schema | ✅ Complete — schema + remote rows seeded |
| 3r | FBU/CKD/SKD Store Frontend | 🧪 Pending floor test |
| 3s | QC_PASS / PKG product_master fix | ✅ Deployed |
| 3t | Dashboard Nav Bar Consolidation | ✅ Complete |
| 3u | Reporting Tab v2 | ✅ Complete |
| 3v | Takt Time / Throughput | ✅ Complete |
| 3w | Auto-refresh Optimisation | ✅ Complete |
| 3x | Dispatch System | ✅ Complete |
| 3y | Store Receiving Overhaul | ✅ Complete — box-first flow, mark range entry, PO auto-populate, box intake with OK/Damaged split, reconciliation panel, box contents panel, upcoming POs, FBU + parts path unified (April 2026) |
| 3z | PO Order Type Auto-detection | ✅ Complete — item_type → order type mapping, auto-set with yellow border, manual override supported (April 2026) |
| 4 | Reconciliation | 🔲 Not started |
| 5 | Audit Module | 🔲 Not started |
| 6 | Assembly Stations | 🔲 Not started |

### 🚨 Open Issues — fix FIRST next session

| Issue | Detail |
|---|---|
| **BOX CONTENTS panel not rendering on store site** | `getElementById('box-contents-body')` returns null in browser despite element being present in deployed GitHub file. Cloudflare CDN is caching stale HTML for `store.legendoftoys.com`. **Fix: purge Cloudflare cache** (Caching → Configuration → Purge Everything) for `store.legendoftoys.com`. Verify by checking `box-contents-body` in console after purge. |
| **`qty_counted` null on receiving lines** | `postReceivingEntry` recomputes and writes `qty_counted` via `update()` but the value shows null in the UI. Error checking added to the handler — if purge resolves the cache issue, test again to see if this is also a stale-file symptom or a genuine worker bug. |

### Pending Test — built but not yet verified on floor

| Item | What to test |
|---|---|
| **FBU/CKD/SKD Store Frontend (3r)** | BY UNITS CKD BOM explosion; FBU GRN; fbu_stock view; issue_mode on runs; issue queue FBU section |
| **Receiving overhaul (3y)** | Mark range entry; box intake OK/Damaged split; reconciliation Matched/Short/Over/Damage states; box contents per-mark breakdown; GRN raise FBU + parts paths; upcoming POs list |
| **QC_PASS fix (3s)** | Knox remote prompt appears correctly |
| **Takt / Throughput (3v)** | Throughput section scroll; bottleneck detection; first unit warm-up |
| **Dispatch System (3x)** | Full DTK→ALLOC→DOUT flow on real units |

### Pending Build Items (prioritised)

**Next up (after fixing open issues):**
1. **Parts receiving flow** — extend box-first receiving to parts shipments (same flow, different SKU grid). Currently designed for FBU; parts path needs the box intake form connected for CKD shipments.
2. **Repair run design session** — full design before any build
3. **Google sign-in** — Supabase OAuth

**Store backlog:**
4. **GRN receiving template** — CKD BOM explosion vs FBU unit count (schema + frontend done; GRN template not yet built)
5. **Price master module**

**Dashboard backlog:**
6. **EAN sticker** — separate sticker at PKG station
7. **Info scan / test scanner mode**
8. **Dashboard day view for production**
9. **Consolidated dispatch view**

**General backlog:**
10. **Unicommerce integration**
11. **Manual override cross-type allocation**
12. **Legacy UPC manual entry**
13. **Reconciliation module** — Phase 4
14. **Audit module** — Phase 5
15. **Assembly stations** — Phase 6
16. **Dashboard tab RBAC**
17. **Biometric integration**
18. **APK rollout to all 15 devices**
19. **Dash/Nitro QR code problem**
20. **material_master name inconsistencies**
21. **Old para items with `(old)` suffix** — pending Afshaan confirmation

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
| Print approach | Polling Supabase print_jobs | Chrome Android blocks local network fetch from HTTPS PWA |
| PKG label print | Non-blocking via print_jobs | Scan not blocked if printer offline |
| Print server v2.2 | Atomic job claiming via conditional PATCH | Race condition fix |
| Void mechanism | voided flag on scans row | Records never deleted |
| **FBU/CKD/SKD format** | PO-level `receive_format` override; product master defaults | Format drives PO creation UI and receiving template |
| **fbu_stock table** | `store.fbu_stock` separate from `stock_ledger` | stock_ledger is component-level; FBU units are a different stock type |
| **issue_mode per WO** | `work_orders.issue_mode = 'components'|'fbu'` | Per-line granularity |
| **Receiving: box-first** | Primary navigation by mark/box, SKUs are secondary | Matches floor reality — staff open boxes, not ledgers |
| **Short is derived** | expected − (OK + Damaged), never entered | Short mid-process is meaningless; only meaningful at final reconciliation |
| **Split disposition** | OK qty + Damaged qty per SKU per box = 2 entries | Granular damage tracking without complex UI |
| **receiving_entries** | Child of receiving_lines; mark_id per entry | Enables per-box breakdown while aggregating to line-level totals |
| **postBoxIntake** | Bulk endpoint for all entries from one box | Single API call per box submission, atomic qty recompute |
| **Mark range entry** | Prefix + From + To + Skip | Sequential marks are the norm; individual entry is the exception |
| **Upcoming POs in receiving** | Derived from po_pending_inward minus shipments.po_reference | Zero-effort visibility into what's coming without duplication |
| **receive_format auto-select** | Derived from po_lines outstanding_lines JSON in po_pending_inward | Data already available; no extra query needed |
| **Order type auto-detect** | item_type → order type mapping, dominant type wins | Eliminates manual selection for 95% of POs |
| **Dispatch station setup** | Scanner Device Setup screen split into Production + Dispatch sections | Prevents production operators from accidentally selecting dispatch stations |

---

## 12. Open Technical Questions

1. **Scan events partitioning:** Defer to month 10 (~500K events/month).
2. **QC_PASS timeout:** Should second scan time out after N seconds? Recommend yes.
3. **Rework module schema:** Pending design session.
4. **Dashboard tab access control:** Deferred.
5. **Defect master ownership:** Who can add/modify defect codes post-seeding?
6. **Channel barcode scanning:** Future.
7. **Legacy UPC gap:** Build when triggered.
8. **Repair run schema:** Two contexts — inline vs L3 auxiliary. Pending design session.
9. **print_jobs retention:** Cleanup for done/failed rows older than 30 days.
10. **All 15 devices APK vs PWA:** Deferred.
11. **Operator performance view:** Data available, view not built.
12. **Shift time boundaries:** Regular 9am-7pm (PKG), 9am-6pm (Assembly/QC). OT 6pm-9pm.
13. **Price master table:** Unit cost history per part.
14. **Hourly target split intelligence:** Currently equal (target/9 hrs).
15. **PRODUCT_VARIANTS / PRODUCT_SUBVARIANTS data:** Nitro, Dash, Fang, Atlas need base variant + colors.
16. **Google sign-in:** Supabase Google OAuth. Decision pending: restrict to `@legendoftoys.com` or open + role-gated.
17. **Takt time thresholds:** Currently ≤5m/≤10m/>10m. Arbitrary — calibrate per station per product.
18. **Dispatch Unicommerce link:** Integration design pending.
19. **SKD receive format:** Deferred — build after FBU+CKD stable.
20. **Cloudflare cache invalidation strategy:** Store site uses Cloudflare CDN. HTML changes may not propagate immediately. Consider cache-busting strategy or setting HTML cache TTL to 0.

---

## 13. Environment Reference

> ⚠️ Do not commit to git.

- **Supabase URL:** `https://jkxcnjabmrkteanzoofj.supabase.co`
- **Supabase publishable key:** `sb_publishable_1Dd-r3h9Mou2Wqgn6t24Dw_lmWdBtLh`
- **Worker URL:** `https://lotopsproxy.afshaan.workers.dev`
- **Scanner:** `https://scanner.legendoftoys.com`
- **Dashboard:** `https://dashboard.legendoftoys.com`
- **Store:** `https://store.legendoftoys.com`
- **Scanner repo:** `legendlot/production`
- **Dashboard repo:** `legendlot/dashboard`
- **Store repo:** `legendlot/Stores`
- **APK keystore:** `/Users/afshaansiddiqui/Documents/lot-scanner-apk/android.keystore`
- **APK package ID:** `com.legendoftoys.scanner`

---

*Update at end of every build session. "Open Issues" and "Pending Build Items" are the source of truth for what to work on next.*
