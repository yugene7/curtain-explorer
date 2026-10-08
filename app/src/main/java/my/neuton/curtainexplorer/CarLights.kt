package my.neuton.curtainexplorer

import android.content.Context
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Reflection-only wrapper around the ECARX "adaptapi" vehicle SDK that the
 * Zeekr 7X head unit ships with (com.ecarx.xui.adaptapi.*).
 *
 * We have no SDK jar to compile against, so EVERYTHING is done by reflection:
 * we find the Car factory, obtain the IVehicle function module, discover the
 * get/set methods at runtime, and read the numeric IDs of the light functions
 * from the SDK's own constant fields.
 *
 * This class is the ground-truth probe. v1's job is to tell us:
 *   1. can a sideloaded App Lab app even reach the ECARX Car object,
 *   2. what the real get/set method signatures are, and
 *   3. whether a WRITE to a light function is permitted or rejected.
 *
 * Nothing here is called automatically; the UI drives each step so the user
 * stays in control. Reads are harmless; writes are only sent when a button is
 * tapped, and the presets are limited to cosmetic, reversible functions.
 */
class CarLights(private val ctx: Context) {

    companion object {
        const val PKG = "com.ecarx.xui.adaptapi"
        const val CAR = "$PKG.car.Car"
        const val ICARFUNCTION = "$PKG.car.base.ICarFunction"
        const val IVEHICLE = "$PKG.car.vehicle.IVehicle"
        const val FUNCTION_STATUS = "$PKG.FunctionStatus"

        /**
         * Light/lamp/ambient function constants we care about, grouped for the
         * UI. The names must match static int fields on IVehicle (or
         * ICarFunction). Their numeric values are resolved on the device.
         * Anything not present on this unit is simply skipped.
         */
        val EXTERIOR = listOf(
            "SETTING_FUNC_LAMP_DAYTIME_RUNNING_LIGHT",
            "SETTING_FUNC_LAMP_EXTERIOR_LIGHT_CONTROL",
            "SETTING_FUNC_LAMP_EXTERNAL_LI_ZEBRA_DSPLY_CTL",
            "SETTING_FUNC_LAMP_EXTERNAL_LI_LANE_LINE_CTL",
            "SETTING_FUNC_LAMP_TRIPLE_FLASH",
            "SETTING_FUNC_LAMP_HOME_SAFE_LIGHT",
            "SETTING_FUNC_LAMP_APPROACH_LIGHT",
            "SETTING_FUNC_CAR_SKY_LIGHT_STATUS",
            "SETTING_FINC_TAIL_DAYTIEM_LIGHT_BOUND",
            "SETTING_FUNC_NVS_FLASHING_LIGHTS",
            "SETTING_FUNC_WINDOW_CLOSE_SUNCURTAIN",
        )
        val CARPET = listOf(
            "SETTING_FUNC_LAMP_CARPET_ONOFF",
            "SETTING_FUNC_LAMP_CARPET_THEME",
            "SETTING_FUNC_LAMP_CARPET_PERC",
            "SETTING_FUNC_LAMP_CARPET_DOWN_SWT",
            "SETTING_FUNC_LAMP_CARPET_THEME_DOWNLOAD_REQ",
            "SETTING_FUNC_LAMP_AUTOMATIC_COURTESY_LIGHT",
        )
        val AMBIENCE = listOf(
            "SETTING_FUNC_AMBIENCE_LIGHT_EXPERIENCE",
            "SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR",
            "SETTING_FUNC_AMBIENCE_LIGHT_COLOR_SET",
            "SETTING_FUNC_AMBIENCE_LIGHT_BRIGHTNESS_DRIVING",
            "SETTING_FUNC_AMBIENCE_LIGHT_BRIGHTNESS_STATIONARY",
            "SETTING_FUNC_AMBIENCE_LIGHT_INTERACTIVE_EFFECT",
            "SETTING_FUNC_AMBIENCE_LIGHT_TOPZONES",
            "SETTING_FUNC_AMBIENCE_LIGHT_MAINZONES",
            "SETTING_FUNC_AMBIENCE_LIGHT_BOTZONES",
        )
        /** Value constants that are useful to resolve for building commands. */
        val VALUE_CONSTS = listOf(
            "AMBIENCE_LIGHT_EXPERIENCE_CUSTOM", "AMBIENCE_LIGHT_EXPERIENCE_FULL",
            "AMBIENCE_LIGHT_MAINCOLOR_BREATHE_MODE", "AMBIENCE_LIGHT_MAINCOLOR_MUSIC",
            "AMBIENCE_LIGHT_MAINCOLOR_SETCOLOR", "AMBIENCE_LIGHT_MAINCOLOR_THEME",
            "AMBIENCE_LIGHT_MAINCOLOR_SPEED_MODE", "AMBIENCE_LIGHT_MAINCOLOR_WEATHER",
            "AMBIENCE_LIGHT_MAINCOLOR_NONE",
            "LAMP_EXTERIOR_LIGHT_CONTROL_OFF", "LAMP_EXTERIOR_LIGHT_CONTROL_POS_LIGHT",
            "LAMP_EXTERIOR_LIGHT_CONTROL_AUTOMATIC", "LAMP_EXTERIOR_LIGHT_CONTROL_AHBC",
        )
    }

