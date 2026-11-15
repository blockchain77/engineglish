package com.cartutor.engineglish.english.drive

import com.cartutor.engineglish.core.gateway.AmbientLightGateway
import com.cartutor.engineglish.english.core.MediaKey
import com.cartutor.engineglish.english.core.PulseKind
import com.cartutor.engineglish.english.core.VirtualTime
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 핸들 미디어 버튼 · 앰비언트 펄스 · HUD 힌트 */
class MediaKeysAndFeedbackTest {

    private val vt = VirtualTime()
    private val scope = vt.scope()

    @After fun tearDown() = scope.cancel()

    // ───────────── 핸들 미디어 버튼 ─────────────

    private fun collect(hub: MediaKeyHub): MutableList<MediaKey> {
        val got = ArrayList<MediaKey>()
        scope.launch { hub.keys.collect { got += it } }
        return got
    }

    @Test fun keyCodesMapOnKeyDownOnly() {
        val hub = MediaKeyHub(null, vt)
        val got = collect(hub)
        assertTrue(hub.onKeyEvent(87, true))
        assertTrue("키를 뗄 때도 소비", hub.onKeyEvent(87, false))
        assertFalse("볼륨 키는 우리 것이 아님", hub.onKeyEvent(24, true))
        vt.advanceBy(400)
        assertTrue(hub.onKeyEvent(88, true))
        vt.advanceBy(400)
        assertTrue(hub.onKeyEvent(85, true))
        vt.advanceBy(400)
        assertTrue(hub.onKeyEvent(127, true))
        assertEquals(listOf(MediaKey.NEXT, MediaKey.PREVIOUS, MediaKey.PLAY_PAUSE, MediaKey.PLAY_PAUSE), got)
    }

    @Test fun repeatsAndDuplicatesAreDebounced() {
        val hub = MediaKeyHub(null, vt)
        val got = collect(hub)
        hub.onKeyEvent(87, true, repeatCount = 0)
        hub.onKeyEvent(87, true, repeatCount = 1)
        vt.advanceBy(100)
        hub.onKeyEvent(87, true)
        assertEquals("길게 누름·이중 전달은 한 번", listOf(MediaKey.NEXT), got)
        vt.advanceBy(300)
        hub.onKeyEvent(87, true)
        assertEquals(2, got.size)
        hub.inject(MediaKey.NEXT)
        assertEquals("데모 주입은 그대로", 3, got.size)
    }

    @Test fun activeFlagWithoutAndroidSession() {
        val hub = MediaKeyHub(null, vt)
        hub.setActive(true)
        assertTrue(hub.active.value)
        hub.setActive(false)
        assertFalse(hub.active.value)
        hub.release()
    }

    // ───────────── 앰비언트 펄스 ─────────────

    private class FakeLight(var color: Int? = 0x112233, var writeOk: Boolean = true) : AmbientLightGateway {
        val writes = ArrayList<Int>()
        var reads = 0
        override suspend fun readColor(): Int? {
            reads++
            return color
        }
        override suspend fun writeColor(rgb: Int): Boolean {
            writes += rgb
            if (writeOk) color = rgb
            return writeOk
        }
    }

    @Test fun pulseWithoutLightIsScreenOnly() {
        val fb = PleosVehicleFeedback(scope, vt, null)
        fb.pulse(PulseKind.GREEN)
        assertEquals(1L, fb.lastPulse.value!!.id)
        assertEquals(PulseKind.GREEN, fb.lastPulse.value!!.kind)
        fb.pulse(PulseKind.AMBER)
        assertEquals(2L, fb.lastPulse.value!!.id)
        assertFalse(fb.ambientSupported.value)
    }

    @Test fun pulseSetsColourForOneSecondThenRestores() {
        val light = FakeLight()
        val fb = PleosVehicleFeedback(scope, vt, light)
        fb.pulse(PulseKind.GREEN)
        assertEquals(listOf(0x2EE66B), light.writes)
        assertTrue(fb.ambientSupported.value)
        vt.advanceBy(999)
        assertEquals(1, light.writes.size)
        vt.advanceBy(1)
        assertEquals(listOf(0x2EE66B, 0x112233), light.writes)
    }

    @Test fun overlappingPulsesRestoreTheOriginalColour() {
        val light = FakeLight()
        val fb = PleosVehicleFeedback(scope, vt, light)
        fb.pulse(PulseKind.GREEN)
        vt.advanceBy(500)
        fb.pulse(PulseKind.AMBER)
        vt.advanceBy(1_000)
        assertEquals(listOf(0x2EE66B, 0xFFB020, 0x112233), light.writes)
        assertEquals(1, light.reads)
    }

    @Test fun anyFailureStopsTrying() {
        val noRead = FakeLight(color = null)
        val fb = PleosVehicleFeedback(scope, vt, noRead)
        fb.pulse(PulseKind.GREEN)
        fb.pulse(PulseKind.GREEN)
        assertEquals(1, noRead.reads)
        assertFalse(fb.ambientSupported.value)
        assertEquals("화면 펄스는 계속", 2L, fb.lastPulse.value!!.id)

        val noWrite = FakeLight(writeOk = false)
        val fb2 = PleosVehicleFeedback(scope, vt, noWrite)
        fb2.pulse(PulseKind.AMBER)
        fb2.pulse(PulseKind.AMBER)
        assertEquals(1, noWrite.writes.size)
        assertFalse(fb2.ambientSupported.value)
    }

    @Test fun hudHintAndRgb() {
        val fb = PleosVehicleFeedback(scope, vt, null)
        fb.showHudHint("  postpone ")
        assertEquals("postpone", fb.hudHint.value)
        fb.showHudHint("   ")
        assertNull(fb.hudHint.value)
        assertEquals(0x2EE66B, PleosVehicleFeedback.rgbOf(PulseKind.GREEN))
        assertEquals(0xFFB020, PleosVehicleFeedback.rgbOf(PulseKind.AMBER))
    }
}
