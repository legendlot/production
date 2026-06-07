# ⚠️ DEPRECATED — this standalone Scanner manual is no longer maintained

**Superseded 2026-06-07 (Session 108-cont).** The scanner PWA (`02_scanner/index.html`) has no
in-app System Manual tab, so its documentation now lives **inside the Garage and Redline in-app
manuals**, split by which team works each station. Do not edit, rebuild, or re-sync the files in
this folder; they predate the S108 department-gated redesign and are stale.

## Where the scanner manual lives now

| Scanner stations | In-app manual | Source fragments |
|---|---|---|
| **Store** — Store Issue, Returns Intake, Direct Issue, Legacy Reg, Lookup (+ the super-admin Scanner Department PINs card) | **Garage** (`garage.legendoftoys.com` → System Manual → Scanner) | `05_Throttle/apps/garage/docs/manual/content/scan-*.html` |
| **Production** — Assembly, QC Pass/Fail, Workshop, Packaging, PKG Out, Repair, Ext Inwarding, Repack In/Out | **Redline** (`redline.legendoftoys.com` → System Manual → Scanner) | `05_Throttle/apps/redline/docs/manual/content/scan-*.html` |
| **Dispatch** — Dispatch In, Allocate, Pack, Dispatch Out, Restock (+ Attendance/Lookup) | **Redline** | `05_Throttle/apps/redline/docs/manual/content/scan-*.html` |
| Shared basics (department gating, PINs, sign-in, auto-shift, reject behaviour) | **both** | `scan-basics.html` in each (re-told per audience) |

Each station is documented in exactly one manual (split, not duplicated); only the "The Floor
Scanner" basics chapter and the Lookup utility appear in both.

## To update scanner docs

Edit the `scan-*.html` fragment in the owning app, then rebuild that app's manual:

```bash
cd 05_Throttle/apps/<garage|redline>/docs/manual && python3 build.py        # PDF
cd 05_Throttle && python3 scripts/build-manual-web.py <garage|redline>      # in-app data + PDF copy
```

See `CORE.md` → "In-app System Manuals" and the `system-manual` skill (its "Folding an app-less
system" section) for the full method.
