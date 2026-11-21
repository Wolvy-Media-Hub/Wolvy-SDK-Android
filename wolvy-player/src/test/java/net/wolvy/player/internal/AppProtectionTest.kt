package net.wolvy.player.internal

import net.wolvy.player.AppRenderVideoSource
import net.wolvy.player.WolvyError
import net.wolvy.player.WolvyVideo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** App protection: the signatures (against values from the server's PHP) and the attestor's flow. */
class AppProtectionTest {

    // ---- Signing: the same strings as Wolvy-Backend/app_protection.php and sdk_app_key.php ----

    private val key = "4f2c0d5e6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f"

    @Test fun boundAndNonceMatchTheServer() {
        val bound = AppSigning.bound("00e1", 29, "android", "net.wolvy.spike.attest")
        assertEquals("wolvy-attest|1|00e1|29|android|net.wolvy.spike.attest", bound)
        // php: wolvy_pi_nonce($bound)
        assertEquals("Rl80GWJjQyTwXmJhaE9N7ZA1A9DuQn3hIt98JEe7z8I", AppSigning.playIntegrityNonce(bound))
    }

    @Test fun signaturesMatchTheServer() {
        // php: hash_hmac('sha256', 'vidA1:29:1795288000', $key)
        assertEquals("72670584018c58a96a37b00480d2717ffedfba4990a623617fcf28276c20e573",
            AppSigning.renderSignature(key, "vidA1", 29, 1795288000))
        // php: hash_hmac('sha256', hash('sha256', 'aaa.bbb.ccc') . ':1795288000', $key)
        assertEquals("c6792382fc0faccd2fc64b46a4bb5d1a3f3495047d63d3e900876d9225f4a2a6",
            AppSigning.licenceSignature(key, "aaa.bbb.ccc", 1795288000))
    }

    // ---- The attestor ----------------------------------------------------------------------

    private class Fake {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val answers = ArrayDeque<WolvyHttp.Answer>()
        var now = 1_795_288_000L
        val slept = mutableListOf<Long>()
        val nonces = mutableListOf<String>()
        val tokenResults = ArrayDeque<() -> String>()

        fun answer(status: Int, json: String) = answers.addLast(WolvyHttp.Answer(status, JSONObject(json)))

        fun attestor() = AppAttestor(
            appId = "net.wolvy.test", sdk = "wolvy-android/0.2.0", apiBase = "https://wolvy.net/api",
            tokens = { nonce -> nonces += nonce; (tokenResults.removeFirstOrNull() ?: { "tok" })() },
            post = { url, headers, form ->
                assertEquals("android", headers["X-Wolvy-Platform"])
                assertEquals("net.wolvy.test", headers["X-Wolvy-App-Id"])
                calls += url.substringAfterLast('/') to form
                answers.removeFirstOrNull() ?: throw WolvyError(WolvyError.Code.NETWORK_ERROR, "down", "network")
            },
            clock = { now },
            sleep = { slept += it },
        )
    }

    private val challenge = "a".repeat(64)

    @Test fun provesTheAppAndKeepsTheKey() {
        val f = Fake()
        f.answer(200, """{"status":"success","method":"play_integrity","challenge":"$challenge","expires_in":120}""")
        f.answer(200, """{"status":"success","key_id":"3.1795309600.${"b".repeat(32)}","key":"k1","expires_at":1795309600,"ttl":21600,"device_level":"device"}""")
        val a = f.attestor()
        val k = a.keyFor(29)
        assertNotNull(k)
        assertEquals("k1", k!!.key)
        assertEquals(listOf("app_challenge", "app_attest"), f.calls.map { it.first })
        assertEquals(mapOf("user_id" to "29", "challenge" to challenge, "token" to "tok"), f.calls[1].second)
        // The nonce proves the bound string for this challenge, account and app.
        assertEquals(AppSigning.playIntegrityNonce(AppSigning.bound(challenge, 29, "android", "net.wolvy.test")), f.nonces.single())
        // Kept until a minute before it ends.
        f.now += 21000
        assertSame(k, a.keyFor(29))
        assertEquals(2, f.calls.size)
        f.now += 560
        f.answer(200, """{"status":"success","method":null}""")
        assertNull(a.keyFor(29))
    }

    @Test fun notSetUpPlaysUnprovenAndWaits() {
        val f = Fake()
        f.answer(200, """{"status":"success","method":null}""")
        val a = f.attestor()
        assertNull(a.keyFor(29))
        assertNull(a.keyFor(29))
        assertEquals(1, f.calls.size) // not asked again for ten minutes
        f.now += 601
        f.answer(200, """{"status":"success","method":null}""")
        assertNull(a.keyFor(29))
        assertEquals(2, f.calls.size)
    }

    @Test fun refusalIsKeptForTheError() {
        val f = Fake()
        f.answer(200, """{"status":"success","method":"play_integrity","challenge":"$challenge"}""")
        f.answer(403, """{"status":"error","code":"device_compromised","message":"This device doesn't pass security checks."}""")
        val a = f.attestor()
        assertNull(a.keyFor(29))
        assertEquals("device_compromised", a.lastRefusal?.reason)
        assertEquals(WolvyError.Code.DEVICE_NOT_SUPPORTED, a.lastRefusal?.code)
    }

    @Test fun transientPlayIntegrityErrorsAreRetried() {
        val f = Fake()
        f.answer(200, """{"status":"success","method":"play_integrity","challenge":"$challenge"}""")
        f.answer(200, """{"status":"success","key_id":"3.1795309600.${"b".repeat(32)}","key":"k1","expires_at":1795309600}""")
        f.tokenResults.addLast { throw AppAttestor.IntegrityFailure(-3, true, "network") }
        f.tokenResults.addLast { throw AppAttestor.IntegrityFailure(-8, true, "too many") }
        f.tokenResults.addLast { "tok3" }
        assertNotNull(f.attestor().keyFor(29))
        assertEquals(listOf(1000L, 2000L), f.slept)
    }

    @Test fun noPlayServicesPlaysUnproven() {
        val f = Fake()
        f.answer(200, """{"status":"success","method":"play_integrity","challenge":"$challenge"}""")
        f.tokenResults.addLast { throw AppAttestor.IntegrityFailure(-6, false, "no play services") }
        val a = f.attestor()
        assertNull(a.keyFor(29))
        assertEquals(listOf("app_challenge"), f.calls.map { it.first }) // never sent to app_attest
        assertTrue(f.slept.isEmpty())
    }

    @Test fun networkTroublePlaysUnprovenBriefly() {
        val f = Fake() // no answers: every call fails
        val a = f.attestor()
        assertNull(a.keyFor(29))
        f.now += 31
        assertNull(a.keyFor(29))
        assertEquals(2, f.calls.size)
    }

    @Test fun anotherAccountGetsItsOwnKey() {
        val f = Fake()
        f.answer(200, """{"status":"success","method":"play_integrity","challenge":"$challenge"}""")
        f.answer(200, """{"status":"success","key_id":"3.1795309600.${"b".repeat(32)}","key":"k29","expires_at":1795309600}""")
        val a = f.attestor()
        a.keyFor(29)
        f.answer(200, """{"status":"success","method":null}""")
        assertNull(a.keyFor(30))
        assertEquals("30", f.calls.last().second["user_id"])
    }

    @Test fun unprovenSourceSendsNoKey() {
        // The String constructor has no attestor: no app-key headers, as in 0.1.
        val video = WolvyVideo("vid1", 29, "vt")
        val e = assertThrows(WolvyError::class.java) {
            AppRenderVideoSource("com.example.app", "x/1.0.0", "http://127.0.0.1:9/api").fetch(video, null)
        }
        assertEquals("network", e.reason)
        assertFalse(e.message.isNullOrEmpty())
    }
}
