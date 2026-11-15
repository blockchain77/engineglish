#!/usr/bin/env bash
export MSYS_NO_PATHCONV=1
# 시연 영상 자동 녹화 (Pleos Connect 에뮬레이터, 약 2분 40초)
# 사용법: bash tools/record_demo.sh  →  docs/demo/yeopjari_demo.mp4
ADB="${ADB_BIN:-adb} -s ${EMULATOR_SERIAL:-emulator-5554}"
OUT=docs/demo; mkdir -p $OUT
go() { $ADB shell "am start -n com.yeopjari.app/.MainActivity $*" >/dev/null 2>&1; }

$ADB shell am force-stop com.yeopjari.app
go ""
sleep 4
$ADB shell rm -f /sdcard/yeopjari_demo.mp4
$ADB shell "screenrecord --size 1920x1080 --bit-rate 3500000 --time-limit 175 /sdcard/yeopjari_demo.mp4" &
REC=$!
sleep 2

# 1) 실차 데이터(Pleos SDK)로 시작하는 홈
sleep 5
# 2) 시연 디렉터 → 평일 아침 출근
go "--ez demoPanel true"; sleep 3
go "--es scenario COMMUTE --ez closeAgent true"; sleep 11
# 3) 지하주차장 → 주차 위치 기억
go "--es scenario PARKING"; sleep 10
go "--es say '지하 3층 C구역 12번 기둥 옆에 주차했어'"; sleep 5
go "--ez closeAgent true --es screen HOME"; sleep 3
# 4) 강릉 가족여행 — 도착 SOC 부족 예측 → 클라우드 추론 통합 계획
go "--es scenario ROADTRIP --ez closeAgent true"; sleep 7
go "--es say '아이들이랑 강릉 가는데 충전이랑 쉬는 거 같이 해결할 수 있게 계획 세워줘'"; sleep 9
go "--es confirm add_waypoint:hoengseong"; sleep 4
go "--ez closeAgent true --es screen CHARGE"; sleep 6
# 5) 충전 중 — 과태료 타이머·쉼 모드
go "--es scenario CHARGING --es screen CHARGE"; sleep 7
# 6) 졸음 — 단계별 케어
go "--es scenario DROWSY --es screen HOME"; sleep 17
go "--es screen SAFETY"; sleep 6
# 7) 반려견 — 펫 케어
go "--es scenario PET --es screen HOME"; sleep 11
go "--es say '강아지 두고 잠깐 내릴게'"; sleep 6
go "--ez closeAgent true --es screen CARE"; sleep 5
# 8) TPMS 경고등 — 차량 닥터
go "--es scenario TPMS --es screen HOME"; sleep 8
go "--es say '타이어 경고등 왜 켜진 거야?'"; sleep 8
# 9) 주행 중 영상 요청 → 안전 정책 차단
go "--es say '유튜브 틀어줘'"; sleep 5
# 10) 하원길 도착 — 뒷좌석 확인
go "--ez closeAgent true --es scenario ARRIVAL --es screen HOME"; sleep 11
# 11) AI 아키텍처
go "--es scenario FREE --es screen SETTINGS"; sleep 7

wait $REC 2>/dev/null
sleep 2
$ADB pull /sdcard/yeopjari_demo.mp4 $OUT/yeopjari_demo.mp4
ls -la $OUT
