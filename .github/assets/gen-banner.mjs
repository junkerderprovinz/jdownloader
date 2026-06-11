/**
 * Generates the JDownloader README banner (house banner convention):
 *   jdownloader-banner.svg / .png : white 1600x500 - the Carbon globe logo on
 *                                   the left, the wordmark + a cheeky claim.
 *
 * The official JDownloader wordmark (jdownloader.org header) is all-caps
 * "JDOWNLOADER" in a heavy condensed grotesque (Impact-style, glossy 2009 look).
 * We replicate it with Anton (OFL, the classic free Impact equivalent), flat in
 * Carbon #161616 - which is both the logo's own circle colour and this image's
 * dark-mode brand. The claim uses Roboto Regular. Fonts are fetched at runtime
 * via the Google-Fonts CSS API (legacy User-Agent -> static TTF URLs), cached
 * in the OS temp dir, and never committed.
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
const NAME_FILL = "#161616"; // Carbon - the logo circle + our dark-mode brand
const CLAIM_FILL = "#5a5d5e"; // house claim grey
const W = 1600, H = 500;
const LH = 400; // logo height (icon.svg is square, 48x48 units)
const nameSize = 150, claimSize = 42, gap = 64, lineGap = 22;
// ---------------------------------------------------------------------------

async function loadFont(spec, cacheName) {
  const path = join(tmpdir(), `jdownloader-${cacheName}.ttf`);
  if (!existsSync(path)) {
    const cssRes = await fetch(`https://fonts.googleapis.com/css2?family=${spec}`, {
      headers: { "User-Agent": "curl/8" }, // legacy UA -> static TTF, no subsets
    });
    if (!cssRes.ok) throw new Error(`font css ${spec}: ${cssRes.status}`);
    const m = (await cssRes.text()).match(/url\((https:[^)]+\.ttf)\)/);
    if (!m) throw new Error(`no ttf url in css for ${spec}`);
    const ttf = await fetch(m[1]);
    if (!ttf.ok) throw new Error(`font ttf ${spec}: ${ttf.status}`);
    writeFileSync(path, Buffer.from(await ttf.arrayBuffer()));
  }
  const buf = readFileSync(path);
  return opentype.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength));
}
const nameFont = await loadFont("Anton", "Anton-400");
const claimFont = await loadFont("Roboto:wght@400", "Roboto-400");

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

const nameW = runWidth(nameFont, NAME, nameSize);
const claimW = runWidth(claimFont, CLAIM, claimSize);
const LW = LH; // square logo
const groupW = LW + gap + Math.max(nameW, claimW);
const startX = (W - groupW) / 2;
const LX = startX, LY = (H - LH) / 2;
const textX = startX + LW + gap;

const em = (f, s) => s / f.unitsPerEm;
const nameAsc = nameFont.ascender * em(nameFont, nameSize);
const nameDesc = -nameFont.descender * em(nameFont, nameSize);
const claimAsc = claimFont.ascender * em(claimFont, claimSize);
const blockH = nameAsc + nameDesc + lineGap + claimAsc;
const nameBaseline = H / 2 - blockH / 2 + nameAsc;
const claimBaseline = nameBaseline + nameDesc + lineGap + claimAsc;

const namePath = runPathData(nameFont, NAME, textX, nameBaseline, nameSize);
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
