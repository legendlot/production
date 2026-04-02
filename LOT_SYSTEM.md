# Legend of Toys — System Understanding Document
**Version:** 1.1 | **Last Updated:** April 2026
**Purpose:** Canonical reference for understanding the LOT production operations system. Feed this to any new AI session to establish full context before building or designing.

---

## 1. Company & Context

**Legend of Toys (LOT)** is a toy manufacturing company producing RC cars. Currently manufacturing ~20,000 units/month with 20% month-on-month growth. At this trajectory, the system must be designed to handle ~120,000 units/month by end of Year 1 and potentially 700,000+ units/month by end of Year 2.

**Co-founders:**
- **Afshaan** — tech, branding, production (owns this system)
- **Vinay** — finance, sales, procurement

**Current state:** Replacing a Google Sheets + Apps Script setup that has hit scalability and reliability limits. Building an integrated production operations system on Supabase + Cloudflare Workers + PWA.

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

**Why Mahesh reports to Afshaan and not Varun:** Deliberate structural decision. Mahesh cross-references inline QC data (Karthik's data) against actual audit findings. If he reported to Varun, there would be mixed incentives — he needs independence to flag issues without political pressure. His scope is expanding to cover stores and dispatch as well.

**Mahesh's system access:** Read-only across all data. Cannot amend, void, or modify any record — ever. This is a permanent system constraint, not a temporary restriction.

**Operator profile:** Unskilled workers on the factory floor. Do not understand systems. Will make frequent, basic mistakes even after training. Any interface they touch must be **descriptive, visual, and foolproof** — assume the worst-case user at all times.

---

## 3. Products

LOT currently makes RC cars. Future product categories include die cast and DIY sets (no remotes). A finished RC car unit consists of:
- The car body (UPC sticker on the bottom)
- Remote control (UPC sticker — independent from car)
- Accessories (varies by SKU)
- Packaging tray
- Box (retail box or ecom box — physically different)
- Shrink wrap (applied by shrink wrap machine at end of packaging)

**SKUs:** 86 active SKUs. Products have variants (e.g., colour variants) and BOM differs by variant.

**`has_remote` flag:** Product master carries a boolean flag indicating whether the product requires a remote pairing at QC. RC cars = true. Die cast, DIY sets, future non-remote products = false. This flag drives scanner behaviour at QC_PASS.

---

## 4. UPC System ✅ LOCKED

### What UPC is
UPC is the **unique identifier for a finished, assembled unit** (car or remote). It is:
- A ~1cm × 1cm QR code sticker
- Metallic/shiny finish — sent out for external printing, not printable in-house
- Applied to the bottom of the car / body of the remote
- The primary tracking identifier throughout the production system

### Format — LOCKED
**`LOT-00000001`** — 8 digits, zero-padded, globally sequential.

- One counter for everything — cars, remotes, all products
- 8 digits handles ~100 million units (well beyond 2-year horizon)
- When counter exceeds 8 digits it naturally becomes 9 — no structural change needed
- QR encodes the UPC string. Human-readable text printed below shows product name (e.g., `KNOX`) for floor-level misapplication prevention

### What the sticker looks like
```
[QR CODE]
LOT-00000001
KNOX
```
Product name on sticker is fixed at print time (one print job). QR encodes only the UPC — product association lives in the DB, not in the code.

### `upc_pool` table — LOCKED
Individual row per UPC. No range designation. UPCs for same product are not contiguous across batches — expected and fine.

```
upc_id        | product       | batch_id | status    | applied_to_unit_id
LOT-00000001  | Knox          | B-001    | applied   | <unit_id>
LOT-00000002  | Knox          | B-001    | applied   | <unit_id>
LOT-00000003  | Knox          | B-001    | damaged   | null
LOT-00000500  | Knox          | B-001    | unused    | null
LOT-00000501  | Flare         | B-002    | applied   | <unit_id>
LOT-00001101  | Knox          | B-007    | applied   | <unit_id>
LOT-00001201  | Knox-Remote   | B-008    | applied   | <unit_id>
```

**UPC lifecycle statuses:** `generated` → `printed` → `received` → `available` → `applied` | `damaged` | `unused`

### Print batch workflow
1. Generate batch in system — N UPCs assigned to product, batch record created
2. Export file sent to external printer
3. Stickers received — batch marked as received
4. Stickers applied on floor — each UPC marked applied when unit is scanned at INW
5. Damaged / unused stickers individually voided — never deleted, never reassigned

### Remote UPC — LOCKED
Remotes get their own independent UPC from the same pool. Same sticker format, same generation process. Product designation distinguishes car from remote (e.g., `Knox` vs `Knox-Remote`).

---

## 5. Physical Production Flow

### High-Level Flow
```
Parts (Store) → Assembly (car + remote on same line) → QC → Packaging → RTE/RTR → Dispatch → Unicommerce
```

### Assembly Line Structure
The production line has 3 stages:
1. **Assembly** — end scan: INW
2. **QC** — end scan: QC_PASS or QC_FAIL
3. **Packaging** — end scan: RTE or RTR

The remote is assembled at the **last station of the same assembly line** as the car. By INW, car and remote have been assembled but are still independent units in the system. They travel through the line together physically but are scanned and tracked as separate units until QC_PASS creates the pairing.

### Detailed Flow — A Single Unit's Journey

**Stage 1: Assembly → INW**
- Car assembled, UPC sticker applied to bottom
- Remote assembled (last station, same line), UPC sticker applied
- Each scanned independently at **INW** — one scan per unit
- System identifies unit type (car vs remote) from UPC lookup against `upc_pool`
- INW = first scan, unit enters production tracking

**Stage 2: QC**

*Products with remote (`has_remote = true`):*
- Karthik's team inspects car and remote together as a set
- Operator picks one car + one remote, tests them together
- If remote fails: QC_FAIL scan on remote UPC → operator picks another remote, retests
- If car fails: QC_FAIL scan on car UPC → remote goes back to available pool physically
- When both pass: operator scans car UPC → scanner prompts "scan remote" → scans remote UPC → **pairing created + QC_PASS recorded for both**
- Pairing is created at QC_PASS only — no tentative pair state exists before this

*Products without remote (`has_remote = false`):*
- Single scan — QC_PASS or QC_FAIL on unit UPC only. No remote prompt.

**Stage 3: Workshop / Repair** (if QC_FAIL)
- Failing unit (car or remote independently) enters workshop
- **WKS_IN** scan on that unit's UPC
- Repairs done → **WKS_OUT** scan
- Unit re-enters QC (loop count incremented — fresh production vs repair tracked separately)

**Stage 4: Packaging**
- QC_PASS moves pair to packaging queue
- At packing station, operator scans car UPC + remote UPC
- System verifies they are a confirmed pair from QC — if not, scanner blocks
- After shrink wrap seals the box:
  - Ecom box → **RTE** scan on batch label
  - Retail box → **RTR** scan on batch label
- **RTD** = RTE + RTR — calculated value, not a physical scan
- At RTE/RTR, ownership transfers to dispatch team

**Stage 5: Dispatch**
- Dispatch team takes ownership
- Channel routing via **Unicommerce** (separate system — manual handoff currently)
- Production system does not track units after RTD
- RTD stock in system = units physically ready and waiting

**Stage 6: Returns (RTO_IN)**
- Market returns re-enter via RTO_IN scan
- Traceability back to original production run maintained

### Scan Points — LOCKED
| Scan Code | Full Name | Stage | Devices | Notes |
|---|---|---|---|---|
| INW | Inward | End of Assembly | 3 (one per line) | Single scan — car or remote independently |
| QC_PASS | QC Pass | End of QC | 3 (one per line) | Two-scan flow if has_remote = true; pairing created here |
| QC_FAIL | QC Fail | End of QC | 3 (one per line) | Single scan — whichever component failed |
| WKS_IN | Workshop In | Workshop Entry | 1 shared | |
| WKS_OUT | Workshop Out | Workshop Exit | 1 shared | |
| RTE | Ready To Ecom | End of Packaging | 1 shared | Scans batch label, not UPC |
| RTR | Ready To Retail | End of Packaging | 1 shared | Scans batch label, not UPC |
| RTO_IN | Return Inward | Returns | 1 shared | |

**RTD** = RTE + RTR (calculated, not a physical scan point)
**Total devices: 13** (unchanged)

---

## 6. Batch Labels

When a unit hits QC_PASS, a batch label is generated and printed at the PKG station.
**Format:** `EAN-YYYYMMDD-NNN`

RTE/RTR scans use the batch label, not the individual UPC. This prevents duplicate dispatch scans — the batch label represents one discrete packaging event.

---

## 7. QC_FAIL — Defect Logging ✅ LOCKED

### Storage Model — Parent + Child rows
```
qc_fail_events (parent — one row per QC_FAIL scan)
  scan_event_id, upc, component_type (car/remote),
  line, operator_id, timestamp, product, loop_count

qc_fail_defects (child — one row per defect logged against that event)
  id, scan_event_id, defect_code, severity
```

Enables:
- **Defect-level:** `GROUP BY defect_code` — how many times did each defect occur
- **Unit-level:** `GROUP BY upc` — how many times did this unit fail, what defects each time
- **Combined slicing:** defect X on line Y, time window Z, by operator

### Defect Master Structure
```
defect_master
  defect_code, category, description,
  component (car / remote / both),
  product (specific product or null = universal),
  severity (Critical / Major / Minor), active
```

**Shared taxonomy, different codes:** Categories shared across car and remote (Visual, Functional, Assembly). Specific codes under each category differ by component. A car might have `SND-001 No sound from speaker`; a remote would have `RMT-001 No signal transmission`. Both sit under Functional.

**Scanner behaviour at QC_FAIL:** After scanning the failing UPC, system identifies car vs remote from `upc_pool`. Defect list filtered to matching component type. Operator multi-selects and confirms.

---

## 8. Scan Correction & Amendment ✅ LOCKED

**Design principle: prevent first, correct second.** Scanner UX prevents errors before they happen. Correction is the fallback.

### Three-Tier Model

**Tier 1 — Operator (self-serve, no approval)**
- Duplicate scan: blocked in real time (already implemented)
- Wrong scan: undo available until the unit receives its **next scan in the flow**
- Once a unit has been scanned at the next stage, Tier 1 closes — the scan has been acted upon
- Logged automatically, no reason required

**Tier 2 — Supervisor within shift (Kishan / Karthik)**
- Can void any scan from the current shift
- Mandatory reason required
- No approval needed but fully visible to managers in audit log
- Kishan: line scans; Karthik: QC scans

**Tier 3 — Post-shift amendment (Siddhant / Varun / Afshaan)**
- Can amend any scan after shift close
- Mandatory reason required
- Immutable audit trail: original value, corrected value, actor, timestamp, reason
- This is the end-of-day reconciliation mechanism

**Mahesh:** Read-only. No amendment access at any tier, ever — permanent constraint.

### Foolproofing in Place
- Product name shown after every UPC scan
- Operator logs in via QR card (no typing)
- Device pre-configured to scan point (operator never chooses scan type)
- Duplicate scans blocked in real time

---

## 9. Rework & Reverse Flow Scenarios

**Core principle:** Original records preserved. Rework captured as distinct event. Final state drives reports. Lineage always queryable.

### Scenario A: Variant Rework (Colour / Config Change)
**Example:** Flare Burnout Grey stock → convert to Flare Race Grey by changing car top.
- Original unit marked `converted`
- Rework order captures BOM delta (parts consumed)
- UPC decision: same UPC preferred (history carries over) — **open question, see Section 10**

### Scenario B: Post-Dispatch Part Replacement (Recall / Field Fix)
**Example:** Knox motor defect found — all finished stock needs motor replacement.
- RTD stock: pulled back via internal rework order
- Post-dispatch units: return via RTO_IN, then rework order
- Original record preserved, rework record linked
- Parts consumed deducted from store stock
- Approval flow: **open question, see Section 10**

### Scenario C: Workshop Repair
Standard QC_FAIL → WKS_IN → WKS_OUT → re-QC. Loop count tracks cycles. Already partially handled.

### Scenario D: Packaging Rework
Wrong box type or damaged packaging.
- Original RTE/RTR scan voided via Tier 2/3 correction
- New RTE/RTR scan created, linked to voided scan

> No scan or record is ever deleted. Corrections create reversing/superseding entries with explicit linkage to the original.

---

## 10. Open Design Questions

Remaining after April 2026 design session. Must be resolved before relevant modules are built.

1. **Variant rework UPC (Scenario A):** Does the converted unit keep its original UPC (preferred — history carries over) or get a new UPC (cleaner product record, requires explicit lineage link)?

2. **Rework order approval (Scenario B):** Who triggers a recall/rework order? Who approves it? Does inventory impact on order creation or only when units are physically returned?

3. **WIP valuation:** For IND AS, how is WIP valued — material cost only, or material + proportional labour?

4. **Unicommerce integration:** Formal API or permanent reconciliation-based handoff? Determines webhook receivers vs export/import tooling.

5. **Biometric integration:** Headcount per shift only, or individual operator clock-in/out for productivity attribution?

6. **Defect master ownership:** Who can add/modify defect codes after initial seeding? Locked, or open with approval flow?

7. **WKS device toggle:** Does operator manually toggle WKS_IN vs WKS_OUT mode on the shared device, or does the system infer correct scan type from the unit's current state?

8. **Dashboard tab access control:** Deferred — role-gate later when feature set is stable.

---

## 11. Shift & Time

- One shift: 9:00 AM – 6:00 PM. OT possible but not the norm.
- Biometric attendance exists on-site, not yet connected.
- Downtime tracking needed, not yet implemented.

---

## 12. Lines & Factory

- 3 lines: L1, L2, L3
- Each runs one product/variant at a time
- Line switches happen mid-day when parts run out (parts availability = primary daily bottleneck)
- **New factory acquired, move pending** — system must support more lines, more scan points, more devices without structural changes. Device and line configuration must be fully dynamic (UI-configurable, not seeded).

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
- Cost hooks: BOM + labour + overhead

### Internal Controls
- Segregation of duties enforced by org structure
- Approval chains on production runs
- Immutable audit log on all mutations
- Role-based permissions at feature level

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
| External UPC printer | Both | Batch gen → print → receive | Manual, needs workflow |
| Supabase | Core | All data | Live |
| Cloudflare Workers | API | Auth, logic, mutations | Live |

---

## 16. Build Status

### Live
- Supabase (`jkxcnjabmrkteanzoofj.supabase.co`), 19 tables + views + RPCs
- Scanner PWA (`scanner.legendoftoys.com`) — QR login, device setup, scan screen
- APK (TWA) — camera + Digital Asset Links resolved
- Worker v4 (`lotopsproxy.afshaan.workers.dev`) — store + production endpoints
- Dashboard (`dashboard.legendoftoys.com`) — Executive / Lines / QC / Runs tabs
- 86 SKUs in product master

### Pending (priority order)
1. Operator seeding + QR card generation
2. Device configuration (all 13)
3. UPC generation system + print batch workflow
4. `has_remote` flag on product master
5. QC_PASS two-scan flow + pairing
6. QC_FAIL schema update (parent/child, component filtering)
7. Scan correction UI (three tiers)
8. Packing verification scan (pair check before RTE/RTR)
9. Rework module (Scenarios A + B)
10. Compliance reporting hooks
11. Unicommerce reconciliation
12. Biometric integration
13. Dashboard tab access control (deferred)

---

*Update at end of every significant build or design session.*
