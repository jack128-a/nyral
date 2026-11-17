package all

import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cloudflare 优选 IP 探测器（v8，Detekt 拆分）。
 *
 * CIDR 采样、TCP/TLS 测速、并发筛选、直连诊断。
 * IP 池状态仍由 CloudflareOptDns 单例持有；本文件只读池、跑测速、回写结果。
 */
object CloudflareIpProber {

    /** 每次测速从 CF 公开 CIDR 里随机抽的候选数 */
    private const val SAMPLE_COUNT = 50

    /** 测速后保留数 */
    private const val POOL_KEEP = 5

    /** 单 IP 探测超时：建连 / 读响应 */
    private const val PROBE_CONNECT_MS = 5000
    private const val PROBE_READ_MS = 8000

    /** 测速并发线程数 */
    private const val PROBE_THREADS = 10

    /** IPv4 地址位数 / 每段位数 / 段最大值 / 段内移位 / 字节掩码 */
    private const val IPV4_BIT_LENGTH = 32
    private const val IPV4_OCTET_COUNT = 4
    private const val OCTET_BIT_COUNT = 8
    private const val OCTET_MAX_VALUE = 255
    private const val OCTET_SHIFT_24 = 24
    private const val OCTET_SHIFT_16 = 16
    private const val OCTET_MASK = 0xFF

    /** 采样防死循环：最多尝试次数 = 候选数 × 系数 */
    private const val SAMPLE_GUARD_FACTOR = 10

    /** 探测 Future 额外等待（读超时之外） */
    private const val PROBE_FUTURE_SLACK_MS = 2000L

    /** HTTP 成功状态码 */
    private const val HTTP_OK = 200

    /** Cloudflare 官方公开 IPv4 段（Anycast 边缘 IP 均可服务任意 CF 域名，SNI 决定路由） */
    private val CF_CIDRS = listOf(
        "104.16.0.0/13",
        "172.64.0.0/13",
        "162.158.0.0/15",
        "108.162.192.0/18"
    )

