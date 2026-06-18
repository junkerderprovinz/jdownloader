/**
 * Generates the JDownloader README banner (house banner convention):
 *   jdownloader-banner.svg / .png : white 1600x500 - the Carbon globe logo on
 *                                   the left, the wordmark + a cheeky claim.
 *
 * The official JDownloader wordmark (jdownloader.org header) is all-caps
 * "JDOWNLOADER" in ARIAL BLACK - verified by matching the logo's letterforms
 * (round O, the R leg, A apex, standard-width heavy grotesque). We use the real
 * font, rendered from the locally installed Windows copy to PATHS only: the
 * banner ships as geometry, exactly like any logo set in a licensed font - the
 * font file itself is never fetched or committed. The claim uses Arial Regular
 * (same family). Regenerating needs Arial/Arial Black installed locally.
 *
 * Text is converted to SVG paths (opentype.js) so the SVG is self-contained.
 * Glyph runs are shaped per glyph (charToGlyph + manual pair kerning) - some
 * fonts' GSUB ccmp lookups crash opentype.js's feature engine, and for plain
 * Latin the per-glyph path is lossless.
 *
 * The OLD logo-only banner is preserved as jdownloader-banner-logo.png/.svg -
 * support threads use that one; do not delete it.
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
const { Resvg } = require(`${gRoot}/@resvg/resvg-js`);
const opentype = require(`${gRoot}/opentype.js`);

const __dir = dirname(fileURLToPath(import.meta.url));

// ---- content + styling -----------------------------------------------------
const NAME = "JDOWNLOADER"; // all-caps, exactly like the official wordmark
const CLAIM = "Grab it. All of it. In the dark.";
// Official wordmark font + family for the claim (local Windows fonts).
const NAME_FONT = "C:/Windows/Fonts/ariblk.ttf"; // Arial Black (the official face)
const CLAIM_FONT = "C:/Windows/Fonts/arial.ttf"; // Arial Regular (same family)
const NAME_FILL = "#161616"; // Carbon - the logo circle + our dark-mode brand
const CLAIM_FILL = "#5a5d5e"; // house claim grey
const W = 1600, H = 500;
const LH = 360; // logo height (icon.svg is square, 48x48 units)
let nameSize = 150; // shrunk below to fit "JDOWNLOADER" (wide in Arial Black)
const claimSize = 42, gap = 64, lineGap = 22;
const MAX_GROUP = W - 160; // keep ~80px breathing room each side
// ---------------------------------------------------------------------------

function loadFont(path) {
  if (!existsSync(path)) {
    throw new Error(`font not found: ${path} (install Arial / Arial Black locally to regenerate)`);
  }
  const buf = readFileSync(path);
  return opentype.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength));
}
const nameFont = loadFont(NAME_FONT);
const claimFont = loadFont(CLAIM_FONT);

// Per-glyph shaping (charToGlyph + manual pair kerning) - bypasses opentype.js's
// crash-prone feature engine; lossless for plain Latin.
function shapeRun(font, text, size) {
  const scale = size / font.unitsPerEm;
  const run = [];
  let x = 0;
  let prev = null;
  for (const ch of text) {
    const g = font.charToGlyph(ch);
    if (prev) x += font.getKerningValue(prev, g) * scale;
    run.push({ g, x });
    x += g.advanceWidth * scale;
    prev = g;
  }
  return { run, width: x };
}
const runWidth = (font, text, size) => shapeRun(font, text, size).width;
function runPathData(font, text, x, y, size) {
  let d = "";
  for (const { g, x: gx } of shapeRun(font, text, size).run) {
    d += g.getPath(x + gx, y, size).toPathData(2);
  }
  return d;
}

const LW = LH; // square logo
const em = (f, s) => s / f.unitsPerEm;

// The official wordmark has an oversized initial "J" — render it larger than the
// rest, on the same baseline so it rises above "DOWNLOADER".
const J_SCALE = 1.5;
const REST = NAME.slice(1); // "DOWNLOADER"
const innerGapFor = (sz) => sz * 0.04;
const nameWidthFor = (sz) =>
  runWidth(nameFont, "J", Math.round(sz * J_SCALE)) + innerGapFor(sz) + runWidth(nameFont, REST, sz);

// Shrink the wordmark until the logo + name group fits the card with margins.
while (nameSize > 80 && LW + gap + nameWidthFor(nameSize) > MAX_GROUP) {
  nameSize -= 2;
}
const jSize = Math.round(nameSize * J_SCALE);
const jW = runWidth(nameFont, "J", jSize);
const innerGap = innerGapFor(nameSize);
const nameW = jW + innerGap + runWidth(nameFont, REST, nameSize);
const claimW = runWidth(claimFont, CLAIM, claimSize);
const groupW = LW + gap + Math.max(nameW, claimW);
const startX = (W - groupW) / 2;
const LX = startX, LY = (H - LH) / 2;
const textX = startX + LW + gap;

const jAsc = nameFont.ascender * em(nameFont, jSize); // the big J defines the top
const claimAsc = claimFont.ascender * em(claimFont, claimSize);
const blockH = jAsc + lineGap + claimAsc;
const nameBaseline = H / 2 - blockH / 2 + jAsc;
const claimBaseline = nameBaseline + lineGap + claimAsc;

const namePath =
  runPathData(nameFont, "J", textX, nameBaseline, jSize) +
  runPathData(nameFont, REST, textX + jW + innerGap, nameBaseline, nameSize);
const claimPath = runPathData(claimFont, CLAIM, textX, claimBaseline, claimSize);

// Embed the Carbon globe (icon.svg, 48x48) verbatim - only the root tag gets
// position/size attributes; the artwork inside is untouched.
let logo = readFileSync(join(__dir, "icon.svg"), "utf8").replace(/<\?xml[^>]*\?>\s*/, "");
logo = logo.replace(
  /<svg[\s\S]*?>/,
  `<svg x="${LX.toFixed(1)}" y="${LY.toFixed(1)}" width="${LW}" height="${LH}" viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg">`,
);

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="JDownloader">
  <rect width="${W}" height="${H}" fill="#ffffff"/>
  ${logo}
  <path d="${namePath}" fill="${NAME_FILL}"/>
  <path d="${claimPath}" fill="${CLAIM_FILL}"/>
</svg>
`;
writeFileSync(join(__dir, "jdownloader-banner.svg"), svg);

const png = new Resvg(svg, { fitTo: { mode: "width", value: W }, background: "white" }).render().asPng();
writeFileSync(join(__dir, "jdownloader-banner.png"), png);
console.log(`wrote jdownloader-banner.svg + .png (name ${Math.round(nameW)}px, claim ${Math.round(claimW)}px, group ${Math.round(groupW)}px)`);
