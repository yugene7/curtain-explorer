package my.neuton.curtainexplorer

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/**
 * Curtain Controller (v1 probe).
 *
 * Connects to the ECARX adaptapi vehicle SDK on the Zeekr head unit and lets us
 * (1) dump the real API, (2) read a light function, and (3) attempt a write, so
 * we learn whether a sideloaded App Lab app can actually drive the exterior
 * "daylight curtain" / ambient / carpet lights. Presets are cosmetic and
 * reversible. TEST WRITES PARKED ONLY.
 *
 * The read-only head-unit scan (Scanner) is still available via the SCAN button.
 */
class MainActivity : Activity() {

    private val carPermissions = arrayOf(
        "android.car.permission.CAR_INFO",
        "android.car.permission.CAR_EXTERIOR_LIGHTS",
        "android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS",
        "android.car.permission.READ_CAR_INTERIOR_LIGHTS",
        "android.car.permission.CONTROL_CAR_INTERIOR_LIGHTS",
        "android.car.permission.GET_CAR_VENDOR_CATEGORY_LIGHT",
        "android.car.permission.SET_CAR_VENDOR_CATEGORY_LIGHT",
        "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
    )

    private lateinit var lights: CarLights
    private lateinit var status: TextView
    private lateinit var urlView: TextView
    private lateinit var logView: TextView
    private lateinit var funcSpinner: Spinner
    private lateinit var zoneInput: EditText
    private lateinit var valueInput: EditText
    private lateinit var linkView: TextView
    private val server = ReportServer()
    private var lastReport: String? = null

