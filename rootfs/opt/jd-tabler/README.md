# jd-tabler: chrome icons for JD Highlighter

JD Highlighter swaps JDownloader's colourful chrome icons (sidebar, toolbar, section
headers, tabs) for one consistent line-art set.

- **Source:** [Tabler Icons](https://tabler.io/icons), outline style, MIT licensed (see
  [`LICENSE.tabler`](LICENSE.tabler)). Each PNG is a straight rasterisation of an
  unmodified Tabler SVG.
- **`mapping.json`** maps a JD flat icon name to a Tabler icon name. A JD icon without an
  entry gets a mono tint of JD's own glyph instead.
- **`png/<jdname>-<size>.png`** are light (`#f4f4f4`) renders at 16, 20, 24 and 32 px. The
  agent picks the nearest size and tints it at runtime, dark on the accent when hovered.
