# Legend of Toys — Technical Build Document
**Status: DEPRECATED — May 2026**

---

## ⚠️ This file is no longer maintained.

The LOT production system migration to the React monorepo is **complete**.
Garage (`garage.legendoftoys.com`) and Redline (`redline.legendoftoys.com`) are live.
All new development happens in `05_Throttle/`.

### Replacement files

| Old file | New file | Purpose |
|---|---|---|
| `02_scanner/LOT_BUILD.md` (this file) | `00_Claude/MONOREPO_BUILD.md` | Chronological build log of all sessions |
| `02_scanner/LOT_SYSTEM.md` | `00_Claude/SYSTEM_STATE.md` | Always-current system state, conventions, open items |

### Session start instructions going forward
1. Read `/Users/afshaansiddiqui/Documents/00_Claude/MONOREPO_BUILD.md`
2. Read `/Users/afshaansiddiqui/Documents/00_Claude/SYSTEM_STATE.md`
3. Do NOT read this file or LOT_SYSTEM.md — they are frozen as of May 2026

---

## What this file covered (archived context)

This file documented the vanilla JS legacy system:
- Scanner PWA (`02_scanner/`) — scan points, operator QR login, batch label printing
- Dashboard (`03_dashboard/`) — Redline legacy (vanilla JS)
- Stores (`04_stores/`) — Garage legacy (vanilla JS)
- Worker (`01_worker/worker.js`) — Cloudflare Worker, still in use

The scanner and worker are still live and unchanged.
The dashboard and stores apps have been superseded by the React monorepo.

### Scanner is still live (not migrated)
- `scanner.legendoftoys.com` → `legendlot/production` repo (main branch)
- Scan points: INW, QC_PASS, QC_FAIL, WKS_IN/OUT, PKG, PKG_OUT, RTO_IN, REPAIR
- Print server: polling architecture, TSC TE244 printers, `print_jobs` table
- No changes to scanner are expected — it was excluded from the migration

### Worker is still live (shared)
- `lotopsproxy.afshaan.workers.dev` — serves both Garage and Redline
- `01_worker/worker.js` — still updated as needed for new features
- All changes to the worker are logged in `MONOREPO_BUILD.md` going forward
