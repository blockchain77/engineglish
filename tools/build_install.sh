#!/usr/bin/env bash
# 빌드 → Pleos 에뮬레이터(emulator-5554)에만 설치 → 실행
set -e
: "${JAVA_HOME:?JDK 17~21 경로를 JAVA_HOME 에 지정하세요}"
ADB="${ADB_BIN:-adb} -s ${EMULATOR_SERIAL:-emulator-5554}"
./gradlew :app:assembleRelease --console=plain -q 2>&1 | grep -E "^e: |error" || true
$ADB install -r -g app/build/outputs/apk/release/app-release.apk | tail -1
$ADB shell am force-stop com.yeopjari.app
$ADB shell am start -n com.yeopjari.app/.MainActivity >/dev/null
