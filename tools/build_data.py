#!/usr/bin/env python3
"""Build app/src/main/assets/world.bin from public geodata.

Sources (downloaded with --download, cached in tools/cache/):
  * world-atlas 2.0.2 countries-50m.json  - TopoJSON of Natural Earth 1:50m
    admin-0 countries. Already topology-aware, so neighbouring countries share
    the exact same border arcs: no slivers, and every border is stored once.
  * mledoze/countries countries.json      - capital, currency, languages,
    borders, area, demonyms, calling codes (ODbL).
  * Natural Earth 50m admin-0 + marine polys - population, continent, seas.

Output is a single little-effort-to-parse binary blob: varint deltas over the
TopoJSON quantised grid, plus per-country text (facts + hints). Everything the
app needs is in this one file, so the APK carries no database and no JSON
parser.
"""

import argparse
import io
import json
import math
import os
import re
import struct
import sys
import tarfile
import unicodedata
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CACHE = os.path.join(HERE, "cache")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "world.bin")

SOURCES = {
    "world-atlas-2.0.2.tgz": "https://registry.npmjs.org/world-atlas/-/world-atlas-2.0.2.tgz",
    "mledoze.json": "https://raw.githubusercontent.com/mledoze/countries/master/countries.json",
    "ne50_countries.geojson": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_countries.geojson",
    "ne50_marine.geojson": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_geography_marine_polys.geojson",
}

FORMAT_VERSION = 1


# --------------------------------------------------------------------------
# download / load
# --------------------------------------------------------------------------

def fetch(force=False):
    os.makedirs(CACHE, exist_ok=True)
    for name, url in SOURCES.items():
        path = os.path.join(CACHE, name)
        if os.path.exists(path) and not force:
            continue
        sys.stderr.write("fetching %s\n" % url)
        urllib.request.urlretrieve(url, path)


def load_sources():
    missing = [n for n in SOURCES if not os.path.exists(os.path.join(CACHE, n))]
    if missing:
        sys.exit("missing cached sources %s - rerun with --download" % missing)
    with tarfile.open(os.path.join(CACHE, "world-atlas-2.0.2.tgz")) as tf:
        topo = json.load(tf.extractfile("package/countries-50m.json"))
    with open(os.path.join(CACHE, "mledoze.json"), encoding="utf-8") as fh:
        meta = json.load(fh)
    with open(os.path.join(CACHE, "ne50_countries.geojson"), encoding="utf-8") as fh:
        ne = json.load(fh)
    with open(os.path.join(CACHE, "ne50_marine.geojson"), encoding="utf-8") as fh:
        marine = json.load(fh)
    return topo, meta, ne, marine


# --------------------------------------------------------------------------
# geometry helpers (all in TopoJSON quantised grid units)
# --------------------------------------------------------------------------

def absolute_arcs(topo):
    """TopoJSON stores arcs delta encoded; return absolute integer points."""
    out = []
    for arc in topo["arcs"]:
        x = y = 0
        pts = []
        for dx, dy in arc:
            x += dx
            y += dy
            pts.append((x, y))
        out.append(pts)
    return out


def ring_points(arcs, ring):
    """Assemble a ring from signed arc indices (~i means reversed)."""
    pts = []
    for idx in ring:
        if idx < 0:
            seg = arcs[~idx][::-1]
        else:
            seg = arcs[idx]
        if pts:
            pts.extend(seg[1:])
        else:
            pts.extend(seg)
    return pts


def polygons_of(geom):
    """Normalise Polygon/MultiPolygon to a list of polygons (list of rings)."""
    t = geom.get("type")
    if t == "Polygon":
        return [geom["arcs"]]
    if t == "MultiPolygon":
        return list(geom["arcs"])
    return []


