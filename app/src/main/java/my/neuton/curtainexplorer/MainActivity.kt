package my.neuton.curtainexplorer

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var urlView: TextView
    private lateinit var reportView: TextView
    private lateinit var scanBtn: Button
    private lateinit var linkView: TextView
    private val server = ReportServer()
    private var report: String? = null

    private val carPermissions = arrayOf(
        "android.car.permission.CAR_INFO",
        "android.car.permission.CAR_EXTERIOR_LIGHTS",
        "android.car.permission.READ_CAR_INTERIOR_LIGHTS",
        "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
        "android.car.permission.CAR_POWERTRAIN",
        "android.car.permission.CAR_SPEED",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        server.start()
        showUrls()
        // Ask for the read-only car permissions; denied ones just hide some data.
        if (Build.VERSION.SDK_INT >= 23) {
            try { requestPermissions(carPermissions, 1) } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#101418"))
        }
        root.addView(TextView(this).apply {
            text = "Curtain Explorer"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Read-only scan. It lists what this head unit exposes and changes nothing on the car."
            textSize = 18f
            setTextColor(Color.parseColor("#9AA4AE"))
            setPadding(0, pad / 4, 0, pad / 2)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanBtn = Button(this).apply {
            text = "SCAN"
            textSize = 22f
            setOnClickListener { scan() }
        }
        val saveBtn = Button(this).apply {
            text = "SAVE REPORT"
            textSize = 22f
            setOnClickListener { save() }
        }
        val uploadBtn = Button(this).apply {
            text = "UPLOAD → GET LINK"
            textSize = 22f
            setOnClickListener { upload() }
        }
        row.addView(scanBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(uploadBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.4f))
        row.addView(saveBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(row)

        linkView = TextView(this).apply {
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4CE08A"))
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(linkView)

        status = TextView(this).apply {
            text = "Tap SCAN to start."
            textSize = 20f
            setTextColor(Color.parseColor("#FFB020"))
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(status)

        urlView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.parseColor("#7FD1FF"))
            setPadding(0, pad / 4, 0, pad / 2)
        }
        root.addView(urlView)

        reportView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.parseColor("#D8DEE4"))
            setTextIsSelectable(true)
            gravity = Gravity.START
        }
        root.addView(ScrollView(this).apply { addView(reportView) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun showUrls() {
        val urls = server.urls()
        urlView.text = if (urls.isEmpty())
            "Phone viewing: connect your phone to the car's Wi-Fi hotspot, then reopen this app."
        else
            "On a phone connected to the car's Wi-Fi, open:\n" + urls.joinToString("\n")
    }

    private fun scan() {
        scanBtn.isEnabled = false
        status.text = "Scanning…"
        thread {
            val text = try {
                Scanner(this) { msg -> runOnUiThread { status.text = msg } }.run()
            } catch (t: Throwable) {
                "Scan crashed: $t"
            }
            report = text
            server.report = text
            runOnUiThread {
                reportView.text = text
                status.text = "Done. Tap SAVE REPORT, or open the address below on your phone."
                scanBtn.isEnabled = true
                showUrls()
            }
        }
    }

    private fun save() {
        val text = report ?: run { toast("Scan first."); return }
        val name = "curtain-report-${System.currentTimeMillis()}.txt"
        try {
            val where = if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("No Downloads folder")
                contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                "Download/$name"
            } else {
                val f = File(getExternalFilesDir(null), name)
                f.writeText(text)
                f.absolutePath
            }
            status.text = "Saved: $where"
        } catch (t: Throwable) {
            status.text = "Save failed ($t). Use the phone address instead."
        }
    }

    /**
     * Uploads the report to paste.rs (free, no account) and shows the short link
     * in big text, so it can be typed into a phone. Uses the car's own LTE.
     * If the full report is too big, it falls back to the summary part only.
     */
    private fun upload() {
        val text = report ?: run { toast("Scan first."); return }
        status.text = "Uploading…"
        linkView.text = ""
        thread {
            val summary = text.substringBefore("==================== Device")
            val result = runCatching { post(text) }
                .recoverCatching { post(summary + "\n(full report too large; summary only)") }
            runOnUiThread {
                result.onSuccess { url ->
                    linkView.text = url
                    status.text = "Open this link on your phone, or send it to Claude:"
                }.onFailure {
                    status.text = "Upload failed: ${it.message}. Is the car online? Try again, or use the Brave method."
                }
            }
        }
    }

    private fun post(body: String): String {
        val conn = (java.net.URL("https://paste.rs/").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val reply = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.trim().orEmpty()
            if (code !in 200..299 || !reply.startsWith("http")) throw IllegalStateException("HTTP $code $reply")
            return reply
        } finally {
            conn.disconnect()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
