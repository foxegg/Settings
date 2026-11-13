package com.foxegg.papergrade.grading

import com.foxegg.papergrade.model.Mark

fun marksFromChatCompletion(body: String): List<Mark> {
    val root = try {
        JsonParser(body).parse() as? JsonValue.Obj
    } catch (_: JsonParseException) {
        null
    } ?: throw MarkParseException("接口返回无法解析")
    val error = root.map["error"] as? JsonValue.Obj
    if (error != null) {
        val message = (error.map["message"] as? JsonValue.Str)?.value ?: "接口返回错误"
        throw GradingException(message)
    }
    val content = messageContent(root) ?: throw MarkParseException("模型没有返回文本")
    return parseMarks(content)
}

private fun messageContent(root: JsonValue.Obj): String? {
    val choice = (root.map["choices"] as? JsonValue.Arr)?.list?.firstOrNull() as? JsonValue.Obj
        ?: return null
    val message = choice.map["message"] as? JsonValue.Obj ?: return null
    val content = textOf(message.map["content"])
    if (!content.isNullOrBlank()) return content
    return textOf(message.map["reasoning_content"])
}

private fun textOf(value: JsonValue?): String? = when (value) {
    is JsonValue.Str -> value.value
    is JsonValue.Arr -> value.list.mapNotNull { part ->
        when (part) {
            is JsonValue.Str -> part.value
            is JsonValue.Obj -> (part.map["text"] as? JsonValue.Str)?.value
            else -> null
        }
    }.joinToString("\n").ifBlank { null }
    else -> null
}
