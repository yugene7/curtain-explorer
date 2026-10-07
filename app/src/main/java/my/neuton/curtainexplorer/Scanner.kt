package my.neuton.curtainexplorer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only survey of the head unit.
 *
 * It only LISTS things: installed apps and their public entry points, system
 * services, car properties, settings keys and available SDK classes. It never
 * calls a setter, sends a broadcast, binds to a service or writes a car
 * property, so running it cannot change anything on the car.
 */
class Scanner(private val ctx: Context, private val progress: (String) -> Unit) {

    private val pm: PackageManager = ctx.packageManager
    private val out = StringBuilder()

    /** Words that hint at the Stargate curtain or exterior lighting. */
    private val lightWords = listOf(
        "light", "lamp", "curtain", "stargate", "led", "ambient", "isd",
        "exterior", "grille", "pixel", "matrix", "banner", "lightshow", "dlp"
    )

    /** Vendors whose system apps are worth a closer look on a Zeekr head unit. */
    private val vendorWords = listOf("zeekr", "ecarx", "geely", "lynk", "flyme", "volvo")

    private fun line(s: String = "") { out.append(s).append('\n') }
    private fun header(s: String) {
        line(); line("==================== $s ====================")
    }
    private fun isLighty(s: String?): Boolean {
        if (s == null) return false
        val l = s.lowercase(Locale.ROOT)
        return lightWords.any { l.contains(it) }
    }
    private fun isVendor(s: String?): Boolean {
        if (s == null) return false
        val l = s.lowercase(Locale.ROOT)
        return vendorWords.any { l.contains(it) }
    }

