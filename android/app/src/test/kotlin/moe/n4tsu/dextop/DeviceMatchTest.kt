package moe.n4tsu.dextop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceMatchTest {
    private val pixel = DeviceIdentity("Google", "google", "Pixel 9", "tokay", "tokay", "google/tokay/build", 35)

    @Test fun normalizesAndMatchesAllSpecifiedFields() {
        val match = DeviceMatch(
            manufacturers = setOf("google"), models = setOf("pixel 9"),
            devices = setOf("tokay"), fingerprintPrefixes = setOf("google/tokay"),
            minSdk = 35, maxSdk = 35
        )
        assertTrue(match.matches(pixel))
    }

    @Test fun modelOverrideDoesNotLeakToOtherModels() {
        val match = DeviceMatch(manufacturers = setOf("google"), models = setOf("pixel 9"))
        assertFalse(match.matches(pixel.copy(model = "Pixel 8")))
        assertFalse(match.matches(pixel.copy(manufacturer = "Samsung", model = "Pixel 9")))
    }

    @Test fun sdkRangeIsIsolated() {
        val match = DeviceMatch(manufacturers = setOf("google"), minSdk = 35, maxSdk = 35)
        assertFalse(match.matches(pixel.copy(sdk = 34)))
        assertFalse(match.matches(pixel.copy(sdk = 36)))
    }
}
