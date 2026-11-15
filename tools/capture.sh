#!/usr/bin/env bash
# 사용법: tools/capture.sh <파일명> [scenario] [screen] [say] [wait초]
ADB="${ADB_BIN:-adb} -s ${EMULATOR_SERIAL:-emulator-5554}"
OUT=docs/screenshots; mkdir -p $OUT
name=$1; sc=$2; scr=$3; say=$4; w=${5:-4}
args=""
[ -n "$sc" ] && args="$args --es scenario $sc"
[ -n "$scr" ] && args="$args --es screen $scr"
if [ -n "$say" ]; then $ADB shell "am start -n com.yeopjari.app/.MainActivity $args --es say '$say'" >/dev/null
else $ADB shell "am start -n com.yeopjari.app/.MainActivity $args --ez closeAgent true" >/dev/null; fi
sleep $w
$ADB exec-out screencap -p > $OUT/$name.png && echo "saved $OUT/$name.png"
