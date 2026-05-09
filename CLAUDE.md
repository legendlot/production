# CLAUDE.md — LOT Scanner App
> Last updated: 2026-05-10

## What this is

Factory floor scanner PWA for Legend of Toys.
Used on Android devices at scan stations across the production line.

Repo: `legendlot/production`
Live URL: `scanner.legendoftoys.com`
Deploy: GitHub Pages, auto-deploys on push to `main`.

## The file

Single file: `index.html`. This is the only file you edit. Nothing else.

## Stations

INW, QC_PASS, QC_FAIL, WKS, PKG, PKG_OUT, RTE/RTR/RTD, LOOKUP (read-only utility)

## Worker dependency

Scanner talks to `lotopsproxy` (`01_worker/worker.js`).
Scanner actions are listed in the `SCANNER_ACTIONS` array in the worker.
Scanner sends no JWT — auth is via device_code only.

Critical: any worker action used by scanner must have its handler inside the
SCANNER_ACTIONS if-chain in worker.js, NOT inside the JWT-authenticated switch block.
Placing a handler in the wrong block causes 401 on every scanner request.

## Session start

```
git pull origin main
```
Remote is source of truth. Always pull before doing anything.

## Workflow

1. Pull from remote.
2. Read the relevant section of `index.html` before editing.
3. Make changes with minimal disruption to surrounding code.
4. `git add index.html && git commit -m "description" && git push origin main`
5. GitHub Pages auto-deploys. Confirm live within ~60s.

## Rules

- Only edit `index.html`. If instructions reference other files, flag and skip.
- Commit and push automatically after every confirmed change.
- Do not make unnecessary changes.
- Suggest enhancements or flag conflicts, but do not apply without approval.
- Worker changes needed to support scanner features are out of scope here —
  flag them and they will be handled in the 01_worker repo.
