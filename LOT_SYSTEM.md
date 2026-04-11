# Legend of Toys — System Understanding Document
**Version:** 2.6 | **Last Updated:** April 2026
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Full production operations system live on floor. Store receiving system overhauled to box-first flow with PO-linked SKU intake, reconciliation panel, and box contents breakdown. FBU receiving fully designed and built. PO order type auto-detection live. Full dispatch system live.

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
- `FBU` (Fully Built Unit) — received as complete assembled product. Stock tracked at unit level in `fbu_stock`.
- `CKD` (Completely Knocked Down) — received as individual parts. GRN records component-level receipts.
- `SKD` (Semi Knocked Down) — partial BOM. Deferred.
- **Current data:** Dash + Nitro = FBU. All other cars = CKD. All remotes = CKD.
- PO-level override available on `store.po_lines.receive_format`.

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

## 6. Store Receiving System ← redesigned April 2026

### Mental model
**Box-first.** Staff open boxes one by one and record what's inside. The system reconciles against PO expectations.

### Flow
1. Create shipment (linked to PO) → SKU lines auto-populated from PO
2. Add shipping marks via RANGE entry (prefix + from + to + skip) or SINGLE
3. Click OPEN BOX on a mark → Box Intake form appears
4. Box Intake: pre-filled grid of expected SKUs with OK qty + Damaged qty inputs per row
5. Unexpected items via "+ ADD UNEXPECTED ITEM" escape hatch
6. Submit box → entries recorded, qty_counted aggregated per SKU
7. Repeat for each box
8. Reconciliation panel shows Matched / Short / Over / Has Damage / Pending per SKU
9. Box Contents panel shows per-mark SKU breakdown
10. Raise GRN → FBU path (fbu_grn_register + fbu_stock) or Parts path (grn_register + stock_ledger)

### Key rules
- **Short is derived:** expected − (OK + Damaged total). Never manually entered. Only meaningful at final reconciliation.
- **Split disposition:** OK qty + Damaged qty per SKU per box = two separate entries
- **Unexpected items:** flagged with UNEXPECTED badge in reconciliation, expected = 0
- **Parts and FBU:** same flow, different SKU label format

### Upcoming POs
Receiving list shows two sections: Active Shipments + Upcoming from POs. Upcoming = confirmed POs without a receiving shipment created yet. Click + CREATE SHIPMENT to pre-fill and open the new shipment form.

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

## 9. Print System

`print_jobs` table → Node.js print server (v2.2) on each PKG laptop → TSC TE244 thermal printer. Atomic claiming prevents duplicate prints. v2.2 deployed to L1 and L2. L3 not yet set up.

---

## 10. FBU vs CKD — Key Distinctions

| Aspect | CKD | FBU |
|---|---|---|
| Receiving | Part-by-part, bagged | Unit-level, no bagging |
| Stock ledger | `stock_ledger` (component level) | `fbu_stock` (unit level) |
| GRN | `grn_register` + `bulk_update_stock_received` | `fbu_grn_register` + `update_fbu_stock_received` |
| Issue | Issues from `stock_ledger` via BOM | Issues from `fbu_stock` at unit level |
| Work order | `issue_mode = 'components'` | `issue_mode = 'fbu'` |
| BOM at INW | Not needed — parts already issued | Not needed — car arrives assembled |
| Reconciliation | issued = produced × BOM + wastage | issued = INW units (1:1) |

---

## 11. Key Technical Learnings

- `unit_status` enum = lowercase; `activity_type` enum = uppercase — mixing caused live bugs
- Supabase row limit 5000 (changed from 1000)
- PKG_OUT scans always have `scans.line = 'SHARED'` — use pkg_scans.car_upc for line attribution
- `product_master` lookup must use `product_code` not product name
- `production_runs.id` in store schema is integer not UUID — UUID guard required
- Supabase Auth Admin API requires `SUPABASE_SERVICE_KEY` for both apikey + Authorization headers
- `element.style.display = ''` removes inline display property — use explicit value
- Worker GET vs POST routing — dashboard sends GET; scanner sends POST
- `body.data || body` pattern for all scanner POST actions
- **Cloudflare caches HTML for store.legendoftoys.com** — after deploying changes, purge Cloudflare cache (Caching → Purge Everything) or wait for TTL expiry. This has caused confusion multiple times.
- **receiving_entries.qty_counted recompute** — postBoxIntake recomputes qty_counted on parent receiving_lines after each submission. If values show null, check worker logs for update failure.

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
- All scanner flows: INW, QC_PASS, QC_FAIL, WKS, PKG, PKG_OUT, RTO_IN
- PKG label printing — TSC TE244 via Supabase polling v2.2
- Full Dispatch system — DTK/ALLOC/DOUT, 17 channels, dispatch dashboard
- Store receiving overhaul — box-first flow, mark range, box intake, reconciliation, box contents
- PO order type auto-detection
- Reporting tab v2, Takt time / throughput
- FBU/CKD/SKD schema

### 🚨 Critical Open Issue
**BOX CONTENTS panel invisible on store site** — element is in deployed file but browser returns null. Root cause: Cloudflare CDN serving stale HTML for `store.legendoftoys.com`. **Fix: Cloudflare dashboard → Caching → Purge Everything.** Must be first action next session.

### Pending Test 🧪
- FBU/CKD/SKD store frontend (issue_mode, fbu_stock, GRN paths)
- Receiving overhaul full end-to-end (mark range, box intake, reconciliation, GRN)
- Dispatch system on real units

### Pending Build 🔲
- Parts receiving box-first flow (same design as FBU, needs wiring)
- Repair run design session
- Google sign-in (OAuth)
- EAN sticker at PKG
- GRN receiving template
- Price master
- Reconciliation module (Phase 4)
- Audit module (Phase 5)
- Assembly stations (Phase 6)

---

## 14. Session Start Protocol

1. Ask Afshaan to share `LOT_BUILD.md`, `LOT_SYSTEM.md`, and relevant source files
2. Read all files before writing any code
3. **Check Open Issues first** — fix before building new features
4. Design before build

---

*Update at end of every significant build or design session.*
