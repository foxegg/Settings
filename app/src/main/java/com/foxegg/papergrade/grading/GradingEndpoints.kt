package com.foxegg.papergrade.grading

fun chatCompletionsUrl(base: String): String {
    val trimmed = base.trim().trimEnd('/')
    if (trimmed.isEmpty()) return chatCompletionsUrl(GradingDefaults.API_BASE)
    return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
}
