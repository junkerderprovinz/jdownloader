#!/usr/bin/env python3
# jd-mono-icons.py <iconset-dir> <mono-hex>
# Recolours JD's flat icon set in place to one tone. JD ignores a custom iconsetid and
# extracts flat again at GUI start, so a separate mono copy would never show. A trailing
# marker makes reruns skip icons that are done, which lets autostart run it again after
# JD's late extraction. Country flags keep their colours.
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
    # icons8 draws most glyphs as strokes. Near-white fill knockouts turn transparent so
    # the glyph reads on any surface.
    if 'stroke' in m.group(1):
        return m.group(1) + mono
    return m.group(1) + ('none' if lum(m.group(2)) >= 0.82 else mono)

changed = 0
for dirpath, _, files in os.walk(root):
    parts = dirpath.replace(os.sep, '/').lower().split('/')
    if 'flags' in parts:
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
        if MARK in svg:
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