def split_at_dateline(pts):
    """Cut a ring where it wraps past 180 degrees and close each piece on the
    edge of the map. The Java loader (World.splitAtDateline) does the same, so
    both sides see identical outlines."""
    if not any(abs(pts[i + 1][0] - pts[i][0]) > 180 for i in range(len(pts) - 1)):
        return [pts]
    pieces = []
    cur = [pts[0]]
    for i in range(len(pts) - 1):
        (x0, y0), (x1, y1) = pts[i], pts[i + 1]
        if abs(x1 - x0) > 180:
            edge = 180.0 if x0 > 0 else -180.0
            span = (x1 + (360.0 if x0 > 0 else -360.0)) - x0
            t = 0.0 if span == 0 else (edge - x0) / span
            if not 0.0 <= t <= 1.0:
                t = 0.0
            yc = y0 + t * (y1 - y0)
            cur.append((edge, yc))
            pieces.append(cur)
            cur = [(-edge, yc)]
        cur.append((x1, y1))
    pieces.append(cur)
    if len(pieces) > 1:                      # the ring is a loop: last meets first
        last = pieces.pop()
        pieces[0] = last + pieces[0][1:]
    return [p for p in pieces if len(p) >= 3] or [pts]


def ring_area(pts):
    s = 0.0
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        s += x1 * y2 - x2 * y1
    return s / 2.0


def point_in_ring(px, py, pts):
    inside = False
    n = len(pts)
    j = n - 1
    for i in range(n):
        xi, yi = pts[i]
        xj, yj = pts[j]
        if (yi > py) != (yj > py):
            if px < (xj - xi) * (py - yi) / float(yj - yi) + xi:
                inside = not inside
        j = i
    return inside