    // Resolved lazily on connect().
    private var carObj: Any? = null
    private var vehicleObj: Any? = null
    private var vehicleCls: Class<*>? = null
    private var getter: Method? = null
    private var setter: Method? = null
    private var availability: Method? = null

    val isConnected get() = vehicleObj != null && getter != null

    /** Resolved function name -> numeric id, filled during connect(). */
    val functionIds = linkedMapOf<String, Int>()
    val valueConsts = linkedMapOf<String, Int>()

    private val sb = StringBuilder()
    private fun log(s: String = "") { sb.append(s).append('\n') }

    // --------------------------------------------------------------- connect
    /** Connects and resolves everything. Returns a human-readable log. */
    fun connect(): String {
        sb.setLength(0)
        log("ECARX adaptapi probe")
        log("pkg: $PKG")
        log()

        val carCls = cls(CAR) ?: run {
            log("FATAL: $CAR not found. This head unit does not expose adaptapi to apps.")
            return sb.toString()
        }
        log("Loaded $CAR")

        carObj = createCar(carCls)
        if (carObj == null) { log("FATAL: could not obtain a Car instance (see attempts above)."); return sb.toString() }
        log("Car instance: ${carObj!!.javaClass.name}")

        // Resolve IVehicle (preferred) or any ICarFunction we can get.
        vehicleObj = resolveModule(carObj!!)
        if (vehicleObj == null) { log("FATAL: could not obtain IVehicle / ICarFunction from Car."); return sb.toString() }
        vehicleCls = vehicleObj!!.javaClass
        log("Vehicle module: ${vehicleCls!!.name}")
        log()

        discoverGetSet(vehicleObj!!)
        log("getter: ${getter?.let { sig(it) } ?: "NOT FOUND"}")
        log("setter: ${setter?.let { sig(it) } ?: "NOT FOUND"}")
        log("availability: ${availability?.let { sig(it) } ?: "n/a"}")
        log()

        resolveConstants()
        log("Resolved ${functionIds.size} light function ids, ${valueConsts.size} value constants.")
        return sb.toString()
    }

    // ------------------------------------------------------------- API dump
    /** Full reflective dump of the SDK surface. This is our ground truth. */
    fun dump(): String {
        val d = StringBuilder()
        fun line(s: String = "") { d.append(s).append('\n') }
        line("==================== ECARX API DUMP ====================")
        line(connect())
        for (name in listOf(CAR, ICARFUNCTION, IVEHICLE, FUNCTION_STATUS)) {
            val c = cls(name)
            if (c == null) { line("\n### $name  -> NOT PRESENT"); continue }
            line("\n### $name")
            line("-- methods --")
            c.methods.sortedBy { it.name }.map { sig(it) }.distinct().forEach { line("  $it") }
            val consts = c.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            if (consts.isNotEmpty()) {
                line("-- int constants (${consts.size}) --")
                consts.sortedBy { it.name }.forEach {
                    val v = try { it.isAccessible = true; it.getInt(null) } catch (_: Throwable) { "?" }
                    line("  ${it.name} = $v")
                }
            }
            if (name == FUNCTION_STATUS) {
                val enums = try { c.enumConstants } catch (_: Throwable) { null }
                enums?.let { line("-- enum values --"); it.forEach { e -> line("  $e") } }
            }
        }
        return d.toString()
    }

    // ----------------------------------------------------------- read / set
    data class Result(val ok: Boolean, val text: String)

