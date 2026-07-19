/**
 * Generates the JDownloader README banners (house banner convention, theme-adaptive pair):
 *   jdownloader-banner.svg / .png      : light 1600x500 - the Carbon globe logo on
 *                                        the left, "JDownloader" + a cheeky claim.
 *   jdownloader-banner-dark.svg / .png : same layout on GitHub-dark #0d1117 with
 *                                        light text. The README serves the pair via
 *                                        <picture> (prefers-color-scheme).
 * The globe logo is embedded VERBATIM in BOTH themes (BombVault pattern) - its
 * coloured artwork reads on either background; only bg/text colours flip.
 *
 * Brand font: the wordmark is set in Bree Serif - OUR house wordmark face (same as
 * BombVault / ShipLog) - instead of JDownloader's official Arial Black. Arial reads
 * as "no font at all" and isn't recognisable as JD's, so the banner now carries our
 * brand. The claim uses Lato, the shared claim font across all repos. Both are OFL,
 * fetched at runtime to the OS temp dir (never committed) and converted to SVG paths
 * (opentype.js) so the SVG is self-contained.
 *
 * The logo-only banner (jdownloader-banner-logo.png/.svg) is a separate asset used
 * by the support thread; it is NOT touched here.
 *
 * Deps: `npm i -g @resvg/resvg-js opentype.js`. Run: node .github/assets/gen-banner.mjs
 */
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { createRequire } from "node:module";
import { execSync } from "node:child_process";

const require = createRequire(import.meta.url);
const gRoot = execSync("npm root -g").toString().trim();
const opentype = require(`${gRoot}/opentype.js`);
// @resvg/resvg-js (native rasterizer) is required lazily below, after the text-to-
// path work. Note: opentype's own getPath() intermittently returns NaN coords in
// this script, so we don't use it - glyphRunPath() transforms each glyph's raw
// outline by hand instead (see its comment). The SVG is checked for NaN before write.

const __dir = dirname(fileURLToPath(import.meta.url));

// ---- content + styling -----------------------------------------------------
const NAME = "JDownloader"; // mixed-case brand wordmark (Bree Serif)
const CLAIM = "Grab it. All of it. In the dark.";
// Theme pair (house rule): light keeps the Carbon wordmark; dark flips to
// GitHub-dark bg + light text. Same logo in both (see header comment).
const THEMES = [
  { suffix: "",      bg: "#ffffff", name: "#161616", claim: "#5a5d5e" }, // Carbon on white
  // Dark theme: the logo's near-black greys (#161616 disc, #0b0b0b outlines)
  // vanish on the #0d1117 canvas — lighten JUST those two greys for this theme
  // (user-ordered 2026-07-19). Exact-colour swaps on the embedded markup only;
  // greens/ambers and all geometry stay verbatim.
  { suffix: "-dark", bg: "#0d1117", name: "#e6edf3", claim: "#9aa4ad",
    logoSwaps: [["#161616", "#2d333b"], ["#0b0b0b", "#21262d"]] },
];
const W = 1600, H = 500;
const LH = 360; // logo height (icon.svg is square, 48x48 units)
const LW = LH;  // square logo
let nameSize = 168; // shrunk below to fit "JDOWNLOADER"
const claimSize = 42, gap = 64, lineGap = 22;
const MAX_GROUP = W - 160; // keep ~80px breathing room each side
// ---------------------------------------------------------------------------

// Brand fonts (OFL) fetched at runtime - never committed. Bree Serif = wordmark
// (our brand face), Lato = claim (shared across all repos).
const breeFile = join(tmpdir(), "JD-BreeSerif-Regular.ttf");
const latoFile = join(tmpdir(), "JD-Lato-Regular.ttf");
async function ensureFont(file, url) {
  if (!existsSync(file)) {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`font fetch ${res.status}: ${url}`);
    writeFileSync(file, Buffer.from(await res.arrayBuffer()));
  }
}
await ensureFont(breeFile, "https://github.com/google/fonts/raw/main/ofl/breeserif/BreeSerif-Regular.ttf");
await ensureFont(latoFile, "https://github.com/google/fonts/raw/main/ofl/lato/Lato-Regular.ttf");
const nameFont = opentype.parse(readFileSync(breeFile));
const claimFont = opentype.parse(readFileSync(latoFile));

