# Upstream Submission — JDownloader Dark Theme

## 1. Theme Contribution (JD_Plain_Dark)

**Target**: JDownloader Forum -> Suggestions & Requests
**URL**: https://board.jdownloader.org/forumdisplay.php?f=14

### Forum post draft (English)

---

**Subject**: [Theme] JD Plain Dark — Breeze Dark colour palette

Hi,

I'd like to share a dark colour theme for JDownloader 2 based on the KDE Breeze Dark palette.

**What it does:** replaces JDownloader's colour tokens (table backgrounds, selection colours,
panels, tabs, etc.) with the Breeze Dark palette — the scheme used in KDE Plasma and apps like
Krusader.

**Colour palette:** background #232629 | alternate row #1e2124 | panel #31363b |
header/menu #1b1e20 | foreground #eff0f1 | selection/focus #3daee9 | link #2980b9 |
progress #2d8a42

**Installation:** import `JD_Plain_Dark_theme.zip` (attached).

**Bug report bundled with this submission:**
Under ANY dark Look & Feel (FLATLAF_DARK, FLATLAF_MAC_DARK, BLACK_EYE, ...), JD's custom-rendered
content areas keep a LIGHT background while the chrome (menu/toolbar/headers/scrollbars) is
correctly dark. Affected at least: the download list, the link grabber, AND the Advanced Settings
table — and the light theme text on the light background is barely readable in places (screenshots
attached). It happens with both FlatLaf-based and Synthetica-based (BLACK_EYE) dark LAFs, so it's
JD's own rendering, not a single LAF. I confirmed externally that the LAF can't reach these areas:
patching FlatLaf's FlatDarkLaf.properties (Table/List/Panel keys) and adding a dark
themes/flat/theme.json didn't change them; only a JVM agent forcing the colours into UIManager
after LAF init worked.

**Request to developers:**
1. Include a built-in, correct dark theme (JD_Plain_Dark) selectable in Settings -> GUI.
2. Fix JD's custom tables/panels (ExtTable) to follow the active LAF colours at paint-time
   (UIManager.getColor("Table.background"/"List.background"/"Panel.background") + foreground),
   so all dark Look & Feels render consistently.

**Environment**: JDownloader 2 (core revision 50639, build 2026-06-02), FlatLaf 3.7,
Java 21.0.10 (Eclipse Adoptium, 64-bit). Reproduced on Windows 11 (25H2) AND on Linux
(Ubuntu Noble, Docker/KasmVNC) — same on both, not container-specific.

Thank you!

---

## 2. GitHub Issue Draft

**Target**: the official JDownloader repo if accessible

---

**Title**: Custom content tables (download list, link grabber, advanced settings) stay light + low-contrast text under any dark Look & Feel

**Forum thread**: https://board.jdownloader.org/showthread.php?t=98756

**Description**:

With any dark Look & Feel (FLATLAF_DARK, FLATLAF_MAC_DARK, BLACK_EYE, ...), JD's custom-rendered
content areas keep a LIGHT background while standard Swing chrome (menu bar, toolbar, tab/column
headers, scrollbars) turns dark correctly. Affected at least: the download list, the link grabber,
and the Advanced Settings table. Combined with the theme's light foreground, some text becomes
light-on-light and is barely readable.

**Screenshots**: [download list + Advanced Settings, both light]

**Expected**: all JD content areas use the dark background of the active dark Look & Feel.

**Observed**: content tables/panels stay light; text low-contrast in places.

**Not LAF-specific**: reproduced with both FlatLaf-based (FLATLAF_DARK, FLATLAF_MAC_DARK) and
Synthetica-based (BLACK_EYE) dark LAFs -> points to JD's own rendering.

**Root cause (hypothesis)**: JD's custom tables/panels (ExtTable) don't read their colours from the
active LAF at paint-time. Verified externally: patching FlatLaf properties (Table/List/Panel keys)
and adding a dark themes/flat/theme.json don't reach them; only a JVM agent forcing the colours
into UIManager after the LAF is applied works. Fix: read
UIManager.getColor("Table.background"/"List.background"/"Panel.background") (+ foreground) at
paint-time.

**Environment**: JDownloader 2 (core revision 50639, build 2026-06-02), FlatLaf 3.7,
Java 21.0.10 (Eclipse Adoptium, 64-bit). Reproduced on Windows 11 (25H2) AND Linux
(Ubuntu Noble, Docker/KasmVNC).

---

## 3. Attached Files

- `JD_Plain_Dark_theme.zip` — importable theme package
- Screenshots: light download list + light Advanced Settings under a dark LAF

## 4. Timeline

- Bug report POSTED: https://board.jdownloader.org/showthread.php?t=98756
- Next: post the theme/request (Suggestions & Requests), linking to the bug thread
- If no response in ~1-2 weeks (board: earliest bump after 3 days) -> open GitHub issue, link all
