#!/usr/bin/env python3
"""Sanity checks on app/src/main/assets/world.bin.

These mirror what the Java loader does (World.assemble, Country.contains), so
they catch a broken asset before it ever reaches a phone. Run in CI.
"""

import sys

from build_data import split_at_dateline
from dump_data import load


def assemble(arcs, ring):
    """Same rule as World.assemble: arcs join end to end, ~i means reversed."""
    out = []
    for n, idx in enumerate(ring):
        a = arcs[~idx] if idx < 0 else arcs[idx]
        seg = a[::-1] if idx < 0 else a
        out.extend(seg if n == 0 else seg[1:])
    return out


def point_in_rings(px, py, rings):
    inside = False
    for pts in rings:
        j = len(pts) - 1
        for i in range(len(pts)):
            xi, yi = pts[i]
            xj, yj = pts[j]
            if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / float(yj - yi) + xi:
                inside = not inside
            j = i
    return inside


def to_map(pts, tr):
    """Grid units -> map units, exactly as World.load does: x = lon, y = -lat."""
    sx, sy, tx, ty = tr
    return [(x * sx + tx, -(y * sy + ty)) for x, y in pts]


def box_to_map(x0, y0, x1, y1, tr):
    sx, sy, tx, ty = tr
    a = x0 * sx + tx
    b = x1 * sx + tx
    c = -(y0 * sy + ty)
    d = -(y1 * sy + ty)
    return (min(a, b), min(c, d), max(a, b), max(c, d))


def main():
    version, tr, raw_arcs, countries = load()
    sx, sy, tx, ty = tr
    arcs = [to_map(a, tr) for a in raw_arcs]
    fails = []

    rings_of = {}
    for c in countries:
        rings = []
        for poly in c["polys"]:
            for ring in poly:
                pts = assemble(arcs, ring)
                if pts[0] != pts[-1]:
                    fails.append("%s: a ring does not close" % c["name"])
                if len(pts) < 4:
                    fails.append("%s: ring with only %d points" % (c["name"], len(pts)))
                rings.extend(split_at_dateline(pts))
        rings_of[c["name"]] = rings
        c["mapLabel"] = to_map([c["label"]], tr)[0]
        c["mapBox"] = box_to_map(*(list(c["bbox"]) + [tr]))
        c["mapMain"] = box_to_map(*(list(c["mainbox"]) + [tr]))

    # every label point must fall inside its own country and no other
    for c in countries:
        lx, ly = c["mapLabel"]
        if not point_in_rings(lx, ly, rings_of[c["name"]]):
            fails.append("%s: label point is outside the country" % c["name"])
        for other in countries:
            if other is c:
                continue
            ox0, oy0, ox1, oy1 = other["mapBox"]
            if not (ox0 <= lx <= ox1 and oy0 <= ly <= oy1):
                continue
            if point_in_rings(lx, ly, rings_of[other["name"]]):
                fails.append("%s: its label point also lands in %s"
                             % (c["name"], other["name"]))

    # bounding boxes must really bound, and the main box must sit inside them
    eps = 1e-4
    for c in countries:
        x0, y0, x1, y1 = c["mapBox"]
        outside = False
        for pts in rings_of[c["name"]]:
            for x, y in pts:
                if not (x0 - eps <= x <= x1 + eps and y0 - eps <= y <= y1 + eps):
                    outside = True
                    break
            if outside:
                break
        if outside:
            fails.append("%s: a point lies outside its bounding box" % c["name"])
        m = c["mapMain"]
        if not (x0 - eps <= m[0] <= m[2] <= x1 + eps and y0 - eps <= m[1] <= m[3] <= y1 + eps):
            fails.append("%s: main landmass box escapes the full box" % c["name"])

    # after the split no piece may still wrap the globe, or it would be drawn
    # as a line straight across the map
    for c in countries:
        for pts in rings_of[c["name"]]:
            if any(abs(pts[i + 1][0] - pts[i][0]) > 180 for i in range(len(pts) - 1)):
                fails.append("%s: outline jumps across the antimeridian" % c["name"])
                break

    # every quizzable country needs something to say and something to hint with
    quiz = [c for c in countries if c["flags"] & 1]
    for c in quiz:
        if len(c["facts"]) < 8:
            fails.append("%s: only %d facts" % (c["name"], len(c["facts"])))
        if len(c["hints"]) < 4:
            fails.append("%s: only %d hints" % (c["name"], len(c["hints"])))
        if not c["aliases"]:
            fails.append("%s: no accepted spellings" % c["name"])

    # no two quiz countries may share a normalised name, or typing is ambiguous
    seen = {}
    for c in quiz:
        for nm in [c["name"]] + c["aliases"]:
            k = "".join(ch for ch in nm.lower() if ch.isalnum())
            if k in seen and seen[k] != c["name"]:
                fails.append("%s and %s both answer to %r" % (seen[k], c["name"], nm))
            seen[k] = c["name"]

    print("%d countries, %d quizzable, %d arcs" % (len(countries), len(quiz), len(arcs)))
    if fails:
        print("\n%d problem(s):" % len(fails))
        for f in sorted(set(fails)):
            print("  - %s" % f)
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
