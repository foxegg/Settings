package com.foxegg.papergrade.grading

sealed class JsonValue {
    data class Obj(val map: Map<String, JsonValue>) : JsonValue()
    data class Arr(val list: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()
}

class JsonParseException(message: String) : Exception(message)

class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): JsonValue {
        skip()
        val value = readValue()
        skip()
        return value
    }

    private fun skip() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun peek(expected: Char): Boolean = index < text.length && text[index] == expected

    private fun expect(expected: Char) {
        if (index >= text.length || text[index] != expected) {
            fail("expected '$expected'")
        }
        index++
    }

    private fun literal(word: String) {
        if (!text.startsWith(word, index)) fail("expected $word")
        index += word.length
    }

    private fun readValue(): JsonValue {
        skip()
        if (index >= text.length) fail("unexpected end")
        return when (text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't' -> {
                literal("true")
                JsonValue.Bool(true)
            }
            'f' -> {
                literal("false")
                JsonValue.Bool(false)
            }
            'n' -> {
                literal("null")
                JsonValue.Null
            }
            '-', in '0'..'9' -> readNumber()
            else -> fail("unexpected character")
        }
    }

    private fun readObject(): JsonValue.Obj {
        expect('{')
        val map = LinkedHashMap<String, JsonValue>()
        skip()
        if (peek('}')) {
            index++
            return JsonValue.Obj(map)
        }
        while (true) {
            skip()
            if (peek('}')) {
                index++
                break
            }
            val key = readString()
            skip()
            expect(':')
            map[key] = readValue()
            skip()
            if (peek(',')) {
                index++
                continue
            }
            expect('}')
            break
        }
        return JsonValue.Obj(map)
    }

    private fun readArray(): JsonValue.Arr {
        expect('[')
        val list = ArrayList<JsonValue>()
        skip()
        if (peek(']')) {
            index++
            return JsonValue.Arr(list)
        }
        while (true) {
            skip()
            if (peek(']')) {
                index++
                break
            }
            list += readValue()
            skip()
            if (peek(',')) {
                index++
                continue
            }
            expect(']')
            break
        }
        return JsonValue.Arr(list)
    }

    private fun readString(): String {
        expect('"')
        val builder = StringBuilder()
        while (index < text.length) {
            val ch = text[index++]
            when (ch) {
                '"' -> return builder.toString()
                '\\' -> {
                    if (index >= text.length) fail("bad escape")
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> builder.append(escaped)
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) fail("bad unicode")
                            val hex = text.substring(index, index + 4)
                            index += 4
                            val code = hex.toIntOrNull(16) ?: fail("bad unicode")
                            builder.append(code.toChar())
                        }
                        else -> fail("bad escape")
                    }
                }
                else -> builder.append(ch)
            }
        }
        fail("unterminated string")
    }

    private fun readNumber(): JsonValue.Num {
        val start = index
        if (peek('-')) index++
        if (index >= text.length || !text[index].isDigit()) fail("bad number")
        if (text[index] == '0') {
            index++
        } else {
            while (index < text.length && text[index].isDigit()) index++
        }
        if (index < text.length && text[index] == '.') {
            index++
            if (index >= text.length || !text[index].isDigit()) fail("bad number")
            while (index < text.length && text[index].isDigit()) index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            if (index >= text.length || !text[index].isDigit()) fail("bad number")
            while (index < text.length && text[index].isDigit()) index++
        }
        val number = text.substring(start, index).toDoubleOrNull() ?: fail("bad number")
        return JsonValue.Num(number)
    }

    private fun fail(message: String): Nothing = throw JsonParseException(message)
}

fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}
