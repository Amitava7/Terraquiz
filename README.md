# Terraquiz

Two geography minigames for one phone. A vector world map you can zoom into,
193 countries to learn, and a queue that keeps putting the ones you don't know
back in front of you.

Built as a plain Android app in Java with **no libraries at all** — no AndroidX,
no Kotlin runtime, no map SDK — so the whole thing is a few hundred kilobytes.
The APK is produced by GitHub Actions; there is nothing to install locally.

## The games

**1 · Find the country.** A name appears, you tap it on the world map. Tap the
wrong one and it turns red and tells you what you actually hit, so a near miss
still teaches you something. Tap the right one and it fills green, the map flies
to it, and you get one of ten facts about it.

**Hints**, in order: the continent and sub-region, whether it is landlocked and
which seas it touches, who its neighbours are, how big it is, its capital, and
finally the first letter and the length of the name. One more press draws a
circle on the map with the answer inside it.

**2 · Name the country.** One country lights up on the map and you type its
name. Spelling is forgiving: "Kazakstan", "Phillipines" and "Madgascar" are all
accepted, because a guess counts as long as no *other* country is a closer
match — so "Iraq" is never taken as a typo for "Iran". Each hint uncovers one
more letter from the left. Getting it right shows a fact, same as game 1.

## How it decides what to ask

Answer a country right first time, with no hints, and it moves to the back of
the queue — you will not see it again until everything else has also been
answered first time. Miss one and it stays at the front, ordered by how much
trouble it has given you. New countries filter in between the two. Each game
keeps its own record, and "Your progress" shows how many of the 193 you have
nailed and which ones are coming back soon.

A fresh install has nothing to go on, so the order starts from how likely a
country is to be recognised — the better of its population and area ranking.
The first game asks about Russia, China, India and the United States; Nauru,
San Marino and Vatican City wait at the far end until the rest are learned.

Progress lives in a small SQLite database on the phone. Nothing is uploaded;
the app asks for no permissions and has no network code in it.

## Getting the APK

Every push builds one. Open the **Actions** tab, pick the latest *Build APK*
run and download the `terraquiz-apk` artifact. Pushing a tag like `v1.0` also
publishes a GitHub release with the APK attached.

By default CI signs with a throwaway key generated for that run, which means
consecutive builds have different signatures — uninstall the old copy before
installing a new one. To get a stable signature, add four repository secrets:
`KEYSTORE_BASE64` (`base64 -w0 your.jks`), `KEY_ALIAS`, `KEY_PASSWORD` and
`STORE_PASSWORD`.

Target device is a Galaxy S24 Ultra, so `minSdk` is 34 and there is no
compatibility code for anything older.

## Why it is small

| what | how |
| --- | --- |
| No dependencies | Framework classes only. The dex file is tens of kilobytes. |
| One binary asset | Map, facts, hints and spellings in a single `world.bin`. |
| Shared borders | The outlines are TopoJSON arcs, so a border between two countries is stored once and both sides trace the identical line. |
| Varint deltas | Coordinates are stored as zig-zag varint deltas on a quantised grid, about a byte and a half per point. |
| No XML layouts | Screens are built in code; the only resources are a theme, three colours and a vector icon. |
| R8 + one locale | Minified and shrunk, English only, no per-density copies, no dependency metadata block. |

The release APK comes out around 240 KB, and CI fails the build if it ever
passes 1.5 MB.

Drawing keeps up by switching detail with the zoom: zoomed right out the world
is two paths — one fill, one stroke over the shared border arcs — so it costs
two draw calls; in the middle zooms each country is drawn separately but
generalised, with anything off screen skipped; close in, the full outlines.
Border strokes are divided by the zoom before drawing, so they stay a crisp
hairline whether you are looking at the whole world or at Luxembourg.

## The map data

`app/src/main/assets/world.bin` is generated, and regenerating it needs network
access:

```bash
python3 tools/build_data.py --download   # writes app/src/main/assets/world.bin
python3 tools/dump_data.py France Japan  # read it back, print facts and hints
cd tools && python3 check_data.py        # the checks CI runs
```

`check_data.py` re-implements the loader's geometry and the app's spelling
rules in Python, and asserts that every ring closes, that no outline wraps
across the antimeridian once split, that every label point lands inside its own
country and inside no other, that bounding boxes really bound, that no two
countries answer to the same typed name, and that 33 worked typing cases come
out right — "Phillipines" accepted for the Philippines, "Iraq" never accepted
for Iran.

Sources, all public:

- [world-atlas](https://github.com/topojson/world-atlas) 50 m country outlines,
  derived from [Natural Earth](https://www.naturalearthdata.com) (public domain).
- [mledoze/countries](https://github.com/mledoze/countries) for capitals,
  currencies, languages, borders, areas and dialling codes (ODbL).
- Natural Earth 50 m admin-0 and marine polygons for population, continents and
  which seas a coastline meets.

Facts are generated from those fields rather than written by hand, so all 1,930
of them say something the data actually supports.

## What CI does

1. **Check map data** — runs `tools/check_data.py` against the committed asset.
2. **Build release APK** — assembles, signs, prints a size breakdown in the job
   summary and fails past the size budget.
3. **Launch on an emulator** — installs the APK on Android 14, opens all four
   screens, plays a round of each game by finding the buttons in the view
   hierarchy, and fails on any crash. It also prints the map as ASCII in the
   log (`tools/screen_ascii.py`) and checks the map area really is mostly ocean
   with a sensible amount of land and visible borders, so a blank or broken map
   cannot pass.

## Layout

```
app/src/main/java/com/terraquiz/
  World.java         loads world.bin, splits rings at the antimeridian
  Country.java       one map feature: outline, bounds, facts, hints
  MapView.java       drawing, pan, pinch zoom, hit testing
  Names.java         accent folding, edit distance, "is this close enough"
  Progress.java      SQLite: what you know, what to ask next
  GameActivity.java  shared game scaffolding
  FindActivity.java  game 1      NameActivity.java  game 2
  MainActivity.java  menu        StatsActivity.java progress
tools/
  build_data.py      builds world.bin from the public sources
  check_data.py      the geometry, content and spelling checks CI runs
  dump_data.py       reads world.bin back and prints it
  smoke_test.sh      drives the APK on an emulator
  screen_ascii.py    turns a screenshot into ASCII for the CI log
```

## Building locally

Needs JDK 17 and an Android SDK with platform 35:

```bash
./gradlew assembleRelease     # add -PtqStoreFile=... to sign it
```
