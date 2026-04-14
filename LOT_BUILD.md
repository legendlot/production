# Legend of Toys — Technical Build Document
**Version:** 2.9 | **Last Updated:** April 2026 (Session: 14 Apr 2026)
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
| Store system | Vanilla JS | `store.legendoftoys.com` — GitHub Pages, repo `legendlot/Stores` |
| Camera/scanning | Native `getUserMedia` + ZXing | Replaced html5-qrcode (MIUI incompatibility) |
| Print server | Node.js v2.2 | `printserver.js` on each line's PKG station laptop — polls Supabase filtered by line. Atomic job claiming prevents cross-line duplicate prints. No HTTP server, no firewall rules needed. |
| Thermal printer | TSC TE244 | TSPL, USB, 50×25mm labels, `@thiagoelg/node-printer` |
| Fonts | Tomorrow + JetBrains Mono | Google Fonts |
| QR generation (dashboard) | QRCode.js from cdnjs | Used in UPC Generator print flow and Operators tab QR card print |
| Filesystem access | Claude MCP plugin | Read-only access to `/Users/afshaansiddiqui/Documents/Claude` — use to read latest source files before writing code |

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
**Note:** `deviceId` (UUID) is now stored in `cfg.deviceId` in scanner — fixed April 13 2026.

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

#### `store.settings` — System-wide config ← new April 13 2026
```
key         TEXT PRIMARY KEY
value       TEXT                  -- nullable (null = no threshold set)
label       TEXT
description TEXT
updated_by  TEXT
updated_at  TIMESTAMPTZ DEFAULT now()
```
Seeded: `po_approval_threshold` — null = all roles self-approve. Managed by super_admin.
RLS: disabled on this table. GRANT ALL to service_role applied.

#### `store.reorder_requests` — Stock reorder pipeline ← new April 13 2026
```
request_id        TEXT PRIMARY KEY    -- RR-XXXX via 'rr' sequence
request_type      TEXT                -- 'part' | 'product'
part_code         TEXT
product           TEXT
variant           TEXT
color             TEXT
part_name         TEXT
requested_qty     NUMERIC NOT NULL
unit              TEXT
urgency           TEXT DEFAULT 'Normal'  -- Normal | Urgent | Critical
notes             TEXT
requested_by      TEXT NOT NULL
requested_by_name TEXT
status            TEXT DEFAULT 'Pending'  -- Pending | Converted | Rejected
rejection_note    TEXT
converted_po_id   TEXT
created_at        TIMESTAMPTZ DEFAULT now()
updated_at        TIMESTAMPTZ DEFAULT now()
```
RLS: disabled. GRANT ALL to service_role applied.

