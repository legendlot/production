# Legend of Toys — System Understanding Document
**Version:** 2.7 | **Last Updated:** April 2026
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Full production operations system live on floor. Store receiving system live with box-first flow. FBU remote tracking built into PO, receiving, and GRN. Procurement redesign partially built — category picker deployed but product filtering incomplete; needs design session before continuing.

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

**SKUs:** 86 active SKUs across ~28 products with multiple model/color variants.

**`has_remote` flag:** RC cars = true. Die cast, DIY sets = false.

**Receive format (April 2026):**
- `FBU` (Fully Built Unit) — received as complete assembled product. Stock tracked at unit level in `fbu_stock`. Cars and remotes tracked separately via `component_type`.
- `CKD` (Completely Knocked Down) — received as individual parts. GRN records component-level receipts.
- `SKD` (Semi Knocked Down) — partial BOM. Deferred.
- **Current data:** Dash + Nitro = FBU. All other cars = CKD. All remotes = CKD.
- PO-level override available on `store.po_lines.receive_format`.

**FBU remote tracking:** For `has_remote` products ordered as FBU, cars and remotes are tracked as separate line items through the entire PO → receiving → GRN → fbu_stock flow. `component_type = 'car'` or `'remote'` on `po_lines`, `receiving_lines`, `fbu_grn_register`, and `fbu_stock`. This was added April 2026 after a real shortfall incident where remote counts were wrong but went undetected.

---

## 4. UPC System ✅ LOCKED

**Format:** `LOT-00000001` — 8 digits, zero-padded, globally sequential.
**Display Code:** `KNAK00000007` (product_code + raw 8-digit LOT sequence, no hyphens).
**QR encodes:** `LOT-` number (permanent, immutable).

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
| QC_FAIL | End of QC | Single scan — whichever component failed |
| WKS | Workshop | System-inferred direction |
| PKG | Packaging station | Two-scan (car + remote), prints batch label |
| PKG_OUT | Dispatch Out | Scans batch label — auto-routes RTE/RTR or RTD_RETURN |
| RTO_IN | Returns | Two paths: intact → direct RTD, damaged → full production flow |
| DTK | Dispatch Intake | Sets handed_over. Fast intake, no channel selection. |
| ALLOC | Dispatch Allocate | Channel dropdown (filtered by box type). Sets allocated. |
| DOUT | Dispatch Out | Hard rejects if not allocated. Sets shipped. |

### Batch Label Format — LOCKED
`LOT-XXXXXXXX-E` (ecom) or `LOT-XXXXXXXX-R` (retail).

---

## 6. Store Receiving System

### Mental model
**Box-first.** Staff open boxes one by one and record what's inside. The system reconciles against PO expectations.

### Flow
1. Create shipment (linked to PO) → SKU lines auto-populated from PO
2. Add shipping marks via RANGE entry (prefix + from + to + skip) or SINGLE
3. Click OPEN BOX on a mark → Box Intake form appears
4. Box Intake: pre-filled grid of expected SKUs with OK qty + Damaged qty inputs per row. For FBU+has_remote products, grid shows separate Car and Remote rows.
5. Unexpected items via "+ ADD UNEXPECTED ITEM" escape hatch
6. Submit box → entries recorded, qty_counted aggregated per SKU
7. Repeat for each box
8. Reconciliation panel shows Matched / Short / Over / Has Damage / Pending per SKU
9. Box Contents panel shows per-mark SKU breakdown
10. Raise GRN → FBU path (fbu_grn_register + fbu_stock, per component_type) or Parts path (grn_register + stock_ledger)

### Key rules
- **Short is derived:** expected − (OK + Damaged total). Never manually entered.
- **Split disposition:** OK qty + Damaged qty per SKU per box = two separate entries
- **Unexpected items:** flagged with UNEXPECTED badge in reconciliation, expected = 0
- **FBU remote lines:** named "Product Variant Color — Car" and "Product Variant Color — Remote" for clarity

---

## 7. Dispatch System

### Flow
```
PKG → pending_rtd → PKG_OUT → rtd → DTK → handed_over → ALLOC → allocated → DOUT → shipped
```

### Key constraints
- Ecom box (-E) → ecom channels only at ALLOC
- Retail box (-R) → retail/other channels only
- DOUT hard rejects if not allocated
- PKG_OUT line attribution via pkg_scans.line (not scans.line which is SHARED)

---

## 8. Returns System

### Return Categories
| Code | Name |
|---|---|
| UDR | Undamaged Return |
| CXR | Customer Return |
| BRV | Bulk Return — Vendor |

### Flow
Store receives → intake → inspection → disposition → handover to production → PKG_OUT RTD_RETURN (intact) or repair (damaged)

---

## 9. Procurement System

### Context
Procurement tab covers 8 use cases: FBU (China), CKD components (China/India), Packaging (India), Metal Parts (India), Electronics/Batteries (India), Consumables/Screws (India), Para (India), Custom/Other.

One PO always goes to one vendor. Print PO functionality planned (PDF for email to vendor).

### Category-first PO creation (partial — April 2026)
NEW PO form starts with a category picker (8 cards). Selecting a category auto-fills order type, source, currency, incoterms, and sets the correct line entry mode. Mode buttons hidden.

**FBU:** BY UNITS mode → Car + Remote qty per variant/color. Remote defaults to car qty.
**CKD:** FROM BOM mode → BOM explosion on save.
**All others:** MANUAL mode → part code search + qty rows.

**⚠️ Current limitation:** After category selection, product list is not yet filtered to category-appropriate products. FBU mode still shows CKD products and CKD/FBU toggle. Needs design + fix next session.

