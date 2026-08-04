/**
 * Generates the JDownloader README banners (theme-adaptive pair):
 *   jdownloader-banner.svg / .png      : light 1600x500 - globe on the LEFT, then the
 *                                        "JDOWNLOADER" wordmark + a cheeky claim.
 *   jdownloader-banner-dark.svg / .png : same layout on GitHub-dark #0d1117, light text.
 * The README serves the pair via <picture> (prefers-color-scheme).
 *
 * Wordmark: a FAITHFUL reproduction of the user-built wordmark (_fonts/wordmark-source.svg,
 * "Element 1.svg") - JDownloader's real look done as a mix of Myriad Pro weights:
 *   - "DOWNLOADER" in Myriad Pro BLACK at 190, with the per-run letter-spacing from the source.
 *   - the initial "J" in Myriad Pro SEMIBOLD at 300 - bigger, so it overshoots up + down, but
 *     the lighter Semibold weight at the larger size keeps the stroke matched to the Black caps
 *     (bigger, not heavier).
 *   - a horizontal crossbar across the top of the J, drawn as a rectangle.
 * All in the source SVG's own coordinate system (viewBox 1324.24 x 326.1); we render the glyph
 * runs to VECTOR PATHS (opentype.js) and place the block beside the globe.
 *
 * The Myriad Pro OTFs live at .github/assets/_fonts/ (gitignored - the font files are NEVER
 * committed; only the outlines land in the SVG). Nominative use of the product's own mark.
 * Letters are FLAT: #161616 on the light card, light on dark. The claim uses Lato (OFL).
 * The globe (icon.svg: green earth + gold arrow) is embedded verbatim in both themes.
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
const { Resvg } = require(`${gRoot}/@resvg/resvg-js`); // needed early for the wordmark bbox
const __dir = dirname(fileURLToPath(import.meta.url));

// ---- content + styling -----------------------------------------------------
const CLAIM = "Grab it. All of it. In the dark.";
const THEMES = [
  { suffix: "",      bg: "#ffffff", name: "#1f2328", claim: "#5a5d5e" },
  { suffix: "-dark", bg: "#0d1117", name: "#e6edf3", claim: "#9aa4ad" },
];
const W = 1600, H = 500;
const LH = 470, LW = LH;      // globe on the left (square) — jdp big-logo standard (~400px ink)
const gap = 70, lineGap = 26;
const claimSize = 44;
const WM_H = 214;             // rendered wordmark height in the banner
const MAX_GROUP = W - 150;
// Source wordmark geometry (from the user's Element 1.svg; viewBox 1324.24 x 326.1) --------
const SRC_VB = { w: 1324.24, h: 326.1 };
const CROSSBAR = { x: 27.78, y: 48.9, w: 70.62, h: 24.14 };
const DL_BASE = { x: 101.22, y: 205.77, size: 190 };   // "DOWNLOADER" in Black
const DL_RUNS = [                                       // [text, x-offset, letter-spacing em]
  ["D", 0, -0.04], ["O", 127.3, -0.05], ["WN", 256.12, -0.04],
  ["L", 547, -0.08], ["O", 632.51, -0.06], ["ADER", 760.57, -0.04],
];
const J_RUN = { text: "J", x: 0, y: 251.1, size: 300 }; // "J" in Semibold
// ---------------------------------------------------------------------------

const black = opentype.parse(readFileSync(join(__dir, "_fonts", "MyriadPro-Black.otf")).buffer);
const semi = opentype.parse(readFileSync(join(__dir, "_fonts", "MyriadPro-Semibold.otf")).buffer);
const latoFile = join(tmpdir(), "JD-Lato-Regular.ttf");
if (!existsSync(latoFile)) {
  const r = await fetch("https://github.com/google/fonts/raw/main/ofl/lato/Lato-Regular.ttf");
  if (!r.ok) throw new Error(`Lato fetch ${r.status}`);
  writeFileSync(latoFile, Buffer.from(await r.arrayBuffer()));
}
const lato = opentype.parse(readFileSync(latoFile).buffer);

// Render a text run to a path at (x, baseline) with optional letter-spacing (em). y-down coords.
function runPath(font, text, x, baseline, size, lsEm = 0) {
  const s = size / font.unitsPerEm, ls = lsEm * size, n = (v) => v.toFixed(2);
  let d = "", cx = x;
  for (const ch of text) {
    const g = font.charToGlyph(ch);
    for (const c of g.path.commands) {
      if (c.type === "M") d += `M${n(cx + c.x * s)} ${n(baseline - c.y * s)}`;
      else if (c.type === "L") d += `L${n(cx + c.x * s)} ${n(baseline - c.y * s)}`;
      else if (c.type === "C") d += `C${n(cx + c.x1 * s)} ${n(baseline - c.y1 * s)} ${n(cx + c.x2 * s)} ${n(baseline - c.y2 * s)} ${n(cx + c.x * s)} ${n(baseline - c.y * s)}`;
      else if (c.type === "Q") d += `Q${n(cx + c.x1 * s)} ${n(baseline - c.y1 * s)} ${n(cx + c.x * s)} ${n(baseline - c.y * s)}`;
      else if (c.type === "Z") d += "Z";
    }
    cx += g.advanceWidth * s + ls;
  }
  return d;
}
function runWidth(font, text, size) {
  const s = size / font.unitsPerEm;
  let w = 0;
  for (const ch of text) w += font.charToGlyph(ch).advanceWidth * s;
  return w;
}

// Build the wordmark (glyph paths + crossbar) in the SOURCE coordinate system.
let wordmarkPath = runPath(semi, J_RUN.text, J_RUN.x, J_RUN.y, J_RUN.size);
for (const [text, dx, ls] of DL_RUNS)
  wordmarkPath += runPath(black, text, DL_BASE.x + dx, DL_BASE.y, DL_BASE.size, ls);
const crossbarPath = `M${CROSSBAR.x} ${CROSSBAR.y} h${CROSSBAR.w} v${CROSSBAR.h} h${-CROSSBAR.w} Z`;
if ((wordmarkPath + crossbarPath).includes("NaN")) throw new Error("NaN path");

// FIXED layout, matching the user's hand-refined theme banner: the globe box sits at a fixed left so
// its circle centre lands at (293, 242); the wordmark starts at a fixed x=522 (its crossbar aligns to
// the user's x), 214px tall; the claim is centred under the wordmark. "JDOWNLOADER" is shorter than
// "JD HIGHLIGHTER" so it leaves more room on the right — the same slightly left-weighted balance.
// House banner standard: logo left-anchored (165), wordmark to its right, the
// [wordmark + claim] block vertically centred; claim left-aligned with the
// wordmark and pulled close (gap 8). Sized + placed by the wordmark's real ink bbox.
const startX = 165, LY = (H - LH) / 2;
const textX = startX + LW + gap;
const WM_TARGET = 150;                                     // visual wordmark height (~ the 132px text names)
const bb = new Resvg(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${Math.ceil(SRC_VB.w) + 40} ${Math.ceil(SRC_VB.h) + 40}"><path d="${wordmarkPath}${crossbarPath}"/></svg>`,
  { fitTo: { mode: "original" } },
).getBBox();
const s2 = Math.min(WM_TARGET / bb.height, (W - textX - 80) / bb.width);   // height target, width-capped so JDOWNLOADER can't overflow
const wmH = bb.height * s2;
const wmWFit = bb.width * s2;
const claimAsc = lato.ascender * claimSize / lato.unitsPerEm;
const claimDesc = -lato.descender * claimSize / lato.unitsPerEm;
const NAME_CLAIM_GAP = 8;
const blockH = wmH + NAME_CLAIM_GAP + claimAsc + claimDesc;
const top = H / 2 - blockH / 2;
const wmX = textX - bb.x * s2;                             // left-anchor the wordmark's ink at textX
const wmTop = top - bb.y * s2;                             // wordmark visible top -> `top`
const claimBaseline = top + wmH + NAME_CLAIM_GAP + claimAsc;
const claimStartX = textX + (wmWFit - runWidth(lato, CLAIM, claimSize)) / 2; // claim centred on the wordmark
const claimPath = runPath(lato, CLAIM, claimStartX, claimBaseline, claimSize);

// Globe: light card keeps the Carbon-dark body; dark card lightens it so it reads on #0d1117.
const iconRaw = readFileSync(join(__dir, "icon.svg"), "utf8").replace(/<\?xml[^>]*\?>\s*/, "");
const placeLogo = (svgStr) => svgStr.replace(/<svg[\s\S]*?>/,
  `<svg x="${startX.toFixed(1)}" y="${LY.toFixed(1)}" width="${LW}" height="${LH}" viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg">`);
const logoLight = placeLogo(iconRaw);
const logoDark = placeLogo(iconRaw.replace(/#161616/gi, "#2d333b").replace(/#0b0b0b/gi, "#21262d"));

for (const t of THEMES) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="JDownloader">
  <rect width="${W}" height="${H}" fill="${t.bg}"/>
  ${t.suffix === "-dark" ? logoDark : logoLight}
  <g transform="translate(${wmX.toFixed(2)} ${wmTop.toFixed(2)}) scale(${s2.toFixed(5)})">
    <path d="${wordmarkPath}" fill="${t.name}"/>
    <path d="${crossbarPath}" fill="${t.name}"/>
  </g>
  <path d="${claimPath}" fill="${t.claim}"/>
</svg>
`;
  writeFileSync(join(__dir, `jdownloader-banner${t.suffix}.svg`), svg);
  const png = new Resvg(svg, { fitTo: { mode: "width", value: W }, background: t.bg }).render().asPng();
  writeFileSync(join(__dir, `jdownloader-banner${t.suffix}.png`), png);
  console.log(`wrote jdownloader-banner${t.suffix}.svg + .png (wordmark ${Math.round(wmWFit)}x${Math.round(SRC_VB.h * s2)})`);
}
