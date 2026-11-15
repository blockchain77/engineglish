package com.yeopjari.app.english.drive

import com.yeopjari.app.english.core.CameraKind
import com.yeopjari.app.english.core.CautionKind
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.ManeuverType

/**
 * Pleos SDK 상수 → 운전 신호 모델 매핑 (명세 §4.1).
 * SDK enum 의 '이름'으로 매핑해 순수 함수로 둔다 (JVM 테스트에서 SDK 클래스를 띄우지 않기 위해).
 */
object NaviMapping {

    /** NaviHelper TurnType 이름 → ManeuverType */
    fun maneuverType(turnType: String?): ManeuverType {
        val t = turnType?.trim()?.uppercase().orEmpty()
        return when {
            t == "TURN_STRAIGHT" -> ManeuverType.STRAIGHT
            t == "TURN_LEFT" || t == "TURN_SHARP_L" -> ManeuverType.LEFT
            t == "TURN_RIGHT" || t == "TURN_SHARP_R" -> ManeuverType.RIGHT
            t == "TURN_KEEP_L" -> ManeuverType.KEEP_LEFT
            t == "TURN_KEEP_R" -> ManeuverType.KEEP_RIGHT
            t == "TURN_UTURN" -> ManeuverType.U_TURN
            t.startsWith("TURN_EXIT_IC_") -> ManeuverType.EXIT
            t.startsWith("TURN_ENTER_IC_") -> ManeuverType.ENTER
            t.startsWith("TURN_ENTER_ROUNDABOUT") || t == "TURN_EXIT_ROUNDABOUT" -> ManeuverType.ROUNDABOUT
            // 지하차도·고가도로 진입/회피: 방향에 따라 차로 변경 또는 직진
            t.contains("_UNDER_") || t.contains("_OVER_") -> when {
                t.endsWith("_L") -> ManeuverType.KEEP_LEFT
                t.endsWith("_R") -> ManeuverType.KEEP_RIGHT
                else -> ManeuverType.STRAIGHT
            }
            t == "TURN_ARRIVE" || t == "TURN_WAY" -> ManeuverType.ARRIVE
            t == "TURN_TUNNEL" -> ManeuverType.TUNNEL
            t == "TURN_DEVIDE_MERGE" -> ManeuverType.MERGE
            t == "TURN_TOLL" -> ManeuverType.TOLL
            t == "TURN_ENTER_SERVICEAREA" || t == "TURN_RESTAREA" -> ManeuverType.REST_AREA
            else -> ManeuverType.OTHER
        }
    }

    /** NaviHelper CameraType 이름 → CameraKind */
    fun cameraKind(cameraType: String?): CameraKind = when (cameraType?.trim()?.uppercase()) {
        "SPEED_CAMERA_FIX", "SPEED_CAMERA_REAR", "VARIABLE_SPEED_CAMERA" -> CameraKind.SPEED_FIXED
        "SPEED_CAMERA_MOV" -> CameraKind.SPEED_MOBILE
        "SPEED_CAMERA_SECTION_START", "SECTION_SPEED_ALERT", "SPEED_CAMERA_SECTION_END" -> CameraKind.SECTION
        "SPEED_CAMERA_TRAFFIC_LIGHT" -> CameraKind.SIGNAL
        "SPEED_CAMERA_SCHOOL_ZONE", "SPEED_CAMERA_SCHOOL_ZONE_TRAFFIC_LIGHT" -> CameraKind.SCHOOL_ZONE
        else -> CameraKind.OTHER
    }

    /** NaviHelper CautionPointType 이름 → CautionKind. 단속·안내성 지점은 null (부하와 무관해서 무시) */
    fun cautionKind(cautionType: String?): CautionKind? {
        val t = cautionType?.trim()?.uppercase().orEmpty()
        return when {
            t == "SCHOOL_ZONE" -> CautionKind.SCHOOL_ZONE
            t == "SPEED_BUMP" -> CautionKind.SPEED_BUMP
            t == "ACCIDENT_PRONE_AREA" -> CautionKind.ACCIDENT_AREA
            t == "RAILROAD_CROSSING" -> CautionKind.RAILROAD
            t == "SLIPPERY_ROAD_ALERT" || t == "FOG_AREA" -> CautionKind.SLIPPERY
            t == "HIGHWAY_CONSTRUCTION" || t == "ROAD_DAMAGE_ALERT" -> CautionKind.CONSTRUCTION
            t.isEmpty() -> null
            t.startsWith("BUS_LANE_") || t.endsWith("_ENFORCEMENT") -> null
            t == "NO_PARKING_ZONE" || t == "TRAFFIC_INFO_COLLECTION" || t == "SIGN_MALFUNCION" || t == "LANE_CHANGE_CAMERA_ALERT" -> null
            else -> CautionKind.OTHER
        }
    }

    /** Vehicle SDK VehicleGear 이름 (또는 레거시 Gear 이름) → GearPosition */
    fun gear(name: String?): GearPosition = when (name?.trim()?.uppercase()) {
        "GEAR_PARK", "P" -> GearPosition.P
        "GEAR_REVERSE", "R" -> GearPosition.R
        "GEAR_NEUTRAL", "N" -> GearPosition.N
        null, "", "GEAR_UNKNOWN", "UNKNOWN" -> GearPosition.UNKNOWN
        else -> GearPosition.D
    }

    /** 공항 목적지 이름 판별 ("김포공항", "Incheon Airport") */
    fun isAirportName(name: String?): Boolean =
        name != null && (name.contains("공항") || name.contains("airport", ignoreCase = true))
}
