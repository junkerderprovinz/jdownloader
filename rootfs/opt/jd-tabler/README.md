# jd-tabler — clean chrome icons for the JD Highlighter theme

The JD Highlighter theme replaces JDownloader's built-in colourful chrome icons
(sidebar, toolbar, section headers, tabs) with a single, consistent line-art set
so the UI reads clean and modern.

- **Source set:** [Tabler Icons](https://tabler.io/icons) (outline), MIT-licensed — see
  [`LICENSE.tabler`](LICENSE.tabler). MIT permits redistribution, so the rasterised
  PNGs are committed here.
- **`mapping.json`** — JD flat-icon name → Tabler outline-icon name. A JD icon with no
  entry falls back to a mono tint of JD's own glyph.
- **`png/<jdname>-<size>.png`** — white (`#f4f4f4`) line-art renders at 16/20/24/32 px.
  The theme agent loads the nearest size at render time, tinting to the accent's
  contrast colour on hover. Regenerate with the `gen-tabler.mjs` helper.

Icons are recoloured to a single tone at runtime; the shipped PNGs are the neutral
light tone only. Nothing here is a modified Tabler source file — each PNG is a direct
rasterisation of an unmodified Tabler outline SVG at a given size.
