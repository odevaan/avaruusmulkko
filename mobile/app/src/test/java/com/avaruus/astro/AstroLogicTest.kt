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

    @Test
    fun testAstroStretchModes() {
        val modes = com.avaruus.astro.ui.AstroStretchMode.values()
        assertEquals(3, modes.size)
        assertEquals("ARCSINH", com.avaruus.astro.ui.AstroStretchMode.ARCSINH.label)
        assertEquals("BOOST", com.avaruus.astro.ui.AstroStretchMode.BOOST.label)
        assertEquals("LINEAR", com.avaruus.astro.ui.AstroStretchMode.OFF.label)
    }

    @Test
    fun testIsoPresetsAndNativeGain() {
        assertEquals(listOf(200, 700, 1600, 3200), AstroDefaults.ISO_PRESETS)
        assertTrue(AstroDefaults.ISO_PRESETS.contains(200))
        assertTrue(AstroDefaults.ISO_PRESETS.contains(700))
        assertTrue(AstroDefaults.ISO_PRESETS.contains(1600))
        assertTrue(AstroDefaults.ISO_PRESETS.contains(3200))
    }

    @Test
    fun testPreviewAutoExposureStepsMonotonicAndSingleStep() {
        val steps = com.avaruus.astro.camera.PreviewAutoExposure.STEPS
        assertTrue(steps.size >= 20)

        // Verify strictly increasing total exposure (exposureTimeNs * iso) across the entire ladder
        for (i in 1 until steps.size) {
            val prevTotal = steps[i - 1].exposureTimeNs.toDouble() * steps[i - 1].iso
            val currTotal = steps[i].exposureTimeNs.toDouble() * steps[i].iso
            assertTrue("Step $i must exceed step ${i - 1}", currTotal > prevTotal)
        }

        // Dark sky frame -> steps UP by exactly +1 step
        val darkStats = com.avaruus.astro.camera.PreviewAutoExposure.FrameLuminanceStats(
            meanY = 12f,
            p90Y = 22f,
            clippedFraction = 0.005f
        )
        assertEquals(18, com.avaruus.astro.camera.PreviewAutoExposure.nextStepIndex(17, darkStats))

        // Overly washed-out frame -> steps DOWN by exactly -1 step
        val brightStats = com.avaruus.astro.camera.PreviewAutoExposure.FrameLuminanceStats(
            meanY = 185f,
            p90Y = 235f,
            clippedFraction = 0.40f
        )
        assertEquals(16, com.avaruus.astro.camera.PreviewAutoExposure.nextStepIndex(17, brightStats))

        // Intentionally overexposed framing sweet spot -> holds steady (+0 steps)
        val sweetSpotStats = com.avaruus.astro.camera.PreviewAutoExposure.FrameLuminanceStats(
            meanY = 120f,
            p90Y = 155f,
            clippedFraction = 0.03f
        )
        assertEquals(17, com.avaruus.astro.camera.PreviewAutoExposure.nextStepIndex(17, sweetSpotStats))

        // Boundary clamping
        assertEquals(0, com.avaruus.astro.camera.PreviewAutoExposure.nextStepIndex(0, brightStats))
        assertEquals(
            steps.lastIndex,
            com.avaruus.astro.camera.PreviewAutoExposure.nextStepIndex(steps.lastIndex, darkStats)
        )
    }

    @Test
    fun testAnalyzeYPlaneWithRowPadding() {
        val width = 32
        val height = 24
        val rowStride = 40 // includes 8 bytes of row padding
        val buffer = java.nio.ByteBuffer.allocate(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Top 25% of rows are bright stars/nebula (250), remaining 75% are lifted sky (100)
                val luma = if (y < height / 4) 250 else 100
                buffer.put(y * rowStride + x, luma.toByte())
            }
        }

        val stats = com.avaruus.astro.camera.PreviewAutoExposure.analyzeYPlane(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = 1
        )
        assertNotNull(stats)
        // 0.25 * 250 + 0.75 * 100 = 137.5
        assertEquals(137.5f, stats!!.meanY, 0.5f)
        assertEquals(250f, stats.p90Y, 0.5f)
        assertEquals(0.25f, stats.clippedFraction, 0.02f)
    }

    @Test
    fun testPreviewDisplayAspectRatioFullFrame() {
        val lens90 = CameraLens(
            id = "0",
            name = "1.0x 50MP f/1.68",
            focalLengthMm = 6.9f,
            aperture = 1.68f,
            maxExposureNs = 16_000_000_000L,
            minExposureNs = 100_000L,
            minIso = 50,
            maxIso = 6400,
            minFocusDistance = 10.0f,
            rawWidth = 8160,
            rawHeight = 6120,
            sensorOrientation = 90,
            previewWidth = 1440,
            previewHeight = 1080
        )

        // Portrait (0°): 4:3 sensor rotated 90° -> 3:4 (0.75) width/height
        assertEquals(0.75f, lens90.displayAspectRatio(0), 0.001f)
        // Landscape (90°): 4:3 (1.3333) width/height
        assertEquals(4f / 3f, lens90.displayAspectRatio(90), 0.001f)

        // Periscope sensor mounted at 270° in portrait (0°) -> also 3:4 (0.75)
        val lens270 = lens90.copy(sensorOrientation = 270)
        assertEquals(0.75f, lens270.displayAspectRatio(0), 0.001f)
    }
}
