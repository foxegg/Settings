package com.foxegg.papergrade.grading

fun redactSecret(text: String, secret: String): String {
    if (secret.isBlank()) return text
    return text.replace(secret, "****")
}
