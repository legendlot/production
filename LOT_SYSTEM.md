# Legend of Toys — System Understanding Document
**Version:** 2.8 | **Last Updated:** April 2026 (Session: 13 Apr 2026)
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Full production operations system live on floor. Full procurement system rebuilt and live — category-first PO creation, redesigned header, reorder requests, vendor supplied items (product/part/category model). Scanner `deviceId` bug fixed (DTK/ALLOC/DOUT now working). INW `component_type` derivation fixed (no more remote stickers being classified as cars). Flare LE product added to system. Line attribution correction tooling used in production (Knox QC scans corrected L1→L2).

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

LOT currently makes RC cars. A finished RC car unit consists of: car body (UPC sticker on bottom), remote control (UPC sticker — independent), accessories, packaging tray + box, shrink wrap.

**SKUs:** 86+ active SKUs across ~28 products with multiple model/color variants.

**`has_remote` flag:** RC cars = true. Die cast, DIY sets = false.

**Receive format:**
- `FBU` (Fully Built Unit) — received as complete assembled product. Stock tracked at unit level in `fbu_stock`. Cars and remotes tracked separately via `component_type`.
- `CKD` (Completely Knocked Down) — received as individual parts. GRN records component-level receipts.
- `SKD` (Semi Knocked Down) — partial BOM. Deferred.
- **Current data:** Dash + Nitro = FBU. Flare LE = FBU. All other cars = CKD. All remotes = CKD.

**FBU remote tracking:** For `has_remote` FBU products, PO has one collapsed remote line per product (not per variant/color). Remotes arrive as product SKU, not variant-specific. Box intake shows separate Car and Remote rows with type badge.

### Products in system (April 2026)
- Flare, Ghost, Knox, Shadow, Nitro, Dash, Fang, Atlas, Bumble, Gazer, Alex (test)
- **Flare LE** ← new April 13 2026: product code `FE`, car SKU `FERK` (Race Black), remote `FEXXR`. FBU. UPC batch of 200 to be generated.

---

## 4. UPC System ✅ LOCKED

**Format:** `LOT-00000001` — 8 digits, zero-padded, globally sequential.
**Display Code:** `KNAK00000007` (product_code + raw 8-digit LOT sequence, no hyphens).
**QR encodes:** `LOT-` number (permanent, immutable).

**component_type at INW:** Derived from `product_master.component_type`. Fallback: product name contains "remote" → 'remote'. Never derived from product_code suffix (GHBR ends in R = Red color, not remote).

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
| PKG_OUT | Dispatch Out | Scans batch label — auto-routes RTE/RTR or RTD_RETURN |
| RTO_IN | Returns | Two paths: intact → direct RTD, damaged → full production flow |
| DTK | Dispatch Intake | Sets handed_over. Fast intake, no channel selection. |
| ALLOC | Dispatch Allocate | Channel dropdown (filtered by box type). Sets allocated. |
| DOUT | Dispatch Out | Hard rejects if not allocated. Sets shipped. |

### Batch Label Format — LOCKED
`LOT-XXXXXXXX-E` (ecom) or `LOT-XXXXXXXX-R` (retail).

### Line attribution
`pkg_scans.line` is the source of truth for dispatch attribution. PKG_OUT device is SHARED — `scans.line` for RTE/RTR is always 'SHARED' and unreliable.

---

## 6. Store Receiving System

### Mental model
**Box-first.** Staff open boxes one by one and record what's inside.

### Flow
1. Create shipment (linked to PO) → SKU lines auto-populated from PO
2. Add shipping marks via RANGE or SINGLE entry
3. OPEN BOX on a mark → Box Intake form
4. Box Intake: grid of expected SKUs with OK + Damaged inputs
   - FBU+has_remote products: separate Car (green badge) and Remote (blue badge) rows
   - Remote rows are product-level, not variant/color level (e.g. "Flare Remote ×240" not "Flare Track Pink Remote ×10")
