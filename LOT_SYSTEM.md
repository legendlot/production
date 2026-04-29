# Legend of Toys — System Understanding Document
**Version:** 3.9 | **Last Updated:** April 2026 (Session: 30 Apr 2026)
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

> **Migration in planning:** A full migration of Garage and Redline into the Throttle React monorepo is underway. Planning documents are at `/Users/afshaansiddiqui/Documents/00_Claude/migration-kit/`. The live system described below is unchanged during the migration period.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Full production operations system live on floor. Repair run system complete. Google OAuth live on Redline + Garage with persistent sessions. Both systems migrated to new subdomains (redline.legendoftoys.com, garage.legendoftoys.com).

---

## 2. Organisation Structure

```
Afshaan (Co-founder)
├── Varun (Head of Production — owns Stores, Assembly, QC, Dispatch)
│   ├── Siddhant (Production Manager — owns assembly lines)
│   │   ├── Kishan (Line Manager — owns the lines day-to-day)
│   │   │   └── Line Leaders / Supervisors (per line)
│   │   └── Karthik (Inline QC Supervisor — across all 3 lines)
│   └── Reann (Temporary Store Manager — not production, reports to Varun)
└── Mahesh Reddy (External QC Auditor — reports to Afshaan directly)
```

**Mahesh's system access:** Read-only across all data. Enforced at worker level. Permanent constraint.

---

## 3. Products

LOT currently makes RC cars. A finished RC car unit consists of: car body, remote control, accessories, packaging tray + box, shrink wrap.

**SKUs:** 86+ active SKUs across ~28 products with multiple model/color variants.

**Receive formats:**
- `FBU` — received as complete assembled product. Stock at unit level in `fbu_stock`.
- `CKD` — received as individual parts. GRN records component-level receipts.
- `SKD` — deferred.
- **Current data:** Dash + Nitro + Flare LE = FBU. All other cars + all remotes = CKD.

### Products in system (April 2026)
Flare, Ghost, Knox, Shadow, Nitro, Dash, Fang, Atlas, Bumble, Gazer, Alex (test), **Flare LE** (FBU, Race Black only)

---

## 4. UPC System ✅ LOCKED

**Format:** `LOT-00000001` — 8 digits, zero-padded, globally sequential.
**Display Code:** `KNAK00000007` (product_code + raw 8-digit LOT sequence, no hyphens).
**QR encodes:** `LOT-` number (permanent, immutable).

**component_type at INW:** Derived from `product_master.component_type`. Fallback: product name contains "remote". Never from product_code suffix.

---

## 5. Physical Production Flow

```
Parts (Store) → Assembly → QC → Packaging → RTD → Dispatch
```

### Scan Points — LOCKED
| Scan Code | Stage | Notes |
|---|---|---|
| INW | End of Assembly | Single scan — car or remote independently |
| QC_PASS | End of QC | Two-scan if has_remote = true |
| QC_FAIL | End of QC | Single scan |
| WKS | Workshop | System-inferred direction |
| PKG | Packaging station | Two-scan (car + remote), prints batch label |
| PKG_OUT | Dispatch Out (prod) | Scans batch label — writes RTE or RTR to scans (never PKG_OUT) |
| RTO_IN | Returns | Two paths: intact → direct RTD, damaged → full production flow |
| DTK | Dispatch Intake | Sets handed_over |
| ALLOC | Dispatch Allocate | Channel dropdown. Sets allocated. |
| PACK | Dispatch Packing | Scans batch label into box. Sets packed_dispatch. ← new April 15 2026 |
| DOUT | Dispatch Out | Scans BOX label (bulk) or batch label (unit). Sets shipped. ← updated April 15 2026 |
| REPAIR | Repair Station | Per-line. Operator selects repair run + mode (START/PASS/SCRAP) then scans LOT UPC ← April 17 2026 |

