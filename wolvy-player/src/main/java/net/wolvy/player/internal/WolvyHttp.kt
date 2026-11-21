package net.wolvy.player.internal

import net.wolvy.player.WolvyError
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One form POST to Wolvy that answers JSON. Used by the playback source and app protection. */
internal object WolvyHttp {
    class Answer(val status: Int, val json: JSONObject)

    /** Posts [form] with [headers]; any HTTP status with a JSON body is an [Answer]. */
    @Throws(WolvyError::class)
    fun postForm(url: String, headers: Map<String, String>, form: Map<String, String>): Answer {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw WolvyError(WolvyError.Code.NETWORK_ERROR, "Could not reach Wolvy.", "network", e)
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val text = (if (status < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw WolvyError(
                    if (status >= 500) WolvyError.Code.NETWORK_ERROR else WolvyError.Code.FAILED,
                    "Wolvy answered HTTP $status without JSON.", "http_$status", e,
                )
            }
            return Answer(status, json)
        } catch (e: WolvyError) {
            throw e
        } catch (e: IOException) {
            throw WolvyError(WolvyError.Code.NETWORK_ERROR, "Could not reach Wolvy.", "network", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