    fun isValidIpv4(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != IPV4_OCTET_COUNT) return false
        return parts.all { p ->
            p.isNotEmpty() && p.all { it.isDigit() } &&
                runCatching { p.toInt() }.getOrNull()?.let { it in 0..OCTET_MAX_VALUE } == true
        }
    }

    // ============ 候选 IP 采样与探测 ============

    /** 从 CF 公开 CIDR 里随机抽候选 IP（去重） */
    fun sampleCandidateIps(count: Int, seed: Long = System.currentTimeMillis()): List<String> {
        val rnd = Random(seed)
        val out = LinkedHashSet<String>()
        var guard = 0
        while (out.size < count && guard < count * SAMPLE_GUARD_FACTOR) {
            guard++
            val cidr = CF_CIDRS[rnd.nextInt(CF_CIDRS.size)]
            out.add(randomIpInCidr(cidr, rnd))
        }
        return out.toList()
    }

    private fun randomIpInCidr(cidr: String, rnd: Random): String {
        val (base, prefix) = cidr.split("/")
        var baseInt = 0L
        for (part in base.split(".")) {
            baseInt = (baseInt shl OCTET_BIT_COUNT) or (part.toLongOrNull() ?: 0L)
        }
        val hostBits = IPV4_BIT_LENGTH - prefix.toInt()
        val mask = (-1L shl hostBits)
        val network = baseInt and mask
        val count = 1L shl hostBits
        val offset = (rnd.nextLong() and Long.MAX_VALUE) % count
        val v = network or offset
        return listOf(
            (v shr OCTET_SHIFT_24) and OCTET_MASK,
            (v shr OCTET_SHIFT_16) and OCTET_MASK,
            (v shr OCTET_BIT_COUNT) and OCTET_MASK,
            v and OCTET_MASK
        ).joinToString(".")
    }

    /**
     * 探测单个候选 IP：直连拨该 IP，GET baseUrl + /models（带 Key），
     * 回 200 即为可用，返回握手+首字节耗时（毫秒）；否则返回 null。
     * 同步调用，调用方必须在后台线程执行。
     */
    fun probeIp(ip: String, baseUrl: String, apiKey: String): Int? {
        val modelsUrl = baseUrl.trimEnd('/') + "/models"
        var conn: HttpURLConnection? = null
        val start = System.currentTimeMillis()
        return try {
            val url = URL(modelsUrl)
            conn = CloudflareSniSocketFactory.openDirect(url, ip)
            conn.requestMethod = "GET"
            conn.connectTimeout = PROBE_CONNECT_MS
            conn.readTimeout = PROBE_READ_MS
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == HTTP_OK) (System.currentTimeMillis() - start).toInt() else null
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * 探测一组指定 IP（导入用），返回能回 200 的并按延迟排序。
     * 同步调用，调用方必须在后台线程执行。
     */
    fun probeIps(ips: List<String>, baseUrl: String, apiKey: String): List<CloudflareOptDns.OptIp> {
        if (ips.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(PROBE_THREADS)
        return try {
            val futures = ips.map { ip ->
                pool.submit<Int?> { probeIp(ip, baseUrl, apiKey) }
            }
            val now = System.currentTimeMillis()
            ips.mapIndexedNotNull { idx, ip ->
                val ms = runCatching {
                    futures[idx].get(PROBE_READ_MS + PROBE_FUTURE_SLACK_MS, TimeUnit.MILLISECONDS)
                }.getOrNull() ?: return@mapIndexedNotNull null
                CloudflareOptDns.OptIp(ip, ms, now)
            }.sortedBy { it.latencyMs }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * 跑一次完整测速：采样 50 个候选 IP 并发探测，取延迟最低且回 200 的前 5 个
     * 写入池子并返回。同步调用，调用方必须在后台线程执行。
     */
    fun runSpeedTest(baseUrl: String, apiKey: String): List<CloudflareOptDns.OptIp> {
        val candidates = sampleCandidateIps(SAMPLE_COUNT)
        val scored = probeIps(candidates, baseUrl, apiKey).take(POOL_KEEP)
        CloudflareOptDns.updateIpPool(scored)
        return scored
    }

    /** 解析粘贴导入的 IP（一行一个），只留合法 IPv4，去重 */
    fun parseImportedIps(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in text.split('\n', ',', ';', ' ', '\t')) {
            val ip = line.trim()
            if (isValidIpv4(ip)) out.add(ip)
        }
        return out.toList()
    }

    /**
     * 直连诊断（同步调用，调用方必须在后台线程执行）：
     * 用同一直接连接分别请求 /cdn-cgi/trace 与 /v1/models。
     * trace 拿不到时不判失败（边缘可能不回该路径），只按 /v1/models 判定直连是否通。
     */
    fun diagnose(baseUrl: String, apiKey: String): CloudflareOptDns.Diagnosis {
        val origin = baseUrl.trimEnd('/').removeSuffix("/v1")
        var traceOk = false
        var traceColo: String? = null
        var traceClientIp: String? = null
        try {
            val traceConn = CloudflareSniSocketFactory.openConnection("$origin/cdn-cgi/trace")
            traceConn.requestMethod = "GET"
            traceConn.connectTimeout = PROBE_CONNECT_MS
            traceConn.readTimeout = PROBE_READ_MS
            if (traceConn.responseCode == HTTP_OK) {
                val body = traceConn.inputStream.bufferedReader().use { it.readText() }
                traceOk = true
                val (colo, clientIp) = parseTraceResponse(body)
                traceColo = colo
                traceClientIp = clientIp
            }
            try { traceConn.disconnect() } catch (_: Exception) {}
        } catch (_: Exception) {
            traceOk = false
        }
        var modelsOk = false
        var modelsMs = 0L
        var conn: HttpURLConnection? = null
        try {
            val start = System.currentTimeMillis()
            conn = CloudflareSniSocketFactory.openConnection(baseUrl.trimEnd('/') + "/models")
            conn.requestMethod = "GET"
            conn.connectTimeout = PROBE_CONNECT_MS
            conn.readTimeout = PROBE_READ_MS
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.setRequestProperty("Accept", "application/json")
            modelsOk = conn.responseCode == HTTP_OK
            modelsMs = System.currentTimeMillis() - start
        } catch (_: Exception) {
            modelsOk = false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
        return CloudflareOptDns.Diagnosis(
            directUsed = CloudflareOptDns.lastDialedIp != null,
            dialedIp = CloudflareOptDns.lastDialedIp,
            traceOk = traceOk,
            traceColo = traceColo,
            traceClientIp = traceClientIp,
            modelsOk = modelsOk,
            modelsMs = modelsMs
        )
    }

    /** 解析 /cdn-cgi/trace 响应体，取 colo= 与 ip= 两行（无则 null） */
    private fun parseTraceResponse(body: String): Pair<String?, String?> {
        var colo: String? = null
        var clientIp: String? = null
        for (line in body.split('\n')) {
            val parts = line.split('=', limit = 2)
            val k = if (parts.size == 2) parts[0] else ""
            val v = if (parts.size == 2) parts[1] else ""
            when (k.trim()) {
                "colo" -> colo = v.trim()
                "ip" -> clientIp = v.trim()
            }
        }
        return colo to clientIp
    }
}