    private var funcNames: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lights = CarLights(this)
        setContentView(buildUi())
        server.start()
        showUrls()
        if (Build.VERSION.SDK_INT >= 23) {
            try { requestPermissions(carPermissions, 1) } catch (_: Throwable) {}
        }
        connect()
    }

    override fun onDestroy() { server.stop(); super.onDestroy() }

    // ------------------------------------------------------------------ UI
    private fun buildUi(): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#101418"))
        }
        root.addView(TextView(this).apply {
            text = "Curtain Controller"
            textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Drives the car's own light functions through the ECARX vehicle API. " +
                   "Test writes with the car PARKED."
            textSize = 15f; setTextColor(Color.parseColor("#FFB020"))
            setPadding(0, pad / 4, 0, pad / 2)
        })

        // Row 1: connect / dump / scan
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("CONNECT") { connect() }, w(1f))
            addView(btn("DUMP API") { dump() }, w(1f))
            addView(btn("SCAN") { scan() }, w(1f))
        })

        status = TextView(this).apply {
            text = "Connecting to ECARX…"
            textSize = 16f; setTextColor(Color.parseColor("#7FD1FF")); setPadding(0, pad / 3, 0, pad / 3)
        }
        root.addView(status)

        // Control row: function spinner
        root.addView(label("Light function"))
        funcSpinner = Spinner(this)
        root.addView(funcSpinner)

        // zone + value inputs
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("zone").also { it.setPadding(0, 0, pad / 2, 0) })
            zoneInput = numField("0"); addView(zoneInput, w(1f))
            addView(label("value").also { it.setPadding(pad / 2, 0, pad / 2, 0) })
            valueInput = numField("1"); addView(valueInput, w(1f))
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("READ") { doRead() }, w(1f))
            addView(btn("SET") { doSet() }, w(1f))
        })

        // Presets (cosmetic, resolved at runtime)
        root.addView(label("Presets (cosmetic, parked)"))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Ambient: Breathe") {
                preset("SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR", "AMBIENCE_LIGHT_MAINCOLOR_BREATHE_MODE")
            }, w(1f))
            addView(btn("Ambient: Music") {
                preset("SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR", "AMBIENCE_LIGHT_MAINCOLOR_MUSIC")
            }, w(1f))
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Carpet ON") { presetVal("SETTING_FUNC_LAMP_CARPET_ONOFF", 1) }, w(1f))
            addView(btn("Carpet OFF") { presetVal("SETTING_FUNC_LAMP_CARPET_ONOFF", 0) }, w(1f))
            addView(btn("Sky ON") { presetVal("SETTING_FUNC_CAR_SKY_LIGHT_STATUS", 1) }, w(1f))
        })

        // Save / upload the log
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("SAVE") { save() }, w(1f))
            addView(btn("UPLOAD → LINK") { upload() }, w(1.4f))
        })
        linkView = TextView(this).apply {
            textSize = 26f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4CE08A")); setTextIsSelectable(true)
        }
        root.addView(linkView)
        urlView = TextView(this).apply {
            textSize = 14f; setTextColor(Color.parseColor("#7FD1FF")); setPadding(0, pad / 4, 0, pad / 4)
        }
        root.addView(urlView)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f
            setTextColor(Color.parseColor("#D8DEE4")); setTextIsSelectable(true); gravity = Gravity.START
        }
        root.addView(ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; textSize = 15f; setOnClickListener { onClick() }
    }
    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.parseColor("#9AA4AE"))
        setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
    }
    private fun numField(default: String) = EditText(this).apply {
        setText(default); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        setTextColor(Color.WHITE)
    }
    private fun w(weight: Float) = LinearLayout.LayoutParams(0, WRAP_CONTENT, weight)

    // ------------------------------------------------------------- actions
    private fun append(s: String) = runOnUiThread {
        logView.append(s + "\n")
        lastReport = logView.text.toString()
        server.report = lastReport!!
    }

    private fun connect() {
        status.text = "Connecting to ECARX…"
        logView.text = ""
        thread {
            val log = try { lights.connect() } catch (t: Throwable) { "connect crashed: $t" }
            runOnUiThread {
                logView.text = log
                lastReport = log; server.report = log
                if (lights.isConnected) {
                    status.text = "Connected. ${lights.functionIds.size} light functions resolved."
                    status.setTextColor(Color.parseColor("#4CE08A"))
                } else {
                    status.text = "Not connected — adaptapi unreachable or no get/set. See log; try DUMP API."
                    status.setTextColor(Color.parseColor("#FF6B6B"))
                }
                populateSpinner()
            }
        }
    }

    private fun populateSpinner() {
        funcNames = if (lights.functionIds.isNotEmpty()) lights.functionIds.keys.toList()
                    else (CarLights.EXTERIOR + CarLights.CARPET + CarLights.AMBIENCE)
        val labels = funcNames.map { n ->
            val id = lights.functionIds[n]
            if (id != null) "${pretty(n)}  [0x%X]".format(id) else "${pretty(n)}  (not on unit)"
        }
        funcSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }
    private fun pretty(n: String) = n.removePrefix("SETTING_FUNC_").removePrefix("SETTING_FINC_")

    private fun selectedFuncId(): Int? {
        val i = funcSpinner.selectedItemPosition
        if (i < 0 || i >= funcNames.size) return null
        return lights.functionIds[funcNames[i]]
    }

    private fun doRead() {
        val id = selectedFuncId() ?: run { toast("That function isn't present on this unit."); return }
        val zone = zoneInput.text.toString().toIntOrNull() ?: 0
        thread { val r = lights.read(id, zone); append(r.text) }
    }

    private fun doSet() {
        val id = selectedFuncId() ?: run { toast("That function isn't present on this unit."); return }
        val zone = zoneInput.text.toString().toIntOrNull() ?: 0
        val value = valueInput.text.toString().toIntOrNull() ?: run { toast("Enter a numeric value."); return }
        thread { val r = lights.set(id, zone, value); append(r.text) }
    }

    private fun preset(funcName: String, valueConstName: String) {
        val id = lights.functionIds[funcName]
        val v = lights.valueConsts[valueConstName]
        if (id == null || v == null) { toast("Preset unavailable on this unit ($funcName / $valueConstName)."); return }
        val zone = zoneInput.text.toString().toIntOrNull() ?: 0
        thread { val r = lights.set(id, zone, v); append("[${pretty(funcName)}=$valueConstName] " + r.text) }
    }

    private fun presetVal(funcName: String, value: Int) {
        val id = lights.functionIds[funcName] ?: run { toast("Preset unavailable on this unit ($funcName)."); return }
        val zone = zoneInput.text.toString().toIntOrNull() ?: 0
        thread { val r = lights.set(id, zone, value); append("[${pretty(funcName)}=$value] " + r.text) }
    }

    private fun dump() {
        status.text = "Dumping ECARX API…"
        thread {
            val text = try { lights.dump() } catch (t: Throwable) { "dump crashed: $t" }
            runOnUiThread {
                logView.text = text; lastReport = text; server.report = text
                status.text = "API dumped. Tap UPLOAD → LINK (or SAVE) and send it back."
                populateSpinner()
            }
        }
    }

    private fun scan() {
        status.text = "Scanning head unit…"
        thread {
            val text = try { Scanner(this) { m -> runOnUiThread { status.text = m } }.run() }
                       catch (t: Throwable) { "Scan crashed: $t" }
            runOnUiThread {
                logView.text = text; lastReport = text; server.report = text
                status.text = "Scan done. SAVE or UPLOAD → LINK."
            }
        }
    }

    private fun save() {
        val text = lastReport ?: run { toast("Nothing to save yet."); return }
        val name = "curtain-${System.currentTimeMillis()}.txt"
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
                val f = File(getExternalFilesDir(null), name); f.writeText(text); f.absolutePath
            }
            status.text = "Saved: $where"
        } catch (t: Throwable) { status.text = "Save failed ($t). Use UPLOAD → LINK instead." }
    }

    private fun upload() {
        val text = lastReport ?: run { toast("Nothing to upload yet."); return }
        status.text = "Uploading…"; linkView.text = ""
        thread {
            val result = runCatching { post(text) }
            runOnUiThread {
                result.onSuccess { url ->
                    linkView.text = url; status.text = "Open this link on your phone, or send it to Claude:"
                }.onFailure { status.text = "Upload failed: ${it.message}. Is the car online? Try SAVE instead." }
            }
        }
    }

    private fun post(body: String): String {
        val conn = (java.net.URL("https://paste.rs/").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val reply = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.trim().orEmpty()
            if (code !in 200..299 || !reply.startsWith("http")) throw IllegalStateException("HTTP $code $reply")
            return reply
        } finally { conn.disconnect() }
    }

    private fun showUrls() {
        val urls = server.urls()
        urlView.text = if (urls.isEmpty())
            "Phone view: join the car's Wi-Fi hotspot, then reopen."
        else "On a phone on the car's Wi-Fi, open: " + urls.joinToString("  ")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
