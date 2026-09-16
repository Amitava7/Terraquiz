#!/usr/bin/env python3
"""Turn a raw `adb exec-out screencap` dump into ASCII and colour statistics.

CI cannot show pictures, so this prints the screen as text: the continents are
recognisable, and the percentages say whether the map drew at all. Used by
tools/smoke_test.sh to assert that the map is on screen rather than a blank
view or a screen of flat colour.

  adb exec-out screencap > screen.raw
  python3 tools/screen_ascii.py screen.raw --top 0.16 --bottom 0.86
"""

import argparse
import struct
import sys

# The app's palette, from Ui.java. Anything else is called "other".
PALETTE = [
    ("ocean", (0x07, 0x14, 0x1F), "."),
    ("land", (0x23, 0x38, 0x4A), "#"),
    ("border", (0x7A, 0xA1, 0xBE), "+"),
    ("bg", (0x0B, 0x11, 0x18), " "),
    ("panel", (0x12, 0x1C, 0x26), ","),
    ("green", (0x2F, 0xBF, 0x71), "G"),
    ("amber", (0xF0, 0xB4, 0x29), "A"),
    ("red", (0xE5, 0x54, 0x4B), "R"),
    ("accent", (0x3D, 0x9B, 0xE9), "B"),
    ("text", (0xE7, 0xEF, 0xF6), "T"),
]


def read_screencap(path):
    """screencap writes width, height, format, [colourspace], then RGBA."""
    with open(path, "rb") as fh:
        data = fh.read()
    w, h, _fmt = struct.unpack("<III", data[:12])
    for header in (12, 16):
        if len(data) - header == w * h * 4:
            return w, h, header, data
    sys.exit("not a screencap dump: %dx%d does not match %d bytes"
             % (w, h, len(data)))


def classify(r, g, b):
    best = None
    best_d = 1 << 30
    for name, (pr, pg, pb), ch in PALETTE:
        d = (r - pr) ** 2 + (g - pg) ** 2 + (b - pb) ** 2
        if d < best_d:
            best_d = d
            best = (name, ch)
    # far from everything in the palette: call it what it is
    if best_d > 4000:
        return "other", "?"
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("--cols", type=int, default=100)
    ap.add_argument("--rows", type=int, default=44)
    ap.add_argument("--top", type=float, default=0.0)
    ap.add_argument("--bottom", type=float, default=1.0)
    a = ap.parse_args()

    w, h, off, data = read_screencap(a.dump)
    y0, y1 = int(h * a.top), int(h * a.bottom)
    counts = {}
    lines = []
    for row in range(a.rows):
        y = y0 + (y1 - y0) * row // a.rows
        line = []
        for col in range(a.cols):
            x = w * col // a.cols
            i = off + (y * w + x) * 4
            name, ch = classify(data[i], data[i + 1], data[i + 2])
            counts[name] = counts.get(name, 0) + 1
            line.append(ch)
        lines.append("".join(line))

    print("screen %dx%d, sampled rows %d-%d" % (w, h, y0, y1))
    print("\n".join(lines))
    total = float(sum(counts.values()))
    parts = sorted(counts.items(), key=lambda kv: -kv[1])
    print(" ".join("%s=%.1f%%" % (k, 100 * v / total) for k, v in parts))
    # machine readable, for the shell to assert on
    for k, v in parts:
        print("PCT_%s %.1f" % (k.upper(), 100 * v / total))


if __name__ == "__main__":
    main()
