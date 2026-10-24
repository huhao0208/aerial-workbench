package app.orchard.tv.system

import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.AerialQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class AerialQualityTest {
    private val all = AerialQuality.entries.toSet() - AerialQuality.Auto

    @Test fun automaticComfortKeepsSdrWithoutReducingResolution() {
        assertEquals(AerialQuality.Uhd, AerialFormats.select(all, AerialQuality.Auto, true))
        assertEquals(AerialQuality.Hd, AerialFormats.select(setOf(AerialQuality.Hd), AerialQuality.Auto, true))
    }

    @Test fun explicitDolbyAndOtherDevicesKeepTheirExistingChoice() {
        assertEquals(AerialQuality.DolbyVision, AerialFormats.select(all, AerialQuality.DolbyVision, true))
        assertEquals(AerialQuality.DolbyVision, AerialFormats.select(all, AerialQuality.Auto, false))
        assertEquals(AerialQuality.Hd, AerialFormats.select(all, AerialQuality.Hd, true))
    }
}