#### `store.vendor_supplied_items` — Vendor-product associations ← redesigned April 13 2026
New columns added to existing table:
```
supply_type   TEXT    -- 'product' | 'part' | 'category'
reference     TEXT    -- product name, part_code, or category key (e.g. 'para')
display_label TEXT    -- human-readable label for display
po_category   TEXT    -- nullable (only set for category type)
```
Old columns (po_category was NOT NULL — dropped constraint April 13 2026). Existing rows wiped; re-enter fresh.
RLS policy `service_role_all` exists. `vendor_supplied_items_id_seq` granted to service_role.

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
| `next_seq_batch` | Reserve N sequential values atomically, returns first value | `seq_name`, `batch_count` | ← April 14 2026 |
| `recompute_line_counts` | Batch-update qty_counted + status on receiving_lines from entries sum | `p_line_ids TEXT[]` | ← April 14 2026 |
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
| `getSettings` | Store — reads store.settings table ← new April 13 2026 |
| `getReorderRequests` | Store — list reorder requests with status/urgency filter ← new April 13 2026 |
| `getGRN` | Store — full grn_register list |
| `getGRNSummary` | Store — grouped GRN summary view |
| `getGRNDetail` | Store — summary + all lines for a single GRN | ← April 14 2026 |

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
| `postReturnInspection` | Store — records inspection + disposition |
| `postReturnHandover` | Store — marks shipment handed_over |
| `getReturnShipments` | Store — list return shipments |
| `getReturnShipment` | Store — single shipment |
| `createUser` | Store admin |
| `resetPassword` | Store admin |
| `postVendorSuppliedItem` | Store — new schema: supply_type + reference + display_label ← updated April 13 2026 |
| `deleteVendorSuppliedItem` | Store — delete by id |
| `postReorderRequest` | Store — creates RR-XXXX reorder request ← new April 13 2026 |
| `updateReorderRequest` | Store — convert to PO or reject with note ← new April 13 2026 |
| `postShipment` | Store — creates shipment + auto-populates receiving lines from PO; bag_size from material_current ← updated April 14 2026 |
| `postBoxIntake` | Store — batch entry insert + recompute_line_counts RPC; subrequest-safe ← updated April 14 2026 |
| `generateBags` | Store — append-only bag generation per line; updates total_bags on existing bags ← updated April 14 2026 |
| `generateBagsForShipment` | Store — batch append-only bag generation for all counted lines in a shipment ← new April 14 2026 |
| `updateLineBagSize` | Store — edit bags_of per receiving line; applies to future bags only ← new April 14 2026 |

---

## 5. Scanner Flows

### INW
Single scan. `component_type` now derived from `pm.component_type` — **never from product_code suffix** (GHBR ends in R = Red, not Remote — suffix logic caused misclassification). Falls back to product name containing "remote" if pm lookup misses (e.g. GHUKR not in PM). Fixed April 13 2026.

### QC_PASS / QC_FAIL / WKS / PKG / PKG_OUT
Unchanged from prior sessions.

### DTK / ALLOC / DOUT (Dispatch)
`cfg.deviceId` (UUID) now correctly stored at setup time — fixed April 13 2026. Operators must redo device setup once after this fix to get fresh `cfg` in localStorage.

---

## 6. Store / Receiving System

### Box-first receiving flow
1. Create shipment (linked to PO) → receiving_lines auto-populated from po_lines
2. Add shipping marks (RANGE or SINGLE)
3. OPEN BOX on a mark → box intake grid
4. Submit box → entries written with mark_id
5. Repeat per box
6. Reconciliation panel: Matched / Short / Over / Has Damage / Pending per SKU
7. BOX CONTENTS panel: per-mark SKU breakdown
8. RAISE GRN → FBU path or Parts path

### FBU box intake ← updated April 13 2026
- Car rows show green "Car" badge, Remote rows show blue "Remote" badge
- Column header: "Product / Type" (was "Product")

### Bag system ← new April 14 2026
- `bags_of` on receiving lines now pulled from `material_current.bag_size` at shipment creation (was hardcoded 25)
- Bag generation is **append-only**: only generates bags for qty not yet bagged; appends from `existing_count + 1`; updates `total_bags` on all prior bags for that line
- Multiple bag sizes per part across shipments fully supported — each bag carries its own qty
- Bag ID format: `BAG-{part_code}-{line_id_last6}-{seq}` — globally unique, QR-scannable at issue time
- Label format: LOT logo + part code + shipment ref | part name + qty + bag X of Y | QR of bag_id | unique bag ID footer
- Per-line controls in reconciliation: editable bag size, expected bag count, GEN button, Print button
- GEN ALL BAGS + PRINT ALL at reconciliation header level
- Editing bag size after bags exist: new size applies to new bags only (old sealed bags unchanged)

### GRN improvements ← April 14 2026
- `grn_summary` view rebuilt: single product if uniform, "N products" if mixed, "—" if all blank
- `product` field in `grn_register` now derived from `bom_current` for lines with blank product (HW/UNV parts inherit parent product)
- GRN rows are now clickable — opens detail modal with full line-by-line breakdown
- GRN detail modal placed at app root (not inside receiving section) so it works from any page

