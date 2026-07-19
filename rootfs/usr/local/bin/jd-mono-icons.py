#!/usr/bin/env python3
# jd-mono-icons.py <iconset-dir> <mono-hex>
# Recolour JD's flat iconset IN PLACE to a single mono tone. JD renders the registered "flat"
# set directly (it ignores a custom iconsetid) and re-extracts flat at GUI start, so a separate
# mono copy never shows — the only thing that sticks is recolouring flat itself. Idempotent via a
# trailing marker comment, so a background re-run (to catch JD's late extraction) is safe and
# only ever touches freshly-extracted colourful SVGs. Flags stay in colour (meaningful);
# everything else (incl. logos) goes mono for the clean, elegant look.
import os, re, sys

root = sys.argv[1]
mono = sys.argv[2]
MARK = "<!--hlmono-->"

def lum(h):
    h = h.lstrip('#')
    if len(h) == 3:
        h = ''.join(c * 2 for c in h)
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    def lin(c):
        c /= 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)

fill_re = re.compile(r'((?:fill|stroke|stop-color)\s*[:=]\s*["\']?)(#[0-9a-fA-F]{3,6})')

def sub(m):
    # strokes are the visible outline (icons8 draws most glyphs with stroke=) -> always mono.
    # fills: near-white knockouts -> transparent so the glyph reads on any surface; else mono.
    if 'stroke' in m.group(1):
        return m.group(1) + mono
    return m.group(1) + ('none' if lum(m.group(2)) >= 0.82 else mono)

changed = 0
for dirpath, _, files in os.walk(root):
    parts = dirpath.replace(os.sep, '/').lower().split('/')
    if 'flags' in parts:            # keep country flags in colour (they are meaningful)
        continue
    for fn in files:
        if not fn.lower().endswith('.svg'):
            continue
        p = os.path.join(dirpath, fn)
        try:
            with open(p, 'r', encoding='utf-8', errors='ignore') as fh:
                svg = fh.read()
        except Exception:
            continue
        if MARK in svg:             # already mono'd -> idempotent, skip
            continue
        new = fill_re.sub(sub, svg) + MARK
        try:
            with open(p, 'w', encoding='utf-8') as fh:
                fh.write(new)
            if new != svg + MARK:
                changed += 1
        except Exception:
            pass
print("[jdownloader-theme] mono icons (in-place): recoloured %d svgs in %s (mono=%s)" % (changed, root, mono))
