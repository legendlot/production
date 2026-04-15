# Legend of Toys — System Understanding Document
**Version:** 3.1 | **Last Updated:** April 2026 (Session: 15 Apr 2026 — Part 2)
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Full production operations system live on floor. Dispatch expansion complete — full PACK stage added with shipment manifests, box management, and label printing. Dashboard dispatch restructured into three separate tabs. Store history now has issue detail view.

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

### Batch Label Format — LOCKED
`LOT-XXXXXXXX-E` (ecom) or `LOT-XXXXXXXX-R` (retail).

### Box Label Format — new April 15 2026
`BOX-XXXXX` — sequential, globally unique. Used for bulk outer cartons. QR-encoded on label. DOUT scans this to ship all units inside atomically.

### Activity type enum rule
`PKG_OUT` is **NEVER** an activity value in the `scans` table. The PKG_OUT scan writes `RTE` (ecom, -E label) or `RTR` (retail, -R label). Always filter by RTE/RTR, never PKG_OUT.

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
- PACK station: shipment mode + direct mode
- PKG label printing — TSC TE244 via Supabase polling v2.2
- Full Dispatch system — DTK/ALLOC/PACK/DOUT, 17 channels + Website
- Shipment manifests — product picker, manifest enforcement at PACK
- Dashboard: Overview/Shipments/Channel Master tabs, shipment detail modal, issue detail modal
- Scans tab date range filter
- Store receiving, procurement, GRN, bag system, FBU issue flow all live

### Pending Deployment ⚠️
- **Dispatch print server** — `dispatch-printserver.js` needs laptop + printer at dispatch table
- **`packed_dispatch` enum** — run `ALTER TYPE unit_status ADD VALUE IF NOT EXISTS 'packed_dispatch'` if not yet applied

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

### Fixed This Session 🔧
- `sbPublic` 204 false error → catch block fix
- Flare Burnout Green ecom→retail label correction (22 units)
- Flare Burnout Green RTE scans voided, units reverted to pending_rtd
- Flare Race Black 75 units INW voided, scrapped

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