// Per-glyph shaping (charToGlyph + glyph.getPath) - bypasses opentype.js's
// feature engine, which emits NaN path coords for some Lato pairs and truncates
// the claim after the first word. Lossless for plain Latin; the small claim line
// needs no kerning.
function glyphRunWidth(font, text, size) {
  const scale = size / font.unitsPerEm;
  let w = 0;
  for (const ch of text) w += font.charToGlyph(ch).advanceWidth * scale;
  return w;
}
// Build the SVG path by transforming each glyph's OWN outline commands (font units)
// ourselves - scale + baseline flip + advance - instead of opentype's getPath().
// opentype's getPath() intermittently emits NaN coords here (a float-state quirk that
// surfaces only in file execution, not via stdin), so we never call it; this is pure
// finite arithmetic on the raw outline, so the output can't contain NaN.
function glyphRunPath(font, text, x, baseline, size) {
  const scale = size / font.unitsPerEm;
  const n = (v) => v.toFixed(2);
  let d = "", cx = x;
  for (const ch of text) {
    const g = font.charToGlyph(ch);
    for (const c of g.path.commands) {
      if (c.type === "M") d += `M${n(cx + c.x * scale)} ${n(baseline - c.y * scale)}`;
      else if (c.type === "L") d += `L${n(cx + c.x * scale)} ${n(baseline - c.y * scale)}`;
      else if (c.type === "C")
        d += `C${n(cx + c.x1 * scale)} ${n(baseline - c.y1 * scale)} ${n(cx + c.x2 * scale)} ${n(baseline - c.y2 * scale)} ${n(cx + c.x * scale)} ${n(baseline - c.y * scale)}`;
      else if (c.type === "Q")
        d += `Q${n(cx + c.x1 * scale)} ${n(baseline - c.y1 * scale)} ${n(cx + c.x * scale)} ${n(baseline - c.y * scale)}`;
      else if (c.type === "Z") d += "Z";
    }
    cx += g.advanceWidth * scale;
  }
  return d;
}

const em = (f, s) => s / f.unitsPerEm;

// Shrink the wordmark until the logo + name group fits the card with margins.
// The whole word is set at one uniform size (no oversized initial letter).
while (nameSize > 80 && LW + gap + glyphRunWidth(nameFont, NAME, nameSize) > MAX_GROUP) {
  nameSize -= 2;
}
const nameW = glyphRunWidth(nameFont, NAME, nameSize);
const claimW = glyphRunWidth(claimFont, CLAIM, claimSize);
const groupW = LW + gap + Math.max(nameW, claimW);
const startX = (W - groupW) / 2;
const LX = startX, LY = (H - LH) / 2;
const textX = startX + LW + gap;

const nameAsc = nameFont.ascender * em(nameFont, nameSize);
const nameDesc = -nameFont.descender * em(nameFont, nameSize);
const claimAsc = claimFont.ascender * em(claimFont, claimSize);
const blockH = nameAsc + nameDesc + lineGap + claimAsc;
const nameBaseline = H / 2 - blockH / 2 + nameAsc;
const claimBaseline = nameBaseline + nameDesc + lineGap + claimAsc;

// Both lines per-glyph (charToGlyph + glyph.getPath). opentype.js's feature engine
// (font.getPath / getAdvanceWidth) corrupts state across two parsed fonts here and
// makes the Lato claim render as NaN coords; per-glyph bypasses it entirely.
const claimPath = glyphRunPath(claimFont, CLAIM, textX, claimBaseline, claimSize);
const namePath = glyphRunPath(nameFont, NAME, textX, nameBaseline, nameSize);
// Never ship a NaN path (the resvg-float-state bug above would silently truncate
// the text); fail loudly so a bad banner can't be committed.
if (claimPath.includes("NaN") || namePath.includes("NaN")) {
  throw new Error("text path contains NaN - aborting (load order / float-state regression)");
}

// Embed the Carbon globe (icon.svg, 48x48) verbatim - only the root tag gets
// position/size attributes; the artwork inside is untouched.
let logo = readFileSync(join(__dir, "icon.svg"), "utf8").replace(/<\?xml[^>]*\?>\s*/, "");
logo = logo.replace(
  /<svg[\s\S]*?>/,
  `<svg x="${LX.toFixed(1)}" y="${LY.toFixed(1)}" width="${LW}" height="${LH}" viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg">`,
);

// Load the native rasterizer now (after all opentype path work) - see note at top.
const { Resvg } = require(`${gRoot}/@resvg/resvg-js`);

for (const t of THEMES) {
  const themedLogo = (t.logoSwaps ?? []).reduce((acc, [from, to]) => acc.split(from).join(to), logo);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="JDownloader">
  <rect width="${W}" height="${H}" fill="${t.bg}"/>
  ${themedLogo}
  <path d="${namePath}" fill="${t.name}"/>
  <path d="${claimPath}" fill="${t.claim}"/>
</svg>
`;
  writeFileSync(join(__dir, `jdownloader-banner${t.suffix}.svg`), svg);
  const png = new Resvg(svg, { fitTo: { mode: "width", value: W }, background: t.bg }).render().asPng();
  writeFileSync(join(__dir, `jdownloader-banner${t.suffix}.png`), png);
  console.log(`wrote jdownloader-banner${t.suffix}.svg + .png (name ${Math.round(nameW)}px @ ${nameSize}, claim ${Math.round(claimW)}px, group ${Math.round(groupW)}px)`);
}
