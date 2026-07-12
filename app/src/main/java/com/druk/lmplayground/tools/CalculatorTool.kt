package com.druk.lmplayground.tools

import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Deterministic math evaluator so the model never has to guess arithmetic.
 * Pure Kotlin, offline, no sandbox: a small recursive-descent parser over
 * + - * / % ^, parentheses, unary +/-, a set of functions and constants.
 * Results are formatted to trim floating-point noise (e.g. 0.1 + 0.2 -> 0.3).
 */
class CalculatorTool : Tool {
    override val name = "calculator"
    override val description = "Evaluate a mathematical expression exactly and return the numeric result. Supports + - * / % ^ (power), parentheses, functions (sqrt, cbrt, abs, exp, ln, log, log10, log2, sin, cos, tan, asin, acos, atan, sinh, cosh, tanh, floor, ceil, round, sign, min, max, pow, hypot) and constants (pi, e, tau). Trig functions use radians. Always use this for arithmetic instead of computing it yourself."
    override val parametersSchema = """{"type":"object","properties":{"expression":{"type":"string","description":"The math expression, e.g. \"(1234 * 5678) / 3\", \"sqrt(2)\", \"sin(pi/4)\", \"log(1000)\"."}},"required":["expression"]}"""

    override fun execute(arguments: String): String {
        return try {
            val expr = JSONObject(arguments).getString("expression")
            val value = Parser(expr).parse()
            if (value.isNaN() || value.isInfinite()) {
                return """{"error":"Result is not a finite number (check for division by zero or an invalid operation)."}"""
            }
            JSONObject()
                .put("expression", expr)
                .put("result", format(value))
                .toString()
        } catch (e: Throwable) {
            // Throwable, not Exception: a pathologically nested expression (thousands
            // of parens / unary minuses) overflows the recursive-descent parser's
            // stack with a StackOverflowError, which is an Error, not an Exception.
            // Catching it here returns a clean error instead of crashing the app.
            // execute() has no suspension points, so this can't swallow cancellation.
            // JSONObject escapes quotes/backslashes/control chars in the parser message.
            JSONObject().put("error", e.message ?: "Could not evaluate the expression").toString()
        }
    }

    /** Trim floating-point noise: exact integers stay integers, otherwise round to 12 significant digits. */
    private fun format(v: Double): String {
        if (v == 0.0) return "0"
        if (v == v.toLong().toDouble() && abs(v) < 1e15) return v.toLong().toString()
        return BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    /** Recursive-descent parser producing a Double. Throws IllegalArgumentException on bad input. */
    private class Parser(private val s: String) {
        private var pos = 0

        fun parse(): Double {
            val v = expr()
            skipWs()
            if (pos < s.length) throw IllegalArgumentException("Unexpected '${s[pos]}' at position $pos")
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                skipWs()
                when (peek()) {
                    '+' -> { pos++; v += term() }
                    '-' -> { pos++; v -= term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) {
                skipWs()
                when (peek()) {
                    '*' -> { pos++; v *= unary() }
                    '/' -> { pos++; v /= unary() }
                    '%' -> { pos++; v = v.rem(unary()) }
                    else -> return v
                }
            }
        }

        // Unary +/- binds LOOSER than '^' (standard math convention):
        // -2^2 == -(2^2) == -4, not (-2)^2 == 4.
        private fun unary(): Double {
            skipWs()
            return when (peek()) {
                '-' -> { pos++; -unary() }
                '+' -> { pos++; unary() }
                else -> power()
            }
        }

        private fun power(): Double {
            val base = primary()
            skipWs()
            if (peek() == '^') {
                pos++
                // Right-associative, and the exponent is a unary so 2^-3 works.
                return base.pow(unary())
            }
            return base
        }

        private fun primary(): Double {
            skipWs()
            val c = peek() ?: throw IllegalArgumentException("Unexpected end of expression")
            if (c == '(') {
                pos++
                val v = expr()
                skipWs()
                expect(')')
                return v
            }
            if (c.isDigit() || c == '.') return number()
            if (c.isLetter() || c == '_') return identifier()
            throw IllegalArgumentException("Unexpected '$c' at position $pos")
        }

        private fun number(): Double {
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                while (peek()?.isDigit() == true) pos++
            }
            return s.substring(start, pos).toDouble()
        }

        private fun identifier(): Double {
            val start = pos
            while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) pos++
            val id = s.substring(start, pos).lowercase()
            skipWs()
            if (peek() == '(') {
                pos++
                val args = mutableListOf<Double>()
                skipWs()
                if (peek() != ')') {
                    args.add(expr())
                    skipWs()
                    while (peek() == ',') { pos++; args.add(expr()); skipWs() }
                }
                expect(')')
                return applyFunc(id, args)
            }
            return constant(id)
        }

        private fun constant(id: String): Double = when (id) {
            "pi" -> PI
            "e" -> E
            "tau" -> 2 * PI
            else -> throw IllegalArgumentException("Unknown name '$id'")
        }

        private fun applyFunc(id: String, a: List<Double>): Double {
            fun one(): Double {
                if (a.size != 1) throw IllegalArgumentException("$id expects 1 argument")
                return a[0]
            }
            fun two(): Pair<Double, Double> {
                if (a.size != 2) throw IllegalArgumentException("$id expects 2 arguments")
                return a[0] to a[1]
            }
            return when (id) {
                "sqrt" -> sqrt(one())
                "cbrt" -> cbrt(one())
                "abs" -> abs(one())
                "exp" -> exp(one())
                "ln" -> ln(one())
                "log", "log10" -> log10(one())
                "log2" -> ln(one()) / ln(2.0)
                "sin" -> sin(one())
                "cos" -> cos(one())
                "tan" -> tan(one())
                "asin" -> asin(one())
                "acos" -> acos(one())
                "atan" -> atan(one())
                "sinh" -> sinh(one())
                "cosh" -> cosh(one())
                "tanh" -> tanh(one())
                "floor" -> floor(one())
                "ceil" -> ceil(one())
                "round" -> round(one())
                "sign" -> sign(one())
                "min" -> a.minOrNull() ?: throw IllegalArgumentException("min expects at least one argument")
                "max" -> a.maxOrNull() ?: throw IllegalArgumentException("max expects at least one argument")
                "pow" -> two().let { it.first.pow(it.second) }
                "hypot" -> two().let { hypot(it.first, it.second) }
                else -> throw IllegalArgumentException("Unknown function '$id'")
            }
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null
        private fun skipWs() { while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') pos++ }
        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("Expected '$c' at position $pos")
            pos++
        }
    }
}
