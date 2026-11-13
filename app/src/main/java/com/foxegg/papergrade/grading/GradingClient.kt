package com.foxegg.papergrade.grading

import android.util.Base64
import android.util.Log
import com.foxegg.papergrade.model.Mark
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 只在用户点「批改」时由界面调用。跟踪线程不会走到这里。
 * 请求体是 OpenAI chat completions：一张 JPEG data URL，加上用户写的评分标准。
 */
class GradingClient {
    fun grade(
        jpeg: ByteArray,
        rubric: String,
        apiKey: String,
        baseUrl: String,
        model: String,
    ): List<Mark> {
        val token = apiKey.trim().removePrefix("Bearer ").trim()
        if (token.isEmpty()) throw GradingException("没有接口密钥")
        val url = chatCompletionsUrl(baseUrl.ifBlank { GradingDefaults.API_BASE })
        val modelName = model.ifBlank { GradingDefaults.MODEL }
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val body = buildString {
            append("{\"model\":")
            append(jsonString(modelName))
            append(",\"messages\":[{\"role\":\"user\",\"content\":[")
            append("{\"type\":\"text\",\"text\":")
            append(jsonString(buildGradingPrompt(rubric)))
            append("},{\"type\":\"image_url\",\"image_url\":{\"url\":")
            append(jsonString(dataUrl))
            append("}}]}]}")
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val response = readBody(connection, code)
            if (code !in 200..299) {
                val detail = redactSecret(response.replace("\\s+".toRegex(), " ").take(180), token)
                Log.w(TAG, "grading HTTP $code $detail")
                val prefix = if (code == 401) "接口拒绝了密钥（HTTP 401）" else "接口返回 HTTP $code"
                throw GradingException(if (detail.isBlank()) prefix else "$prefix：$detail")
            }
            return marksFromChatCompletion(response)
        } catch (error: GradingException) {
            throw error
        } catch (error: MarkParseException) {
            throw error
        } catch (_: SocketTimeoutException) {
            throw GradingException("批改请求超时")
        } catch (_: UnknownHostException) {
            throw GradingException("无法解析接口地址")
        } catch (_: IOException) {
            throw GradingException("无法连接批改接口")
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private companion object {
        const val TAG = "GradingClient"
    }
}
