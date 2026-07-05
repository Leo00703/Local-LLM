package com.druk.lmplayground.tools

import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

/**
 * Offline, deterministic unit conversion. Each unit maps to a category base
 * unit via a multiplicative factor; temperature is special-cased (affine, not
 * a pure factor). No network, no permission, not sensitive.
 */
class UnitConverterTool : Tool {
    override val name = "convert_units"
    override val description = "Convert a value between units of the same kind. Length (m, km, cm, mm, mi, yd, ft, in, nmi), mass (kg, g, mg, t, lb, oz, st), temperature (c, f, k), volume (l, ml, m3, gal, qt, pt, cup, floz), area (m2, km2, ha, acre, ft2, in2), speed (mps, kph, mph, knot), data (b, kb, mb, gb, tb, kib, mib, gib), time (s, min, h, day, week). Use the short codes shown."
    override val parametersSchema = """{"type":"object","properties":{"value":{"type":"number","description":"The numeric value to convert"},"from":{"type":"string","description":"Source unit code, e.g. \"mi\""},"to":{"type":"string","description":"Target unit code, e.g. \"km\""}},"required":["value","from","to"]}"""

    override fun execute(arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val value = args.getDouble("value")
            val from = args.getString("from").trim().lowercase()
            val to = args.getString("to").trim().lowercase()

            // Temperature first (affine conversion, not a shared linear base).
            convertTemp(value, from, to)?.let { return resultJson(value, from, to, it) }

            val f = UNITS[from] ?: return errorJson("Unknown unit '$from'")
            val t = UNITS[to] ?: return errorJson("Unknown unit '$to'")
            if (f.category != t.category) {
                return errorJson("Cannot convert '$from' (${f.category}) to '$to' (${t.category})")
            }
            resultJson(value, from, to, value * f.factor / t.factor)
        } catch (e: Exception) {
            errorJson(e.message ?: "Conversion failed")
        }
    }

    private fun convertTemp(v: Double, from: String, to: String): Double? {
        val c = when (from) {
            "c", "celsius" -> v
            "f", "fahrenheit" -> (v - 32) * 5.0 / 9.0
            "k", "kelvin" -> v - 273.15
            else -> return null
        }
        return when (to) {
            "c", "celsius" -> c
            "f", "fahrenheit" -> c * 9.0 / 5.0 + 32
            "k", "kelvin" -> c + 273.15
            else -> null
        }
    }

    private fun resultJson(value: Double, from: String, to: String, result: Double): String =
        JSONObject()
            .put("value", value)
            .put("from", from)
            .put("to", to)
            .put("result", format(result))
            .toString()

    private fun format(v: Double): String {
        if (v == 0.0) return "0"
        if (v == v.toLong().toDouble() && abs(v) < 1e15) return v.toLong().toString()
        return BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private fun errorJson(m: String) = """{"error":"${m.replace("\"", "'")}"}"""

    private data class U(val category: String, val factor: Double)

    companion object {
        // factor = how many base units one of this unit equals.
        private val UNITS: Map<String, U> = buildMap {
            // length (base: meter)
            put("m", U("length", 1.0)); put("km", U("length", 1000.0)); put("cm", U("length", 0.01))
            put("mm", U("length", 0.001)); put("mi", U("length", 1609.344)); put("yd", U("length", 0.9144))
            put("ft", U("length", 0.3048)); put("in", U("length", 0.0254)); put("nmi", U("length", 1852.0))
            // mass (base: kilogram)
            put("kg", U("mass", 1.0)); put("g", U("mass", 0.001)); put("mg", U("mass", 1e-6))
            put("t", U("mass", 1000.0)); put("lb", U("mass", 0.45359237)); put("oz", U("mass", 0.028349523125))
            put("st", U("mass", 6.35029318))
            // volume (base: liter)
            put("l", U("volume", 1.0)); put("ml", U("volume", 0.001)); put("m3", U("volume", 1000.0))
            put("gal", U("volume", 3.785411784)); put("qt", U("volume", 0.946352946)); put("pt", U("volume", 0.473176473))
            put("cup", U("volume", 0.2365882365)); put("floz", U("volume", 0.0295735295625))
            // area (base: square meter)
            put("m2", U("area", 1.0)); put("km2", U("area", 1e6)); put("ha", U("area", 10000.0))
            put("acre", U("area", 4046.8564224)); put("ft2", U("area", 0.09290304)); put("in2", U("area", 0.00064516))
            // speed (base: meter/second)
            put("mps", U("speed", 1.0)); put("kph", U("speed", 1000.0 / 3600.0))
            put("mph", U("speed", 1609.344 / 3600.0)); put("knot", U("speed", 1852.0 / 3600.0))
            // data (base: byte)
            put("b", U("data", 1.0)); put("kb", U("data", 1000.0)); put("mb", U("data", 1e6))
            put("gb", U("data", 1e9)); put("tb", U("data", 1e12))
            put("kib", U("data", 1024.0)); put("mib", U("data", 1024.0 * 1024)); put("gib", U("data", 1024.0 * 1024 * 1024))
            // time (base: second)
            put("s", U("time", 1.0)); put("min", U("time", 60.0)); put("h", U("time", 3600.0))
            put("day", U("time", 86400.0)); put("week", U("time", 604800.0))
        }
    }
}
