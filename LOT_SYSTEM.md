# Legend of Toys — System Understanding Document
**Version:** 1.2 | **Last Updated:** April 2026
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth. At this trajectory, the system must be designed to handle ~120,000 units/month by end of Year 1 and potentially 700,000+ units/month by end of Year 2.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Google Sheets + Apps Script replaced. New integrated production operations system on Supabase + Cloudflare Workers + PWA is live and scanning.

---

## 2. Organisation Structure

```
Afshaan (Co-founder)
├── Varun (Head of Production — owns Stores, Assembly, QC, Dispatch)
│   ├── Siddhant (Production Manager — owns assembly lines)
│   │   ├── Kishan (Line Manager — owns the lines day-to-day)
│   │   │   └── Line Leaders / Supervisors (per line)
│   │   └── Karthik (Inline QC Supervisor — across all 3 lines)
│   └── Reann (Temporary Store Manager — not production, reports to Varun until proper hire)
└── Mahesh Reddy (External QC Auditor — reports to Afshaan directly)
```

**Why Mahesh reports to Afshaan and not Varun:** Deliberate structural decision. Mahesh cross-references inline QC data (Karthik's data) against actual audit findings. If he reported to Varun, there would be mixed incentives. His scope is expanding to cover stores and dispatch as well.

**Mahesh's system access:** Read-only across all data. Cannot amend, void, or modify any record — ever. This is a **permanent system constraint**, not a temporary restriction. Enforced at worker level.

**Operator profile:** Unskilled workers on the factory floor. Do not understand systems. Will make frequent, basic mistakes even after training. Any interface they touch must be **descriptive, visual, and foolproof** — assume the worst-case user at all times.

---

## 3. Products

LOT currently makes RC cars. Future product categories include die cast and DIY sets (no remotes). A finished RC car unit consists of:
- The car body (UPC sticker on the bottom)
- Remote control (UPC sticker — independent from car)
- Accessories (varies by SKU)
- Packaging tray + box (retail box or ecom box — physically different)
- Shrink wrap (applied by machine at end of packaging)

**SKUs:** 86 active SKUs across ~28 products with multiple model/color variants.

**`has_remote` flag:** Product master carries a boolean. RC cars = true. Die cast, DIY sets, future non-remote products = false. Drives scanner behaviour at QC_PASS (single scan vs two-scan pairing flow).

---

## 4. UPC System ✅ LOCKED

### What UPC is
A ~1.5cm × 1.5cm QR code sticker (metallic/shiny finish). Sent for external printing on A3 sheets. Applied to the bottom of the car / body of the remote. Primary tracking identifier throughout the production system.

### Format — LOCKED
**`LOT-00000001`** — 8 digits, zero-padded, globally sequential. One counter for all products. QR encodes this string. Never reassigned, never reused, never deleted.

### Display Code — LOCKED
Human-readable code printed below the QR: **`KNAK-7`** (product_code + per-product sequence number).

- Product code: 4-char for cars (KNAK), 5-char for remotes (KNAKR = car code + R)
- Per-product sequence: independent counter per product_code — KNAK-1, KNAK-2... completely separate from FLBG-1, FLBG-2...
- Sticker shows only the display code — no LOT- number printed. QR still encodes LOT-XXXXXXXX.

```
[QR CODE]
KNAK-7
```

The QR is the permanent anchor. The display code lets operators identify stickers and match system error messages like "Wrong remote — expected KNAKR-7" to physical stickers.

### Product Code System ✅ LOCKED
**Formula:** PP+M+C (2-char product + 1-char model + 1-char color)

**Color key:** K=Black, B=Blue, G=Green, R=Red, W=White, E=Grey, S=Silver, P=Purple, V=Purple2, M=Multi, N=Pink, X=Excavator, Y=Yellow, O=Orange, D=Desert

**Remote:** car code + 'R'. If car is KNAK (Knox Adventure Black), remote is KNAKR.

All 86 SKUs have unique 4-char codes seeded in `product_master.product_code`.

### Print Batch Workflow
1. Admin generates batch in dashboard — picks product + component (car/remote) + quantity
2. System assigns LOT- numbers + display codes, creates batch record
3. Print file sent to external printer (A3, 16×23 grid = 368 stickers/sheet)
4. Stickers received → admin clicks Received → all UPCs flip from `generated` → `available`
5. Stickers applied on floor → each UPC marked `applied` when scanned at INW
6. Damaged/unused stickers individually voided — never deleted, never reassigned

**Status lifecycle:** `generated` → `available` → `applied` | `damaged` | `unused`

---

## 5. Physical Production Flow

### High-Level Flow
```
Parts (Store) → Assembly (car + remote on same line) → QC → Packaging → RTE/RTR → Dispatch
```

### Scan Points — LOCKED
| Scan Code | Stage | Devices | Notes |
|---|---|---|---|
| INW | End of Assembly | 3 (one per line) | Single scan — car or remote independently |
| QC_PASS | End of QC | 3 (one per line) | Two-scan flow if has_remote = true; pairing created here |
| QC_FAIL | End of QC | 3 (one per line) | Single scan — whichever component failed |
| WKS_IN | Workshop Entry | 1 shared | |
| WKS_OUT | Workshop Exit | 1 shared | |
| RTE | End of Packaging (ecom) | 1 shared | Scans batch label, not UPC |
| RTR | End of Packaging (retail) | 1 shared | Scans batch label, not UPC |
| RTD | Dispatch | — | Calculated: RTE + RTR. Not a physical scan. |
| RTO_IN | Returns | 1 shared | Scans UPC or EAN |

### Detailed Unit Journey

**Stage 1: Assembly → INW**
- Car assembled, UPC sticker applied. Remote assembled (last station same line), UPC sticker applied.
- Each scanned independently at INW. System identifies car vs remote from `upc_pool.product_code`.

**Stage 2: QC**
- *has_remote = true:* Operator scans car → green prompt "📡 NOW SCAN REMOTE" → scans remote → pairing created at QC_PASS. Failed component goes to QC_FAIL independently.
- *has_remote = false:* Single scan only.

**Stage 3: Workshop (if QC_FAIL)**
- WKS_IN → repair → WKS_OUT → re-enters QC. Loop count incremented.

**Stage 4: Packaging**
- Operator scans car UPC + remote UPC — system verifies they are a confirmed pair.
- Shrink wrap → RTE or RTR scan on batch label.

**Stage 5: Dispatch**
- RTD ownership transfers to dispatch team → Unicommerce (manual handoff currently).

**Stage 6: Returns (RTO_IN)**
- Market returns re-enter via RTO_IN. Traceability to original run maintained.

---

## 6. Devices & Station Mapping ✅ LOCKED

Each device is permanently associated with one station and one activity. The station IS the lock — operators cannot change scan type on a configured device.

| Device Code | Station | Activity | Line |
|---|---|---|---|
| INW-L1/L2/L3 | INW | INW | L1/L2/L3 |
| QCP-L1/L2/L3 | QC_PASS | QC_PASS | L1/L2/L3 |
| QCF-L1/L2/L3 | QC_FAIL | QC_FAIL | L1/L2/L3 |
| WKS | WKS | WKS (modal per scan) | SHARED |
| PKG | PKG | PKG | SHARED |
| RTD | RTD | RTD | SHARED |
| RTO | RTO_IN | RTO_IN | SHARED |

Scanner setup screen: operator selects Station + Line (2 taps). Device code auto-resolved from DB. No manual entry of any kind.

---

## 7. Reporting & Dashboard ✅ LIVE

**URL:** `dashboard.legendoftoys.com`

**All production tools and white-collar-facing interfaces live here.** The scanner (`scanner.legendoftoys.com`) is for factory floor devices only.

### Dashboard Tabs
| Tab | Users | Content |
|---|---|---|
| Executive | Afshaan, Vinay, Varun | Today KPIs, hourly output, open runs |
| Lines | Siddhant, Kishan | Per-line cards, operator stats |
| QC | Karthik, Mahesh | FPY by line, defect heatmap, repeat failures |
| Runs | All production_view | Plan vs actual |
| UPC Generator | Afshaan, Varun | Generate batches, print stickers, receive |

Auto-refresh: 30 seconds. Real-time for all live tabs.

---

## 8. Scan Correction — Three Tiers ✅ DESIGNED

**Tier 1 — Operator (self-serve, no approval)**
- Available until unit receives its next scan in the flow
- `voided = true` on scan row, logged automatically

**Tier 2 — Supervisor within shift (Kishan / Karthik)**
- Can void any scan from the current shift
- Mandatory reason required
- Kishan: line scans; Karthik: QC scans

**Tier 3 — Post-shift amendment (Siddhant / Varun / Afshaan)**
- Can amend any scan after shift close
- Mandatory reason, immutable audit trail in `scan_amendments`

**Mahesh:** Read-only. No amendment access at any tier, ever.

### Foolproofing in Place
- Display code shown after every INW scan (KNAK-7)
- Operator logs in via QR card only (no typing)
- Device station = locked activity (no choice exposed to operator)
- Duplicate scans blocked in real time with DUPLICATE SCAN screen
- Batch not received → NOT AVAILABLE screen with clear explanation

---

## 9. Rework & Reverse Flow Scenarios

**Core principle:** Original records preserved. Rework captured as distinct event. Final state drives reports. Lineage always queryable.

### Scenario A: Variant Rework
**Example:** Flare Burnout Grey → convert to Flare Race Grey.
- Original unit marked `converted`; rework order captures BOM delta
- **Open question:** keep original UPC or issue new one?

### Scenario B: Post-Dispatch Part Replacement
**Example:** Knox motor defect — finished stock needs rework.
- RTD stock: pulled back via internal rework order
- Post-dispatch: RTO_IN → rework order
- **Open question:** who approves, when does inventory impact?

### Scenario C: Workshop Repair
Standard QC_FAIL → WKS_IN → WKS_OUT → re-QC. Loop count tracks cycles. Implemented.

### Scenario D: Packaging Rework
Original RTE/RTR voided via Tier 2/3; new scan created and linked.

---

## 10. Open Design Questions

1. **Variant rework UPC:** Keep original UPC (preferred — history carries over) or new UPC (cleaner product record)?

2. **Rework approval:** Who triggers and approves recall/rework orders? When does inventory impact?

3. **WIP valuation:** Material cost only or material + proportional labour?

4. **Unicommerce integration:** Formal API or reconciliation-based handoff?

5. **Biometric integration:** Headcount only or individual operator clock-in/out?

6. **Defect master ownership:** Who can add/modify defect codes post-seeding?

7. **WKS device mode:** Modal per scan (current) vs system-inferred from unit state.

8. **Dashboard tab access control:** Deferred — role-gate later when feature set is stable.

---

## 11. Shift & Time

- One shift: 9:00 AM – 6:00 PM. OT possible.
- Biometric attendance exists on-site, not connected.
- Downtime tracking needed, not yet implemented.

---

## 12. Lines & Factory

- 3 lines: L1, L2, L3. Each runs one product at a time.
- Line switches happen mid-day when parts run out (parts availability = primary daily bottleneck).
- **New factory acquired, move pending** — system must support more lines, more scan points, more devices without structural changes. Device and line configuration fully dynamic via dashboard.

---

## 13. Scale

| Month | Units/Month | Scan Events/Month | upc_pool rows (cumulative) |
|---|---|---|---|
| Now | 20,000 | ~100,000 | ~20,000 |
| +6 | 50,000 | ~250,000 | ~220,000 |
| +12 | 124,000 | ~620,000 | ~920,000 |
| +18 | 308,000 | ~1,540,000 | ~3M |
| +24 | 763,000 | ~3,800,000 | ~10M |

Scan events is the largest table. Partition by month at ~500K events/month (~month 10). Dashboard views must pre-aggregate — never query raw scan table for reporting.

---

## 14. Compliance Readiness

### ISO
- Full unit traceability (raw material → finished product)
- Non-conformance records (QC_FAIL + defect codes)
- Corrective action tracking (training flags on repeat defects)
- BOM version history

### IND AS
- WIP: units between INW and RTD
- Finished goods: units at RTD
- Valuation method: open question

### Non-negotiable architectural constraints
- Records never deleted — void with reason only
- Every mutation logged with actor + timestamp + metadata
- Timestamps stored UTC, displayed IST
- UPC permanent — never reassigned, never reused
- BOM versions are historical

---

## 15. Integration Points

| System | Direction | What | Status |
|---|---|---|---|
| Unicommerce | Out | RTD handoff, channel routing | Manual currently |
| Biometric | In | Headcount / shift data | On-site, not connected |
| External UPC printer | Both | Batch gen → A3 print → receive | Live workflow via dashboard |
| Supabase | Core | All data | Live |
| Cloudflare Workers | API | Auth, logic, mutations | Live |

---

## 16. Build Status

### Live ✅
- Supabase DB — all tables, schema confirmed and corrected this session
- Product master — 86 SKUs with product codes
- Scanner PWA (`scanner.legendoftoys.com`) — operator login, simplified setup, INW scanning confirmed
- Worker v4 (`lotopsproxy.afshaan.workers.dev`) — all production + store endpoints
- Dashboard (`dashboard.legendoftoys.com`) — Executive / Lines / QC / Runs / UPC Generator tabs
- UPC batch generation + A3 print workflow

### Tested & Confirmed This Session ✅
- INW scan → unit created in DB, UPC marked applied
- Batch generation with product codes + per-product sequences
- QR print to new window (data URL approach)
- Setup screen auto-resolves device from station+line

### Built But Not Yet End-to-End Tested 🔶
- QC_PASS two-scan pairing flow
- QC_FAIL defect modal
- WKS_IN / WKS_OUT modal
- Offline queue replay

### Pending 🔲
- QC_PASS + QC_FAIL end-to-end testing
- Scan correction UI on dashboard (Tier 2 void + Tier 3 amend)
- Operator QR card generation + print
- RTE/RTR scan flow (batch label, not UPC)
- Packing pair verification (car + remote pair check before RTE/RTR)
- Rework module
- Biometric integration
- Unicommerce reconciliation
- Dashboard tab access control

---

*Update at end of every significant build or design session.*
