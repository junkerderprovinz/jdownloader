/**
 * Generates the JDownloader README banners (theme-adaptive pair):
 *   jdownloader-banner.svg / .png      : light 1600x500 - globe on the LEFT, then the
 *                                        "JDOWNLOADER" wordmark + a cheeky claim.
 *   jdownloader-banner-dark.svg / .png : same layout on GitHub-dark #0d1117, light text.
 * The README serves the pair via <picture> (prefers-color-scheme).
 *
 * Wordmark: JDownloader's own website wordmark is Myriad Pro Bold with TWO modifications
 * to the initial J - it is enlarged so it overshoots the other caps BOTH up and down, and
 * it carries a horizontal crossbar across its top (Myriad's plain J has neither). The
 * geometry below is MEASURED from the original logo (ratios are relative to the main cap
 * height): the J's cap top sits J_OVER_UP above the caps, its hook drops J_OVER_DOWN below
 * the baseline, and the crossbar's thickness/length are CROSSBAR_TH/CROSSBAR_LEN.
 *
 * The glyphs are rendered to VECTOR PATHS from a LOCAL, gitignored copy of Myriad Pro Bold
 * (.github/assets/_fonts/MyriadPro-Bold.otf - the font file is NEVER committed; only the
 * outlines land in the SVG, as with any logo). Nominative use of the product's own mark
 * (this repo packages JDownloader). Letters are FLAT (no 3D bevel): #161616 on the light
 * card, light on dark. The claim uses Lato (OFL), fetched at runtime. The globe (icon.svg:
 * green earth + gold download arrow) is embedded verbatim in both themes.
 *
 * Requires Myriad Pro Bold at .github/assets/_fonts/MyriadPro-Bold.otf (ships with Adobe
 * apps; copy it there locally - it stays gitignored).
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
const __dir = dirname(fileURLToPath(import.meta.url));

// ---- content + styling -----------------------------------------------------
const NAME_POST = "DOWNLOADER"; // the J is handled separately (oversized + crossbar)
const CLAIM = "Grab it. All of it. In the dark.";
const THEMES = [
  { suffix: "",      bg: "#ffffff", name: "#161616", claim: "#5a5d5e" },
  { suffix: "-dark", bg: "#0d1117", name: "#e6edf3", claim: "#9aa4ad" },
];
const W = 1600, H = 500;
const LH = 300, LW = LH;      // globe on the left (square)
const gap = 60, lineGap = 22;
const claimSize = 40;
// Measured from the original logo (all as fractions of the MAIN cap height):
const J_OVER_UP = 0.27;       // J cap top above the other caps
const J_OVER_DOWN = 0.30;     // J hook below the baseline (overshoots DOWNLOADER downward)
const CROSSBAR_TH = 0.24;     // J crossbar thickness
const CROSSBAR_LEN = 0.47;    // J crossbar length, from the stem-right edge leftward
const THICKEN = 4;            // faux-weight: stroke the glyphs a touch (the logo's bevel reads heavier)
const MAX_GROUP = W - 160;
let nameSize = 200;           // shrunk below to fit beside the globe
// ---------------------------------------------------------------------------

const MYRIAD = join(__dir, "_fonts", "MyriadPro-Bold.otf");
if (!existsSync(MYRIAD)) throw new Error(`Myriad Pro Bold not found at ${MYRIAD} (copy the .otf there; it stays gitignored)`);
const nameFont = opentype.parse(readFileSync(MYRIAD).buffer);
const latoFile = join(tmpdir(), "JD-Lato-Regular.ttf");
if (!existsSync(latoFile)) {
  const r = await fetch("https://github.com/google/fonts/raw/main/ofl/lato/Lato-Regular.ttf");
  if (!r.ok) throw new Error(`Lato fetch ${r.status}`);
  writeFileSync(latoFile, Buffer.from(await r.arrayBuffer()));
}
const claimFont = opentype.parse(readFileSync(latoFile).buffer);
const emOf = (f) => f.unitsPerEm;

function runWidth(font, text, size) {
  const s = size / font.unitsPerEm;
  let w = 0;
  for (const ch of text) w += font.charToGlyph(ch).advanceWidth * s;
  return w;
}
// sizeY defaults to sizeX (uniform). Passing sizeY > sizeX stretches the glyph VERTICALLY
// only - taller, but the vertical stems keep the sizeX stroke width (used for the oversized J
// so it is bigger than the other letters WITHOUT getting heavier).
function runPath(font, text, x, baseline, sizeX, sizeY = sizeX) {
  const sx = sizeX / font.unitsPerEm, sy = sizeY / font.unitsPerEm, n = (v) => v.toFixed(2);
  let d = "", cx = x;
  for (const ch of text) {
    const g = font.charToGlyph(ch);
    for (const c of g.path.commands) {
      if (c.type === "M") d += `M${n(cx + c.x * sx)} ${n(baseline - c.y * sy)}`;
      else if (c.type === "L") d += `L${n(cx + c.x * sx)} ${n(baseline - c.y * sy)}`;
      else if (c.type === "C") d += `C${n(cx + c.x1 * sx)} ${n(baseline - c.y1 * sy)} ${n(cx + c.x2 * sx)} ${n(baseline - c.y2 * sy)} ${n(cx + c.x * sx)} ${n(baseline - c.y * sy)}`;
      else if (c.type === "Q") d += `Q${n(cx + c.x1 * sx)} ${n(baseline - c.y1 * sy)} ${n(cx + c.x * sx)} ${n(baseline - c.y * sy)}`;
      else if (c.type === "Z") d += "Z";
    }
    cx += g.advanceWidth * sx;
  }
  return d;
}

const jbFU = nameFont.charToGlyph("J").getBoundingBox();     // J glyph bbox, font units
const jGlyphH = jbFU.y2 - jbFU.y1;                           // J glyph height, font units
const emU = emOf(nameFont);
const capU = nameFont.tables.os2.sCapHeight;                 // main cap height, font units

// The J is enlarged PROPORTIONALLY (uniform scale) so it overshoots up and down. It reads a
// touch heavier than the other letters, but a vertical-only stretch distorts the shape, so
// keep it proportional (the original's J is proportional too).
const jScaleFor = (size) => (1 + J_OVER_UP + J_OVER_DOWN) * (capU * size / emU) / jGlyphH;
const jAdvFor = (size) => nameFont.charToGlyph("J").advanceWidth * jScaleFor(size);
while (nameSize > 90 && LW + gap + jAdvFor(nameSize) + runWidth(nameFont, NAME_POST, nameSize) > MAX_GROUP) nameSize -= 2;

const capMain = capU * nameSize / emU;
const jScaleU = jScaleFor(nameSize);
const SJ = jScaleU * emU;
const jAdv = jAdvFor(nameSize);
const nameW = jAdv + runWidth(nameFont, NAME_POST, nameSize);
const claimW = runWidth(claimFont, CLAIM, claimSize);
const groupW = LW + gap + Math.max(nameW, claimW);
const startX = (W - groupW) / 2;
const LX = startX, LY = (H - LH) / 2;
const textX = startX + LW + gap;

// Vertical placement: the J spans J_OVER_UP above the caps to J_OVER_DOWN below the baseline.
const claimAsc = claimFont.ascender * claimSize / emOf(claimFont);
const jTopAbove = (1 + J_OVER_UP) * capMain;   // J cap top above the main baseline
const jBotBelow = J_OVER_DOWN * capMain;       // J hook below the main baseline
const blockH = jTopAbove + jBotBelow + lineGap + claimAsc;
const nameBaseline = H / 2 - blockH / 2 + jTopAbove;   // DOWNLOADER sits on this baseline
const claimBaseline = nameBaseline + jBotBelow + lineGap + claimAsc;

const jBaseline = nameBaseline + jBotBelow + jbFU.y1 * jScaleU;
const jPath = runPath(nameFont, "J", textX, jBaseline, SJ);
const restPath = runPath(nameFont, NAME_POST, textX + jAdv, nameBaseline, nameSize);
const namePath = jPath + restPath;

// J crossbar: a thin horizontal bar across the top of the J. Right edge at the stem/glyph
// right; length + thickness are the measured fractions of the main cap height. Thickness is
// reduced by THICKEN so the stroke brings it back to the measured value.
const barTop = nameBaseline - jTopAbove;
const barRight = textX + jbFU.x2 * jScaleU;
const barLeft = barRight - CROSSBAR_LEN * capMain;
const barTh = Math.max(2, CROSSBAR_TH * capMain - THICKEN);
const crossbar = `M${barLeft.toFixed(2)} ${barTop.toFixed(2)} H${barRight.toFixed(2)} V${(barTop + barTh).toFixed(2)} H${barLeft.toFixed(2)} Z`;

const claimPath = runPath(claimFont, CLAIM, textX, claimBaseline, claimSize);
if ([namePath, crossbar, claimPath].some((d) => d.includes("NaN"))) throw new Error("NaN path");

let logo = readFileSync(join(__dir, "icon.svg"), "utf8").replace(/<\?xml[^>]*\?>\s*/, "");
logo = logo.replace(/<svg[\s\S]*?>/,
  `<svg x="${LX.toFixed(1)}" y="${LY.toFixed(1)}" width="${LW}" height="${LH}" viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg">`);

const { Resvg } = require(`${gRoot}/@resvg/resvg-js`);
for (const t of THEMES) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="JDownloader">
  <rect width="${W}" height="${H}" fill="${t.bg}"/>
  ${logo}
  <path d="${namePath}" fill="${t.name}" stroke="${t.name}" stroke-width="${THICKEN}" stroke-linejoin="round"/>
  <path d="${crossbar}" fill="${t.name}" stroke="${t.name}" stroke-width="${THICKEN}" stroke-linejoin="round"/>
  <path d="${claimPath}" fill="${t.claim}"/>
</svg>
`;
  writeFileSync(join(__dir, `jdownloader-banner${t.suffix}.svg`), svg);
  const png = new Resvg(svg, { fitTo: { mode: "width", value: W }, background: t.bg }).render().asPng();
  writeFileSync(join(__dir, `jdownloader-banner${t.suffix}.png`), png);
  console.log(`wrote jdownloader-banner${t.suffix}.svg + .png (name ${Math.round(nameW)}px @ ${nameSize})`);
}
