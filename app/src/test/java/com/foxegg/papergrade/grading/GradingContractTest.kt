package com.foxegg.papergrade.grading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradingContractTest {
    @Test
    fun defaultsToDashScopeQwenVl() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", GradingDefaults.API_BASE)
        assertEquals("qwen3-vl-plus", GradingDefaults.MODEL)
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            chatCompletionsUrl(GradingDefaults.API_BASE),
        )
    }

    @Test
    fun joinsOpenAiCompatibleBases() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            chatCompletionsUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            chatCompletionsUrl("https://generativelanguage.googleapis.com/v1beta/openai"),
        )
        assertEquals(
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
            chatCompletionsUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"),
        )
    }

    @Test
    fun promptCarriesRubricAndDoesNotInventASecret() {
        val prompt = buildGradingPrompt("第一题答案是光合作用")
        assertTrue(prompt.contains("第一题答案是光合作用"))
        assertTrue(prompt.contains("\"type\":\"check\""))
        assertTrue(!prompt.contains("GRADING_API_KEY"))
    }

    @Test
    fun parsesSpecMarksAndFencedChatResponse() {
        val marks = SampleMarks.load()
        assertEquals(3, marks.size)
        assertEquals("check", marks[0].type)
        assertEquals(0.72f, marks[0].x, 0.0001f)
        assertEquals(0.18f, marks[0].y, 0.0001f)
        assertEquals("cross", marks[1].type)
        assertEquals("text", marks[2].type)
        assertEquals("要点不完整", marks[2].text)

        val body = """
            {"choices":[{"message":{"content":"```json\n{\"marks\":[{\"type\":\"underline\",\"x\":0.1,\"y\":0.2,\"x2\":0.4,\"y2\":0.2},{\"type\":\"对\",\"x\":\"0.5\",\"y\":0.6}]}\n```"}}]}
        """.trim()
        val parsed = marksFromChatCompletion(body)
        assertEquals("underline", parsed[0].type)
        assertEquals(0.4f, parsed[0].x2!!, 0.0001f)
        assertEquals("check", parsed[1].type)
        assertEquals(0.5f, parsed[1].x, 0.0001f)
    }

    @Test
    fun stripsThinkingBeforeJson() {
        val raw = "<think>先看第一题</think>\n{\"marks\":[{\"type\":\"circle\",\"x\":0.2,\"y\":0.3,\"r\":0.04}]}"
        val marks = parseMarks(raw)
        assertEquals("circle", marks.single().type)
        assertEquals(0.04f, marks.single().radius!!, 0.0001f)
    }

    @Test
    fun redactsKey() {
        assertEquals("rejected ****", redactSecret("rejected token-value", "token-value"))
    }
}
