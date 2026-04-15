# Legend of Toys — Technical Build Document
**Version:** 3.2 | **Last Updated:** April 2026 (Session: 16 Apr 2026)
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
| Print server (production) | Node.js v2.2 | `printserver.js` on each line's PKG station laptop — polls Supabase filtered by line. Atomic job claiming prevents cross-line duplicate prints. |
| Print server (dispatch) | Node.js v1.0 | `dispatch-printserver.js` on dispatch laptop — polls `line = 'DISPATCH'`. Handles `PKG_LABEL` + `BOX_LABEL` job types. **Not yet deployed — needs setup on dispatch laptop.** |
| Thermal printer | TSC TE244 | TSPL, USB, 50×25mm labels, `@thiagoelg/node-printer` |
| Fonts | Tomorrow + JetBrains Mono | Google Fonts |
| QR generation (dashboard) | QRCode.js from cdnjs | Used in UPC Generator print flow and Operators tab QR card print |
| Filesystem access | Claude MCP plugin | Read-only access to `/Users/afshaansiddiqui/Documents/Claude` — use to read latest source files before writing code |

---

## 2. Supabase Schema

### Schema Separation
- **`store` schema** — inventory, procurement, GRN, issues, work orders, production runs, returns
- **`public` schema** — scan tables, units, devices, operators, dispatch, views, RPCs

Store schema API calls include `Accept-Profile: store` / `Content-Profile: store` headers.
Public schema RPC calls use `sbPublic()` helper — no profile headers.

**Supabase max rows setting:** 5000 (changed from default 1000).

---

### Production Schema Tables (public)

#### `units` — One row per physical unit
```
upc             TEXT PRIMARY KEY
ean             TEXT
sku             TEXT
product         TEXT
model           TEXT
color           TEXT
current_status  TEXT (enum unit_status)
loop_count      INT
is_fresh        BOOLEAN
created_at      TIMESTAMPTZ
updated_at      TIMESTAMPTZ
component_type  TEXT                  -- 'car' | 'remote'
paired_with     TEXT
production_run_id UUID
legacy_ean      TEXT                  -- EAN of the legacy unit's SKU
legacy_car_upc  TEXT                  -- Full legacy car QR value (EAN + suffix)
is_legacy       BOOLEAN DEFAULT false -- True for all legacy-registered units
```

**unit_status enum values (confirmed + updated):**
`inwarded | qc_pass | qc_fail | in_repair | repaired | pending_rtd | packed | rtd | scrapped | rto_in | handed_over | allocated | packed_dispatch | shipped`
- `pending_rtd` — set after PKG scan
- `packed` — legacy, no longer written
- `packed_dispatch` — set at PACK station ← added April 15 2026 (`ALTER TYPE unit_status ADD VALUE IF NOT EXISTS 'packed_dispatch'`)
- `handed_over` — set at DTK
- `allocated` — set at ALLOC
- `shipped` — set at DOUT