**Car + remote co-update rule (April 30 2026):** After QC_PASS pairing, cars and remotes are physically inseparable. All dispatch stage transitions (DTK → handed_over, ALLOC → allocated, DOUT → shipped) now co-update the paired remote in the same handler. The remote UPC is identified via `paired_with=eq.{carUpc}&component_type=eq.remote`. This applies to both UNIT path (single scan) and BOX path (batch) in postDout.

### Repair Run System ← April 17 2026

**Flow:** Units accumulate in store (qc_fail, rto_in, scrapped_repair) → production manager creates repair run in Garage → assigns line + planned units by product/variant/color → operators scan at REPAIR station → REP_START (in_repair_run) → REP_PASS (repaired) → QC_PASS → PKG → RTD

**Key tables:** `repair_runs` (run shell), `repair_run_units` (scanner activity), `repair_run_lines` (planned units per variant)

**Garage (Store):** Production Runs page has FRESH/REPAIR toggle. Repair form: line + date + notes + product picker. Runs list shows both RUN-XXX and REP-XXX with type badge.

**Redline (Dashboard):** Repair nav group with Queue tab — aggregated view of repairable units by product/model/color/status.

**`hasRemote` rule:** All repair units have remotes regardless of product — picker always shows CAR + REMOTE columns.

### Batch Label Format — LOCKED
`LOT-XXXXXXXX-E` (ecom) or `LOT-XXXXXXXX-R` (retail).

### Box Label Format — new April 15 2026
`BOX-XXXXX` — sequential, globally unique. Used for bulk outer cartons. QR-encoded on label. DOUT scans this to ship all units inside atomically.

### Activity type enum rule
`PKG_OUT` is **NEVER** an activity value in the `scans` table. The PKG_OUT scan writes `RTE` (ecom, -E label) or `RTR` (retail, -R label). Always filter by RTE/RTR, never PKG_OUT.

### Legacy Unit Entry Points ← April 16 2026

Three modes via LEGACY_REG scanner station + `registerLegacyUnit` worker action:

| Mode | When | Scan | Starting status | Car QR stored? |
|---|---|---|---|---|
| DISPATCH | Batch session for ~1,500 dispatch units | EAN from box exterior | `handed_over` | No (inaccessible under shrink wrap) |
| STORE | On-touch as store units are handled | Legacy car QR from car base | `inwarded` | Yes |
| RETURN | At RTO_IN for legacy returns (ongoing) | EAN or legacy car QR | `rto_in` | Yes (if QR accessible) |

**Detection:** purely numeric scan at RTO_IN → auto-routes to legacy return flow. LOT- prefixed scans always go to existing flow.
**Dedup key:** `legacy_car_upc` — checked before creating any new unit in store/return modes.
**Remote pairing:** always synthetic (new LOT UPC), looked up via `linked_product_code` on product_master.
**`is_legacy = true`** on all three pillars — for dashboard filtering and flush-progress tracking.

---

## 6. Dispatch System ← expanded April 15 2026

### Full flow
```
PKG → pending_rtd → PKG_OUT → rtd → DTK → handed_over → ALLOC → allocated → PACK → packed_dispatch → DOUT → shipped
```

### Shipment planning
Shipments are created on the dashboard before packing begins. Each shipment has a manifest — product/variant/color level line items with target quantities. The PACK scanner enforces the manifest: blocks units not in manifest, blocks lines that are already full.

**Shipment statuses:** `draft → packing → shipped` (or `cancelled`)

**Ready-to-pack flag:** System automatically flags a shipment as ready when `pool_count (allocated units) >= expected_units`. Visible in dashboard and scanner dropdown.

**Shipment ID format:** `DSO-XXXX` — separate from procurement shipments (`SHP-XXXX`). Reset to DSO-0001.

### Two dispatch channels
- **Bulk** (`fulfillment_model = 'bulk'`): N units packed into big outer carton. PACK station: open box → scan units → close box → BOX label prints. DOUT scans BOX label → all units shipped.
- **Unit** (`fulfillment_model = 'unit'`): One unit per outer carton. PACK station auto-closes box after each scan and reprints PKG label for outer carton. DOUT scans batch label.