def seg_point_dist2(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return (px - ax) ** 2 + (py - ay) ** 2
    t = ((px - ax) * dx + (py - ay) * dy) / float(dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    qx, qy = ax + t * dx, ay + t * dy
    return (px - qx) ** 2 + (py - qy) ** 2


# --------------------------------------------------------------------------
# text helpers
# --------------------------------------------------------------------------

def commas(n):
    return "{:,}".format(int(n))


def people(n):
    n = float(n)
    if n >= 1e9:
        return "%.2f billion" % (n / 1e9)
    if n >= 1e6:
        return "%.1f million" % (n / 1e6)
    if n >= 1e3:
        return "%s thousand" % round(n / 1e3)
    return commas(n)


def ordinal(n):
    n = int(n)
    if 10 <= n % 100 <= 20:
        suf = "th"
    else:
        suf = {1: "st", 2: "nd", 3: "rd"}.get(n % 10, "th")
    return "%d%s" % (n, suf)


def join_list(items, conj="and"):
    items = list(items)
    if not items:
        return ""
    if len(items) == 1:
        return items[0]
    return "%s %s %s" % (", ".join(items[:-1]), conj, items[-1])


def coord_ns(v):
    return "%.0f°%s" % (abs(v), "N" if v >= 0 else "S")


def ascii_fold(s):
    s = unicodedata.normalize("NFD", s)
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


# --------------------------------------------------------------------------
# curated extras: names people actually type / say
# --------------------------------------------------------------------------

# Map features that carry no ISO code, spelled out.
NO_CODE_NAMES = {
    "N. Cyprus": "Northern Cyprus",
    "Indian Ocean Ter.": "Indian Ocean Territories",
    "Siachen Glacier": "Siachen Glacier",
}


EXTRA_ALIASES = {
    "USA": ["America", "United States of America", "US", "USA"],
    "GBR": ["UK", "Britain", "Great Britain", "England"],
    "NLD": ["Holland", "The Netherlands"],
    "CZE": ["Czech Republic", "Czechia"],
    "MMR": ["Burma"],
    "CIV": ["Ivory Coast"],
    "CPV": ["Cape Verde"],
    "SWZ": ["Swaziland"],
    "MKD": ["Macedonia"],
    "TLS": ["East Timor"],
    "COD": ["DR Congo", "Congo Kinshasa", "Zaire", "Democratic Republic of the Congo"],
    "COG": ["Congo Brazzaville", "Republic of the Congo"],
    "KOR": ["South Korea"],
    "PRK": ["North Korea"],
    "ARE": ["UAE", "Emirates"],
    "VAT": ["Vatican"],
    "RUS": ["Russian Federation"],
    "TUR": ["Turkey", "Turkiye"],
    "CHN": ["China", "PRC"],
    "IRN": ["Persia"],
    "LAO": ["Laos"],
    "BRN": ["Brunei"],
    "TZA": ["Tanzania"],
    "BOL": ["Bolivia"],
    "VEN": ["Venezuela"],
    "SYR": ["Syria"],
    "MDA": ["Moldova"],
    "PSE": ["Palestine"],
    "GMB": ["Gambia"],
    "BHS": ["Bahamas"],
    "PHL": ["Philippines"],
    "SDN": ["Sudan"],
    "SSD": ["South Sudan"],
    "CAF": ["Central African Republic", "CAR"],
    "DOM": ["Dominican Republic"],
    "STP": ["Sao Tome and Principe"],
    "VCT": ["Saint Vincent", "St Vincent and the Grenadines"],
    "KNA": ["Saint Kitts", "St Kitts and Nevis"],
    "LCA": ["St Lucia"],
    "TTO": ["Trinidad", "Trinidad and Tobago"],
    "BIH": ["Bosnia", "Bosnia and Herzegovina"],
    "MHL": ["Marshall Islands"],
    "FSM": ["Micronesia"],
    "PNG": ["Papua New Guinea"],
    "SLB": ["Solomon Islands"],
    "NZL": ["Aotearoa"],
    "IRL": ["Eire", "Republic of Ireland"],
}


def build_aliases(rec, common, official):
    out = []
    seen = set()

    def add(s):
        if not s:
            return
        s = s.strip()
        key = normalise(s)
        if not key or key in seen or len(s) > 42:
            return
        seen.add(key)
        out.append(s)

    add(common)
    add(official)
    for s in rec.get("altSpellings", []):
        if len(s) <= 3 and s.isupper():
            continue  # bare country codes
        if "," in s:
            continue
        add(s)
    for s in EXTRA_ALIASES.get(rec.get("cca3", ""), []):
        add(s)
    folded = ascii_fold(common)
    if folded != common:
        add(folded)
    return out[:8]


def normalise(s):
    s = ascii_fold(s).lower()
    s = re.sub(r"[^a-z0-9]+", "", s)
    return s


# --------------------------------------------------------------------------
# main build
# --------------------------------------------------------------------------

def build():
    topo, meta, ne, marine = load_sources()

    arcs = absolute_arcs(topo)
    transform = topo["transform"]
    sx, sy = transform["scale"]
    tx, ty = transform["translate"]

    def to_lon(x):
        return x * sx + tx

    def to_lat(y):
        return y * sy + ty

    def to_x(lon):
        return int(round((lon - tx) / sx))

    def to_y(lat):
        return int(round((lat - ty) / sy))

    geoms = topo["objects"]["countries"]["geometries"]

    by_n3 = {c["ccn3"]: c for c in meta if c.get("ccn3")}
    by_a3 = {c["cca3"]: c for c in meta}
    ne_by_a3 = {}
    for f in ne["features"]:
        p = f["properties"]
        ne_by_a3.setdefault(p.get("ADM0_A3"), p)

    # ---- assemble country records -------------------------------------
    countries = []
    seen_ids = {}
    for g in geoms:
        name = g["properties"]["name"]
        gid = g.get("id")
        rec = by_n3.get(gid)
        polys = polygons_of(g)
        if not polys:
            continue
        if gid and gid in seen_ids:
            # world-atlas gives some outlying territories their parent's
            # country code (Ashmore and Cartier Is. -> Australia). Fold them
            # into the parent so a tap there names the parent.
            seen_ids[gid]["polys"].extend(polys)
            continue
        cc = {
            "name": NO_CODE_NAMES.get(name, rec["name"]["common"] if rec else name),
            "meta": rec,
            "polys": polys,
            # "~name" for features with no ISO code, so they can never collide
            # with a real alpha-3 (the map's "Indian Ocean Ter." is not India).
            "a3": rec["cca3"] if rec else "~" + name,
            "a2": rec["cca2"] if rec else "",
        }
        if gid:
            seen_ids[gid] = cc
        countries.append(cc)

    # geometry-derived values ------------------------------------------
    # Work in map units (x = longitude, y = -latitude) and split every ring
    # where it wraps the globe, so Russia's bounding box, label and area are
    # about Russia and not about the whole northern hemisphere.
    def to_map(pts):
        return [(x * sx + tx, -(y * sy + ty)) for x, y in pts]

    def to_grid(x, y):
        return int(round((x - tx) / sx)), int(round((-y - ty) / sy))

    arc_owner = {}
    for ci, cc in enumerate(countries):
        rings = []
        outer = []
        for poly in cc["polys"]:
            for ri, ring in enumerate(poly):
                for idx in ring:
                    arc_owner.setdefault(idx if idx >= 0 else ~idx, set()).add(ci)
                for piece in split_at_dateline(to_map(ring_points(arcs, ring))):
                    rings.append(piece)
                    if ri == 0:
                        outer.append(piece)
        cc["rings"] = rings
        biggest = max(outer, key=lambda p: abs(ring_area(p)))
        cc["biggest"] = biggest
        cc["grid_area"] = abs(ring_area(biggest))
        cc["parts"] = len(cc["polys"])
        xs = [p[0] for r in rings for p in r]
        ys = [p[1] for r in rings for p in r]
        cc["bbox_map"] = (min(xs), min(ys), max(xs), max(ys))
        bxs = [p[0] for p in biggest]
        bys = [p[1] for p in biggest]
        cc["mainbox_map"] = (min(bxs), min(bys), max(bxs), max(bys))
        cc["bbox"] = to_grid(cc["bbox_map"][0], cc["bbox_map"][3]) \
            + to_grid(cc["bbox_map"][2], cc["bbox_map"][1])
        cc["mainbox"] = to_grid(cc["mainbox_map"][0], cc["mainbox_map"][3]) \
            + to_grid(cc["mainbox_map"][2], cc["mainbox_map"][1])

    # label point: the roomiest spot inside the main landmass, so the name is
    # written on the country and not next to it
    for cc in countries:
        pts = cc["biggest"]
        minx, miny, maxx, maxy = cc["mainbox_map"]
        best = None
        best_d = -1
        steps = 14
        step = max(1, len(pts) // 250)
        for i in range(1, steps):
            for j in range(1, steps):
                px = minx + (maxx - minx) * i / float(steps)
                py = miny + (maxy - miny) * j / float(steps)
                if not point_in_ring(px, py, pts):
                    continue
                d = min(seg_point_dist2(px, py, pts[k][0], pts[k][1],
                                        pts[(k + 1) % len(pts)][0],
                                        pts[(k + 1) % len(pts)][1])
                        for k in range(0, len(pts), step))
                if d > best_d:
                    best_d = d
                    best = (px, py)
        if best is None:
            best = (sum(p[0] for p in pts) / float(len(pts)),
                    sum(p[1] for p in pts) / float(len(pts)))
        cc["label"] = to_grid(best[0], best[1])

    # longest shared border, from the arcs two countries have in common
    for cc in countries:
        cc["border_len"] = {}
    for ai, owners in arc_owner.items():
        if len(owners) < 2:
            continue
        pts = arcs[ai]
        length = 0.0
        for i in range(len(pts) - 1):
            dx = (pts[i + 1][0] - pts[i][0]) * sx
            if abs(dx) > 180:
                continue
            dy = (pts[i + 1][1] - pts[i][1]) * sy
            lat = math.radians(to_lat((pts[i][1] + pts[i + 1][1]) / 2.0))
            length += math.hypot(dx * math.cos(lat), dy) * 111.0
        for a in owners:
            for b in owners:
                if a != b:
                    countries[a]["border_len"][b] = countries[a]["border_len"].get(b, 0.0) + length

    # marine adjacency ---------------------------------------------------
    seas = []
    for f in marine["features"]:
        p = f["properties"]
        nm = p.get("name") or ""
        if not nm or p.get("featurecla") not in ("ocean", "sea", "gulf", "bay"):
            continue
        rings = []
        geo = f["geometry"]
        if geo["type"] == "Polygon":
            rings = [geo["coordinates"][0]]
        elif geo["type"] == "MultiPolygon":
            rings = [poly[0] for poly in geo["coordinates"]]
        if not rings:
            continue
        nm = nm.strip()
        if nm.isupper():
            nm = nm.title()
        pretty = re.sub(r"\s+", " ", nm)
        area = sum(abs(ring_area(r)) for r in rings)
        seas.append({"name": pretty, "rings": rings, "area": area,
                     "rank": {"ocean": 0, "sea": 1, "gulf": 2, "bay": 3}[p["featurecla"]],
                     "bbox": [(min(x for x, _ in r), min(y for _, y in r),
                              max(x for x, _ in r), max(y for _, y in r)) for r in rings]})

    TOL = 1.1  # degrees: a coastal vertex this close to a sea polygon counts
    for cc in countries:
        hits = {}
        # sample the coastline so this stays quick
        lonlat = []
        for pts in cc["rings"]:
            step = max(1, len(pts) // 120)
            lonlat.extend((x, -y) for x, y in pts[::step])
        for sea in seas:
            n = 0
            for lon, lat in lonlat:
                for ri, ring in enumerate(sea["rings"]):
                    bx0, by0, bx1, by1 = sea["bbox"][ri]
                    if lon < bx0 - TOL or lon > bx1 + TOL or lat < by0 - TOL or lat > by1 + TOL:
                        continue
                    if point_in_ring(lon, lat, ring):
                        n += 1
                        break
                    d2 = min(seg_point_dist2(lon, lat, ring[k][0], ring[k][1],
                                             ring[k + 1][0], ring[k + 1][1])
                             for k in range(len(ring) - 1))
                    if d2 <= TOL * TOL:
                        n += 1
                        break
                else:
                    continue
            if n:
                hits[sea["name"]] = (n, sea["rank"], sea["area"])
        ranked = sorted(hits.items(), key=lambda kv: (kv[1][1], -kv[1][0]))
        strong = [k for k, v in ranked if v[0] >= 2] or [k for k, v in ranked]
        cc["seas"] = strong[:3]

    # ---- quiz pool and rankings ---------------------------------------
    for cc in countries:
        m = cc["meta"]
        cc["quiz"] = bool(m and m.get("unMember"))
        nep = ne_by_a3.get(cc["a3"]) or {}
        cc["pop"] = nep.get("POP_EST")
        cc["gdp"] = nep.get("GDP_MD")
        cc["continent"] = nep.get("CONTINENT") or (m or {}).get("region") or ""
        cc["area_km2"] = (m or {}).get("area")
        if m and not m.get("independent"):
            cc["sovereign"] = (nep.get("SOVEREIGNT") or "").strip()
        else:
            cc["sovereign"] = ""

    quiz = [c for c in countries if c["quiz"]]
    n_landlocked = sum(1 for c in quiz if (c["meta"] or {}).get("landlocked"))
    for c in countries:
        c["n_landlocked"] = n_landlocked
    by_area = sorted([c for c in quiz if c["area_km2"]], key=lambda c: -c["area_km2"])
    for i, c in enumerate(by_area):
        c["area_rank"] = i + 1
    by_pop = sorted([c for c in quiz if c["pop"]], key=lambda c: -c["pop"])
    for i, c in enumerate(by_pop):
        c["pop_rank"] = i + 1
    n_area = len(by_area)
    n_pop = len(by_pop)

    name_by_a3 = {c["a3"]: c["name"] for c in countries}
    idx_by_a3 = {c["a3"]: i for i, c in enumerate(countries)}

    # ---- facts and hints ----------------------------------------------
    for cc in countries:
        cc["facts"] = make_facts(cc, name_by_a3, idx_by_a3, countries, n_area, n_pop,
                                 to_lon, to_lat)
        cc["hints"] = make_hints(cc, name_by_a3, n_area)
        m = cc["meta"] or {}
        cc["aliases"] = build_aliases(m, cc["name"],
                                      (m.get("name") or {}).get("official", ""))

    write_binary(countries, arcs, transform)
    report(countries, quiz)


def neighbour_names(cc, name_by_a3):
    m = cc["meta"] or {}
    return [name_by_a3.get(b, b) for b in m.get("borders", []) if b in name_by_a3]


def make_facts(cc, name_by_a3, idx_by_a3, countries, n_area, n_pop, to_lon, to_lat):
    m = cc["meta"] or {}
    name = cc["name"]
    out = []

    caps = m.get("capital") or []
    if caps:
        if len(caps) == 1:
            out.append("Its capital city is %s." % caps[0])
        else:
            out.append("It has %d capitals: %s." % (len(caps), join_list(caps)))

    if cc.get("pop"):
        if cc.get("pop_rank"):
            out.append("Around %s people live there, making it the %s most populous country in the world."
                       % (people(cc["pop"]), ordinal(cc["pop_rank"])))
        else:
            out.append("Around %s people live there." % people(cc["pop"]))

    if cc.get("area_km2"):
        if cc.get("area_rank"):
            out.append("It covers %s km², the %s largest country on Earth (of %d)."
                       % (commas(cc["area_km2"]), ordinal(cc["area_rank"]), n_area))
        else:
            out.append("It covers about %s km²." % commas(cc["area_km2"]))

    langs = list((m.get("languages") or {}).values())
    if langs:
        if len(langs) == 1:
            out.append("The official language is %s." % langs[0])
        else:
            out.append("It has %d official languages: %s." % (len(langs), join_list(langs[:5])))

    curs = m.get("currencies") or {}
    if curs:
        code, cur = sorted(curs.items())[0]
        sym = cur.get("symbol")
        out.append("Money there is the %s (%s%s)." % (cur.get("name", code), code,
                                                     ", %s" % sym if sym else ""))

    nb = neighbour_names(cc, name_by_a3)
    if nb:
        if len(nb) <= 3:
            out.append("It shares land borders with %s." % join_list(nb))
        else:
            out.append("It has %d land neighbours, among them %s."
                       % (len(nb), join_list(nb[:3])))
    elif m.get("landlocked"):
        out.append("It is landlocked, with no coastline at all.")
    else:
        out.append("It has no land neighbours - every border it has is a coastline.")

    bl = cc.get("border_len") or {}
    if bl:
        bi = max(bl, key=bl.get)
        if countries[bi]["name"] != name and bl[bi] > 40:
            out.append("Its longest land border, roughly %s km of it, is with %s."
                       % (commas(round(bl[bi] / 10.0) * 10), countries[bi]["name"]))

    if cc.get("seas"):
        out.append("Its coast meets the %s." % join_list(cc["seas"]))
    elif m.get("landlocked"):
        out.append("It is one of the world's %d landlocked countries."
                   % cc["n_landlocked"])

    dem = ((m.get("demonyms") or {}).get("eng") or {}).get("m")
    if dem and dem.lower() != name.lower():
        out.append("Someone from %s is called %s." % (name, dem))

    idd = m.get("idd") or {}
    tld = (m.get("tld") or [None])[0]
    if idd.get("root") and idd.get("suffixes"):
        code = idd["root"] + (idd["suffixes"][0] if len(idd["suffixes"]) == 1 else "")
        if tld:
            out.append("Phone numbers there start with %s and web addresses end in %s." % (code, tld))
        else:
            out.append("Its international dialling code is %s." % code)
    elif tld:
        out.append("Its internet domain ending is %s." % tld)

    minx, miny, maxx, maxy = cc["mainbox_map"]
    lat0, lat1 = -maxy, -miny
    lon0, lon1 = minx, maxx
    mid = (lat0 + lat1) / 2.0
    if lat0 < 0 < lat1:
        out.append("The equator runs straight through it.")
    elif lat0 > 60:
        out.append("All of it lies north of 60°N, deep in the far north.")
    elif lat1 - lat0 < 2:
        out.append("The whole country sits at about %s." % coord_ns(mid))
    elif abs(mid) < 23.5 and abs(lat0) > 0:
        out.append("It lies in the tropics, between %s and %s."
                   % (coord_ns(lat0), coord_ns(lat1)))
    else:
        out.append("It stretches from %s to %s." % (coord_ns(lat0), coord_ns(lat1)))

    if cc.get("pop") and cc.get("area_km2") and cc["area_km2"] > 0:
        dens = cc["pop"] / float(cc["area_km2"])
        out.append("On average %s people share every square kilometre."
                   % (("%.1f" % dens) if dens < 10 else commas(round(dens))))

    span = abs(lon1 - lon0)
    if span > 40:
        out.append("It is so wide, about %d° of longitude, that it spans roughly %d time zones."
                   % (round(span), max(2, int(round(span / 15.0)))))
    if cc["parts"] > 8:
        out.append("On the map it is drawn as %d separate pieces of land." % cc["parts"])

    if cc.get("gdp"):
        out.append("Its economy is worth roughly %s billion US dollars a year."
                   % commas(round(cc["gdp"] / 1000.0)))

    if cc["sovereign"]:
        out.append("It is not fully independent - it is a territory of %s." % cc["sovereign"])

    seen = set()
    uniq = []
    for f in out:
        if f not in seen:
            seen.add(f)
            uniq.append(f)
    return uniq[:10]


def make_hints(cc, name_by_a3, n_area):
    m = cc["meta"] or {}
    hints = []
    cont = cc.get("continent") or m.get("region") or ""
    sub = m.get("subregion") or ""
    if cont and sub and sub != cont:
        hints.append("Continent: %s - more precisely, %s." % (cont, sub))
    elif cont:
        hints.append("Continent: %s." % cont)

    if m.get("landlocked"):
        hints.append("It is landlocked - no sea touches it anywhere.")
    elif cc.get("seas"):
        hints.append("Its coast is on the %s." % join_list(cc["seas"]))

    nb = neighbour_names(cc, name_by_a3)
    if nb:
        if len(nb) <= 3:
            hints.append("It borders %s." % join_list(nb))
        else:
            hints.append("It has %d neighbours, including %s." % (len(nb), join_list(nb[:3])))
    else:
        hints.append("It has no land neighbours - look for an island or a piece of coast.")

    if cc.get("area_rank"):
        r = cc["area_rank"]
        if r <= 15:
            hints.append("It is big: the %s largest country in the world." % ordinal(r))
        elif r > n_area - 20:
            hints.append("It is tiny - one of the 20 smallest countries. Zoom right in.")
        else:
            hints.append("By area it ranks %s of %d countries." % (ordinal(r), n_area))
    if cc.get("pop_rank") and cc["pop_rank"] <= 20:
        hints.append("It is crowded: %s in the world by population." % ordinal(cc["pop_rank"]))

    caps = m.get("capital") or []
    if caps:
        hints.append("Its capital is %s." % caps[0])

    nm = cc["name"]
    letters = len(re.sub(r"[^A-Za-z]", "", nm))
    hints.append("The name starts with \"%s\" and has %d letters." % (nm[0], letters))
    return hints


# --------------------------------------------------------------------------
# binary writer
# --------------------------------------------------------------------------

class Writer(object):
    def __init__(self):
        self.buf = io.BytesIO()

    def u8(self, v):
        self.buf.write(struct.pack(">B", v & 0xFF))

    def u16(self, v):
        self.buf.write(struct.pack(">H", v))

    def i32(self, v):
        self.buf.write(struct.pack(">i", v))

    def f64(self, v):
        self.buf.write(struct.pack(">d", v))

    def uvar(self, v):
        assert v >= 0, v
        while True:
            b = v & 0x7F
            v >>= 7
            if v:
                self.buf.write(struct.pack(">B", b | 0x80))
            else:
                self.buf.write(struct.pack(">B", b))
                return

    def svar(self, v):
        self.uvar((v << 1) ^ (v >> 63) if v >= 0 else ((-v) << 1) - 1)

    def text(self, s):
        b = (s or "").encode("utf-8")
        self.uvar(len(b))
        self.buf.write(b)

    def raw(self):
        return self.buf.getvalue()


def write_binary(countries, arcs, transform):
    w = Writer()
    w.buf.write(b"TQD")
    w.u8(FORMAT_VERSION)
    for v in (transform["scale"][0], transform["scale"][1],
              transform["translate"][0], transform["translate"][1]):
        w.f64(v)

    w.uvar(len(arcs))
    for pts in arcs:
        w.uvar(len(pts))
        px = py = 0
        for x, y in pts:
            w.svar(x - px)
            w.svar(y - py)
            px, py = x, y

    w.uvar(len(countries))
    for cc in countries:
        w.text(cc["name"])
        flags = (1 if cc["quiz"] else 0) | (2 if cc["sovereign"] else 0)
        w.u8(flags)
        w.text(cc["a2"])
        w.text(cc["sovereign"])
        w.uvar(len(cc["aliases"]))
        for a in cc["aliases"]:
            w.text(a)
        w.svar(cc["label"][0])
        w.svar(cc["label"][1])
        minx, miny, maxx, maxy = cc["bbox"]
        w.svar(minx)
        w.svar(miny)
        w.svar(maxx - minx)
        w.svar(maxy - miny)
        mx0, my0, mx1, my1 = cc["mainbox"]
        w.svar(mx0 - minx)
        w.svar(my0 - miny)
        w.svar(mx1 - mx0)
        w.svar(my1 - my0)
        assert minx <= mx0 <= mx1 <= maxx and miny <= my0 <= my1 <= maxy
        w.uvar(len(cc["polys"]))
        for poly in cc["polys"]:
            w.uvar(len(poly))
            for ring in poly:
                w.uvar(len(ring))
                for idx in ring:
                    w.svar(idx)
        w.uvar(len(cc["facts"]))
        for f in cc["facts"]:
            w.text(f)
        w.uvar(len(cc["hints"]))
        for h in cc["hints"]:
            w.text(h)

    data = w.raw()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as fh:
        fh.write(data)
    return data


def report(countries, quiz):
    import zlib
    size = os.path.getsize(OUT)
    with open(OUT, "rb") as fh:
        deflated = len(zlib.compress(fh.read(), 9))
    pts = sum(len(r) for c in countries for r in c["rings"])
    facts = [len(c["facts"]) for c in quiz]
    print("countries       : %d (%d quizzable)" % (len(countries), len(quiz)))
    print("outline points  : %d" % pts)
    print("facts per quiz  : min %d avg %.1f" % (min(facts), sum(facts) / float(len(facts))))
    print("hints per quiz  : min %d" % min(len(c["hints"]) for c in quiz))
    print("world.bin       : %s bytes (%s deflated, which is what the APK stores)"
          % (commas(size), commas(deflated)))
    thin = [c["name"] for c in quiz if len(c["facts"]) < 8]
    if thin:
        print("thin fact lists : %s" % ", ".join(thin))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--download", action="store_true", help="fetch sources into tools/cache")
    ap.add_argument("--force", action="store_true", help="re-download even if cached")
    a = ap.parse_args()
    if a.download or a.force:
        fetch(a.force)
    build()