### Cloudflare subrequest limit fixes ← April 14 2026
- Root cause: Cloudflare Workers limit = 50 subrequests per invocation. CKD POs with 77 lines hit this in 3 places.
- `postShipment`: `batchNextSeq` for receiving line IDs (1 RPC instead of N)
- `postBoxIntake`: `batchNextSeq` for ENT IDs + single batch insert + `recompute_line_counts` RPC (was N×4 subrequests)
- `raiseGRNFromReceiving`: single batch update with `IN` filter (was N individual updates)

---

## 7. Procurement System ← major rebuild April 13 2026

### Page structure
```
Procurement
├── Dashboard        ← new: pending requests + open POs summary + pending approvals + arriving soon
├── Purchase Orders  ← rebuilt: category-first, redesigned header, improved line items
├── Reorder Requests ← new: team inbox + procurement guy action view
├── Vendors          ← existing + simplified supplied items (product/part/category types)
└── Forwarders       ← unchanged
```

### PO Creation flow — FBU ← updated April 14 2026
**New layout:** Product selector panel comes FIRST (before ORDER DETAILS). Header is now a contained breadcrumb bar. Expected Delivery field removed from ORDER DETAILS (calculated from Shipping Timeline instead).
- SELECT PRODUCT panel: single product dropdown + format badge
- ORDER DETAILS: Vendor + Payment Terms only (no expected delivery)
- LINE ITEMS: variant qty grid + ADD TO ORDER + queue
- SHIPPING TIMELINE: calculates expected arrival; auto-sets `po-delivery` hidden field

### PO Creation flow — CKD ← rebuilt April 14 2026
**New mode:** `ckd-units` (replaces old `bom` mode). Multi-product queue with BOM explosion.

**SELECT PRODUCTS panel:**
- Add multiple products from dropdown; each expands into variant grid
- Each variant row: Car qty + Remote qty (Remote defaults to mirror Car, editable independently)
- Remote qty is **product-level** in explosion: sum of all variant remote qtys per product
- `CKD_EXCLUDED_PRODUCTS`: Dash, Nitro, Flare LE (FBU-only — hidden from CKD selector)
- EXPLODE BOM button enabled once any qty is entered

**BOM explosion logic:**
- Car parts: `calcKit(product, variant, colour, carQty)` per variant row, **excluding Remote category**
- Remote parts: `calcKit(product, '', '', totalRemoteQty)` per product, **Remote category only**
- Parts merged by `part_code` across all calls (quantities summed)
- Results grouped into display categories: Car Parts, Remote Parts, Fasteners, Batteries, Packaging (off by default), Para (off by default), Other

**LINE ITEMS panel after explosion:**
- Summary bar: total cars + remotes, per-product breakdown
- Collapsible BOM groups with master include/exclude toggle per group
- Editable qty per part row
- RE-SELECT button resets explosion

**On submit:** `getCKDLines()` reads checked groups + edited qtys; submitted as parts lines with `receive_format: 'CKD'`

### Vendor supplied items — redesigned April 13 2026
Three supply types (replaces old category+product+variant+color+component_type system):
- **Product** — "Kai supplies Flare" — searchable product dropdown
- **Part** — "Deng supplies Knox PCB" — searchable part code/name
- **Category** — "Manvik supplies all Para" — fixed 8-category list

Auto-fill logic: product-level match first, then category-level catch-all.

### Reorder requests — new April 13 2026
**Who can raise:** All roles (via `reorder_raise` permission added to all roles).
**Two entry types:** By Part Code (searchable, shows current stock) or By Product (product → variant → color dropdowns).
**Urgency:** Normal / Urgent / Critical.
**Procurement guy view:** Full list with CONVERT → PO and REJECT actions.
**Convert flow:** Opens PO form pre-filled, links RR to created PO on submit.

