package net.wolvy.player

import org.junit.Assert.assertEquals
import org.junit.Test

class WolvyErrorTest {
    @Test fun serverRefusalsKeepTheirCodeAsReason() {
        val e = WolvyError.fromServer(403, "app_not_allowed", "This app is not on the account's allowed apps.")
        assertEquals(WolvyError.Code.PLAYBACK_ERROR, e.code)
        assertEquals("app_not_allowed", e.reason)
        assertEquals("This app is not on the account's allowed apps.", e.message)
    }

    @Test fun rateLimitsAndServerFailuresAreNetworkErrors() {
        assertEquals(WolvyError.Code.NETWORK_ERROR, WolvyError.fromServer(429, "rate_limited", "").code)
        assertEquals(WolvyError.Code.NETWORK_ERROR, WolvyError.fromServer(503, "not_available", null).code)
        assertEquals(WolvyError.Code.NETWORK_ERROR, WolvyError.fromServer(500, null, null).code)
    }

    @Test fun noCodeAtAll() {
        val e = WolvyError.fromServer(200, null, "")
        assertEquals(WolvyError.Code.FAILED, e.code)
        assertEquals("http_200", e.reason)
    }

    @Test fun codesMatchTheWebPlayerApi() {
        assertEquals(
            listOf("network_error", "playback_error", "device_not_supported", "not_ready", "invalid_argument", "destroyed", "failed"),
            WolvyError.Code.values().map { it.value },
        )
    }
}