#### `scans` — All scan activity (immutable ledger)
```
id              UUID PRIMARY KEY
timestamp       TIMESTAMPTZ
upc             TEXT
ean             TEXT
activity        TEXT (enum activity_type)
                -- INW | QC_PASS | QC_FAIL | WKS_IN | WKS_OUT | PKG | RTE | RTR
                -- RTO_IN | RTD_RETURN | DTK | ALLOC | DOUT | PACK
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
**Critical:** `activity_type` is a Postgres enum. `PKG_OUT` is NEVER written to scans — PKG_OUT scan writes `RTE` (ecom) or `RTR` (retail). Never filter scans by `PKG_OUT`.

#### `dispatch_channels`
```
id                UUID PRIMARY KEY
name              TEXT
type              TEXT              -- 'ecom' | 'retail' | 'other'
fulfillment_model TEXT              -- 'unit' | 'bulk'
is_sale           BOOLEAN
is_active         BOOLEAN
```
17 seeded channels including Website (ecom, unit, is_sale=true) ← added April 15 2026.

#### `dispatch_allocations`
```
car_upc       TEXT PRIMARY KEY
batch_label   TEXT
channel_id    UUID FK → dispatch_channels
allocated_by  UUID
allocated_at  TIMESTAMPTZ
shipped_at    TIMESTAMPTZ
shipment_id   UUID FK → dispatch_shipments  -- nullable
box_id        UUID FK → dispatch_boxes      -- nullable ← April 15 2026
packed_at     TIMESTAMPTZ                   -- nullable ← April 15 2026
```

#### `dispatch_shipments` ← extended April 15 2026
```
id                    UUID PRIMARY KEY
shipment_no           TEXT    -- DSO-XXXX (dso_seq, reset to 1) ← renamed from SHP- April 15 2026
channel_id            UUID FK → dispatch_channels
status                TEXT    -- draft | packing | ready | shipped | cancelled
expected_units        INT     -- auto-computed from SUM(shipment_lines.target_qty) ← April 15 2026
packed_count          INT DEFAULT 0                                                 ← April 15 2026
destination_warehouse TEXT
scheduled_date        DATE
notes                 TEXT
created_by            TEXT
shipped_at            TIMESTAMPTZ
created_at            TIMESTAMPTZ
```
**Sequence:** `dso_seq` (reset to 1 April 15 2026). Old `shp_seq` still exists for procurement shipments — completely separate.

#### `dispatch_shipment_lines` ← new April 15 2026
```
id          UUID PRIMARY KEY
shipment_id UUID NOT NULL FK → dispatch_shipments
product     TEXT NOT NULL
model       TEXT
color       TEXT
target_qty  INT NOT NULL DEFAULT 0
packed_qty  INT NOT NULL DEFAULT 0
created_at  TIMESTAMPTZ DEFAULT now()
```
GRANT ALL to service_role applied.

#### `dispatch_boxes` ← new April 15 2026
```
id                UUID PRIMARY KEY
box_ref           TEXT UNIQUE NOT NULL    -- BOX-XXXXX (box sequence)
shipment_id       UUID FK → dispatch_shipments  -- nullable (direct mode)
channel_id        UUID FK → dispatch_channels
fulfillment_model TEXT                    -- 'unit' | 'bulk'
status            TEXT DEFAULT 'open'    -- open | packed | shipped
unit_count        INT NOT NULL DEFAULT 0
packed_at         TIMESTAMPTZ
shipped_at        TIMESTAMPTZ
created_at        TIMESTAMPTZ DEFAULT now()
```
GRANT ALL to service_role applied. `shipment_id` is nullable — direct mode boxes have no shipment.

#### `dispatch_box_units` ← new April 15 2026
```
id          UUID PRIMARY KEY
box_id      UUID NOT NULL FK → dispatch_boxes
car_upc     TEXT NOT NULL
batch_label TEXT NOT NULL
product     TEXT
model       TEXT
color       TEXT
added_at    TIMESTAMPTZ DEFAULT now()
removed_at  TIMESTAMPTZ
removed_by  TEXT
is_active   BOOLEAN NOT NULL DEFAULT true
```
GRANT ALL to service_role applied.

#### `print_jobs` ← extended April 15 2026
```
id          UUID PRIMARY KEY
batch_label TEXT
product     TEXT
model       TEXT
color       TEXT
channel     TEXT
line        TEXT        -- L1 | L2 | L3 | DISPATCH
packed_at   TIMESTAMPTZ
status      TEXT        -- pending | printing | done | failed
job_type    TEXT DEFAULT 'PKG_LABEL'   -- PKG_LABEL | BOX_LABEL ← April 15 2026
payload     JSONB                      -- box label data for BOX_LABEL ← April 15 2026
```

#### `devices` (relevant new entry)
`PACK-SHARED` — station=PACK, line=SHARED ← added April 15 2026.

#### Other tables (unchanged from v3.0)
`operators`, `operator_sessions`, `store.settings`, `store.reorder_requests`, `store.vendor_supplied_items` — see v3.0 for details.

---

## 3. RPCs (public schema)

Same as v3.0. No new RPCs this session.

---

## 4. Worker Endpoints (GET actions)

| Action | Description |
|---|---|
| *(all prior GET actions unchanged)* | See v3.0 |
| `getDispatchShipments` | Updated ← April 15 2026: now includes `pool_count` + `is_ready` flag per shipment |
| `getShipmentLines` | Lines for a shipment (`dispatch_shipment_lines`) ← April 15 2026 |
| `getShipmentBoxes` | Boxes for a shipment ← April 15 2026 |
| `getBoxDetail` | Single box + all units ← April 15 2026 |
| `getAllScans` | Updated ← April 15 2026: supports `date_from` + `date_to` params (not just single `date`); limit bumped to 2000 |

## 4b. Worker Endpoints (POST/Scanner actions)

| Action | Description |
|---|---|
| *(all prior POST actions unchanged)* | See v3.0 |
| `getActiveShipments` | Scanner — today's draft + any packing shipments, with pool counts + ready flag ← April 15 2026 |
| `createBox` | Scanner — create box for shipment_id OR channel_id (direct mode) ← April 15 2026 |
| `getOpenBox` | Scanner — check for open box on a shipment ← April 15 2026 |
| `postPack` | Scanner — scan unit into box; manifest check; auto-close for unit mode ← April 15 2026 |
| `closeBox` | Scanner — close bulk box + queue print job ← April 15 2026 |
| `reopenBox` | Scanner — reopen packed box, reverses packed_count ← April 15 2026 |
| `createShipment` | Updated ← April 15 2026: accepts `lines[]` array; auto-computes `expected_units` |
| `updateShipment` | Updated ← April 15 2026: accepts `expected_units` |
| `updateShipmentLines` | Replace manifest lines for a shipment (used in edit flow) ← April 15 2026 |
| `postDout` | Updated ← April 15 2026: accepts BOX-XXXXX labels (bulk) + batch labels (unit); accepts `packed_dispatch` status |
| `removeBoxUnit` | JWT — dashboard use only. Remove unit from box, revert to allocated ← April 15 2026 |
| `cancelShipment` | JWT — draft/packing → cancelled ← April 15 2026 |
| `deleteShipment` | JWT — draft only, no boxes → hard delete lines + shipment ← April 15 2026 |
| `registerLegacyUnit` | Legacy unit integration — three modes: dispatch (EAN scan → handed_over), store (legacy car QR → inwarded), return (EAN or legacy QR → rto_in). Auto-generates LOT UPC pair, deduplicates via legacy_car_upc ← April 16 2026 |

---

## 5. Scanner Flows

### Full dispatch flow — updated April 15 2026
```
DTK (handed_over) → ALLOC (allocated) → PACK (packed_dispatch) → DOUT (shipped)
```

### PACK station — new April 15 2026
Two modes selectable via toggle at top of scanner screen:

**Shipment mode (bulk + planned unit):**
- Operator selects active shipment from dropdown
- Active = `status=packing` (any date) OR `status=draft AND scheduled_date=today`
- Bulk: tap OPEN BOX → scan units one by one → tap CLOSE BOX → label prints
- Unit: scan unit → auto-creates box, adds unit, auto-closes, label prints
- Hard blocks: wrong channel, not in manifest, line already full
- Box management: close, reopen from scanner

**Direct mode (unit channels only, no shipment):**
- Operator selects channel from dropdown (unit fulfillment channels only)
- Each scan: auto-create box → pack → auto-close → PKG label reprint queued
- No manifest, no planning — purely for operational tracking + label printing
- Switched via SHIPMENT / DIRECT toggle, persisted in localStorage

### DOUT — updated April 15 2026
Accepts two scan formats:
- `BOX-XXXXX` — bulk box label: marks all units in box as shipped, auto-marks shipment shipped if last box
- `LOT-XXXXXXXX-E/R` — unit batch label: marks single unit shipped (same as before)

### DTK / ALLOC
Unchanged from v3.0.

### PKG_OUT activity_type rule
`PKG_OUT` is NEVER an `activity` value in the `scans` table. The PKG_OUT scan writes `RTE` (ecom suffix -E) or `RTR` (retail suffix -R). Always filter by `RTE`/`RTR`, never `PKG_OUT`.

---

## 6. Store / Receiving System

No changes this session. See v3.0.

---

## 7. Procurement System

No changes this session. See v3.0.

---

## 8. Dispatch System ← new April 15 2026

### Shipment lifecycle
```
draft → packing → (ready) → shipped
              ↘ cancelled
