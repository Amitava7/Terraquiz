#!/usr/bin/env bash
# Installs the release APK on a running emulator, opens every screen, plays a
# round of each game and fails on a crash or on anything not responding.
# Buttons are found by reading the view hierarchy, so this does not depend on
# the screen size. Screenshots land in shots/ and the workflow uploads them.
set -euo pipefail

APK=$(ls apk/*.apk | head -1)
PKG=com.terraquiz
SHOTS=shots
mkdir -p "$SHOTS"

adb install -r "$APK"
adb root
adb wait-for-device
sleep 5

shot() { adb exec-out screencap -p > "$SHOTS/$1.png"; }

# Prints the screen as ASCII in the log, and leaves the percentages of each
# palette colour in $SHOTS/$1.txt for the assertions below to read.
render() {
    adb exec-out screencap > "$SHOTS/$1.raw"
    python3 tools/screen_ascii.py "$SHOTS/$1.raw" "${2:---top}" "${3:-0.0}" \
        "${4:---bottom}" "${5:-1.0}" | tee "$SHOTS/$1.txt"
    rm -f "$SHOTS/$1.raw"
}

pct() {  # pct <name> <COLOUR>
    awk -v k="PCT_$2" '$1 == k {print int($2)}' "$SHOTS/$1.txt" | head -1
}

ui() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/ui.xml 2>/dev/null || true
}

# Taps the middle of the first node matching an attribute, e.g. 'text="Hint"'.
tap_node() {
    local match="$1" xy
    xy=$(ui | tr '<' '\n' | grep -F "$match" | head -1 \
        | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 \
        | tr -c '0-9' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}')
    if [ -z "$xy" ]; then
        echo "::error::nothing on screen matches $match"
        ui | head -5
        exit 1
    fi
    echo "tapping $match at $xy"
    adb shell input tap $xy
    sleep 2
}

expect() {
    if ! ui | grep -qF "$1"; then
        echo "::error::expected to see \"$1\""
        shot "missing-${2:-text}"
        ui | tr '<' '\n' | grep -o 'text="[^"]*"' | grep -v 'text=""' || true
        exit 1
    fi
}

crashed() {
    if adb logcat -d | grep -q "FATAL EXCEPTION"; then
        echo "::error::$1 crashed"
        adb logcat -d | grep -A 40 "FATAL EXCEPTION"
        shot "$1-crash"
        exit 1
    fi
}

open_screen() {
    adb logcat -c
    adb shell am start -W -n "$PKG/.$1"
    sleep 7
    shot "$1"
    crashed "$1"
}

for SCREEN in MainActivity FindActivity NameActivity StatsActivity; do
    open_screen "$SCREEN"
done
# 193 countries in the pool means world.bin parsed and the quiz list is whole
expect "of 193 countries" stats

# ---- game 1 ---------------------------------------------------------------
open_screen FindActivity
# If the background parse had failed the heading would still say "Loading".
expect "Find the country" find
if ui | grep -q "Loading the world"; then
    echo "::error::the map never finished loading"
    exit 1
fi

# The map fills the middle of the screen. Draw it in the log and insist that
# it really is a map: plenty of ocean, a decent amount of land, and borders.
render FindActivity-map --top 0.17 --bottom 0.85
LAND=$(pct FindActivity-map LAND)
OCEAN=$(pct FindActivity-map OCEAN)
BORDER=$(pct FindActivity-map BORDER)
echo "map pixels: land=$LAND% ocean=$OCEAN% border=$BORDER%"
if [ "${LAND:-0}" -lt 8 ] || [ "${LAND:-0}" -gt 70 ]; then
    echo "::error::land covers $LAND% of the map area, which is not a world map"
    exit 1
fi
if [ "${OCEAN:-0}" -lt 20 ]; then
    echo "::error::only $OCEAN% of the map area is ocean"
    exit 1
fi
if [ "${BORDER:-0}" -lt 1 ]; then
    echo "::error::no country borders drawn"
    exit 1
fi

adb logcat -c
adb shell input tap 540 1200          # somewhere on the map
sleep 2
shot FindActivity-tapped
tap_node 'text="Hint"'
shot FindActivity-hint
tap_node 'text="Hint"'
tap_node 'text="Give up"'
sleep 2
shot FindActivity-revealed
crashed FindActivity-play
expect "It was " reveal
# giving up paints the answer amber and writes its name on the map
render FindActivity-revealed --top 0.17 --bottom 0.85
if [ "$(pct FindActivity-revealed AMBER)" -lt 1 ]; then
    echo "::error::the answer was not highlighted on the map"
    exit 1
fi
tap_node 'text="Next'
shot FindActivity-next
crashed FindActivity-next

# ---- game 2 ---------------------------------------------------------------
adb logcat -c
adb shell am start -n "$PKG/.NameActivity"
sleep 7
shot NameActivity-question
# game 2 highlights the country it is asking about before you type anything
render NameActivity-question --top 0.17 --bottom 0.80
if [ "$(pct NameActivity-question AMBER)" -lt 1 ]; then
    echo "::error::no country is highlighted to name"
    exit 1
fi
tap_node 'class="android.widget.EditText"'
adb shell input text "Zzzqqx"
sleep 1
tap_node 'text="Check"'
shot NameActivity-wrong
expect "Not Zzzqqx" wrong-answer
tap_node 'text="Hint"'
shot NameActivity-hint
tap_node 'text="Give up"'
sleep 2
shot NameActivity-revealed
expect "It was " name-reveal
crashed NameActivity-play

# Rotating is the classic way to shake out a crash in a view holding state.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 5
shot NameActivity-landscape
crashed NameActivity-rotate
adb shell settings put system user_rotation 0
sleep 3
crashed NameActivity-rotate-back

echo "every screen opened, both games played, no crashes"
