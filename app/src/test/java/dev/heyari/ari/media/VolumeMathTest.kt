package dev.heyari.ari.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeMathTest {
    @Test fun half_of_15_rounds_to_8() = assertEquals(8, percentToStreamVolume(50, 15))
    @Test fun zero_is_zero() = assertEquals(0, percentToStreamVolume(0, 15))
    @Test fun hundred_is_max() = assertEquals(15, percentToStreamVolume(100, 15))
    @Test fun over_100_clamps_to_max() = assertEquals(15, percentToStreamVolume(250, 15))
    @Test fun negative_clamps_to_zero() = assertEquals(0, percentToStreamVolume(-10, 15))
    @Test fun rounds_to_nearest() = assertEquals(2, percentToStreamVolume(10, 15)) // 1.5 -> 2
}