### Approval threshold
Managed via `store.settings` table, key `po_approval_threshold`. Null = self-approve always. Set by super_admin. Not yet wired to PO status — UI indicator exists but approval gate not enforced yet (future work).

---

## 8. Schema Changes Log

| Change | SQL | Purpose |
|---|---|---|
| `store.settings` | CREATE TABLE + seed `po_approval_threshold` | System-wide config, approval threshold ← April 13 2026 |
| `store.reorder_requests` | CREATE TABLE | Reorder request pipeline ← April 13 2026 |
| `store.sequences 'rr'` | INSERT | RR-XXXX sequence ← April 13 2026 |
| `store.roles: reorder_raise permission` | UPDATE all roles | Everyone can raise reorder requests ← April 13 2026 |
| `store.roles: procurement role` | UPDATE permissions | Added grn/stock/reports/receiving/production_view ← April 13 2026 |
| `store.vendor_supplied_items: supply_type, reference, display_label` | ALTER TABLE ADD COLUMN | Simplified vendor-product associations ← April 13 2026 |
| `store.vendor_supplied_items: po_category DROP NOT NULL` | ALTER TABLE | product/part types don't have a category ← April 13 2026 |
| `store.vendor_supplied_items_id_seq` | GRANT USAGE, SELECT TO service_role | Sequence permission fix ← April 13 2026 |
| `store.settings` RLS | GRANT ALL TO service_role | Permission fix (RLS disabled on table) ← April 13 2026 |
| `store.reorder_requests` RLS | GRANT ALL TO service_role | Permission fix (RLS disabled on table) ← April 13 2026 |
| `public.product_master` | INSERT 2 rows (FERK car + FEXXR remote) | Flare LE product added ← April 13 2026 |
| `store.next_seq_batch` | CREATE FUNCTION | Batch sequence reservation — returns first value of N reserved | ← April 14 2026 |
| `store.recompute_line_counts` | CREATE FUNCTION | Batch recompute qty_counted on receiving_lines | ← April 14 2026 |
| `store.grn_summary` | CREATE OR REPLACE VIEW | Product column now shows single product / "N products" / "—" | ← April 14 2026 |
| All prior schema changes | — | See v2.8 for full history |

---

## 9. Build Phases & Status

| Phase | Module | Status |
|---|---|---|
| 1 | DB + Scanner App | ✅ Complete |
| 1b | PKG Print System | ✅ Complete — polling v2.2 per-line atomic |
| 2 | Reporting Dashboard | ✅ Live |
| 3–3ab | All prior phases | ✅ Complete — see v2.7 for details |
| 3ac | Procurement Redesign | ✅ Complete — full rebuild April 13 2026 |
| 3ad | Scanner `deviceId` fix | ✅ Complete — `cfg.deviceId` now stored at setup ← April 13 2026 |
| 3ae | INW `component_type` fix | ✅ Complete — derives from pm, not product_code suffix ← April 13 2026 |
| 3af | `buildProductList` FBU fix | ✅ Complete — FBU products with no BOM now appear in all dropdowns ← April 13 2026 |
| 3ag | QC line attribution fix | ✅ Complete — Knox QC_PASS scans corrected L1→L2 via SQL; scanner setup screen now shows active run per line ← April 13 2026 |
| 3ah | Flare LE product | ✅ Complete — product_master rows inserted, store frontend updated ← April 13 2026 |
| 3ai | FBU PO form redesign | ✅ Complete — product-first layout, breadcrumb header, expected delivery removed ← April 14 2026 |
| 3aj | CKD PO flow rebuild | ✅ Complete — ckd-units mode, multi-product queue, BOM explosion, grouped result, editable qtys ← April 14 2026 |
| 3ak | Cloudflare subrequest fixes | ✅ Complete — batchNextSeq, recompute_line_counts, batch IN filter ← April 14 2026 |
| 3al | Bag system | ✅ Complete — append-only generation, material_master bag_size defaults, per-line controls, labels with QR ← April 14 2026 |
| 3am | GRN improvements | ✅ Complete — grn_summary view rebuilt, detail modal, product derivation from bom_current ← April 14 2026 |
| 4 | Reconciliation | 🔲 Not started |
| 5 | Audit Module | 🔲 Not started |
| 6 | Assembly Stations | 🔲 Not started |

