package com.yeopjari.app.english.drive

import com.yeopjari.app.english.core.CameraKind
import com.yeopjari.app.english.core.CautionKind
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pleos SDK 상수 이름 → 모델 (명세 §4.1 표) */
class NaviMappingTest {

    @Test fun turnTypes() {
        val expected = mapOf(
            "TURN_UNKNOWN" to ManeuverType.OTHER,
            "TURN_STRAIGHT" to ManeuverType.STRAIGHT,
            "TURN_LEFT" to ManeuverType.LEFT,
            "TURN_SHARP_L" to ManeuverType.LEFT,
            "TURN_RIGHT" to ManeuverType.RIGHT,
            "TURN_SHARP_R" to ManeuverType.RIGHT,
            "TURN_KEEP_L" to ManeuverType.KEEP_LEFT,
            "TURN_KEEP_R" to ManeuverType.KEEP_RIGHT,
            "TURN_ENTER_UNDER_L" to ManeuverType.KEEP_LEFT,
            "TURN_AVOID_OVER_L" to ManeuverType.KEEP_LEFT,
            "TURN_ENTER_OVER_R" to ManeuverType.KEEP_RIGHT,
            "TURN_AVOID_UNDER_R" to ManeuverType.KEEP_RIGHT,
            "TURN_ENTER_UNDER_C" to ManeuverType.STRAIGHT,
            "TURN_AVOID_OVER_C" to ManeuverType.STRAIGHT,
            "TURN_UTURN" to ManeuverType.U_TURN,
            "TURN_EXIT_IC_R" to ManeuverType.EXIT,
            "TURN_EXIT_IC_C" to ManeuverType.EXIT,
            "TURN_ENTER_IC_L" to ManeuverType.ENTER,
            "TURN_DEVIDE_MERGE" to ManeuverType.MERGE,
            "TURN_ENTER_ROUNDABOUT_03" to ManeuverType.ROUNDABOUT,
            "TURN_ENTER_ROUNDABOUT_12" to ManeuverType.ROUNDABOUT,
            "TURN_EXIT_ROUNDABOUT" to ManeuverType.ROUNDABOUT,
            "TURN_TOLL" to ManeuverType.TOLL,
            "TURN_TUNNEL" to ManeuverType.TUNNEL,
            "TURN_ARRIVE" to ManeuverType.ARRIVE,
            "TURN_WAY" to ManeuverType.ARRIVE,
            "TURN_ENTER_SERVICEAREA" to ManeuverType.REST_AREA,
            "TURN_RESTAREA" to ManeuverType.REST_AREA,
        )
        for ((name, type) in expected) assertEquals(name, type, NaviMapping.maneuverType(name))
        assertEquals(ManeuverType.OTHER, NaviMapping.maneuverType(null))
        assertEquals(ManeuverType.OTHER, NaviMapping.maneuverType("SOMETHING_NEW"))
    }

    @Test fun cameraTypes() {
        assertEquals(CameraKind.SPEED_FIXED, NaviMapping.cameraKind("SPEED_CAMERA_FIX"))
        assertEquals(CameraKind.SPEED_FIXED, NaviMapping.cameraKind("SPEED_CAMERA_REAR"))
        assertEquals(CameraKind.SPEED_FIXED, NaviMapping.cameraKind("VARIABLE_SPEED_CAMERA"))
        assertEquals(CameraKind.SPEED_MOBILE, NaviMapping.cameraKind("SPEED_CAMERA_MOV"))
        assertEquals(CameraKind.SECTION, NaviMapping.cameraKind("SPEED_CAMERA_SECTION_START"))
        assertEquals(CameraKind.SECTION, NaviMapping.cameraKind("SECTION_SPEED_ALERT"))
        assertEquals(CameraKind.SECTION, NaviMapping.cameraKind("SPEED_CAMERA_SECTION_END"))
        assertEquals(CameraKind.SIGNAL, NaviMapping.cameraKind("SPEED_CAMERA_TRAFFIC_LIGHT"))
        assertEquals(CameraKind.SCHOOL_ZONE, NaviMapping.cameraKind("SPEED_CAMERA_SCHOOL_ZONE"))
        assertEquals(CameraKind.SCHOOL_ZONE, NaviMapping.cameraKind("SPEED_CAMERA_SCHOOL_ZONE_TRAFFIC_LIGHT"))
        assertEquals(CameraKind.OTHER, NaviMapping.cameraKind(null))
    }

    @Test fun cautionTypesIgnoreEnforcementNotices() {
        assertEquals(CautionKind.SCHOOL_ZONE, NaviMapping.cautionKind("SCHOOL_ZONE"))
        assertEquals(CautionKind.SPEED_BUMP, NaviMapping.cautionKind("SPEED_BUMP"))
        assertEquals(CautionKind.ACCIDENT_AREA, NaviMapping.cautionKind("ACCIDENT_PRONE_AREA"))
        assertEquals(CautionKind.RAILROAD, NaviMapping.cautionKind("RAILROAD_CROSSING"))
        assertEquals(CautionKind.SLIPPERY, NaviMapping.cautionKind("SLIPPERY_ROAD_ALERT"))
        assertEquals(CautionKind.SLIPPERY, NaviMapping.cautionKind("FOG_AREA"))
        assertEquals(CautionKind.CONSTRUCTION, NaviMapping.cautionKind("HIGHWAY_CONSTRUCTION"))
        assertEquals(CautionKind.CONSTRUCTION, NaviMapping.cautionKind("ROAD_DAMAGE_ALERT"))
        assertEquals(CautionKind.OTHER, NaviMapping.cautionKind("FALLING_ROCK_AREA"))
        assertEquals(CautionKind.OTHER, NaviMapping.cautionKind("WRONG_WAY_DRIVING_ALERT"))
        for (ignored in listOf(
            "BUS_LANE_VIOLATION_ENFORCEMENT", "BUS_LANE_UNAVAILABLE_NOTICE", "BUS_LANE_AVAILABLE_NOTICE",
            "TRAFFIC_LIGHT_VIOLATION_ENFORCEMENT", "CUT_IN_VIOLATION_ENFORCEMENT", "BLOCK_INTERSECTION_ENFORCEMENT",
            "NO_PARKING_ZONE", "TRAFFIC_INFO_COLLECTION", "SIGN_MALFUNCION", "LANE_CHANGE_CAMERA_ALERT",
        )) assertNull(ignored, NaviMapping.cautionKind(ignored))
        assertNull(NaviMapping.cautionKind(null))
    }

    @Test fun gearsAndAirport() {
        assertEquals(GearPosition.P, NaviMapping.gear("GEAR_PARK"))
        assertEquals(GearPosition.R, NaviMapping.gear("GEAR_REVERSE"))
        assertEquals(GearPosition.N, NaviMapping.gear("GEAR_NEUTRAL"))
        assertEquals(GearPosition.UNKNOWN, NaviMapping.gear("GEAR_UNKNOWN"))
        assertEquals(GearPosition.D, NaviMapping.gear("GEAR_DRIVE"))
        assertEquals(GearPosition.D, NaviMapping.gear("GEAR_3"))
        assertEquals(GearPosition.P, NaviMapping.gear("P"))
        assertEquals(GearPosition.D, NaviMapping.gear("D"))
        assertTrue(NaviMapping.isAirportName("김포공항 국내선"))
        assertTrue(NaviMapping.isAirportName("Incheon International Airport"))
        assertFalse(NaviMapping.isAirportName("판교역"))
        assertFalse(NaviMapping.isAirportName(null))
    }
}