5. Submit box → entries recorded, qty_counted aggregated per SKU
6. Reconciliation panel: Matched / Short / Over / Has Damage / Pending
7. Raise GRN → FBU path (fbu_grn_register + fbu_stock) or Parts path

---

## 7. Dispatch System

### Flow
```
PKG → pending_rtd → PKG_OUT → rtd → DTK → handed_over → ALLOC → allocated → DOUT → shipped
```

### Scanner fix ← April 13 2026
`cfg.deviceId` was never being stored. DTK/ALLOC/DOUT all read `cfg.deviceId` → always null → "Missing batch_label or device_id" error. Fixed: `saveSetup()` now stores `deviceId: device.id` in cfg. Operators must redo device setup once.

---

## 8. Returns System

### Return Categories
| Code | Name |
|---|---|
| UDR | Undamaged Return |
| CXR | Customer Return |
| BRV | Bulk Return — Vendor |

---

## 9. Procurement System ← rebuilt April 13 2026

### Page structure
```
Procurement
├── Dashboard        ← cards: pending requests, open POs, pending approval, arriving soon
├── Purchase Orders  ← category-first creation, redesigned order details header
├── Reorder Requests ← anyone raises, procurement guy reviews/converts/rejects
├── Vendors          ← supplied items: product/part/category (3 types, not 5 fields)
└── Forwarders
```

### PO creation flow
1. **Category** (8 cards) — FBU / CKD / Packaging / Metal / Electronics / Consumables / Para / Other
2. **Order Details** — auto-set strip (type · source · currency · incoterms, EDIT to override) + user fills (vendor · payment terms · delivery · port · notes)
3. **Line items** — mode set by category, not user

**FBU line items:**
- Step 1: Select product → format badge shows FBU (forced by category regardless of product default)
- Step 2: Enter car + remote qty per variant/color (remote auto-syncs to car qty)
- On ADD TO ORDER: car lines = per variant/color; remote lines = collapsed to one product-level line
- On submit: FBU category always submits as unit-level lines, never BOM-explodes

**CKD line items:** Product + variant → BOM explosion on submit.
**Manual (all others):** Part code search + qty rows.

### Vendor supplied items (3 types)
- **Product** — "Kai supplies Flare" — product dropdown
- **Part** — "Deng supplies Knox PCB" — searchable part code/name with current stock display
- **Category** — "Manvik supplies all Para" — fixed 8-category list

Auto-fill on PO: product-level match → category-level match → no auto-fill.

### Reorder requests
- **Anyone** can raise (all roles have `reorder_raise` permission)
- Two paths: By Part Code (shows current stock) or By Product (product/variant/color)
- Urgency: Normal / Urgent / Critical
- Procurement guy: reviews list, CONVERT → opens PO form pre-filled, REJECT → requires note
- Convert links RR to created PO (`converted_po_id` stamped on submit)

### Approval threshold
`store.settings` table, key `po_approval_threshold`. Null = self-approve always. Set by super_admin. UI indicator exists but approval gate not yet enforced.

---

## 10. Print System

`print_jobs` table → Node.js print server (v2.2) on each PKG laptop → TSC TE244 thermal printer. Atomic claiming prevents duplicate prints. v2.2 deployed to L1 and L2. L3 not yet set up.

---

## 11. Key Technical Learnings (don't repeat these mistakes)

