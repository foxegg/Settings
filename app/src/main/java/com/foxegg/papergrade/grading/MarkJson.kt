package com.foxegg.papergrade.grading

import com.foxegg.papergrade.model.Mark

fun canonicalMarkType(raw: String): String? = when (raw.trim().lowercase()) {
    "check", "tick", "correct", "right", "对", "对钩", "勾" -> "check"
    "cross", "wrong", "x", "叉", "错" -> "cross"
    "underline", "line", "下划线" -> "underline"
    "circle", "圈" -> "circle"
    "text", "comment", "note", "旁批", "score", "分数" -> "text"
    else -> null
}

fun extractJsonObject(raw: String): String {
    var text = raw.trim().removePrefix("\uFEFF")
    text = text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    if (text.startsWith("```")) {
        val newline = text.indexOf('\n')
        if (newline >= 0) text = text.substring(newline + 1)
        val fence = text.lastIndexOf("```")
        if (fence >= 0) text = text.substring(0, fence)
        text = text.trim()
    }
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end < start) throw MarkParseException("模型返回里没有 JSON")
    return text.substring(start, end + 1)
}

fun parseMarks(raw: String): List<Mark> {
    try {
        return parseMarksUnchecked(raw)
    } catch (error: MarkParseException) {
        throw error
    } catch (_: JsonParseException) {
        throw MarkParseException("模型返回无法解析")
    }
}

private fun parseMarksUnchecked(raw: String): List<Mark> {
    val root = JsonParser(extractJsonObject(raw)).parse() as? JsonValue.Obj
        ?: throw MarkParseException("模型返回不是标记对象")
    val marks = root.map["marks"] as? JsonValue.Arr
        ?: throw MarkParseException("模型返回缺少 marks")
    val parsed = ArrayList<Mark>()
    for (item in marks.list) {
        val obj = item as? JsonValue.Obj ?: continue
        val type = (obj.map["type"] as? JsonValue.Str)?.value?.let(::canonicalMarkType) ?: continue
        val x = jsonFloat(obj.map["x"]) ?: continue
        val y = jsonFloat(obj.map["y"]) ?: continue
        val text = (obj.map["text"] as? JsonValue.Str)?.value?.trim()?.ifEmpty { null }
        if (type == "text" && text == null) continue
        parsed += Mark(
            type = type,
            x = x,
            y = y,
            x2 = jsonFloat(obj.map["x2"]),
            y2 = jsonFloat(obj.map["y2"]),
            text = text,
            radius = jsonFloat(obj.map["r"]) ?: jsonFloat(obj.map["radius"]),
        )
    }
    return parsed
}

private fun jsonFloat(value: JsonValue?): Float? = when (value) {
    is JsonValue.Num -> value.value.toFloat().takeIf { it.isFinite() }
    is JsonValue.Str -> value.value.toFloatOrNull()?.takeIf { it.isFinite() }
    else -> null
}
