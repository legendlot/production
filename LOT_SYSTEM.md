# Legend of Toys — System Understanding Document
**Version:** 2.9 | **Last Updated:** April 2026 (Session: 14 Apr 2026)
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Full production operations system live on floor. Procurement system live with FBU and CKD PO flows fully rebuilt. CKD PO now uses multi-product queue with live BOM explosion, grouped editable results, and correct car/remote separation. Receiving system live with full box-first flow, bag generation (append-only, QR labels), and GRN raise updating stock. Cloudflare subrequest limit issues resolved across postShipment, postBoxIntake, and raiseGRNFromReceiving. GRN detail modal built and accessible from any page.

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

### PO creation flow — FBU
1. **Category** (8 cards) — FBU sets Product type, China, RMB, FOB automatically
2. **SELECT PRODUCT** panel (first) — product dropdown + format badge
3. **Order Details** — Vendor + Payment Terms (Expected Delivery removed; calculated from Shipping Timeline)
4. **Line Items** — variant qty grid with Car + Remote columns; ADD TO ORDER queues lines; remote collapses to product-level
5. **Shipping Timeline** — calculates expected arrival; auto-sets hidden `po-delivery` field

### PO creation flow — CKD
1. **Category** — CKD sets Component type, China, RMB, FOB
2. **SELECT PRODUCTS** panel (first) — multi-product queue; each product expands into variant grid
   - Car qty + Remote qty per variant; Remote defaults to mirror Car
   - Remote qty is product-level in explosion (sum across all variants)
   - FBU-only products excluded: Dash, Nitro, Flare LE
3. **EXPLODE BOM** — calls `calcKit` per variant (car parts) + per product (remote parts); merges by part_code
4. **Order Details** — Vendor + Payment Terms
5. **LINE ITEMS** — explosion result grouped by category; Packaging + Para off by default; editable qtys; RE-SELECT resets
6. **Shipping Timeline**

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

## 10. Bag System ← new April 14 2026

Bags are physical bags that parts are sorted into during receiving. Each bag gets a unique label scanned at store-to-production issue time.

**Flow:** Count parts into box → submit box → reconciliation shows qty_counted → GEN bags (per line or all) → print labels → seal bags.

**Bag size:** Default from `material_current.bag_size` per part. Editable per receiving line before generation. New size applies to future bags only; existing sealed bags unchanged.

**Append logic:** Generation is append-only. Day 2 more qty arrives → GEN creates only new bags starting from next seq; updates `total_bags` on all prior bags for that line.

**Multiple bag sizes per part across shipments:** Fully supported. Each bag carries its own qty. No conflict between a bag of 25 from shipment A and bag of 50 from shipment B.

**Label:** LOT logo + part code + shipment ID | part name + qty (large) + bag X of Y | QR code (encodes `bag_id`) | unique bag ID + date footer.

**Bag ID format:** `BAG-{part_code}-{line_id_last6}-{seq}` — globally unique.

**Issue flow (future):** Store team scans `bag_id` QR at issue time to consume the bag against a work order.

---

## 11. Key Technical Learnings (don't repeat these mistakes)

## 12. Print System

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
- **Fixed-position modals must live at app root** — `position:fixed` inside `display:none` parent = invisible. All modals go directly inside `#app`, outside all view sections. ← April 14 2026
- **`closing_stock` is a generated column** — `stock_ledger.closing_stock` cannot be updated directly. Reverse received stock by subtracting from `total_received`. Formula: `opening_stock + total_received - total_issued + returned`. ← April 14 2026
- **Cloudflare Workers: 50 subrequest limit** — never loop `await` per line inside a handler. Use `batchNextSeq`, single-array INSERT, `IN` filter for batch updates, RPCs for aggregates. Always count subrequests before deploying a new handler that iterates over PO/shipment lines. ← April 14 2026
- **`grn_register.product` is blank for HW/UNV parts** — these parts have no product linkage in `receiving_lines`. Must derive from `bom_current` at GRN creation time by looking up each part_code. ← April 14 2026

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
- Store receiving — box-first flow, FBU Car/Remote badges, CKD parts flow, bag generation + labels
- Procurement system — FBU PO (product-first layout), CKD PO (multi-product queue + BOM explosion), reorder requests, vendor supplied items, procurement dashboard
- Reporting tab v2, Takt time / throughput, all dashboard tabs
- FBU/CKD/SKD schema + FBU remote tracking
- Flare LE product added
- GRN system — raises stock correctly, detail modal, product shown correctly

### Pending Test 🧪
- **CKD PO full flow** — create → explode → submit → PO lines correct
- **Receiving against CKD PO** — shipment → boxes → reconciliation → GRN → stock updated
- **Bag generation** — GEN per line + GEN ALL + PRINT; append on day 2
- **GRN detail modal** — click row → opens with correct lines
- **FBU PO form** — product-first, no expected delivery, shipping timeline auto-calc
- **Dispatch DTK fix** — operator redoes setup → DTK scan succeeds
- **INW component_type** — Ghost Burnout Red + remote inward correctly

### Open Issues 🔶
None currently.

### Pending Build 🔲
- **Scanner setup: show active run per line**
- **Reorder requests: stock page entry point**
- **Procurement approval gate**
- **Repair run design + build**
- **Google sign-in** — Supabase OAuth
- **EAN sticker at PKG**
- **Info scan / test scanner mode**
- **Price master module**
- **Product entry frontend**
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
