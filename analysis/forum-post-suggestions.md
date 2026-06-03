# Forum Post — Suggestions & Requests

**Forum board**: https://board.jdownloader.org/forumdisplay.php?f=14
(Section: "Suggestions & Requests")

**Subject**: [Theme + Bug] JD Plain Dark theme + white background bug in dark mode

---

## Post body (English)

Hi JDownloader team,

I'd like to submit a dark colour theme for JD Plain plus a related bug report that
affects every dark Look & Feel currently shipped with JDownloader 2.

### 1. Theme suggestion: JD Plain Dark

I've put together a dark variant of the **JD Plain** icon set using the KDE Breeze
Dark colour palette. The goal was a clean, modern dark theme that matches what users
get on KDE Plasma desktops (Krusader, Dolphin, etc.).

The theme is a simple `theme.json` overlay on the existing JD Plain icons — no new
icon set needed. Attached as `JD_Plain_Dark_theme.zip`.

**Palette:**

| Token | Hex | Purpose |
|-------|-----|---------|
| backgroundColor | `#232629` | Main window background |
| alternateRowBackground | `#1e2124` | Zebra rows |
| panelBackground | `#31363b` | Inner panels |
| headerBackground / menuBackground | `#1b1e20` | Tab headers, menu bar |
| foregroundColor | `#eff0f1` | Body text |
| selectionBackground | `#3daee9` | Breeze blue selection |
| linkColor | `#2980b9` | Hyperlinks |
| progressBarForeground | `#2d8a42` | Download progress |
| focusColor | `#3daee9` | Focus rings |

**Request:** Could you consider including JD_Plain_Dark as a built-in theme option
alongside JD_Plain? Many users on KDE-based distros (and dark-mode users in general)
would benefit.

### 2. Bug report: white download list & link grabber background in dark themes

While testing this theme — and also while testing the stock `FLATLAF_DARK` Look & Feel —
I noticed a consistent rendering bug:

When a dark Look & Feel is active (`FLATLAF_DARK`, `FLATLAF_MAC_DARK`, `BLACK_EYE`, or
any other dark theme), the **menu bar, toolbar, tab headers, column headers, and
scrollbars** correctly switch to dark colours. However, JD's custom content areas — the
**download list**, the **link grabber**, AND the **Advanced Settings table** — keep a
white/light background, and the light theme text on them is barely readable in places.
It affects both FlatLaf-based and Synthetica-based (BLACK_EYE) dark LAFs. See screenshots.

This makes any dark theme look broken because the largest area of the UI is the wrong
colour.

**Hypothesis** (symptom + failed fixes verified; exact cause not proven): JD's ExtTable
(download list / link grabber) does not follow the active LAF. Patching FlatLaf's
FlatDarkLaf.properties with explicit Table/List/Panel keys did not change the content
area, and adding a dark themes/flat/theme.json did not either — only a JVM agent that
forces the colours into UIManager *after* the LAF is applied worked. So the ExtTable
seems to read its colour from a source the LAF does not drive.

**Suggested fix**: have the ExtTable read `UIManager.getColor("Table.background")`
(or `"List.background"` / `"Panel.background"`) at paint-time, instead of a value
captured at component initialisation.

**Reproduction**:
1. Start JDownloader on a fresh profile
2. Settings → GUI → Look & Feel → `FLATLAF_DARK`
3. Restart JD
4. Observe: menu bar is dark, download list is white

**Environment**: JDownloader 2 (core revision 50639, build 2026-06-02), FlatLaf 3.7,
Java 21.0.10 (Eclipse Adoptium, 64-bit). Reproduced on Windows 11 (25H2) AND on Linux
(Ubuntu Noble, Docker/KasmVNC) — identical (dark chrome, white content area).

Screenshots attached.

Thank you for considering — happy to provide more details, additional palettes, or
contribute an icon variant if useful.

— junkerderprovinz

---

## Attachments to upload

1. `JD_Plain_Dark_theme.zip`  ← in `analysis/` folder
2. Screenshot: white download list with dark menu bar
3. Screenshot (optional): same view after manual reload

## After posting

- Subscribe to the thread
- If no response in 2 weeks, bump once with "any update?"
- Cross-link in any GitHub issue you open
