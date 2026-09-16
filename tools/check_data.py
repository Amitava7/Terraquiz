#!/usr/bin/env python3
"""Sanity checks on app/src/main/assets/world.bin.

These mirror what the Java loader does (World.assemble, Country.contains), so
they catch a broken asset before it ever reaches a phone. Run in CI.
"""

import sys
import unicodedata

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


# --------------------------------------------------------------------------
# A mirror of Names.java, so the rules the app answers by are pinned down here
# --------------------------------------------------------------------------

FOLD = {"\u00f8": "o", "\u0111": "d", "\u0142": "l", "\u00fe": "t", "\u00f0": "d"}
SPLIT = {"\u00e6": "ae", "\u0153": "oe", "\u00df": "ss"}


def normalise(s):
    out = []
    for ch in unicodedata.normalize("NFD", s):
        c = ch.lower()
        if 0x300 <= ord(c) <= 0x36F:
            continue
        if c in SPLIT:
            out.append(SPLIT[c])
            continue
        c = FOLD.get(c, c)
        if c.isascii() and (c.isalpha() or c.isdigit()):
            out.append(c)
    flat = "".join(out)
    if len(flat) > 3 and flat.startswith("the"):
        flat = flat[3:]      # "The Gambia" is the same answer as "Gambia"
    return flat


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(cur[j - 1] + 1, prev[j] + 1,
                         prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev[len(b)]


def tolerance(n):
    return 1 if n <= 6 else (2 if n <= 12 else 3)


def closeness(guess, c):
    return min([edit_distance(guess, normalise(c["name"]))]
               + [edit_distance(guess, normalise(a)) for a in c["aliases"]])


def accepts(typed, target, others):
    guess = normalise(typed)
    if not guess:
        return False
    mine = closeness(guess, target)
    if mine == 0:
        return True
    if mine > tolerance(len(guess)):
        return False
    return all(closeness(guess, o) > mine for o in others if o is not target)


# typed, the country it is meant to name, whether it should be accepted
TYPING_CASES = [
    ("Phillipines", "Philippines", True),
    ("Kazakstan", "Kazakhstan", True),
    ("Madgascar", "Madagascar", True),
    ("Kyrgistan", "Kyrgyzstan", True),
    ("Liechtenstien", "Liechtenstein", True),
    ("Argentinia", "Argentina", True),
    ("Jappan", "Japan", True),
    ("Cubaa", "Cuba", True),
    ("cuba", "Cuba", True),
    ("Turkey", "T\u00fcrkiye", True),
    ("Sao Tome and Principe", "S\u00e3o Tom\u00e9 and Pr\u00edncipe", True),
    ("Holland", "Netherlands", True),
    ("USA", "United States", True),
    ("America", "United States", True),
    ("UK", "United Kingdom", True),
    ("England", "United Kingdom", True),
    ("Burma", "Myanmar", True),
    ("Czech Republic", "Czechia", True),
    ("Bosnia", "Bosnia and Herzegovina", True),
    ("Zaire", "DR Congo", True),
    ("The Gambia", "Gambia", True),
    ("the Bahamas", "Bahamas", True),
    ("The Netherlands", "Netherlands", True),
    # these must never pass: another country is at least as close
    ("Iraq", "Iran", False),
    ("Iran", "Iraq", False),
    ("Niger", "Nigeria", False),
    ("Nigeria", "Niger", False),
    ("Austria", "Australia", False),
    ("Australia", "Austria", False),
    ("Zambia", "Gambia", False),
    ("Mali", "Malta", False),
    ("Chile", "China", False),
    ("Guinea", "Guyana", False),
    ("Slovakia", "Slovenia", False),
    ("North Korea", "South Korea", False),
    ("Sudan", "South Sudan", False),
]


def check_typing(quiz, fails):
    by_name = {c["name"]: c for c in quiz}
    for typed, name, want in TYPING_CASES:
        target = by_name.get(name)
        if target is None:
            fails.append("typing test names %r, which is not a country" % name)
            continue
        if accepts(typed, target, quiz) != want:
            fails.append("typing %r for %s should%s be accepted"
                         % (typed, name, "" if want else " not"))
    # whatever a country is called, typing that must name it
    for c in quiz:
        for nm in [c["name"]] + c["aliases"]:
            if not accepts(nm, c, quiz):
                fails.append("%s does not accept its own name %r" % (c["name"], nm))


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

    check_typing(quiz, fails)

    print("%d countries, %d quizzable, %d arcs, %d typing cases"
          % (len(countries), len(quiz), len(arcs), len(TYPING_CASES)))
    if fails:
        print("\n%d problem(s):" % len(fails))
        for f in sorted(set(fails)):
            print("  - %s" % f)
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
