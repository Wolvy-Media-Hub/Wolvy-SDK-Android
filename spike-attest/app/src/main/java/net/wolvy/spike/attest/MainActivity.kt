package net.wolvy.spike.attest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Phase 5, spike A: can a customer's app ask for a Play Integrity verdict under Wolvy's Cloud
 * project (Wolvy Player's, 406707116001), and can Wolvy decode it with the package filled in?
 *
 * The app asks for a token (Standard, or Classic) and shows a report: the package, its signing
 * certificate, the request hash or nonce and the token. The report goes to Wolvy's Mac, where
 * tools/decode-integrity.mjs decodes it with the project's service account.
 *
 * Not the SDK. Nothing is sent anywhere by this app except to Google Play services.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var projectInput: EditText
    private lateinit var output: TextView
    private val lines = mutableListOf<String>()
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        projectInput = EditText(this).apply {
            hint = "Cloud project number (empty: the project linked in Play Console)"
            setText(DEFAULT_PROJECT)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val standard = Button(this).apply { text = "Standard request" }
        val classic = Button(this).apply { text = "Classic request" }
        val copy = Button(this).apply { text = "Copy report" }
        val share = Button(this).apply { text = "Share report" }
        output = TextView(this).apply { setTextIsSelectable(true); textSize = 13f }

        standard.setOnClickListener { runStandard() }
        classic.setOnClickListener { runClassic() }
        copy.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Wolvy attest report", report.ifEmpty { lines.joinToString("\n") }))
            Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
        }
        share.setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report.ifEmpty { lines.joinToString("\n") })
            }, "Share report"))
        }

        val row = { a: Button, b: Button ->
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        root.addView(projectInput)
        root.addView(row(standard, classic))
        root.addView(row(copy, share))
        root.addView(ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        show(deviceLines())
    }

    /** What the server will compare the verdict with. */
    private fun deviceLines(): List<String> {
        val pm = packageManager
        val certs = signingCerts()
        return listOf(
            "package=$packageName",
            "version_code=${pm.getPackageInfo(packageName, 0).let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }}",
            "cert_sha256_hex=${certs.joinToString(",") { it.hex() }}",
            "cert_sha256_b64url=${certs.joinToString(",") { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }}",
            "installer=${installer() ?: "none (sideloaded)"}",
            "device=${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "play_services=${versionOf("com.google.android.gms")}",
            "play_store=${versionOf("com.android.vending")}",
        )
    }

    private fun runStandard() {
        val project = projectNumber()
        val challenge = randomHex(32)
        // The string the design binds the proof to (phase-5-attestation.md, 2.2).
        val bound = "wolvy-attest|1|$challenge|spike|android|$packageName"
        val requestHash = sha256(bound.toByteArray()).hex()
        val head = deviceLines() + listOf("type=standard", "project=${project ?: "linked"}", "challenge=$challenge", "bound=$bound", "request_hash=$requestHash")
        show(head + "status=preparing…")
        val started = System.currentTimeMillis()
        val manager = IntegrityManagerFactory.createStandard(applicationContext)
        manager.prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .apply { if (project != null) setCloudProjectNumber(project) }.build()
        ).addOnSuccessListener { provider ->
            val prepared = System.currentTimeMillis()
            show(head + listOf("prepare_ms=${prepared - started}", "status=requesting…"))
            provider.request(
                StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()
            ).addOnSuccessListener { response ->
                val token = response.token()
                finish(head + listOf("prepare_ms=${prepared - started}", "request_ms=${System.currentTimeMillis() - prepared}",
                    "status=ok", "token_length=${token.length}", "token=$token"))
            }.addOnFailureListener { e -> finish(head + listOf("prepare_ms=${prepared - started}", "status=request_failed") + errorLines(e)) }
        }.addOnFailureListener { e -> finish(head + listOf("status=prepare_failed") + errorLines(e)) }
    }

    private fun runClassic() {
        val project = projectNumber()
        val challenge = randomHex(32)
        // As the design does it (phase-5-attestation.md, 2.2): nonce = base64url(SHA-256(bound)), unpadded.
        val bound = "wolvy-attest|1|$challenge|spike|android|$packageName"
        val nonce = Base64.encodeToString(sha256(bound.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val head = deviceLines() + listOf("type=classic", "project=${project ?: "linked"}", "challenge=$challenge", "bound=$bound", "nonce=$nonce")
        show(head + "status=requesting…")
        val started = System.currentTimeMillis()
        IntegrityManagerFactory.create(applicationContext).requestIntegrityToken(
            IntegrityTokenRequest.builder().setNonce(nonce)
                .apply { if (project != null) setCloudProjectNumber(project) }.build()
        ).addOnSuccessListener { response ->
            val token = response.token()
            finish(head + listOf("request_ms=${System.currentTimeMillis() - started}", "status=ok",
                "token_length=${token.length}", "token=$token"))
        }.addOnFailureListener { e -> finish(head + listOf("status=request_failed") + errorLines(e)) }
    }

    private fun errorLines(e: Exception): List<String> {
        val code = when (e) {
            is StandardIntegrityException -> e.errorCode.toString()
            is IntegrityServiceException -> e.errorCode.toString()
            else -> "n/a"
        }
        return listOf("error_class=${e.javaClass.simpleName}", "error_code=$code", "error_message=${e.message}")
    }

    private fun finish(all: List<String>) {
        report = "WOLVY-ATTEST-REPORT\n" + all.joinToString("\n") + "\nEND"
        show(all)
    }

    private fun show(all: List<String>) {
        lines.clear(); lines.addAll(all)
        // The token is long: show its start on screen; the full value is in the copied report.
        output.text = all.joinToString("\n") { if (it.startsWith("token=") && it.length > 90) it.take(90) + "…" else it }
    }

    /** Null when the field is empty: the request then runs under the project linked in Play Console. */
    private fun projectNumber(): Long? = projectInput.text.toString().trim().toLongOrNull()

    @Suppress("DEPRECATION")
    private fun signingCerts(): List<ByteArray> {
        val pm = packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (info == null) emptyArray() else if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        return sigs.orEmpty().map { sha256(it.toByteArray()) }
    }

    @Suppress("DEPRECATION")
    private fun installer(): String? = try {
        if (Build.VERSION.SDK_INT >= 30) packageManager.getInstallSourceInfo(packageName).installingPackageName
        else packageManager.getInstallerPackageName(packageName)
    } catch (_: Exception) { null }

    private fun versionOf(pkg: String): String = try {
        packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) { "not installed" }

    private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun randomHex(n: Int) = randomBytes(n).hex()
    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        /** Wolvy Player's Cloud project (public identifier, as in Wolvy-Player-Mobile-App). */
        const val DEFAULT_PROJECT = "406707116001"
    }
}
