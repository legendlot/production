# Changelog - Scanner Floor Guide

The version here, in `manual.json`, and on the cover/footer of the PDF must always
match. Versioning is manual.

## [DEPRECATED] - 2026-06-07
### Deprecated
- This standalone Scanner manual is **superseded** and no longer maintained. The scanner
  PWA has no in-app manual tab, so its station docs were folded into the **Garage** (Store
  stations + the Scanner Department PINs card) and **Redline** (Production + Dispatch stations)
  in-app manuals, split by owning team. See `DEPRECATED.md` and `README.md` in this folder.
  The 1.0.0 content below predates the S108 department-gated redesign and is stale; do not
  update or rebuild it.

## [1.0.0] - 2026-05-29
### Added
- Complete self-serve guide to the Scanner PWA: 5 parts, 22 chapters covering every
  station (Getting Started, Production, Dispatch, Store & Returns, Repair /
  Outsourced / Utilities), each written for non-technical floor staff with
  step-by-step flows and the universal scan-feedback conventions.
- Role-segmented (Operator / Supervisor). Built with the shared pipeline
  (self-bootstrapping build.py, Chrome render, bookmarks, page-numbered footers)
  and the impeccable-polished print theme; copy in house style (no em dashes).