```
- `draft` — created on dashboard, waiting for packing to begin
- `packing` — first box opened at PACK station
- `shipped` — all boxes shipped (auto-set at DOUT when last box clears)
- `cancelled` — manually cancelled from dashboard (draft or packing only)

### Shipment manifest
Created at same time as shipment, on dashboard. Product picker identical to FBU receiving flow — select product, all variants expand, enter qty per variant. Multiple products. PACK station hard-blocks units not in manifest and units that would exceed line target.

**Ready-to-pack flag:** `pool_count >= expected_units` — visible in shipments table and scanner dropdown.

### Dispatch print server
`dispatch-printserver.js` — **new file, not yet deployed**. Must be set up on dispatch laptop:
- Same architecture as production print servers
- Polls `print_jobs WHERE line = 'DISPATCH'`
- `PKG_LABEL` jobs: reprint of PKG label (unit fulfillment outer box)
- `BOX_LABEL` jobs: combined box label with channel, product breakdown, QR of box_ref, unit count, shipment ref, date
- QR encodes `box_ref` (e.g. `BOX-00001`) — scanned at DOUT for bulk exit

### Box label format (bulk)
50×25mm TSC TE244 label:
- LOT logo + channel name
- Product breakdown (compact: "Flare×10 Ghost×5")
- Unit count (large)
- Shipment ref + date
- QR code encoding box_ref
- Box ref ID footer

---

## 9. Dashboard Changes ← April 15 2026

### Dispatch nav restructure
Dispatch is now a dropdown with three sub-tabs, each a full scrollable content panel:
- **Overview** — live status cards, allocated by channel, sent out by channel, units table (with `packed_dispatch` status)
- **Shipments** — shipments table with pool/packed counts, ready badge, edit/cancel/delete actions
- **Channel Master** — channel CRUD

### Shipments table enhancements
- Pool count vs expected units per shipment
- ✓ READY badge when `pool_count >= expected_units`
- Packing + Cancelled status badges
- Edit button (draft/packing) — opens modal with editable manifest
- Cancel button (draft/packing) — sets status=cancelled
- Delete button (draft only, no boxes) — hard deletes
- Click row → shipment detail modal (manifest progress + boxes + unit remove)

### New Shipment modal
- Multi-product manifest picker (same UX as FBU receiving)
- Add products one by one — product block appended without re-rendering others (preserves entered values)
- Quantities auto-total at bottom
- Channel → Warehouse field toggles for bulk channels

### Scans tab date filter
- Own From/To date inputs + Today/This Week/This Month presets
- No longer tied to global datebar
- Worker `getAllScans` now accepts `date_from` + `date_to` params

### Store history issue detail
- Click any issue row → modal showing all part lines
- Columns: Part Code, Part Name, BOM Qty, BOM Issue Qty, Actual Issued, Variance
- Totals footer with total variance

---

## 10. Schema Changes Log

| Change | SQL | Purpose |
|---|---|---|
| `dispatch_shipments: expected_units, packed_count` | ALTER TABLE ADD COLUMN | Shipment manifest tracking ← April 15 2026 |
| `print_jobs: job_type, payload` | ALTER TABLE ADD COLUMN | Support BOX_LABEL type ← April 15 2026 |
| `dispatch_boxes` | CREATE TABLE | Outer carton tracking ← April 15 2026 |
| `dispatch_box_units` | CREATE TABLE | Unit→box junction ← April 15 2026 |
| `dispatch_shipment_lines` | CREATE TABLE | Shipment manifest lines ← April 15 2026 |
| `dispatch_allocations: box_id, packed_at` | ALTER TABLE ADD COLUMN | Link allocation to box ← April 15 2026 |
| `store.sequences 'box'` | INSERT | BOX-XXXXX sequence ← April 15 2026 |
| `devices: PACK-SHARED` | INSERT | PACK station device ← April 15 2026 |
| `dispatch_channels: Website` | INSERT | Website channel (ecom, unit, is_sale=true) ← April 15 2026 |
| `dispatch_shipments.shipment_no` | ALTER COLUMN DEFAULT | Changed prefix SHP- → DSO-, new `dso_seq` reset to 1 ← April 15 2026 |
| `dispatch_boxes.shipment_id` | ALTER COLUMN DROP NOT NULL | Direct mode boxes have no shipment ← April 15 2026 |
| `unit_status: packed_dispatch` | ALTER TYPE ADD VALUE | New dispatch packing status ← April 15 2026 |
| All prior schema changes | — | See v3.0 for full history |

---

## 11. Build Phases & Status

| Phase | Module | Status |
|---|---|---|
| 1 | DB + Scanner App | ✅ Complete |
| 1b | PKG Print System | ✅ Complete — polling v2.2 per-line atomic |
| 2 | Reporting Dashboard | ✅ Live |
| 3–3an | All prior phases | ✅ Complete — see v3.0 for details |
| 3ao | Dispatch expansion (PACK stage) | ✅ Complete — full flow DTK→ALLOC→PACK→DOUT ← April 15 2026 |
| 3ap | Dispatch print server | ⚠️ Built, not deployed — needs setup on dispatch laptop |
| 3aq | Dashboard dispatch restructure | ✅ Complete ← April 15 2026 |
| 3ar | Shipment manifest + product picker | ✅ Complete ← April 15 2026 |
| 3as | Scans tab date filter | ✅ Complete ← April 15 2026 |
| 3at | Store history issue detail | ✅ Complete ← April 15 2026 |
| 3au | Legacy unit integration | ✅ Complete ← April 16 2026 |
| 4 | Reconciliation | 🔲 Not started |
| 5 | Audit Module | 🔲 Not started |
| 6 | Assembly Stations | 🔲 Not started |

### 🚨 Open Issues — fix FIRST next session

| Issue | Detail |
|---|---|
| Dispatch print server not deployed | dispatch-printserver.js built, needs laptop + printer setup at dispatch table |
| LOT-00007572 | Flare Race Black qc_fail — team checking whether to scrap |
| Worker: registerLegacyUnit not yet deployed | Built, needs wrangler deploy |

### ✅ Fixed This Session (15 Apr 2026 Part 2)

| Fix | Root cause | Resolution |
|---|---|---|
| `sbPublic` 204 false error | `JSON.parse("")` throws on empty body; catch block returned `ok: false` even for successful 204 | Changed catch to `ok: res.ok` — 204 no longer misread as failure |
| `createShipment` lines 400 | `dispatch_shipment_lines` table existed but `sbPublic` 204 fix above was the real root cause | Fixed via `sbPublic` patch |
| Flare Burnout Green wrong labels | 22 units PKG'd as ecom, should be retail | `pkg_scans` channel+label corrected, RTE scans voided, units reverted to `pending_rtd`, new retail print jobs queued |
| Flare Race Black variant error | 75 units INW'd (+ 1 qc_fail), wrong variant produced | INW scans voided, units set to `scrapped` — chassis to be restickered |
| `packed_dispatch` enum missing | `ALTER TYPE unit_status ADD VALUE` not yet run | Run: `ALTER TYPE unit_status ADD VALUE IF NOT EXISTS 'packed_dispatch'` |

### ✅ Fixed This Session (16 Apr 2026)

| Fix | Root cause | Resolution |
|---|---|---|
| Store issue detail modal transparent | `--surface` / `--surface2` not defined in `:root` | Replace with `--bg2` / `--bg3` in modal HTML (stores index) |
| Activity tab scans not loading | `+05:30` in raw URL string decoded as space by Postgres | Wrap timestamp values in `encodeURIComponent()` in `getAllScans` worker handler (line ~647) |
| Legacy unit integration | Old units have no LOT UPC, cannot enter new system | New `registerLegacyUnit` worker action + LEGACY_REG scanner station — 3 modes: dispatch/store/return |

### Pending Test

| Item | What to test |
|---|---|
| **PACK station — bulk mode** | Select shipment → open box → scan units → close → BOX label prints at dispatch printer |
| **PACK station — direct mode** | Select unit channel → scan unit → auto-close → PKG label reprints |
| **DOUT — box label** | Scan BOX-XXXXX → all units marked shipped |
| **Manifest enforcement** | Scan wrong product at PACK → hard block |
| **Line full block** | Pack all units for a line → next scan hard blocks |
| **Shipment ready flag** | Allocate enough units → READY badge appears in dashboard + scanner |
| **Dispatch print server** | Setup on laptop → print PKG_LABEL → print BOX_LABEL |
| **Shipment edit** | Edit manifest qtys and date → changes persist |
| **Cancel/delete shipment** | Cancel packing shipment → status=cancelled; delete draft → removed |

### Pending Build Items (prioritised)

**Next session:**
1. **Deploy worker** — `registerLegacyUnit` is built, not deployed
2. **Test LEGACY_REG station end-to-end** — dispatch mode first (batch session)
3. **Dispatch print server setup** — deploy on dispatch laptop, confirm both label types print
4. **LOT-00007572 resolution** — scrap or keep pending team decision
5. **FBU GRN for Flare LE** — 200 units received, need GRN before production scans
6. **Scanner setup screen: show active run** — show current product + run before LAUNCH
7. **Reorder requests — stock page entry** — raise from stock/inventory page
8. **Repair run design session** — design only, no code

**Store backlog:**
9. **Print PO** — PDF for emailing to vendor
10. **Price master module**
11. **Parts receiving reconciliation edge cases** — Short/Over/Damage in GRN raise flow
12. **Procurement approval gate** — wire threshold to PO status

**Dashboard backlog:**
13. **EAN sticker** — separate sticker at PKG station
14. **Info scan / test scanner mode**
15. **Dashboard day view for production**
16. **Consolidated dispatch view**

**General backlog:**
17. **Product entry frontend**
18. **Unicommerce integration**
19. **Legacy UPC manual entry**
20. **Reconciliation module** — Phase 4
21. **Audit module** — Phase 5
22. **Assembly stations** — Phase 6
23. **Dashboard tab RBAC**
24. **Google sign-in** — Supabase OAuth
25. **Biometric integration**
26. **APK rollout to all 15 devices**
27. **Dash/Nitro QR code problem**
28. **material_master name inconsistencies**
29. **Old para items with `(old)` suffix**
30. **PRODUCT_SUBVARIANTS for Nitro/Dash/Fang/Atlas**
31. **Dispatch calendar in system** — currently Google Sheets, deferred

---

## 12. Cycle Time Design

Same as v3.0. No changes this session.

---

## 13. Technical Decisions Log

| Decision | Choice | Reason |
|---|---|---|
| *(all prior decisions unchanged)* | — | See v3.0 |
| **`sbPublic` 204 handling** | `catch(e) { return { ok: res.ok, ... } }` not `ok: false` | PostgREST returns 204 No Content on successful DELETE/INSERT with Prefer:return=minimal. `JSON.parse("")` throws but the operation succeeded. ← April 15 2026 |
| **`activity_type` enum: PKG_OUT never written** | `PKG_OUT` scan writes `RTE` or `RTR` based on label suffix | Confirmed via live data query. Never use `PKG_OUT` as activity filter. ← April 15 2026 |
| **Dispatch shipment_no prefix: DSO-** | Changed from SHP- (used in procurement) | Avoids floor confusion between procurement shipments and dispatch outward shipments. Reset sequence to DSO-0001. ← April 15 2026 |
| **PACK direct mode for unit channels** | No shipment required; channel selector only | Unit fulfillment orders are ad-hoc (one unit per order); forcing a manifest would be impractical. Direct mode gives operational trackability without planning overhead. ← April 15 2026 |
| **PACK manifest enforced at scan time** | Hard block if product not in manifest OR line full | Prevents mis-packing; dispatch team must create shipment with correct manifest before packing begins. ← April 15 2026 |
| **DOUT accepts BOX- or batch label** | Auto-detected by regex — BOX-\d+ vs LOT-\d+-[ER] | Operator doesn't change behaviour; scan format determines path. One DOUT scan marks all units in a box as shipped. ← April 15 2026 |
| **Scanner: today-only shipments in PACK** | `status=packing` (any date) OR `status=draft AND scheduled_date=today` | Already-started shipments always show. Future-dated drafts are hidden until their day. Prevents accidental early packing. ← April 15 2026 |
| **`packed_dispatch` enum value** | Added to `unit_status` | Required before first PACK scan reaches floor — PostgREST rejects unknown enum values with 400. Always add enum values via SQL before deploying code that writes them. ← April 15 2026 |
| **Box label QR encodes box_ref** | `BOX-XXXXX` string | DOUT scanner reads this, looks up all units in box, marks all shipped atomically. Outer carton scan = entire contents exit. ← April 15 2026 |
| **`dispatch_boxes.shipment_id` nullable** | DROP NOT NULL ← April 15 2026 | Direct mode boxes have no shipment. Made nullable to support both modes with one table. |
| **Store history detail: cache rows in `window._shIssueRows`** | No extra API call — reuse already-fetched data | `getIssues` returns all line data; aggregate to header for table, cache raw for detail modal. ← April 15 2026 |
| **Scans tab: own date inputs** | `scanDateFrom`/`scanDateTo` replace global datebar | Global datebar is production-day-only; scans tab needs multi-day range for investigations. ← April 15 2026 |
| **Legacy unit identity** | EAN (13 digits) = SKU-level; legacy car QR (14+ digits) = EAN + unit suffix = unit-level | EAN detectable from box exterior; car QR only accessible when box open. Detection regex: `/^\d{13}$/` for EAN, `/^\d{14,}$/` for legacy car QR ← April 16 2026 |
| **Legacy starting statuses** | dispatch=`handed_over`, store=`inwarded`, return=`rto_in` | Reflects physical reality — dispatch units already handed over, store units re-enter at INW, returns enter at RTO_IN ← April 16 2026 |
| **Legacy UPC generation** | Max of `upc_pool.upc_id` and `units.upc`, increment | Legacy units bypass upc_pool entirely; checking both tables ensures no collisions ← April 16 2026 |
| **Remote lookup for legacy** | `component_type=remote AND linked_product_code=car.product_code` | Car rows have `linked_product_code=null`; remote rows point back to their car's product_code ← April 16 2026 |

---

## 14. Open Technical Questions

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
14. **Google sign-in:** Supabase Google OAuth. Decision pending.
15. **Takt time thresholds:** Currently ≤5m/≤10m/>10m. Arbitrary — calibrate per station per product.
16. **Dispatch Unicommerce link:** Integration design pending.
17. **SKD receive format:** Deferred.
18. **Procurement approval gate:** Not yet enforced on PO status.
19. **Print PO:** Designed; not built.
20. **Dispatch calendar in system:** Currently Google Sheets — deferred to next build.
21. **PACK: shipments with no scheduled date** — if team forgets to set date, shipment won't appear in scanner. Consider allowing override or showing undated drafts.

---

## 15. Environment Reference

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