### 🚨 Open Issues — fix FIRST next session

None currently. All known bugs fixed this session.

### Pending Test — built but not yet verified on floor

| Item | What to test |
|---|---|
| **CKD PO full flow** | Create CKD PO with Ghost × 125 → explode BOM → submit → PO lines correct |
| **Receiving against CKD PO** | Create shipment → marks → open box → submit → reconciliation shows correctly |
| **Bag generation** | GEN per line → correct bag count; GEN ALL → all lines; PRINT → label with QR opens |
| **Bag append** | Day 2 more qty → GEN → new bags appended, total_bags updated on existing bags |
| **GRN detail modal** | Click GRN row → modal opens with correct lines and qtys |
| **FBU PO form** | Product-first layout renders; expected delivery absent; shipping timeline auto-calculates |
| **Dispatch DTK fix (3ad)** | Operator redoes setup on DTK device → DTK scan succeeds |
| **INW component_type fix (3ae)** | Ghost Burnout Red and remote stickers inward with correct component_type |

### Pending Build Items (prioritised)

**Next session:**
1. **Full receiving → stock flow verification** — end-to-end with real CKD PO on live data
2. **Scanner setup screen: show active run** — when operator picks a line, show current product + run before LAUNCH
3. **Reorder requests — stock page integration** — raise request directly from stock/inventory page
4. **Procurement approval gate** — wire `po_approval_threshold` to actual PO status enforcement
5. **Repair run design session** — design only, no code

**Store backlog:**
6. **Print PO** — PDF for emailing to vendor
7. **Price master module**
8. **Parts receiving reconciliation edge cases** — Short/Over/Damage handling in GRN raise flow

**Dashboard backlog:**
9. **EAN sticker** — separate sticker at PKG station
10. **Info scan / test scanner mode**
11. **Dashboard day view for production**
12. **Consolidated dispatch view**

