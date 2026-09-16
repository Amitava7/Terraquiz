#!/usr/bin/env python3
"""Read app/src/main/assets/world.bin back and print it.

Doubles as a check on the format the Java loader expects: if this parses the
whole file and ends exactly at EOF, the layout is self-consistent.
"""

import os
import struct
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                   "app", "src", "main", "assets", "world.bin")


class Reader(object):
    def __init__(self, data):
        self.d = data
        self.i = 0

    def u8(self):
        v = self.d[self.i]
        self.i += 1
        return v if isinstance(v, int) else ord(v)

    def f64(self):
        v = struct.unpack_from(">d", self.d, self.i)[0]
        self.i += 8
        return v

    def uvar(self):
        shift = 0
        out = 0
        while True:
            b = self.u8()
            out |= (b & 0x7F) << shift
            if not b & 0x80:
                return out
            shift += 7

    def svar(self):
        v = self.uvar()
        return (v >> 1) if not v & 1 else -((v + 1) >> 1)

    def text(self):
        n = self.uvar()
        s = self.d[self.i:self.i + n].decode("utf-8")
        self.i += n
        return s


def load():
    with open(OUT, "rb") as fh:
        r = Reader(fh.read())
    assert r.d[:3] == b"TQD", "bad magic"
    r.i = 3
    version = r.u8()
    sx, sy, tx, ty = r.f64(), r.f64(), r.f64(), r.f64()
    arcs = []
    for _ in range(r.uvar()):
        n = r.uvar()
        x = y = 0
        pts = []
        for _ in range(n):
            x += r.svar()
            y += r.svar()
            pts.append((x, y))
        arcs.append(pts)
    countries = []
    for _ in range(r.uvar()):
        c = {"name": r.text()}
        c["flags"] = r.u8()
        c["a2"] = r.text()
        c["sovereign"] = r.text()
        c["aliases"] = [r.text() for _ in range(r.uvar())]
        c["label"] = (r.svar(), r.svar())
        x0, y0 = r.svar(), r.svar()
        c["bbox"] = (x0, y0, x0 + r.svar(), y0 + r.svar())
        mx0, my0 = x0 + r.svar(), y0 + r.svar()
        c["mainbox"] = (mx0, my0, mx0 + r.svar(), my0 + r.svar())
        c["polys"] = [[[r.svar() for _ in range(r.uvar())] for _ in range(r.uvar())]
                      for _ in range(r.uvar())]
        c["facts"] = [r.text() for _ in range(r.uvar())]
        c["hints"] = [r.text() for _ in range(r.uvar())]
        countries.append(c)
    assert r.i == len(r.d), "trailing bytes: read %d of %d" % (r.i, len(r.d))
    return version, (sx, sy, tx, ty), arcs, countries


def main():
    version, tr, arcs, countries = load()
    wanted = [a.lower() for a in sys.argv[1:]]
    print("format v%d, %d arcs, %d countries" % (version, len(arcs), len(countries)))
    for c in countries:
        if wanted and c["name"].lower() not in wanted:
            continue
        if not wanted and not c["flags"] & 1:
            continue
        lon = c["label"][0] * tr[0] + tr[2]
        lat = c["label"][1] * tr[1] + tr[3]
        print("\n== %s (%s)%s  label %.1f,%.1f  parts %d" %
              (c["name"], c["a2"], " territory of " + c["sovereign"] if c["sovereign"] else "",
               lon, lat, len(c["polys"])))
        print("   also accepted: %s" % ", ".join(c["aliases"]))
        for f in c["facts"]:
            print("   * %s" % f)
        for h in c["hints"]:
            print("   ? %s" % h)


if __name__ == "__main__":
    main()