### Vendor supplied items (April 2026)
Vendors can be tagged with the products/categories they supply. Used to auto-fill vendor field when product is selected in PO creation — one match auto-fills, multiple match shows picker.

### Remote tracking in procurement
- **FBU:** Cars and remotes are separate po_lines rows (`component_type = 'car'` / `'remote'`). Tracked independently through receiving and GRN.
- **CKD:** Remotes are part of the BOM — ordered as components, not as units. No separate line needed.
- Vendor can supply car and remote from the same factory (99% of FBU cases) or from different vendors (e.g. Knox remote plastic from a separate vendor).

---

## 10. Print System

`print_jobs` table → Node.js print server (v2.2) on each PKG laptop → TSC TE244 thermal printer. Atomic claiming prevents duplicate prints. v2.2 deployed to L1 and L2. L3 not yet set up.

---

## 11. FBU vs CKD — Key Distinctions

| Aspect | CKD | FBU |
|---|---|---|
| Receiving | Part-by-part, bagged | Unit-level, no bagging |
| Stock ledger | `stock_ledger` (component level) | `fbu_stock` (unit level, per component_type) |
| GRN | `grn_register` + `bulk_update_stock_received` | `fbu_grn_register` + `update_fbu_stock_received` |
| Issue | Issues from `stock_ledger` via BOM | Issues from `fbu_stock` at unit level |
| Work order | `issue_mode = 'components'` | `issue_mode = 'fbu'` |
| BOM at INW | Not needed — parts already issued | Not needed — car arrives assembled |
| Remote in PO | Part of BOM — single component line | Separate unit-level line with component_type |
| Reconciliation | issued = produced × BOM + wastage | issued = INW units (1:1) |

---

## 12. Key Technical Learnings

- `unit_status` enum = lowercase; `activity_type` enum = uppercase — mixing caused live bugs
- Supabase row limit 5000 (changed from 1000)
- PKG_OUT scans always have `scans.line = 'SHARED'` — use pkg_scans.car_upc for line attribution
- `product_master` lookup must use `product_code` not product name
- `production_runs.id` in store schema is integer not UUID — UUID guard required
- Supabase Auth Admin API requires `SUPABASE_SERVICE_KEY` for both apikey + Authorization headers
- `element.style.display = ''` removes inline display property — use explicit value
- Worker GET vs POST routing — dashboard sends GET; scanner sends POST
- `body.data || body` pattern for all scanner POST actions
- **GitHub Pages caches aggressively** — HTML structural changes don't propagate until Pages CDN invalidates. Fix: make a trivial commit (add a space) to force a new deployment. There is NO Cloudflare proxy on the store site — it is served directly by GitHub Pages.
- **receiving_entries.qty_counted recompute** — postBoxIntake recomputes qty_counted on parent receiving_lines after each submission.
- **update_fbu_stock_received RPC** — now includes `p_component_type TEXT DEFAULT 'car'` parameter. Upsert key is `(product, variant, color, component_type)`.
- **HAS_REMOTE fallback** — frontend has `HAS_REMOTE = new Set(['Dash','Nitro'])` as fallback. Primary source is `productMetaCache[product]?.has_remote` loaded from `getProductMeta` endpoint at PO form open.

---

## 13. Scale

| Month | Units/Month |
|---|---|
| Now | 20,000 |
| +6 | 50,000 |
| +12 | 124,000 |
| +18 | 308,000 |
| +24 | 763,000 |

---

## 14. Build Status

### Live & Confirmed ✅
- All scanner flows: INW, QC_PASS, QC_FAIL, WKS, PKG, PKG_OUT, RTO_IN
- PKG label printing — TSC TE244 via Supabase polling v2.2
- Full Dispatch system — DTK/ALLOC/DOUT, 17 channels, dispatch dashboard
- Store receiving overhaul — box-first flow, mark range, box intake OK/Damaged split, reconciliation, box contents panel, upcoming POs
- PO order type auto-detection
- Reporting tab v2, Takt time / throughput
- FBU/CKD/SKD schema
- FBU remote tracking — component_type through full PO→receiving→GRN→fbu_stock flow ← April 2026
- Procurement category picker + vendor supplied items ← April 2026 (incomplete)

### 🚨 Critical Open Issue
**Procurement redesign incomplete** — category picker deployed but FBU selection still shows all products + CKD toggle in line items. Must design before building further. Key question: how does the UI distinguish FBU (unit-level remotes) from CKD (BOM-level remotes) clearly for the procurement user?

### Pending Test 🧪
- FBU/CKD/SKD store frontend (issue_mode, fbu_stock, GRN paths)
- FBU remote tracking end-to-end (Dash PO → shipment → box intake → GRN → fbu_stock car + remote rows)
- Receiving overhaul full end-to-end (mark range, box intake, reconciliation, GRN)
- Dispatch system on real units

### Pending Build 🔲
- Procurement redesign fix (design session first)
- Parts receiving box-first flow
- Reorder requests UI (schema done)
- Print PO (PDF)
- Repair run design + build
- Google sign-in (OAuth)
- EAN sticker at PKG
- GRN receiving template
- Price master
- Reconciliation module (Phase 4)
- Audit module (Phase 5)
- Assembly stations (Phase 6)

---

## 15. Session Start Protocol

1. Ask Afshaan to share `LOT_BUILD.md`, `LOT_SYSTEM.md`, and relevant source files
2. Read all files before writing any code
3. **Check Open Issues first** — fix before building new features
4. Design before build — especially for procurement which has an open design question

---

*Update at end of every significant build or design session.*