**General backlog:**
13. **Product entry frontend** — UI for adding new products/variants without SQL
14. **Unicommerce integration**
15. **Legacy UPC manual entry**
16. **Reconciliation module** — Phase 4
17. **Audit module** — Phase 5
18. **Assembly stations** — Phase 6
19. **Dashboard tab RBAC**
20. **Google sign-in** — Supabase OAuth
21. **Biometric integration**
22. **APK rollout to all 15 devices**
23. **Dash/Nitro QR code problem**
24. **material_master name inconsistencies**
25. **Old para items with `(old)` suffix**
26. **PRODUCT_SUBVARIANTS for Nitro/Dash/Fang/Atlas**

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
| Print approach | Polling Supabase print_jobs | Chrome Android blocks local network fetch from HTTPS PWA |
| Void mechanism | voided flag on scans row | Records never deleted |
| **component_type derivation at INW** | `pm.component_type` → product name contains "remote" → 'car' | product_code suffix (e.g. GHBR = Red) caused misclassification. pm lookup first, name fallback second ← April 13 2026 |
| **cfg.deviceId** | Stored at saveSetup time from `device.id` | Was never stored; DTK/ALLOC/DOUT all read `cfg.deviceId` which was always undefined ← April 13 2026 |
| **Procurement: category locks format** | `poCurrentCategory` overrides `receiveFormatCache` in grid, badge, and submission | Product default (CKD) must not bleed through when user explicitly chose FBU category ← April 13 2026 |
| **FBU remote: product-level line** | `addUnitsToQueue` collapses all remote qtys per product into one line | Remotes are product SKU, not variant-specific. Avoids duplicate box intake rows ← April 13 2026 |
| **Vendor supplied items: 3-type model** | product / part / category (replaces old 5-field model) | Matches how people think: "Kai does Flare", "Deng does PCBs", "Manvik does all Para" ← April 13 2026 |
| **Reorder requests: anyone can raise** | `reorder_raise` permission added to all roles | Low-stakes action; team members closest to stock shortage should be able to flag it ← April 13 2026 |
| **store.settings table** | Key-value store for system config | First use: `po_approval_threshold`. Managed by super_admin. ← April 13 2026 |
| **RLS on new tables** | Disabled; GRANT ALL to service_role | Worker uses service_role key; RLS without policies = permission denied ← April 13 2026 |
| **buildProductList FBU products** | Merge PRODUCT_VARIANTS keys into product list | FBU products have no BOM entries → never appeared in `materialCache` → missing from all dropdowns ← April 13 2026 |
| **Cloudflare 50-subrequest limit** | Never loop `await` calls per-line in a Worker handler. Always batch: use `batchNextSeq` for sequences, single INSERT for row arrays, `IN` filter for batch updates, RPCs for aggregate operations. N lines = must stay under ~40 total subrequests including auth + logging. ← April 14 2026 |
| **`closing_stock` is generated** | `stock_ledger.closing_stock` is a Postgres generated column (`opening_stock + total_received - total_issued + returned`). Cannot UPDATE directly. Reverse stock by subtracting from `total_received`. ← April 14 2026 |
| **`grn_register.product` blank for CKD** | `receiving_lines.product` is null for hardware/universal parts. Fix: derive from `bom_current` by part_code at GRN creation time. ← April 14 2026 |
| **Fixed-position modals must be at app root** | A `position:fixed` element inside a `display:none` parent is hidden regardless. GRN detail modal (and any future modals) must live outside all view sections, directly inside `#app`. ← April 14 2026 |
| All prior decisions | — | See v2.8 for full history |

---

## 12. Open Technical Questions

1. **Scan events partitioning:** Defer to month 10 (~500K events/month).
2. **QC_PASS timeout:** Should second scan time out after N seconds? Recommend yes.
3. **Rework module schema:** Pending design session.
4. **Dashboard tab access control:** Deferred.
5. **Defect master ownership:** Who can add/modify defect codes post-seeding?
6. **Legacy UPC gap:** Build when triggered.
7. **Repair run schema:** Two contexts — inline vs L3 auxiliary. Pending design session.
8. **print_jobs retention:** Cleanup for done/failed rows older than 30 days.
9. **All 15 devices APK vs PWA:** Deferred.
10. **Operator performance view:** Data available, view not built.
11. **Price master table:** Unit cost history per part.
12. **Hourly target split intelligence:** Currently equal (target/9 hrs).
13. **PRODUCT_SUBVARIANTS data:** Nitro, Dash, Fang, Atlas need base variant + colors.
14. **Google sign-in:** Supabase Google OAuth. Decision pending: `@legendoftoys.com` or open + role-gated.
15. **Takt time thresholds:** Currently ≤5m/≤10m/>10m. Arbitrary — calibrate per station per product.
16. **Dispatch Unicommerce link:** Integration design pending.
17. **SKD receive format:** Deferred — build after FBU+CKD stable.
18. **Procurement approval gate:** `po_approval_threshold` in settings but not yet enforced on PO status.
19. **Print PO:** Designed; not built. Language-ready (Chinese support deferred).
20. **FBU receiving format auto-select:** Currently determined by `allFbu` check on po_lines. POs created before FBU fix (e.g. CN-PRD-0009) will show PARTS — expected, receive as-is.

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
- **Local Claude folder:** `/Users/afshaansiddiqui/Documents/Claude` — 01_Scanner, 02_Dashboard, 03_Store, 04_Worker

---

*Update at end of every build session. "Open Issues" and "Pending Build Items" are the source of truth for what to work on next.*
