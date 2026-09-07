package com.avaruus.astro

import com.avaruus.astro.camera.AstroDefaults
import com.avaruus.astro.camera.AstroMode
import com.avaruus.astro.camera.CameraLens
import com.avaruus.astro.camera.FrameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroLogicTest {

    @Test
    fun testExposurePresetsConversion() {
        val preset10s = AstroDefaults.EXPOSURE_PRESETS.find { it.label == "10.0s" }
        assertNotNull(preset10s)
        assertEquals(10_000_000_000L, preset10s!!.durationNs)
        assertEquals(10.0, preset10s.durationSec, 0.001)

        val preset1s = AstroDefaults.EXPOSURE_PRESETS.find { it.label == "1.0s" }
        assertEquals(1_000_000_000L, preset1s!!.durationNs)
        assertEquals(1.0, preset1s.durationSec, 0.001)
    }

    @Test
    fun testCameraLensCalculations() {
        val mainLens = CameraLens(
            id = "0",
            name = "1.0x 50MP f/1.68",
            opticalZoomRatio = 1.0f,
            opticalZoom = "1.0x",
            focalLengthMm = 6.9f,
            aperture = 1.68f,
            maxExposureNs = 16_000_000_000L,
            minExposureNs = 100_000L,
            minIso = 50,
            maxIso = 6400,
            minFocusDistance = 10.0f,
            isRawSupported = true,
            rawWidth = 8160,
            rawHeight = 6120
        )

        assertEquals("1.0x", mainLens.opticalZoom)
        assertEquals("1.0x 50MP f/1.68", mainLens.name)
        assertEquals(16.0, mainLens.maxExposureSec, 0.001)
        assertTrue(mainLens.isRawSupported)
        assertEquals(50, mainLens.minIso)
        assertEquals(6400, mainLens.maxIso)
        assertEquals(49.9392, mainLens.megaPixels, 0.01)
    }

    @Test
    fun testOpticalTripletUniqueness() {
        val lenses = listOf(
            CameraLens(id = "0", name = "0.5x 48MP f/1.7", opticalZoomRatio = 0.5f, opticalZoom = "0.5x", focalLengthMm = 2.1f, aperture = 1.7f, maxExposureNs = 16_000_000_000L, minExposureNs = 100_000L, minIso = 50, maxIso = 6400, minFocusDistance = 10.0f),
            CameraLens(id = "0", name = "1.0x 50MP f/1.68", opticalZoomRatio = 1.0f, opticalZoom = "1.0x", focalLengthMm = 6.9f, aperture = 1.68f, maxExposureNs = 16_000_000_000L, minExposureNs = 100_000L, minIso = 50, maxIso = 6400, minFocusDistance = 10.0f),
            CameraLens(id = "0", name = "5.0x 48MP f/2.8", opticalZoomRatio = 5.0f, opticalZoom = "5.0x", focalLengthMm = 19.0f, aperture = 2.8f, maxExposureNs = 16_000_000_000L, minExposureNs = 100_000L, minIso = 50, maxIso = 6400, minFocusDistance = 5.0f)
        )
        // Exactly 3 lenses, each unique ratio, no duplicates
        assertEquals(3, lenses.size)
        assertEquals(3, lenses.distinctBy { it.opticalZoomRatio }.size)
        assertEquals(3, lenses.distinctBy { it.name }.size)
        assertEquals(listOf("0.5x 48MP f/1.7", "1.0x 50MP f/1.68", "5.0x 48MP f/2.8"), lenses.map { it.name })
    }

    @Test
    fun testFrameTypesPrefixes() {
        assertEquals("LIGHT", FrameType.LIGHT.prefix)
        assertEquals("DARK", FrameType.DARK.prefix)
        assertEquals("BIAS", FrameType.BIAS.prefix)
        assertEquals("FLAT", FrameType.FLAT.prefix)
    }

    @Test
    fun testAstroModes() {
        assertEquals(FrameType.LIGHT, AstroMode.LIGHT.frameType)
        assertEquals(FrameType.DARK, AstroMode.DARK.frameType)
        assertEquals(FrameType.BIAS, AstroMode.BIAS.frameType)
        assertEquals(FrameType.FLAT, AstroMode.FLAT.frameType)
        assertNull(AstroMode.FOCUS.frameType)
        assertNull(AstroMode.VIEW.frameType)
    }
}