    fun run(): String {
        line("CURTAIN EXPLORER REPORT v0.1 (read-only)")
        line("Generated: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        section("Device") { device() }
        section("Car properties") { carProperties() }
        section("Lighting-related apps") { packages() }
        section("Permissions") { permissions() }
        section("System services") { services() }
        section("Settings keys") { settings() }
        section("SDK class probes") { classProbes() }
        line(); line("END OF REPORT")

        // Short summary first, so it fits on screen and in an upload.
        val body = out.toString()
        val keys = listOf("<<< LIGHT", "<<< VENDOR", "FOUND  ", "### ", "declares-permission",
            "NOT available", "Properties visible", "Android:", "Model:", "Manufacturer:")
        val summary = body.lines().filter { l -> keys.any { l.contains(it) } }
            .distinct().take(300)
        return buildString {
            append("==================== SUMMARY ====================\n")
            summary.forEach { append(it).append('\n') }
            append('\n')
            append(body)
        }
    }

    private fun section(name: String, block: () -> Unit) {
        progress("Scanning: $name…")
        header(name)
        try { block() } catch (t: Throwable) { line("!! section failed: $t") }
    }

    // ---------------------------------------------------------------- device
    private fun device() {
        line("Manufacturer: ${Build.MANUFACTURER}")
        line("Brand:        ${Build.BRAND}")
        line("Model:        ${Build.MODEL}")
        line("Device:       ${Build.DEVICE}")
        line("Product:      ${Build.PRODUCT}")
        line("Board:        ${Build.BOARD}")
        line("Hardware:     ${Build.HARDWARE}")
        line("Android:      ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        line("Build:        ${Build.DISPLAY}")
        line("Fingerprint:  ${Build.FINGERPRINT}")
        line("Automotive feature: " + pm.hasSystemFeature("android.hardware.type.automotive"))
        line()
        line("-- System shared libraries --")
        pm.systemSharedLibraryNames?.sorted()?.forEach { line("  $it") }
        line()
        line("-- System features (vendor / car related) --")
        pm.systemAvailableFeatures
            .mapNotNull { it.name }
            .filter { !it.startsWith("android.hardware.") || it.contains("automotive") || isLighty(it) || isVendor(it) }
            .sorted()
            .forEach { line("  $it") }
    }

    // ---------------------------------------------------------- car (AAOS)
    private fun carProperties() {
        val carCls = try {
            Class.forName("android.car.Car")
        } catch (t: Throwable) {
            line("android.car library NOT available on this head unit ($t).")
            line("=> Lighting is probably controlled by a vendor SDK instead; see the app and class sections.")
            return
        }
        val car = carCls.getMethod("createCar", Context::class.java).invoke(null, ctx)
        if (car == null) { line("Car.createCar returned null"); return }
        try {
            val mgr = carCls.getMethod("getCarManager", String::class.java).invoke(car, "property")
            if (mgr == null) { line("CarPropertyManager not available"); return }
            val list = mgr.javaClass.getMethod("getPropertyList").invoke(mgr) as List<*>
            val idsCls = try { Class.forName("android.car.VehiclePropertyIds") } catch (_: Throwable) { null }
            val toName = idsCls?.getMethod("toString", Int::class.javaPrimitiveType)

            line("Properties visible to this app: ${list.size}")
            line("(VENDOR = custom Zeekr/ECARX property; ACCESS 1=read 2=write 3=read+write)")
            line()
            val rows = list.mapNotNull { cfg ->
                if (cfg == null) return@mapNotNull null
                val c = cfg.javaClass
                val id = c.getMethod("getPropertyId").invoke(cfg) as Int
                val name = try { toName?.invoke(null, id) as? String } catch (_: Throwable) { null } ?: "?"
                val access = try { c.getMethod("getAccess").invoke(cfg) } catch (_: Throwable) { "?" }
                val type = try { (c.getMethod("getPropertyType").invoke(cfg) as Class<*>).simpleName } catch (_: Throwable) { "?" }
                val areas = try { (c.getMethod("getAreaIds").invoke(cfg) as IntArray).joinToString(",") } catch (_: Throwable) { "?" }
                val vendor = (id.toLong() and 0xF0000000L) == 0x20000000L
                val flag = when {
                    isLighty(name) -> "  <<< LIGHT"
                    vendor -> "  <<< VENDOR"
                    else -> ""
                }
                Triple(id, String.format(Locale.US, "0x%08X  %-45s access=%s type=%s areas=[%s]%s",
                    id, name, access, type, areas, flag), flag.isNotEmpty())
            }
            line("-- Flagged (light or vendor) --")
            rows.filter { it.third }.sortedBy { it.first }.forEach { line(it.second) }
            line()
            line("-- All --")
            rows.sortedBy { it.first }.forEach { line(it.second) }
        } finally {
            try { carCls.getMethod("disconnect").invoke(car) } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------- packages
    private fun packages() {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS
        @Suppress("DEPRECATION")
        val all = pm.getInstalledPackages(0)
        line("Installed packages: ${all.size}")

        val interesting = all.filter { p ->
            isLighty(p.packageName) || isLighty(label(p.applicationInfo))
        }
        line("Packages whose name/label mentions lighting: ${interesting.size}")
        interesting.forEach { p ->
            @Suppress("DEPRECATION")
            val full = try { pm.getPackageInfo(p.packageName, flags) } catch (t: Throwable) { p }
            dumpPackage(full)
        }

        line()
        line("-- Vendor system apps with lighting-related components --")
        all.filter { isVendor(it.packageName) && it !in interesting }.forEach { p ->
            @Suppress("DEPRECATION")
            val full = try { pm.getPackageInfo(p.packageName, flags) } catch (_: Throwable) { return@forEach }
            val comps = components(full).filter { isLighty(it.second) }
            val perms = full.permissions?.map { it.name }?.filter { isLighty(it) }.orEmpty()
            if (comps.isNotEmpty() || perms.isNotEmpty()) dumpPackage(full)
        }

        line()
        line("-- All installed packages --")
        all.sortedBy { it.packageName }.forEach {
            val sys = if ((it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0) "sys" else "usr"
            line("  [$sys] ${it.packageName}  v${it.versionName}")
        }
    }

    private fun label(ai: ApplicationInfo?): String? =
        try { ai?.loadLabel(pm)?.toString() } catch (_: Throwable) { null }

    /** (kind, className, exported, permission) */
    private fun components(p: PackageInfo): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        p.activities?.forEach { list += "activity" to "${it.name}${exp(it.exported, it.permission)}" }
        p.services?.forEach { list += "service " to "${it.name}${exp(it.exported, it.permission)}" }
        p.receivers?.forEach { list += "receiver" to "${it.name}${exp(it.exported, it.permission)}" }
        p.providers?.forEach {
            list += "provider" to "${it.name} authority=${it.authority}${exp(it.exported, it.readPermission)}"
        }
        return list
    }

    private fun exp(exported: Boolean, perm: String?) =
        (if (exported) "  [EXPORTED]" else "") + (if (perm != null) "  perm=$perm" else "")

    private fun dumpPackage(p: PackageInfo) {
        line()
        val sys = if ((p.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0) "system" else "user"
        line("### ${p.packageName}  (\"${label(p.applicationInfo)}\")  v${p.versionName}  $sys")
        p.permissions?.forEach { line("  declares-permission ${it.name}  level=${it.protectionLevel}") }
        p.requestedPermissions?.filter { isLighty(it) || isVendor(it) }?.forEach { line("  uses-permission ${it}") }
        components(p).forEach { (k, v) -> line("  $k $v") }
        p.applicationInfo?.sharedLibraryFiles?.forEach { line("  shared-lib $it") }
    }

    // ---------------------------------------------------------- permissions
    private fun permissions() {
        val seen = sortedSetOf<String>()
        val groups = (pm.getAllPermissionGroups(0).map { it.name } + listOf<String?>(null))
        for (g in groups) {
            try {
                pm.queryPermissionsByGroup(g, 0).forEach {
                    if (isLighty(it.name)) seen += "${it.name}  level=${it.protectionLevel}  pkg=${it.packageName}"
                }
            } catch (_: Throwable) {}
        }
        line("Lighting-related permissions defined on this unit: ${seen.size}")
        line("(level 0 = normal, 1 = dangerous, 2 = signature, 18 = signature|privileged)")
        seen.forEach { line("  $it") }
    }

    // -------------------------------------------------------------- services
    private fun services() {
        val names: List<String> = try {
            val sm = Class.forName("android.os.ServiceManager")
            @Suppress("UNCHECKED_CAST")
            (sm.getMethod("listServices").invoke(null) as Array<String>).toList()
        } catch (t: Throwable) {
            line("ServiceManager.listServices blocked ($t); trying 'service list'")
            try {
                val p = Runtime.getRuntime().exec(arrayOf("service", "list"))
                BufferedReader(InputStreamReader(p.inputStream)).readLines()
            } catch (t2: Throwable) {
                line("'service list' also failed: $t2"); emptyList()
            }
        }
        line("System services: ${names.size}")
        line("-- Flagged --")
        names.filter { isLighty(it) || isVendor(it) }.forEach { line("  $it") }
        line("-- All --")
        names.forEach { line("  $it") }
    }

    // -------------------------------------------------------------- settings
    private fun settings() {
        for (table in listOf("system", "global", "secure")) {
            try {
                ctx.contentResolver.query(
                    Uri.parse("content://settings/$table"), arrayOf("name", "value"), null, null, null
                )?.use { c ->
                    var n = 0
                    while (c.moveToNext()) {
                        val name = c.getString(0)
                        if (isLighty(name) || isVendor(name)) {
                            line("  $table/$name = ${c.getString(1)}"); n++
                        }
                    }
                    line("  ($table: $n matching keys)")
                }
            } catch (t: Throwable) {
                line("  $table: not readable ($t)")
            }
        }
    }

    // ---------------------------------------------------------- class probes
    private fun classProbes() {
        line("Checks which car SDK classes this app can load (no methods are called).")
        val candidates = listOf(
            "android.car.Car",
            "android.car.hardware.property.CarPropertyManager",
            "android.car.VehiclePropertyIds",
            "android.car.hardware.CarPropertyValue",
            "com.ecarx.xui.adaptapi.car.Car",
            "com.ecarx.xui.adaptapi.car.base.ICarFunction",
            "com.ecarx.xui.adaptapi.car.vehicle.IVehicle",
            "com.ecarx.xui.adaptapi.FunctionStatus",
            "ecarx.car.ECarXCar",
            "com.geely.lib.oneosapi.OneOSApiManager",
        )
        for (name in candidates) {
            val ok = try { Class.forName(name); true } catch (_: Throwable) { false }
            line("  ${if (ok) "FOUND  " else "missing"}  $name")
            if (ok && name.contains("ecarx", true)) dumpMethods(name)
        }
    }

    private fun dumpMethods(name: String) {
        try {
            val c = Class.forName(name)
            c.declaredMethods.map { it.toString() }.filter { isLighty(it) }.sorted()
                .forEach { line("      $it") }
            c.declaredFields.map { it.toString() }.filter { isLighty(it) }.sorted()
                .forEach { line("      $it") }
        } catch (_: Throwable) {}
    }
}