- `unit_status` enum = lowercase; `activity_type` enum = uppercase
- Supabase row limit 5000 (changed from 1000)
- PKG_OUT scans always have `scans.line = 'SHARED'` — use pkg_scans.car_upc for line attribution
- `product_master` lookup must use `product_code` not product name
- Worker GET vs POST routing — dashboard sends GET; scanner sends POST
- `body.data || body` pattern for all scanner POST actions
- GitHub Pages caches aggressively — dummy commit forces CDN invalidation. No Cloudflare proxy on store site.
- **`component_type` at INW must come from `product_master.component_type`** — never from product_code suffix. GHBR ends in R = Red (color), not Remote. GHUKR is a remote whose product_code doesn't match PM (GHXXR). Always use `pm?.component_type` with fallback to product name containing "remote". ← April 13 2026
- **`cfg.deviceId` must be stored at `saveSetup` time** — not just `cfg.deviceCode`. DTK/ALLOC/DOUT read `cfg.deviceId`. Fixed April 13 2026.
- **FBU category must lock format** — `poCurrentCategory === 'fbu'` overrides `receiveFormatCache[product]` in grid display, format badge, and submission logic. CKD product defaults must not bleed through. ← April 13 2026
- **FBU remote lines are product-level** — one remote line per product (total qty), not per variant/color. Remotes arrive as product SKU. ← April 13 2026
- **RLS on new store tables** — when RLS is disabled (`relrowsecurity = false`), policies don't matter. Issue is grants. `GRANT ALL ON table TO service_role` is the fix. ← April 13 2026
- **`buildProductList` misses FBU products** — FBU-only products (no BOM entries) never appear in `materialCache` → not in PRODUCTS list → missing from all dropdowns. Fix: merge `PRODUCT_VARIANTS` keys into PRODUCTS after materialCache scan. ← April 13 2026
- **Wrong line attribution** — operators can set up devices on the wrong line. Prevention: show active production run on setup screen when line is selected. SQL correction: `UPDATE scans SET line = 'L2' WHERE ... product = 'Knox' AND line = 'L1' AND DATE(...) = CURRENT_DATE`. ← April 13 2026

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
- All scanner flows: INW (component_type fix), QC_PASS, QC_FAIL, WKS, PKG, PKG_OUT, RTO_IN
- PKG label printing — TSC TE244 via Supabase polling v2.2
- Full Dispatch system — DTK/ALLOC/DOUT (deviceId fix deployed), 17 channels, dispatch dashboard
- Store receiving — box-first flow, FBU Car/Remote badges, upcoming POs
- Procurement system — full rebuild: category-first PO, redesigned header, reorder requests, vendor supplied items (3-type model), procurement dashboard
- Reporting tab v2, Takt time / throughput, all dashboard tabs
- FBU/CKD/SKD schema + FBU remote tracking
- Flare LE product added (FERK + FEXXR in product_master, store frontend updated)

### Pending Test 🧪
- **DTK scan** — operator redoes setup after deviceId fix
- **INW component_type** — Ghost Burnout Red + remote stickers inward correctly
- **Procurement full flow** — FBU PO create → no BOM explosion → collapsed remote line
- **Reorder request flow** — raise → convert → PO linked
- **Vendor supplied items** — 3-type entry + auto-fill on PO
- **Setup screen active run** — shows product/run on line selection (not yet built — pending)
- **Flare LE UPC batch** — generate 200 stickers
- **FBU/CKD/SKD store frontend** — issue_mode, fbu_stock, GRN paths

### Open Issues 🔶
None currently.

### Pending Build 🔲
- **Scanner setup: show active run per line** — design agreed; not yet built
- **Reorder requests: stock page entry point** — procurement tab entry done; stock page pending
- **Procurement approval gate** — threshold in settings, enforcement not wired
- **Parts receiving box-first** — FBU done; CKD parts path needs wiring
- **Repair run design + build**
- **Google sign-in** — Supabase OAuth
- **EAN sticker at PKG**
- **Info scan / test scanner mode**
- **GRN receiving template**
- **Price master module**
- **Product entry frontend** ← noted April 13 2026
- Reconciliation (Phase 4), Audit (Phase 5), Assembly stations (Phase 6)
- Dashboard day view, Consolidated dispatch view
- Unicommerce integration, Biometric integration
- Dashboard RBAC, APK rollout, Dash/Nitro QR code problem

---

## 14. Session Start Protocol

1. Use filesystem access to read latest `LOT_BUILD.md` and `LOT_SYSTEM.md` from `/Users/afshaansiddiqui/Documents/Claude/01_Scanner/`
2. Read relevant source files from `01_Scanner`, `02_Dashboard`, `03_Store`, `04_Worker` before writing any code
3. Check Open Issues first — fix before building new features
4. Design before build

---

*Update at end of every significant build or design session.*
