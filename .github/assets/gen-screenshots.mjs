/**
 * Renders the README pictures in the store style: 1920x1000, the logo as a relief behind the
 * caption and the raw capture of the test container in a neutral, slightly tilted browser window.
 * The raw captures are 1904x952 grabs of the X display (screenshots/raw), taken with the same
 * packages every time: Big Buck Bunny, Sintel and Linux ISOs.
 *
 * Needs playwright-core installed globally with its Chromium.
 * Run: node .github/assets/gen-screenshots.mjs
 */
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import { execSync } from "node:child_process";

const require = createRequire(import.meta.url);
const { chromium } = require(`${execSync("npm root -g").toString().trim()}/playwright-core`);

const here = dirname(fileURLToPath(import.meta.url));
const shots = join(here, "screenshots");

async function cached(file, url) {
  const path = join(tmpdir(), `jdownloader-shots-${file}`);
  if (!existsSync(path)) {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`${file}: fetch ${res.status}`);
    writeFileSync(path, Buffer.from(await res.arrayBuffer()));
  }
  return readFileSync(path);
}

const dataUrl = (buf, type) => `data:${type};base64,${buf.toString("base64")}`;
const fonts = "https://github.com/google/fonts/raw/main/ofl";
const bree = dataUrl(await cached("BreeSerif-Regular.ttf", `${fonts}/breeserif/BreeSerif-Regular.ttf`), "font/ttf");
const lato = dataUrl(await cached("Lato-Regular.ttf", `${fonts}/lato/Lato-Regular.ttf`), "font/ttf");
const logo = dataUrl(readFileSync(join(here, "icon.svg")), "image/svg+xml");

const SHOTS = [
  {
    raw: "downloads",
    caption: "Downloads, <em>minus the ads</em>",
    sub: "JDownloader 2 on Unraid, the full desktop app in your browser",
  },
  {
    raw: "linkgrabber",
    caption: "Paste the links, <em>it sorts them</em>",
    sub: "The LinkGrabber checks every link and groups them into packages before anything loads",
  },
  {
    raw: "settings",
    caption: "Dark down to <em>the settings</em>",
    sub: "One palette and your accent colour for the download list, the LinkGrabber and every settings page",
  },
  {
    raw: "light",
    caption: "Also in <em>light</em>",
    sub: "JD Highlighter Light, picked under Settings › User Interface, with the plain looks next to it",
  },
];

const W = 1920;
const H = 1000;
const VIEW_W = 1252;
const VIEW_H = 626;
const TABS_H = 38;
const BAR_H = 42;

function frame(shot, png) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
@font-face { font-family: "Bree Serif"; src: url(${bree}); }
@font-face { font-family: Lato; src: url(${lato}); }
* { box-sizing: border-box; margin: 0; }
body { width: ${W}px; height: ${H}px; overflow: hidden; position: relative; font-family: Lato, sans-serif; background: #0c0c0b; }
.backdrop, .backdrop * { position: absolute; }
.backdrop { inset: 0; overflow: hidden; }
.wall { inset: 0; background: radial-gradient(55% 60% at 62% 35%, #26231d, #0f0e0c 72%); }
.mark { left: -9%; top: -4%; width: 46%; transform: rotate(-10deg); opacity: .32;
  filter: grayscale(1) brightness(.36) contrast(1.2) drop-shadow(-2px -2px 0 rgba(255,255,255,.16)) drop-shadow(12px 18px 26px rgba(0,0,0,.85)); }
.vignette { inset: 0; box-shadow: inset 0 0 200px rgba(0,0,0,.6); }
.copy { position: absolute; left: 84px; top: 0; bottom: 0; width: 470px; display: flex; flex-direction: column; justify-content: center; gap: 28px; }
.copy img { width: 96px; }
h1 { font: 400 60px/1.12 "Bree Serif", serif; color: #f4f4f4; text-wrap: balance; }
h1 em { font-style: normal; color: #FCC419; }
.copy p { font-size: 26px; line-height: 1.45; color: #9d9481; }
.stage { position: absolute; left: 630px; top: ${(H - VIEW_H - TABS_H - BAR_H) / 2}px; width: ${VIEW_W}px; height: ${VIEW_H + TABS_H + BAR_H}px; perspective: 2400px; }
.floor { position: absolute; left: 6%; right: 6%; bottom: -34px; height: 60px; border-radius: 50%; background: rgba(0,0,0,.75); filter: blur(28px); }
.win { position: absolute; inset: 0; border-radius: 12px; background: #1c1c1c; transform: rotateY(-7deg) rotateX(3deg); overflow: hidden;
  box-shadow: 0 0 0 1px #333, 0 1px 0 1px rgba(255,255,255,.05), 0 2px 4px rgba(0,0,0,.35), 0 16px 32px rgba(0,0,0,.4), 0 48px 96px rgba(0,0,0,.5); }
.tabs { height: ${TABS_H}px; display: flex; align-items: flex-end; padding: 0 12px; background: #1c1c1c; }
.tab { height: 30px; width: 230px; display: flex; align-items: center; gap: 10px; padding: 0 14px; border-radius: 10px 10px 0 0; background: #242424; color: #d4d4d4; font-size: 14px; }
.tab img { width: 16px; height: 16px; }
.bar { height: ${BAR_H}px; display: flex; align-items: center; gap: 16px; padding: 0 14px; background: #242424; border-bottom: 1px solid #0e0e0e; }
.nav { display: flex; gap: 14px; color: #7c7c7c; } .nav svg { width: 16px; height: 16px; display: block; }
.url { flex: 1; height: 28px; border-radius: 14px; background: #2b2b2b; color: #b4b4b4; font-size: 14px; display: flex; align-items: center; padding: 0 16px; }
.view img { display: block; }
</style></head><body>
<div class="backdrop"><div class="wall"></div><img class="mark" src="${logo}"><div class="vignette"></div></div>
<div class="copy"><img src="${logo}"><h1>${shot.caption}</h1><p>${shot.sub}</p></div>
<div class="stage">
  <div class="floor"></div>
  <div class="win">
    <div class="tabs"><div class="tab"><img src="${logo}">JDownloader 2</div></div>
    <div class="bar">
      <div class="nav">
        <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M10 3 5 8l5 5"/></svg>
        <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="m6 3 5 5-5 5"/></svg>
      </div>
      <div class="url">nas.local:3001</div>
    </div>
    <div class="view"><img src="${dataUrl(png, "image/png")}" width="${VIEW_W}" height="${VIEW_H}"></div>
  </div>
</div>
</body></html>`;
}

const browser = await chromium.launch();
try {
  for (const shot of SHOTS) {
    const raw = readFileSync(join(shots, "raw", `${shot.raw}.png`));
    // Rendered at twice the size and drawn at half, or the tilt softens the small text.
    const big = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 2 });
    await big.setContent(frame(shot, raw));
    await big.evaluate(() => document.fonts.ready);
    await big.evaluate(() => Promise.all([...document.images].map((img) => img.decode())));
    const png = await big.screenshot();
    await big.close();
    const out = await browser.newPage({ viewport: { width: W, height: H } });
    await out.setContent(`<body style="margin:0"><img src="${dataUrl(png, "image/png")}" width="${W}" height="${H}" style="display:block">`);
    await out.evaluate(() => Promise.all([...document.images].map((img) => img.decode())));
    await out.screenshot({ path: join(shots, `jdownloader-${shot.raw}.png`) });
    await out.close();
    console.log(`wrote jdownloader-${shot.raw}.png`);
  }
} finally {
  await browser.close();
}