    fun read(funcId: Int, zone: Int): Result {
        val g = getter ?: return Result(false, "No getter discovered; cannot read.")
        return try {
            val args = argsFor(g, funcId, zone, null)
            val r = g.invoke(vehicleObj, *args)
            Result(true, "READ func=0x%X zone=%d -> %s".format(funcId, zone, fmt(r)))
        } catch (t: Throwable) {
            Result(false, "READ func=0x%X zone=%d FAILED: %s".format(funcId, zone, root(t)))
        }
    }

    fun set(funcId: Int, zone: Int, value: Int): Result {
        val s = setter ?: return Result(false, "No setter discovered; cannot write.")
        return try {
            val args = argsFor(s, funcId, zone, value)
            val r = s.invoke(vehicleObj, *args)
            val status = fmt(r)
            val ok = status.contains("SUCCESS", true) || status == "0" || status == "true"
            Result(ok, "SET  func=0x%X zone=%d value=%d -> %s".format(funcId, zone, value, status))
        } catch (t: Throwable) {
            Result(false, "SET  func=0x%X zone=%d value=%d FAILED: %s".format(funcId, zone, value, root(t)))
        }
    }

    // -------------------------------------------------------------- helpers
    private fun cls(name: String): Class<*>? = try { Class.forName(name) } catch (_: Throwable) { null }

    /** Try every plausible static factory on Car to get an instance. */
    private fun createCar(carCls: Class<*>): Any? {
        val statics = carCls.methods.filter {
            Modifier.isStatic(it.modifiers) && carCls.isAssignableFrom(it.returnType)
        }.sortedBy { it.parameterTypes.size }
        for (m in statics) {
            try {
                val a = m.parameterTypes.map { suppliedArg(it) }.toTypedArray()
                val inst = m.invoke(null, *a)
                if (inst != null) { log("Car via ${sig(m)}"); return inst }
                log("Car via ${sig(m)} returned null")
            } catch (t: Throwable) { log("Car via ${sig(m)} failed: ${root(t)}") }
        }
        // Fallback: public constructor taking Context.
        return try {
            val con = carCls.getConstructor(Context::class.java)
            con.newInstance(ctx).also { log("Car via constructor(Context)") }
        } catch (_: Throwable) { null }
    }

    /** Obtain an IVehicle (preferred) or ICarFunction instance from the Car. */
    private fun resolveModule(car: Any): Any? {
        val vehCls = cls(IVEHICLE)
        val fnCls = cls(ICARFUNCTION)
        val carCls = car.javaClass
        // Prefer a method returning something assignable to IVehicle.
        val candidates = carCls.methods.filter { m ->
            (vehCls != null && vehCls.isAssignableFrom(m.returnType)) ||
            (fnCls != null && fnCls.isAssignableFrom(m.returnType) && m.returnType != Any::class.java)
        }.sortedBy { if (vehCls != null && vehCls.isAssignableFrom(it.returnType)) 0 else 1 }
        for (m in candidates) {
            val argSets = moduleArgSets(m, fnCls)
            for (a in argSets) {
                try {
                    val r = m.invoke(car, *a)
                    if (r != null) { log("Module via ${sig(m)} ${a.joinToString(prefix="[", postfix="]")}"); return r }
                } catch (t: Throwable) { log("Module via ${sig(m)} failed: ${root(t)}") }
            }
        }
        return null
    }

    /** Argument sets to try for a module accessor (no-arg, or module-id int). */
    private fun moduleArgSets(m: Method, fnCls: Class<*>?): List<Array<Any?>> {
        val pt = m.parameterTypes
        if (pt.isEmpty()) return listOf(emptyArray())
        if (pt.size == 1 && pt[0] == Int::class.javaPrimitiveType) {
            val mods = intConst(fnCls, "CAR_MODULE_LAMP") ?: intConst(fnCls, "CAR_MODULE_AMBIENCE_LIGHT")
            return listOfNotNull(mods?.let { arrayOf<Any?>(it) }, arrayOf<Any?>(0))
        }
        return listOf(pt.map { suppliedArg(it) }.toTypedArray())
    }

