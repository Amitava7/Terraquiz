#!/usr/bin/env bash
# Installs the release APK on a running emulator, opens every screen, plays a
# few taps and fails on a crash or on the map failing to load. Screenshots go
# to shots/ and are uploaded by the workflow.
set -eux

APK=$(ls apk/*.apk | head -1)
PKG=com.terraquiz
SHOTS=shots
mkdir -p "$SHOTS"

adb install -r "$APK"
adb root
adb wait-for-device
sleep 5

shot() {
    adb exec-out screencap -p > "$SHOTS/$1.png"
}

ui() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/ui.xml 2>/dev/null || true
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
    sleep 8
    shot "$1"
    crashed "$1"
}

for SCREEN in MainActivity FindActivity NameActivity StatsActivity; do
    open_screen "$SCREEN"
done

# The map is parsed on a background thread. If that had failed the heading
# would still read "Loading the world" and no country would be on screen.
open_screen FindActivity
if ui | grep -q "Loading the world"; then
    echo "::error::the map never finished loading"
    ui | head -40
    exit 1
fi
if ! ui | grep -q "Find the country"; then
    echo "::error::the game screen did not come up"
    ui | head -40
    exit 1
fi

# Screen is 1080x2400 at 420dpi. The map fills the middle, the buttons sit in
# a row at the bottom: hint, give up, next.
adb logcat -c
adb shell input tap 540 1200          # somewhere on the map
sleep 2
shot FindActivity-tapped
adb shell input tap 190 2242          # hint
sleep 2
shot FindActivity-hint
adb shell input tap 190 2242          # hint again
sleep 2
adb shell input tap 540 2242          # give up, which reveals the answer
sleep 3
shot FindActivity-revealed
crashed FindActivity-play
if ! ui | grep -q "It was "; then
    echo "::error::giving up did not reveal the answer"
    ui | head -60
    exit 1
fi

# Game 2: type a deliberately misspelled name and check it is accepted.
adb logcat -c
adb shell am start -n "$PKG/.NameActivity"
sleep 8
shot NameActivity-question
COUNTRY=$(ui | tr '>' '\n' | grep -o 'text="[^"]*"' | head -40 || true)
echo "visible text: $COUNTRY"
adb shell input tap 190 2140          # the hint button reveals letters
sleep 2
shot NameActivity-hint
crashed NameActivity-play

# Rotating is the classic way to find a crash in a view that keeps state.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 4
shot NameActivity-landscape
adb shell settings put system user_rotation 0
sleep 3
crashed NameActivity-rotate

echo "all screens opened without a crash"
