package net.wolvy.player

import net.wolvy.player.internal.AppAttestor
import net.wolvy.player.internal.AppSigning
import net.wolvy.player.internal.WolvyHttp
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread

/** The client of app_render_video against a local HTTP server (a plain socket: no extra test library). */
class AppRenderVideoSourceTest {
    private lateinit var server: ServerSocket
    private var port = 0
    @Volatile private var status = 200
    @Volatile private var body = """{"status":"success","token":"t","server_id":"25001","session_id":"s1"}"""
    private val seenHeaders = HashMap<String, String>()
    private var seenForm = mapOf<String, String>()
    private var seenPath = ""
    private var seenMethod = ""

    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                socket.use { s ->
                    val input = BufferedInputStream(s.getInputStream())
                    fun line(): String {
                        val b = StringBuilder()
                        while (true) {
                            val c = input.read()
                            if (c < 0 || c == '\n'.code) break
                            if (c != '\r'.code) b.append(c.toChar())
                        }
                        return b.toString()
                    }
                    val request = line().split(' ')
                    seenMethod = request[0]
                    seenPath = request.getOrElse(1) { "" }
                    var length = 0
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        val name = h.substringBefore(':').trim()
                        val value = h.substringAfter(':').trim()
                        seenHeaders[name] = value
                        if (name.equals("Content-Length", ignoreCase = true)) length = value.toInt()
                    }
                    val form = ByteArray(length)
                    var read = 0
                    while (read < length) { val n = input.read(form, read, length - read); if (n < 0) break; read += n }
                    seenForm = form.toString(Charsets.UTF_8).split('&').filter { it.isNotEmpty() }
                        .associate { kv -> kv.substringBefore('=') to URLDecoder.decode(kv.substringAfter('='), "UTF-8") }
                    val bytes = body.toByteArray()
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    @After fun stop() = server.close()

    private fun source() = AppRenderVideoSource("com.example.app", "wolvy-android/9.9.9", "http://127.0.0.1:$port/api")
    private val video = WolvyVideo("vid_1", 42, "v.t+/=")

    @Test fun sendsWhatTheEndpointExpects() {
        val d = source().fetch(video, sessionId = null)
        assertEquals("POST", seenMethod)
        assertEquals("/api/app_render_video", seenPath)   // extensionless, never .php
        assertEquals("android", seenHeaders["X-Wolvy-Platform"])
        assertEquals("com.example.app", seenHeaders["X-Wolvy-App-Id"])
        assertEquals("wolvy-android/9.9.9", seenHeaders["X-Wolvy-SDK"])
        assertEquals("application/x-www-form-urlencoded", seenHeaders["Content-Type"])
        assertEquals(mapOf("user_id" to "42", "video_id" to "vid_1", "vt" to "v.t+/="), seenForm)
        assertEquals("s1", d.sessionId)
        assertEquals("http://127.0.0.1:$port/api/process_videos/widevine?token=t", d.licenceUrl)
    }

    @Test fun sendsTheSessionBackWhenAskingAgain() {
        source().fetch(video, sessionId = "0123456789abcdef0123456789abcdef")
        assertEquals("0123456789abcdef0123456789abcdef", seenForm["session_id"])
    }

    @Test fun refusalsBecomeErrorsWithTheServersCode() {
        status = 403
        body = """{"status":"error","code":"app_not_allowed","message":"This app is not allowed."}"""
        val e = assertThrows(WolvyError::class.java) { source().fetch(video, null) }
        assertEquals(WolvyError.Code.PLAYBACK_ERROR, e.code)
        assertEquals("app_not_allowed", e.reason)
    }

    @Test fun anErrorEnvelopeWithHttp200IsStillAnError() {
        body = """{"status":"error","code":"viewer_token_required","message":"Sign a viewer token."}"""
        assertEquals("viewer_token_required", assertThrows(WolvyError::class.java) { source().fetch(video, null) }.reason)
    }

    @Test fun aServerErrorPageIsANetworkError() {
        status = 502
        body = "<html>Bad gateway</html>"
        val e = assertThrows(WolvyError::class.java) { source().fetch(video, null) }
        assertEquals(WolvyError.Code.NETWORK_ERROR, e.code)
        assertEquals("http_502", e.reason)
    }

    /** A source whose attestor proves the app at once (its two calls are faked; the render goes to the local server). */
    private fun provenSource(): AppRenderVideoSource {
        val challenge = "c".repeat(64)
        val attestor = AppAttestor(
            "com.example.app", "wolvy-android/9.9.9", "http://unused/api",
            tokens = { "tok" },
            post = { url, _, _ ->
                WolvyHttp.Answer(200, JSONObject(
                    if (url.endsWith("app_challenge")) """{"status":"success","method":"play_integrity","challenge":"$challenge"}"""
                    else """{"status":"success","key_id":"3.1795309600.${"d".repeat(32)}","key":"appkey","expires_at":1795309600}""",
                ))
            },
            clock = { 1_795_288_000L },
        )
        return AppRenderVideoSource("com.example.app", "wolvy-android/9.9.9", "http://127.0.0.1:$port/api", attestor) { 1_795_288_000L }
    }

    @Test fun aProvenAppSignsTheRequestAndTheLicence() {
        body = """{"status":"success","token":"lic.tok.en","server_id":"25001","session_id":"s1","attested":true}"""
        val d = provenSource().fetch(video, null)
        assertEquals("3.1795309600.${"d".repeat(32)}", seenHeaders["X-Wolvy-App-Key-Id"])
        assertEquals("1795288000", seenHeaders["X-Wolvy-App-Timestamp"])
        assertEquals(AppSigning.renderSignature("appkey", "vid_1", 42, 1_795_288_000L), seenHeaders["X-Wolvy-App-Signature"])
        assertEquals(true, d.attested)
        assertEquals(mapOf(
            "X-Wolvy-Licence-Timestamp" to "1795288000",
            "X-Wolvy-Licence-Signature" to AppSigning.licenceSignature("appkey", "lic.tok.en", 1_795_288_000L),
        ), d.licenceHeaders)
    }

    @Test fun anUnprovenAnswerGetsNoLicenceSignature() {
        body = """{"status":"success","token":"t","server_id":"25001","attested":false}"""
        val d = provenSource().fetch(video, null)
        assertEquals(false, d.attested)
        assertEquals(emptyMap<String, String>(), d.licenceHeaders)
    }

    @Test fun noServerIsANetworkError() {
        server.close()
        val e = assertThrows(WolvyError::class.java) {
            AppRenderVideoSource("com.example.app", "x/1.0.0", "http://127.0.0.1:$port/api").fetch(video, null)
        }
        assertEquals(WolvyError.Code.NETWORK_ERROR, e.code)
        assertEquals("network", e.reason)
    }
}
