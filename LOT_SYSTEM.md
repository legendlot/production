# Legend of Toys — System Understanding Document
**Status: DEPRECATED — May 2026**

---

## ⚠️ This file is no longer maintained.

The LOT production system migration to the React monorepo is **complete**.
The migration note in the original header of this file ("Migration in planning") is now resolved.
Both Garage and Redline are live as React apps. All new development is in `05_Throttle/`.

### Replacement files

| Old file | New file | Purpose |
|---|---|---|
| `02_scanner/LOT_SYSTEM.md` (this file) | `00_Claude/SYSTEM_STATE.md` | Always-current system state, architecture, conventions, open items |
| `02_scanner/LOT_BUILD.md` | `00_Claude/MONOREPO_BUILD.md` | Chronological build log |

### Session start instructions going forward
1. Read `/Users/afshaansiddiqui/Documents/00_Claude/MONOREPO_BUILD.md`
2. Read `/Users/afshaansiddiqui/Documents/00_Claude/SYSTEM_STATE.md`
3. Do NOT read this file or LOT_BUILD.md — they are frozen as of May 2026

---

## What this file covered (archived context)

This file was the canonical reference for the legacy LOT system architecture:
- Supabase schema (`public` + `store` schemas) — still unchanged, still in use
- Worker API action reference — now maintained in `SYSTEM_STATE.md`
- Org structure (Afshaan, Varun, Siddhant, Kishan, Karthik, Mahesh, Reann, Vinay) — unchanged
- Scan point architecture — unchanged, scanner still live
- Permission model — unchanged, auth still uses same `hasPermission` pattern
- Product codes, UPC format, batch label format — unchanged

### What is still live and unchanged from this document
- `scanner.legendoftoys.com` — Android TWA scanner app, all scan points
- `01_worker/worker.js` — Cloudflare Worker (lotopsproxy), still updated
- Supabase database — same `public` and `store` schemas
- Print server — polling architecture, TSC TE244 printers
- Biometric attendance system — exists, downtime tracking not yet built

### What changed
- `03_dashboard/` (Redline legacy) → superseded by `redline.legendoftoys.com`
- `04_stores/` (Garage legacy) → superseded by `garage.legendoftoys.com`
- Migration waves G-W1 through cutover → all complete
- `MONOREPO_BUILD.md` and `SYSTEM_STATE.md` are now the source of truth