    /** Discover getFunctionValue / setFunctionValue style methods on the module. */
    private fun discoverGetSet(veh: Any) {
        val fsCls = cls(FUNCTION_STATUS)
        val ms = veh.javaClass.methods
        fun intArg(c: Class<*>) = c == Int::class.javaPrimitiveType || c == Integer::class.java
        // getter: name hints get/read/query/value, int params (1-2), returns int-ish.
        getter = ms.filter { m ->
            val p = m.parameterTypes
            p.size in 1..2 && p.all { intArg(it) } &&
            (m.returnType == Int::class.javaPrimitiveType || m.returnType == Integer::class.java) &&
            m.name.matches(Regex(".*(get|read|query).*[Vv]alue.*|.*[Ff]unction[Vv]alue.*|get(Int)?Property"))
        }.ifEmpty {
            // looser: any int-returning method with (int) or (int,int) and a get-ish name
            ms.filter { m ->
                val p = m.parameterTypes
                p.size in 1..2 && p.all { intArg(it) } &&
                (m.returnType == Int::class.javaPrimitiveType || m.returnType == Integer::class.java) &&
                m.name.startsWith("get", true)
            }
        }.minByOrNull { it.parameterTypes.size }

        // setter: name hints set/execute/control, int params (2-3), returns FunctionStatus/int/void.
        setter = ms.filter { m ->
            val p = m.parameterTypes
            p.size in 2..3 && p.all { intArg(it) } &&
            (m.returnType == Void.TYPE || m.returnType == Int::class.javaPrimitiveType ||
             m.returnType == Integer::class.java || (fsCls != null && fsCls.isAssignableFrom(m.returnType))) &&
            m.name.matches(Regex(".*(set|execute|control).*[Vv]alue.*|.*[Ss]et[Ff]unction.*|set(Int)?Property|execute.*"))
        }.ifEmpty {
            ms.filter { m ->
                val p = m.parameterTypes
                p.size in 2..3 && p.all { intArg(it) } && m.name.startsWith("set", true)
            }
        }.minByOrNull { it.parameterTypes.size }

        availability = ms.firstOrNull { m ->
            m.name.contains("availab", true) && m.parameterTypes.all { intArg(it) }
        }
    }

    /** Build an argument array matching a discovered get/set method's arity. */
    private fun argsFor(m: Method, funcId: Int, zone: Int, value: Int?): Array<Any?> {
        return when (m.parameterTypes.size) {
            1 -> arrayOf<Any?>(funcId)
            2 -> if (value != null && !isIntReturn(m))
                     arrayOf<Any?>(funcId, value)          // setter(func, value)
                 else arrayOf<Any?>(funcId, value ?: zone) // reader(func, zone) or setter(func,value)
            3 -> arrayOf<Any?>(funcId, zone, value ?: 0)   // (func, zone, value)
            else -> m.parameterTypes.map { suppliedArg(it) }.toTypedArray()
        }
    }
    private fun isIntReturn(m: Method) =
        m.returnType == Int::class.javaPrimitiveType || m.returnType == Integer::class.java

    private fun resolveConstants() {
        val vc = vehicleCls ?: cls(IVEHICLE)
        val fnCls = cls(ICARFUNCTION)
        fun grab(names: List<String>) {
            for (n in names) {
                val v = intConst(vc, n) ?: intConst(fnCls, n)
                if (v != null) functionIds[n] = v
            }
        }
        grab(EXTERIOR); grab(CARPET); grab(AMBIENCE)
        for (n in VALUE_CONSTS) {
            val v = intConst(vc, n) ?: intConst(fnCls, n)
            if (v != null) valueConsts[n] = v
        }
    }

    private fun intConst(c: Class<*>?, name: String): Int? {
        if (c == null) return null
        return try { val f = c.getField(name); f.isAccessible = true; f.getInt(null) }
        catch (_: Throwable) {
            try { val f = c.getDeclaredField(name); f.isAccessible = true; f.getInt(null) }
            catch (_: Throwable) { null }
        }
    }

    private fun suppliedArg(t: Class<*>): Any? = when {
        t == Context::class.java -> ctx
        t == Int::class.javaPrimitiveType -> 0
        t == Boolean::class.javaPrimitiveType -> false
        t == Long::class.javaPrimitiveType -> 0L
        else -> null
    }

    private fun fmt(r: Any?): String = when (r) {
        null -> "null"
        is Int -> "$r (0x%X)".format(r)
        else -> r.toString()
    }

    private fun sig(m: Method): String =
        "${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"

    private fun root(t: Throwable): String {
        var e: Throwable? = t
        while (e?.cause != null && e.cause != e) e = e.cause
        return "${e?.javaClass?.simpleName}: ${e?.message}"
    }
}
