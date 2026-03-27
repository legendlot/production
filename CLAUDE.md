# CLAUDE.md — LOT Scanner App
# Repo: legendlot/production
# Live: scanner.legendoftoys.com

## What this is
A Progressive Web App (PWA) for barcode scanning on the Legend of Toys production floor.
No backend server — Google Apps Script handles all data. Hosted on GitHub Pages.
Stack is 100% free. Do not introduce paid services or new infrastructure without asking.

## Architecture
- Frontend: HTML/JS PWA (single or few HTML files), hosted via GitHub Pages
- Backend: Google Apps Script (Apps Script URL called via fetch)
- Data store: Google Sheets (single sheet: scan_log)
- No npm, no build step, no frameworks — plain HTML/JS only

## Production floor context
- 3 lines: L1, L2 (main, ~24-30 people), L3 (auxiliary/repair, ~3-8 people)
- Flow: Prep → Production (4 assembly stations + remote) → QC → Packaging → Dispatch
- 10 scanning devices total across the floor
- Scan points: INW (end of production), QC-PASS, QC-FAIL (2 devices per line), RTD at shrink wrap (1 shared per line)

## Barcode system
- UPC = 13-digit EAN + 4-digit unique suffix (17 digits total)
- EAN identifies the variant (e.g. Flare Blue vs Flare Red)
- UPC is on the car bottom (QR sticker)
- EAN is on the outer box
- Scan validation: reject anything not 13 or 17 digits

## Features already built
- Barcode length validation (reject short scans with alarm)
- Duplicate detection
- Product name lookup from EAN
- Locked device mode with PIN
- Shift selector
- RTD session tally
- Offline queue (syncs when back online)
- LockService on Apps Script to prevent concurrent write conflicts

## Known issues
- Duplicate RTD scans: currently unresolved, handled by manual count reconciliation
- Do not attempt to "fix" this without a full discussion first — it has production floor implications

## Coding rules
- Keep it plain HTML/JS — no React, no Vue, no build tools
- All Apps Script calls via fetch to the deployed web app URL
- Store the Apps Script URL in a config object at the top of the JS file
- Never hardcode sheet names or scan point codes as magic strings — use named constants
- Test offline behaviour before any deploy

## Deploy
- Push to main branch → GitHub Pages auto-deploys
- No build step needed
- Check scanner.legendoftoys.com after push to confirm
