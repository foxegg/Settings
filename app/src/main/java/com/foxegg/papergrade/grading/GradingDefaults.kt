package com.foxegg.papergrade.grading

/**
 * 默认走阿里云百炼（Model Studio）北京地域的 OpenAI 兼容接口。
 * 文档写明 https://dashscope.aliyuncs.com 仍然可用，而且不需要先填业务空间 ID。
 * 末尾不要带 /chat/completions，请求时再拼接。
 */
object GradingDefaults {
    const val API_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val MODEL = "qwen3-vl-plus"
}