### PACK station modes
- **Shipment mode** — planned shipments with manifest enforcement. Works for bulk and planned unit shipments.
- **Direct mode** — unit channels only, no shipment required. Operator picks channel, scans units, each auto-creates/closes a box. For ad-hoc unit orders.

### Box management (dashboard)
- Click shipment → detail modal → see boxes + unit manifest
- Remove unit from box (dashboard only) — unit reverts to allocated
- Reopen packed box (dashboard)

### Dispatch print server
`dispatch-printserver.js` — not yet deployed. Polls `print_jobs WHERE line = 'DISPATCH'`. Handles:
- `PKG_LABEL` — reprint of original PKG label for unit outer carton
- `BOX_LABEL` — combined bulk outer carton label (channel, products, unit count, QR, shipment ref)

---

## 7. Store Receiving System

No changes from v3.0. See v3.0 for full details.

---

## 8. Procurement System

No changes from v3.0. See v3.0 for full details.

---

## 9. Dashboard ← updated April 15 2026

### Dispatch tabs (restructured)
Dispatch nav is now a dropdown with three separate full-content tabs:
- **Overview** — live status cards, allocated pool, sent-out chart, units table
- **Shipments** — shipments with manifest progress, ready badge, edit/cancel/delete
- **Channel Master** — channel CRUD

### Scans tab
Now has its own date range filter (From/To + presets: Today / This Week / This Month). No longer tied to global production datebar.

### Store history
Issue rows are now clickable — opens detail modal showing all part lines with BOM qty, actual issued, and variance per line.

---

## 10. Key Technical Learnings (don't repeat these mistakes)

- `unit_status` enum = lowercase; `activity_type` enum = uppercase
- `PKG_OUT` is NEVER an activity value — scanner writes `RTE` or `RTR`
- `packed_dispatch` must be added via `ALTER TYPE unit_status ADD VALUE` before first PACK scan
- Always add enum values via SQL BEFORE deploying code that writes them — PostgREST 400 otherwise
- `sbPublic` 204 responses: `JSON.parse("")` throws but operation succeeded. Catch block must use `ok: res.ok` not `ok: false`
- Supabase row limit 5000
- PKG_OUT scans always have `scans.line = 'SHARED'` — use pkg_scans.car_upc for line attribution
- Worker GET vs POST routing — dashboard sends GET; scanner sends POST
- `body.data || body` pattern for all scanner POST actions
- GitHub Pages caches aggressively — dummy commit forces CDN invalidation
- `component_type` at INW must come from `product_master.component_type` — never from product_code suffix
- `cfg.deviceId` must be stored at `saveSetup` time
- Every new store/public table needs explicit `GRANT ALL ON {table} TO service_role`
- Cloudflare Workers: 50 subrequest limit — never loop `await` per line
- Fixed-position modals must live at app root
- `dispatch_shipments.shipment_no` prefix: SHP- = procurement (old), DSO- = dispatch outward (new)
- PACK direct mode: gate `!packShipment` check with `packMode === 'shipment' &&` to avoid blocking direct mode
- `dispatch_boxes.shipment_id` is nullable — direct mode boxes have no shipment
- RPCs that count units must filter `component_type = 'car'` — cars and remotes both generate INW/QC_PASS scans causing double-counting otherwise
- `get_dispatch_counts` must use `current_status` filters, not cumulative scan counts — pipeline tiles must reflect current state not historical totals
- `generateUpcBatch` must check `MAX(units.upc)` in addition to `MAX(upc_pool)` and `MAX(upc_batches.upc_to)` — legacy units written directly to `units` bypass `upc_pool` entirely
- `upc_pool` valid statuses: generated, printed, received, available, applied, damaged, unused, voided — `voided` added April 24 2026
- Re-alloc at ALLOC is intentional (channel change) but same-channel re-scan must be blocked — check `dispatch_allocations.channel_id` before writing
- Concurrent scans (same second) can both pass status checks read at handler start — re-fetch critical status fields atomically just before writing for idempotency-sensitive handlers (PKG_OUT)
- `postFlush` in worker had `canFlush` defined but never called — always verify permission gates are actually invoked inside their case, not just defined at top of file
- **Async-gap double-scan race in scanner POST handlers** — any scanner action that does a uniqueness pre-check on the worker before writing will leak duplicates if the same barcode is scanned twice during the awaiting RTT (gun scanners fire fast). Two-layer fix is mandatory: (1) frontend in-flight boolean (`pkgInFlight`-style) set on entry, cleared in `finally` + every cancel/reset path, drops second scan silently; (2) worker catches Postgres `23505` on the unique-constraint insert and returns the same human-readable duplicate message the pre-check uses. PKG handler patched 29 Apr 2026 (`02_scanner` `6cd6832`, `01_worker` `c4f0183`); audit other scanner POSTs (e.g. INW, QC, WKS, RTO_IN) for the same shape — most have similar pre-check-then-insert structure
- `get_dispatch_counts` RPC is `component_type = 'car'` only — remote status drift is invisible on the dashboard. Remotes must be kept in sync via worker logic, not by relying on dashboard visibility.
- After QC_PASS, cars and remotes move as a pair for the rest of their lifecycle. Any handler that moves a car through dispatch stages must also move its paired remote in the same operation.
- All prior learnings — see v3.0

