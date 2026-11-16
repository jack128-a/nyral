package io.github.aixtin.nyral

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tavily 搜索独立模块（v3 从 WebTools.kt 抽出，消 LargeClass）。
 * 行为与原 WebTools 内联实现一致：POST api.tavily.com/search（search_depth=advanced），
 * 按分数过滤（score >= 0.45），只要 title/url/content 齐全的条目；
 * 前 2 条取大预算，其余取小预算；最多 4 条。
 * 无 Key / 网络异常 / 空结果时返回 null，调用方回退 HTML 链路。
 */
object TavilySearch {

    private const val TAVILY_ENDPOINT = "https://api.tavily.com/search"
    private const val TAVILY_CONNECT_TIMEOUT_MS = 6000
    private const val TAVILY_READ_TIMEOUT_MS = 10000
    private const val TAVILY_MAX_RESULTS = 5
    private const val TAVILY_MIN_SCORE = 0.45
    private const val TAVILY_TOP_BUDGET = 1200
    private const val TAVILY_SUB_BUDGET = 450
    private const val TAVILY_TOP_COUNT = 2
    private const val TAVILY_MAX_VALID = 4
    private const val HTTP_OK_START = 200
    private const val HTTP_OK_END = 299

    /**
     * Tavily 搜索：成功返回与 HTML 链路同格式的结果串（带“搜索结果来源:【Tavily】”标记）；
     * 无 Key / 网络异常 / 空结果时返回 null，调用方回退原有链路。
     */
    fun search(query: String, apiKey: String, maxResults: Int = TAVILY_MAX_RESULTS): String? {
        if (apiKey.isBlank()) return null
        val raw = openTavilyConnection(apiKey, query, maxResults)
        return formatResults(raw, maxResults)
    }

    /** 发 Tavily POST 请求，返回原始 JSON 串；网络异常 / 非 2xx 时返回 null */
    private fun openTavilyConnection(apiKey: String, query: String, maxResults: Int): String? {
        var conn: HttpURLConnection? = null
        var result: String? = null
        try {
            conn = URL(TAVILY_ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = TAVILY_CONNECT_TIMEOUT_MS
            conn.readTimeout = TAVILY_READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val payload = JSONObject()
                .put("api_key", apiKey)
                .put("query", query)
                .put("search_depth", "advanced")
                .put("max_results", maxResults)
                .put("include_answer", false)
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in HTTP_OK_START..HTTP_OK_END) {
                result = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (_: IOException) {
            result = null
        } catch (_: JSONException) {
            result = null
        } finally {
            runCatching { conn?.disconnect() }
        }
        return result
    }

    /**
     * 解析 + 格式化 Tavily results：按分数过滤，只要 title/url/content 齐全的条目；
     * 最多取 limit 条。
     */
    private fun formatResults(raw: String?, maxResults: Int): String? {
        val arr = try {
            raw?.let { JSONObject(it).optJSONArray("results") }
        } catch (_: JSONException) {
            null
        } ?: return null
        val limit = minOf(maxResults, TAVILY_MAX_VALID)
        val sb = StringBuilder("搜索结果来源:【Tavily】")
        var validCount = 0
        for (i in 0 until arr.length()) {
            if (validCount >= limit) break
            val formatted = arr.optJSONObject(i)?.let { formatSingleItem(it, validCount + 1) } ?: continue
            validCount++
            sb.append(formatted)
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /** 单条 Tavily 结果：分数/链接/内容缺一不可；摘要超预算时截断并加省略号 */
    private fun formatSingleItem(item: JSONObject, index: Int): String? {
        val score = item.optDouble("score", 1.0)
        val link = item.optString("url", "").trim()
        val content = item.optString("content", "").trim()
        if (score < TAVILY_MIN_SCORE || link.isEmpty() || content.isEmpty()) return null
        val title = item.optString("title", "").trim()
        val budget = if (index <= TAVILY_TOP_COUNT) TAVILY_TOP_BUDGET else TAVILY_SUB_BUDGET
        val snippet = if (content.length > budget) content.take(budget) + "…" else content
        return "\n$index. $title\n   $link\n   ${if (snippet.isEmpty()) "(无摘要)" else snippet}"
    }
}
