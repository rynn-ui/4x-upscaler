package com.upscaler.studio.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionPolicyTest {

    @Test
    fun preservesAspectRatioAcrossOrientations() {
        assertEquals(3840 to 2160, ResolutionPolicy.computeTargetDimensions(1280, 720, "4K (2160p)", "Real-ESRGAN Anime"))
        assertEquals(2160 to 3840, ResolutionPolicy.computeTargetDimensions(720, 1280, "4K (2160p)", "Real-ESRGAN Anime"))
        assertEquals(1080 to 1920, ResolutionPolicy.computeTargetDimensions(540, 960, "1080p (FHD)", "2x-sudo-UltraCompact"))
        assertEquals(1080 to 1080, ResolutionPolicy.computeTargetDimensions(512, 512, "1080p (FHD)", "Real-ESRGAN General"))
        assertEquals(1440 to 1080, ResolutionPolicy.computeTargetDimensions(640, 480, "1080p (FHD)", "Real-ESRGAN General"))
    }

    @Test
    fun reflectsModelNativeScaleAccurately() {
        assertEquals("Auto (2× Native)", ResolutionPolicy.nativeLabelForModel("2x-sudo-UltraCompact"))
        assertEquals("Auto (2× Native)", ResolutionPolicy.nativeLabelForModel("Real-ESRGAN Anime"))
        assertEquals(666 to 442, ResolutionPolicy.computeTargetDimensions(333, 221, "Auto (Native)", "2x-sudo-UltraCompact"))
        assertEquals(666 to 442, ResolutionPolicy.computeTargetDimensions(333, 221, "Auto (Native)", "Real-ESRGAN Anime"))
    }

    @Test
    fun twoTimesAlwaysDoublesTheSource() {
        // 1080p clip -> 4K, regardless of which 2× model is selected.
        assertEquals(3840 to 2160, ResolutionPolicy.computeTargetDimensions(1920, 1080, "2×", "2x-sudo-UltraCompact"))
        assertEquals(3840 to 2160, ResolutionPolicy.computeTargetDimensions(1920, 1080, "2×", "Real-ESRGAN Anime"))
        assertEquals(2160 to 3840, ResolutionPolicy.computeTargetDimensions(1080, 1920, "2×", "Real-ESRGAN General"))
        assertEquals(1440 to 2560, ResolutionPolicy.computeTargetDimensions(720, 1280, "2x", "2x-sudo-UltraCompact"))
    }
}