---

## 11. Print System

**Production:** `print_jobs` table → Node.js print server (v2.2) on each PKG laptop → TSC TE244. Deployed on L1 and L2. L3 not yet set up.

**Dispatch:** `dispatch-printserver.js` (v1.0) — same polling architecture, `line = 'DISPATCH'`. **Not yet deployed.** Handles `PKG_LABEL` + `BOX_LABEL` job types. `payload` JSONB column carries box label data.

---

## 12. Scale

| Month | Units/Month |
|---|---|
| Now | 20,000 |
| +6 | 50,000 |
| +12 | 124,000 |
| +18 | 308,000 |
| +24 | 763,000 |

---

## 13. Build Status

### Live & Confirmed ✅
- All scanner flows: INW, QC_PASS, QC_FAIL, WKS, PKG, PKG_OUT, RTO_IN, DTK, ALLOC, DOUT
- REPAIR station: REP_START (enters repair run), REP_PASS (marks repaired → proceeds to QC), REP_SCRAP (scraps unit)
- Repair run creation in Garage: FRESH/REPAIR toggle, product×variant×color picker, lines saved to `repair_run_lines`
- Repair Queue tab in Redline: aggregated repairable units by product/status
- Google OAuth on Redline + Garage — Supabase JS client, persistent localStorage sessions, legendoftoys.com domain restriction, auto-provision trigger
- Domain migration: Redline → redline.legendoftoys.com, Garage → garage.legendoftoys.com
- PNG favicons on Redline + Garage (fixes Safari address bar globe)
- PACK station: shipment mode + direct mode
- PKG label printing — TSC TE244 via Supabase polling v2.2
- Full Dispatch system — DTK/ALLOC/PACK/DOUT, 17 channels + Website
- Shipment manifests — product picker, manifest enforcement at PACK
- Dashboard: Overview/Shipments/Channel Master tabs, shipment detail modal, issue detail modal
- Scans tab date range filter
- Store receiving, procurement, GRN, bag system, FBU issue flow all live
- Dispatch remote co-update: postDtk, postAlloc, postDout (unit + box paths) now co-update paired remote on every car status change ← April 30 2026

