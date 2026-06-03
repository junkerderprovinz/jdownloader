# Upstream Submission — JDownloader Dark Theme

## 1. Theme Contribution (JD_Plain_Dark)

**Target**: JDownloader Forum → Themes & Templates section
**URL**: https://board.jdownloader.org/forumdisplay.php?f=14

### Forum post draft (English)

---

**Subject**: [Theme] JD Plain Dark — Breeze Dark colour palette for FlatLaf

Hi,

I'd like to share a dark colour theme for JDownloader 2 based on the KDE Breeze Dark palette,
designed to work with FlatLaf Dark as the Look & Feel.

**What it does:**
Replaces all JDownloader colour tokens (table backgrounds, selection colours, panels, tabs, etc.)
with the Breeze Dark palette — the same colour scheme used in KDE Plasma and apps like Krusader.

**Colour palette:**

| Token | Colour | Hex |
|-------|--------|-----|
| Background | Breeze Dark Window | `#232629` |
| Alternate row | Slightly darker | `#1e2124` |
| Foreground | Near-white | `#eff0f1` |
| Selection | Breeze Blue | `#3daee9` |
| Panel | Breeze Dark Panel | `#31363b` |
| Header/Menu | Very dark | `#1b1e20` |
| Progress | Breeze Green | `#2d8a42` |

**Installation:**
1. Download `JD_Plain_Dark_theme.zip` (attached)
2. JDownloader → Settings → GUI → Themes → Import
3. Restart JDownloader

**Bug report bundled with this submission:**
When FlatLaf Dark is selected as the Look & Feel, the download list and link grabber content area
stay white (see attached screenshots). Menu bar, toolbar and column headers turn dark correctly —
only JD's ExtTable (download list / link grabber) stays light. I tried to fix it from the outside
and could not reach it through the LAF: patching FlatLaf's FlatDarkLaf.properties with explicit
Table/List/Panel keys did not change the content area, and adding a dark themes/flat/theme.json did
not either. Only a JVM agent that forces the colours into UIManager *after* JD's LAF init made it
dark. So the ExtTable appears to resolve its background from a source the active LAF does not drive.
(The symptom and the failed external fixes are verified; the exact root cause is a hypothesis.)

**Request to developers:**
1. Include JD_Plain_Dark as a built-in theme option
2. Fix the download list / link grabber background to respect the active FlatLaf LAF colours
   (use `UIManager.getColor("List.background")` or `UIManager.getColor("Panel.background")`
   instead of a hardcoded or pre-initialized colour value)

Thank you!

---

## 2. GitHub Issue Draft

**Target**: https://github.com/mirror/jd2 (or the official JD repo if accessible)

---

**Title**: Download list background stays white when FlatLaf Dark is selected as Look & Feel

**Description**:

When `lookandfeeltheme=FLATLAF_DARK` is configured in `GraphicalUserInterfaceSettings.json`,
the FlatLaf Dark Look & Feel is correctly applied to standard Swing components (menu bar,
toolbar, tab headers, scrollbars all become dark grey). However, the main download list panel
and the link grabber content area remain **white**.

**Screenshots**: [attach the two screenshots]

**Expected**: All JD content areas (download list, link grabber, log panel) use dark background
consistent with FlatLaf Dark.

**Observed**: Download list and link grabber content area background is white/light.

**Root cause (hypothesis)**: the ExtTable used by the download list / link grabber does not follow
the active LAF. Verified externally: patching FlatLaf's FlatDarkLaf.properties with explicit
Table/List/Panel/Viewport keys does not affect the content area, and adding a dark
`themes/flat/theme.json` does not either — only a JVM agent that forces the colours into UIManager
*after* the LAF is applied makes it dark. This suggests the ExtTable reads its background from a
value captured early / from a source the LAF does not drive. Fix would be to read
`UIManager.getColor("Table.background"/"List.background")` at paint-time.

**Environment**: JDownloader 2 (core revision 50639, build 2026-06-02), FlatLaf 3.7,
Java 21.0.10 (Eclipse Adoptium, 64-bit). Reproduced on Windows 11 (25H2) AND on Linux
(Ubuntu Noble, Docker/KasmVNC) — same result on both, so it is not container-specific.

---

## 3. Attached Files

- `JD_Plain_Dark_theme.zip` — importable theme package
- Screenshots showing the half-light issue

## 4. Timeline

- Submit forum post first (faster response)
- If no response in 2 weeks → open GitHub issue
- Link both in the issue/post so context is shared
