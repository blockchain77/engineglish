#!/usr/bin/env bash
export MSYS_NO_PATHCONV=1
# 개인화 보정 + 앞유리 HUD 시연 영상 자동 녹화 (Pleos Connect 에뮬레이터, 약 2분 40초)
# 사용법: bash tools/record_personal_demo.sh  →  docs/demo/yeopjari_personal_demo.mp4
# 'hear' extra 는 STT SDK 가 돌려주는 최종 텍스트를 넣는 것과 같은 경로(store.send)를 탄다.
ADB="${ADB_BIN:-adb} -s ${EMULATOR_SERIAL:-emulator-5554}"
OUT=docs/demo; mkdir -p $OUT
APP=com.yeopjari.app/.MainActivity
go() { $ADB shell "am start -n $APP $*" >/dev/null 2>&1; }
hear() { $ADB shell "am start -n $APP --es hear '$1'" >/dev/null 2>&1; }
# 실제 NaviHelper 연동으로 기본 내비가 앞으로 나오면 옆자리로 돌아온다
back() { sleep "${1:-2}"; $ADB shell "am start -n $APP" >/dev/null 2>&1; }

$ADB shell am force-stop com.yeopjari.app
go "--es personal restore"; sleep 3
# 학습 초기화 · 토요일 오전 잠실 · HUD 도로 움직임(시연용) 켬 · 실제 내비 반영은 필요한 장면에서만
go "--es personal reset --es scenario P_WEEKEND --ez hudMotion true --ez mirror false"; sleep 4
$ADB shell rm -f /sdcard/yeopjari_personal_demo.mp4
$ADB shell "screenrecord --size 1920x1080 --bit-rate 3500000 --time-limit 170 /sdcard/yeopjari_personal_demo.mp4" &
REC=$!
sleep 2

# 1) 정차 중 '맞춤 AI' — 앞유리 HUD 시뮬레이터 + AI가 고른 이유 · 차 안 학습 · 개인정보
sleep 6
# 2) 토요일 오전 잠실 집 앞: "스타벅스 가자" → 주말엔 잠실새내역점 (한 번 확인) → "응" → 학습
hear "스타벅스 가자"; sleep 7
hear "응"; sleep 6
# 3) 평일 아침 판교 출근길: 같은 말 → 늘 가던 판교테크노밸리점으로 바로 안내 (10초 안 '아니야'로 되돌리기)
go "--es scenario P_WEEKDAY"; sleep 4
hear "스타벅스 가자"; sleep 8
# 4) 토요일 대전 본가 가는 길(주행 중 HUD): "서심다응 가자" → 성심당 대전역점? → "응" → 실제 Pleos 내비가 경로 탐색
go "--es scenario P_DAEJEON"; sleep 4
hear "서심다응 가자"; sleep 7
go "--ez mirror true"; hear "응"; back 4; go "--ez mirror false"; sleep 3
# 5) 같은 말 다시 → 배운 대로 바로 안내 → "아니야" → HUD 선택지 3곳 → "1번"
go "--es scenario P_DAEJEON"; sleep 4
hear "서심다응 가자"; sleep 7
hear "아니야"; sleep 7
hear "1번"; sleep 7
# 6) 종류로 말하기: "빵집 들렀다 가자" → 가는 길의 단골 빵집을 경유지로 제안
hear "빵집 들렀다 가자"; sleep 7
hear "취소"; sleep 3
# 7) 정차(P) 후: 왜 골랐는지 · 차 안에서 배운 것 · 서버로 보낼 것(변화량 6개 + 서명) · 해시 체인
go "--es gear P"; sleep 9
go "--es personal sync"; sleep 6
# 8) 내 기록 지우기 → 같은 말이 처음 상태로 (개인 기록 없이 발음만)
go "--es personal erase"; sleep 3
hear "서심다응 가자"; sleep 7
hear "취소"; sleep 2
go "--es personal restore"; sleep 2
# 9) Gleo 연동: "글레오야, 옆자리로 서심다응 들렀다 가자" → GO_TO_MY_PLACE → HUD 확인 → "응 들르자"
go "--es scenario P_DAEJEON"; sleep 4
$ADB shell "am start -a GO_TO_MY_PLACE -n $APP --es ACTION_PARAM '{\"place\":\"서심다응\",\"stop_type\":\"waypoint\"}'" >/dev/null 2>&1
sleep 7
hear "응 들르자"; sleep 7

wait $REC 2>/dev/null
sleep 2
go "--ez hudMotion false --ez mirror true"
$ADB pull /sdcard/yeopjari_personal_demo.mp4 $OUT/yeopjari_personal_demo.mp4
ls -la $OUT
