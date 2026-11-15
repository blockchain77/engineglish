package com.yeopjari.app.english.core

/*
 * 작은 JSON 읽기/쓰기 — org.json 은 JVM 단위 테스트에서 동작하지 않으므로(안드로이드 스텁) 순수 Kotlin 으로 둔다.
 * 콘텐츠 로더(content)와 학습자 상태 저장(learn)이 함께 쓴다.
 *
 * 읽기 결과 타입: 객체 → Map<String, Any?> (LinkedHashMap, 키 순서 유지), 배열 → List<Any?>,
 * 문자열 → String, 정수 → Long, 실수 → Double, true/false → Boolean, null → null.
 * 너그러운 점: 끝의 쉼표(`[1,2,]`, `{"a":1,}`)와 BOM 을 허용한다.
 */

class JsonException(message: String, val position: Int) : IllegalArgumentException("$message (at $position)")

object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Trailing characters", p.pos)
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("Top-level value is not an object", 0)

    /** Map(키는 toString) · Iterable · Array · String · Number · Boolean · Enum(name) · null 을 쓴다. 그 밖은 toString() 문자열 */
    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    fun toBytes(value: Any?): ByteArray = stringify(value).toByteArray(Charsets.UTF_8)

    fun parseBytes(bytes: ByteArray): Any? = parse(String(bytes, Charsets.UTF_8))

    // ───────────── 쓰기 ─────────────

    private fun write(sb: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> sb.append(number(v))
            is Float -> sb.append(number(v.toDouble()))
            is Number -> sb.append(number(v.toDouble()))
            is Enum<*> -> quote(sb, v.name)
            is Char -> quote(sb, v.toString())
            is Map<*, *> -> writeObject(sb, v, pretty, depth)
            is Iterable<*> -> writeArray(sb, v.toList(), pretty, depth)
            is Array<*> -> writeArray(sb, v.toList(), pretty, depth)
            is IntArray -> writeArray(sb, v.toList(), pretty, depth)
            is LongArray -> writeArray(sb, v.toList(), pretty, depth)
            is DoubleArray -> writeArray(sb, v.toList(), pretty, depth)
            is FloatArray -> writeArray(sb, v.toList(), pretty, depth)
            is BooleanArray -> writeArray(sb, v.toList(), pretty, depth)
            else -> quote(sb, v.toString())
        }
    }

    private fun number(d: Double): String = if (d.isNaN() || d.isInfinite()) "null" else d.toString()

    private fun writeObject(sb: StringBuilder, m: Map<*, *>, pretty: Boolean, depth: Int) {
        if (m.isEmpty()) { sb.append("{}"); return }
        sb.append('{')
        var first = true
        for ((k, value) in m) {
            if (!first) sb.append(',')
            first = false
            if (pretty) newline(sb, depth + 1)
            quote(sb, if (k is Enum<*>) k.name else k.toString())
            sb.append(if (pretty) ": " else ":")
            write(sb, value, pretty, depth + 1)
        }
        if (pretty) newline(sb, depth)
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, list: List<*>, pretty: Boolean, depth: Int) {
        if (list.isEmpty()) { sb.append("[]"); return }
        sb.append('[')
        list.forEachIndexed { i, value ->
            if (i > 0) sb.append(',')
            if (pretty) newline(sb, depth + 1)
            write(sb, value, pretty, depth + 1)
        }
        if (pretty) newline(sb, depth)
        sb.append(']')
    }

    private fun newline(sb: StringBuilder, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    // ───────────── 읽기 ─────────────

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') pos++ else break
            }
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw JsonException("Unexpected end of input", pos)
            val c = s[pos]
            return when {
                c == '{' -> readObject()
                c == '[' -> readArray()
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> readNumber()
                else -> throw JsonException("Unexpected character '$c'", pos)
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Expected '$word'", pos)
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                skipWs()
                when (peek()) {
                    '}' -> { pos++; return map }
                    '"' -> {
                        val key = readString()
                        skipWs()
                        if (peek() != ':') throw JsonException("Expected ':' after key \"$key\"", pos)
                        pos++
                        map[key] = readValue()
                        skipWs()
                        when (peek()) {
                            ',' -> pos++
                            '}' -> { pos++; return map }
                            else -> throw JsonException("Expected ',' or '}'", pos)
                        }
                    }
                    else -> throw JsonException("Expected '\"' or '}'", pos)
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            while (true) {
                skipWs()
                if (peek() == ']') { pos++; return list }
                list.add(readValue())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw JsonException("Expected ',' or ']'", pos)
                }
            }
        }

        private fun readString(): String {
            val start = pos
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonException("Unterminated string", start)
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw JsonException("Bad escape", pos)
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad unicode escape", pos)
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw JsonException("Bad unicode escape \\u$hex", pos)
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e'", pos - 1)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length) {
                val c = s[pos]
                if (c in '0'..'9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++ else break
            }
            val t = s.substring(start, pos)
            val isInt = t.none { it == '.' || it == 'e' || it == 'E' }
            if (isInt) t.toLongOrNull()?.let { return it }
            return t.toDoubleOrNull() ?: throw JsonException("Bad number '$t'", start)
        }
    }
}

// ───────────── 읽은 Map 에서 값 꺼내기 (없거나 타입이 다르면 null) ─────────────

fun Map<String, Any?>.jStr(key: String): String? = this[key] as? String
fun Map<String, Any?>.jInt(key: String): Int? = (this[key] as? Number)?.toInt()
fun Map<String, Any?>.jLong(key: String): Long? = (this[key] as? Number)?.toLong()
fun Map<String, Any?>.jDouble(key: String): Double? = (this[key] as? Number)?.toDouble()
fun Map<String, Any?>.jFloat(key: String): Float? = (this[key] as? Number)?.toFloat()
fun Map<String, Any?>.jBool(key: String): Boolean? = this[key] as? Boolean

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.jObj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.jArr(key: String): List<Any?>? = this[key] as? List<Any?>

fun Map<String, Any?>.jStrList(key: String): List<String> = jArr(key)?.mapNotNull { it as? String } ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.jObjList(key: String): List<Map<String, Any?>> = jArr(key)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

/** 문자열 → enum (이름 정확히 일치, 없으면 null) */
inline fun <reified E : Enum<E>> Map<String, Any?>.jEnum(key: String): E? {
    val s = jStr(key) ?: return null
    return enumValues<E>().firstOrNull { it.name == s }
}
