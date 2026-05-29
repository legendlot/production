# Scanner Floor Guide

The self-serve guide to the Legend of Toys production-floor scanner PWA
(`scanner.legendoftoys.com`, the single-file `02_scanner/index.html`). The manual
source lives here so it versions alongside the scanner it documents.

The deliverable is **`Scanner-Operations-Manual.pdf`** (the build derives the
filename from the `manual.json` title; the cover reads "Scanner Floor Guide").

Built with the same pipeline as the Redline and Garage manuals
(`05_Throttle/apps/*/docs/manual/`):

```bash
cd 02_scanner/docs/manual
python3 build.py            # self-bootstraps a venv, renders the PDF with Chrome
python3 build.py --html     # also write manual.debug.html for quick checks
```

## Structure

- `manual.json` - the spine: title, version, roles (`op` Operator, `sup`
  Supervisor) and the ordered parts -> chapters (organised by station flow, not
  page routes, since the scanner is a single-screen app).
- `content/*.html` - one fragment per station/chapter (body only; the build adds
  the title, breadcrumb and role badges).
- `assets/theme.css` - styling (LOT dark cover, red/yellow; role classes `op`/`sup`).
- `build.py` - shared build pipeline.

## House style

No em dashes in copy (commas, colons, semicolons, periods, parentheses). En dashes
are fine in ranges. Each chapter opens with a `<p class="lead">` and a `.glance`
strip, then sections and `.callout` boxes. Copy any existing station chapter (for
example `content/prod-pkg.html`) to match the components.

Note: the scanner app itself is edited only in `index.html` (see the app's
CLAUDE.md). This `docs/manual/` folder is documentation and does not affect the
deployed PWA.