### Pending Deployment ⚠️
- **Dispatch print server** — `dispatch-printserver.js` needs laptop + printer at dispatch table
- **`packed_dispatch` enum** — run `ALTER TYPE unit_status ADD VALUE IF NOT EXISTS 'packed_dispatch'` if not yet applied
- **`postFlush` canFlush gate** — only one of the original 24-Apr 4-pack still NOT in worker HEAD. Other three (`generateUpcBatch` units check, `postAlloc` same-channel guard, `postPkgOut` re-fetch) deployed live in `lotopsproxy` v `56c37241` on 29 Apr 2026
- **Favicon** — new `03_dashboard/favicon.png` generated (3 yellow + 2 red bars), push to repo

### Pending Test 🧪
- PACK bulk flow end-to-end (open box → scan → close → BOX label)
- PACK direct mode (unit channel, no shipment)
- DOUT with BOX label
- Manifest enforcement (wrong product hard block, line full hard block)
- Shipment ready flag
- Shipment edit flow
- Cancel/delete shipment

### Open Issues 🔶
- LOT-00007572 (Flare Race Black, qc_fail) — team checking whether to scrap with the 75
- Dispatch print server not yet deployed
- Line Flush Unauthorised for Anusha — `postFlush` `canFlush` gate still missing inside the case block (function defined, never called). **Diagnosis complete: Anusha role = admin, issue was expired JWT. Immediate fix: log out + log back in. Permanent: add canFlush invocation to handler and deploy.**
- L3 reprint not working — investigation pending

### Fixed This Session 🔧 (30 Apr 2026)
- **Dispatch funnel DB cleanup** — physical stock-take of 6,355 batch labels used as ground truth. Marked 2,723 cars + 6,042 remotes shipped. Synced 3,941 remotes to correct intermediate stages. All cars in L3 29-Apr IST (82 rtd + 3 pending_rtd) preserved. Audit trail via updated_at::date = 2026-04-30.
- **Worker: remote co-update on all dispatch handlers** — postDtk, postAlloc, postDout unit + box paths. Cars and remotes now move together through all dispatch stages.

### Fixed This Session 🔧 (29 Apr 2026)
- **PKG double-scan race condition** → operator surface no longer dumps raw Postgres 23505 JSON when a gun scanner fires twice on the same car barcode. Two-layer fix: scanner `pkgInFlight` flag (`02_scanner` `6cd6832`) + worker `pkg_scans_batch_label_key` 23505 catch (`01_worker` `c4f0183`). Both layers needed because GH Pages cache delays the scanner update reaching operator devices
- **3 prior-pending worker fixes shipped in same deploy** — `npx wrangler deploy` on 29 Apr swept HEAD, which carried `1c5cd24` (`generateUpcBatch` units check), `48787b1` (`postAlloc` same-channel guard), `e1446d9` (`postPkgOut` race re-fetch). All now live in `lotopsproxy` version `56c37241-209e-4567-ae14-6b1cea9b46c6`

### Fixed This Session 🔧 (24 Apr 2026)
- `get_dispatch_counts` RPC inflated PKG Out tile (6596 vs actual 2102) → replaced with correct current_status filter
- Redline favicon all yellow → regenerated with 3 yellow + 2 red bars
- ALLOC same-channel duplicate scan accepted → same-channel guard added to `postAlloc` (deployed 29 Apr)
- First Cry channel `fulfillment_model` corrected to `unit` via SQL
- Legacy UPC ↔ upc_pool collision (2593 conflicts) → voided conflicted pool entries, worker fix written (deployed 29 Apr)

### Fixed This Session 🔧 (prior)

### Pending Build 🔲
See LOT_BUILD.md §11 Pending Build Items for full prioritised list.

---

## 14. Session Start Protocol

1. Use filesystem access to read latest `LOT_BUILD.md` and `LOT_SYSTEM.md` from `/Users/afshaansiddiqui/Documents/Claude/01_Scanner/`
2. Read relevant source files from `01_Scanner`, `02_Dashboard`, `03_Store`, `04_Worker` before writing any code
3. Check Open Issues first — fix before building new features
4. Design before build

---

*Update at end of every significant build or design session.*
