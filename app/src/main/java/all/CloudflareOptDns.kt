package io.github.aixtin.nyral

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Cloudflare 优选 IP 直连（v8，免费直连路线）。
 *
 * 适配说明（与 Hydrogen 原方案的出入）：
 * 本工程没有 OkHttp（全站 HttpURLConnection），所以不用 OkHttp 自定义 Dns，
 * 改用等价的 HttpURLConnection 实现：直连时拨指定优选 IP，TLS 握手 SNI 与
 * HTTP Host 头仍用原域名，Cloudflare 边缘据此路由到 Named Tunnel，零证书报错。
 * 其余语义与原方案一致：5–8 个 IP 池（内存 + SharedPreferences 持久化）、
 * 启动/切网时测速排序、请求失败惩罚降权并顺延切换、IP 几天到几周失效后重测。
 *
 * 失败惩罚：失败计数只放内存（App 重启清零），持久化只存 IP + 延迟 + 测速时间，
 * 避免一次弱网把池子永久毒化。全部 IP 都连续失败时 bestIp() 返回 null，
 * openConnection() 自动回落到系统 DNS，不会把路走死。
 */
object CloudflareOptDns {

    private const val TAG = "CloudflareOptDns"
    private const val PREFS_NAME = "dafeiyu_opt_dns"
    private const val KEY_POOL = "ip_pool"
    private const val KEY_ENABLED = "direct_enabled"

    /** 池子容量与测速后保留数 */
    private const val POOL_MAX = 8
    private const val POOL_KEEP = 5

    /** 每次测速从 CF 公开 CIDR 里随机抽的候选数 */
    private const val SAMPLE_COUNT = 50

    /** 连续失败几次后该 IP 暂不再当首选（内存计数，重启清零） */
    private const val FAIL_LIMIT = 3
    private const val FAIL_CAP = 10

    /** 单 IP 探测超时：建连 / 读响应 */
    private const val PROBE_CONNECT_MS = 5000
    private const val PROBE_READ_MS = 8000

    /** 测速并发线程数 */
    private const val PROBE_THREADS = 10

    /** 直连建连超时（SNI 工厂里裸 socket 的 connect 超时） */
    private const val DIRECT_CONNECT_MS = 10000

    /** Cloudflare 官方公开 IPv4 段（Anycast 边缘 IP 均可服务任意 CF 域名，SNI 决定路由） */
    private val CF_CIDRS = listOf(
        "104.16.0.0/13",
        "172.64.0.0/13",
        "162.158.0.0/15",
        "108.162.192.0/18"
    )

    /** 持久化条目：IP + 测速延迟 + 测速时间 */
    data class OptIp(val ip: String, val latencyMs: Int, val lastTestedAt: Long)

    /** 直连诊断结果（设置页展示用） */
    data class Diagnosis(
        val directUsed: Boolean,
        val dialedIp: String?,
        val traceOk: Boolean,
        val traceColo: String?,
        val traceClientIp: String?,
        val modelsOk: Boolean,
        val modelsMs: Long
    )

    @Volatile private var appContext: Context? = null
    private val poolLock = Any()
    @Volatile private var poolCache: List<OptIp> = emptyList()
    private val failures = ConcurrentHashMap<String, Int>()
    @Volatile private var lastDialedIp: String? = null

    /** 供 LocalEngine / 设置页在有 Context 时调用一次（幂等），之后可无 Context 使用 */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            loadPool()
        }
    }

    private fun prefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 直连总开关（默认开）；池子为空时即使开着也会自动回落系统 DNS */
    fun isDirectEnabled(): Boolean = prefs()?.getBoolean(KEY_ENABLED, true) ?: true

    fun setDirectEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    /** 当前池子快照（UI 副标题用） */
    fun poolSnapshot(): List<OptIp> = synchronized(poolLock) { poolCache.toList() }

    /** 最佳 IP：失败数最少、延迟最低；全部 IP 都连续失败则返回 null（回落系统 DNS） */
    fun bestIp(): String? {
        val pool = synchronized(poolLock) { poolCache }
        if (pool.isEmpty()) return null
        val usable = pool.filter { (failures[it.ip] ?: 0) < FAIL_LIMIT }
        val pickFrom = if (usable.isNotEmpty()) usable else return null
        return pickFrom.minWithOrNull(
            compareBy<OptIp> { failures[it.ip] ?: 0 }.thenBy { it.latencyMs }
        )?.ip
    }

    /** 本次请求成功：清掉该 IP 的失败计数（直连恢复后立刻重新当首选） */
    fun reportSuccess() {
        lastDialedIp?.let { failures.remove(it) }
    }

    /** 本次请求失败：该 IP 失败计数 +1（到上限后暂不再当首选，顺延到下一个） */
    fun reportFailure() {
        lastDialedIp?.let { ip ->
            failures.compute(ip) { _, v -> ((v ?: 0) + 1).coerceAtMost(FAIL_CAP) }
        }
    }

    /**
     * 打开连接：直连开着且池子有可用 IP 时拨优选 IP（SNI/Host 仍用原域名），
     * 否则走系统 DNS。连接是惰性的（真正建连在写请求体/读响应码时），
     * 失败由调用方按 reportSuccess/reportFailure 回报。
     */
    fun openConnection(urlStr: String): HttpURLConnection {
        val url = URL(urlStr)
        lastDialedIp = null
        val ip = if (isDirectEnabled()) bestIp() else null
        if (ip == null) return url.openConnection() as HttpURLConnection
        if (url.protocol != "https") return url.openConnection() as HttpURLConnection
        return try {
            lastDialedIp = ip
            openDirect(url, ip)
        } catch (e: Exception) {
            Log.w(TAG, "直连建连失败($ip)，回落系统 DNS: ${e.message}")
            lastDialedIp = null
            url.openConnection() as HttpURLConnection
        }
    }

    /** 直连打开 HTTPS：拨 IP，SNI 与 Host 头仍用原域名，证书按原域名校验 */
    private fun openDirect(url: URL, ip: String): HttpsURLConnection {
        val directUrl = URL(url.protocol, ip, url.port, url.file)
        val conn = directUrl.openConnection() as HttpsURLConnection
        val hostHeader = if (url.port != -1 && url.port != url.defaultPort) {
            "${url.host}:${url.port}"
        } else {
            url.host
        }
        conn.setRequestProperty("Host", hostHeader)
        conn.sslSocketFactory = SniSocketFactory(
            SSLSocketFactory.getDefault() as SSLSocketFactory, url.host
        )
        conn.hostnameVerifier = HostnameVerifier { _, session ->
            HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, session)
        }
        return conn
    }

    /**
     * SNI 套接字工厂：裸 socket 拨 IP，TLS 层用原域名做 SNI。
     * 具名类（不用匿名内部类，d8 安全）；回调一律走本类的具名重写。
     */
    private class SniSocketFactory(
        private val delegate: SSLSocketFactory,
        private val sniHost: String
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(): Socket = delegate.createSocket()

        override fun createSocket(host: String, port: Int): Socket =
            wrapNew(host, port)

        override fun createSocket(
            host: String, port: Int, localHost: InetAddress, localPort: Int
        ): Socket = wrapNew(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket =
            wrapNew(host.hostAddress ?: host.toString(), port)

        override fun createSocket(
            address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int
        ): Socket = wrapNew(
            address.hostAddress ?: address.toString(), port, localAddress, localPort
        )

        override fun createSocket(
            s: Socket, host: String, port: Int, autoClose: Boolean
        ): Socket {
            val ssl = delegate.createSocket(s, sniHost, port, autoClose) as SSLSocket
            return setSni(ssl)
        }

        /** 裸 socket 先拨 IP，再套 TLS（SNI 用原域名） */
        private fun wrapNew(
            host: String, port: Int,
            localHost: InetAddress? = null, localPort: Int = 0
        ): Socket {
            val plain = Socket()
            try {
                if (localHost != null) plain.bind(InetSocketAddress(localHost, localPort))
                plain.connect(InetSocketAddress(host, port), DIRECT_CONNECT_MS)
            } catch (e: Exception) {
                try { plain.close() } catch (_: Exception) {}
                throw e
            }
            val ssl = delegate.createSocket(plain, sniHost, port, true) as SSLSocket
            return setSni(ssl)
        }

        /** 兜底再设一次 SNI（delegate 已用 sniHost 建连时此步是多余的、无害的） */
        private fun setSni(ssl: SSLSocket): SSLSocket {
            try {
                val params: SSLParameters = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                ssl.sslParameters = params
            } catch (_: Exception) {
                // 忽略：delegate 已用 sniHost 建连
            }
            return ssl
        }
    }

    // ============ IP 池持久化 ============

    private fun loadPool() {
        val raw = prefs()?.getString(KEY_POOL, null) ?: return
        val list = runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val ip = o.optString("ip")
                    if (!isValidIpv4(ip)) continue
                    add(
                        OptIp(
                            ip = ip,
                            latencyMs = o.optInt("latencyMs", Int.MAX_VALUE),
                            lastTestedAt = o.optLong("lastTestedAt", 0L)
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
        synchronized(poolLock) { poolCache = list.take(POOL_MAX) }
    }

    /** 写入池子（测速/导入后调用）；调用方在后台线程执行 */
    fun updateIpPool(ips: List<OptIp>) {
        val kept = ips.sortedBy { it.latencyMs }.take(POOL_MAX)
        synchronized(poolLock) { poolCache = kept }
        val arr = JSONArray()
        for (entry in kept) {
            arr.put(
                JSONObject()
                    .put("ip", entry.ip)
                    .put("latencyMs", entry.latencyMs)
                    .put("lastTestedAt", entry.lastTestedAt)
            )
        }
        prefs()?.edit()?.putString(KEY_POOL, arr.toString())?.apply()
        Log.i(TAG, "优选 IP 池已更新 ${kept.size} 个: ${kept.joinToString { it.ip }}")
    }

    fun isValidIpv4(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.all { it.isDigit() } &&
                runCatching { p.toInt() }.getOrNull()?.let { it in 0..255 } == true
        }
    }

    // ============ 候选 IP 采样与探测 ============

    /** 从 CF 公开 CIDR 里随机抽候选 IP（去重） */
    fun sampleCandidateIps(count: Int, seed: Long = System.currentTimeMillis()): List<String> {
        val rnd = Random(seed)
        val out = LinkedHashSet<String>()
        var guard = 0
        while (out.size < count && guard < count * 10) {
            guard++
            val cidr = CF_CIDRS[rnd.nextInt(CF_CIDRS.size)]
            out.add(randomIpInCidr(cidr, rnd))
        }
        return out.toList()
    }

    private fun randomIpInCidr(cidr: String, rnd: Random): String {
        val (base, prefix) = cidr.split("/")
        val baseInt = ipToInt(base)
        val hostBits = 32 - prefix.toInt()
        val mask = (-1L shl hostBits)
        val network = baseInt and mask
        val count = 1L shl hostBits
        val offset = (rnd.nextLong() and Long.MAX_VALUE) % count
        return intToIp(network or offset)
    }

    private fun ipToInt(ip: String): Long {
        var v = 0L
        for (part in ip.split(".")) {
            v = (v shl 8) or (part.toLongOrNull() ?: 0L)
        }
        return v
    }

    private fun intToIp(v: Long): String =
        listOf(
            (v shr 24) and 0xFF, (v shr 16) and 0xFF,
            (v shr 8) and 0xFF, v and 0xFF
        ).joinToString(".")

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
            conn = openDirect(url, ip)
            conn.requestMethod = "GET"
            conn.connectTimeout = PROBE_CONNECT_MS
            conn.readTimeout = PROBE_READ_MS
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 200) (System.currentTimeMillis() - start).toInt() else null
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
    fun probeIps(ips: List<String>, baseUrl: String, apiKey: String): List<OptIp> {
        if (ips.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(PROBE_THREADS)
        return try {
            val futures = ips.map { ip ->
                pool.submit<Int?> { probeIp(ip, baseUrl, apiKey) }
            }
            val now = System.currentTimeMillis()
            ips.mapIndexedNotNull { idx, ip ->
                val ms = runCatching {
                    futures[idx].get(PROBE_READ_MS + 2000L, TimeUnit.MILLISECONDS)
                }.getOrNull() ?: return@mapIndexedNotNull null
                OptIp(ip, ms, now)
            }.sortedBy { it.latencyMs }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * 跑一次完整测速：采样 50 个候选 IP 并发探测，取延迟最低且回 200 的前 5 个
     * 写入池子并返回。同步调用，调用方必须在后台线程执行。
     */
    fun runSpeedTest(baseUrl: String, apiKey: String): List<OptIp> {
        val candidates = sampleCandidateIps(SAMPLE_COUNT)
        val scored = probeIps(candidates, baseUrl, apiKey).take(POOL_KEEP)
        updateIpPool(scored)
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
    fun diagnose(baseUrl: String, apiKey: String): Diagnosis {
        val origin = baseUrl.trimEnd('/').removeSuffix("/v1")
        var traceOk = false
        var traceColo: String? = null
        var traceClientIp: String? = null
        try {
            val traceConn = openConnection("$origin/cdn-cgi/trace")
            traceConn.requestMethod = "GET"
            traceConn.connectTimeout = PROBE_CONNECT_MS
            traceConn.readTimeout = PROBE_READ_MS
            if (traceConn.responseCode == 200) {
                val body = traceConn.inputStream.bufferedReader().use { it.readText() }
                traceOk = true
                for (line in body.split('\n')) {
                    val (k, v) = line.split('=', limit = 2).let {
                        if (it.size == 2) it[0] to it[1] else "" to ""
                    }
                    when (k.trim()) {
                        "colo" -> traceColo = v.trim()
                        "ip" -> traceClientIp = v.trim()
                    }
                }
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
            conn = openConnection(baseUrl.trimEnd('/') + "/models")
            conn.requestMethod = "GET"
            conn.connectTimeout = PROBE_CONNECT_MS
            conn.readTimeout = PROBE_READ_MS
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.setRequestProperty("Accept", "application/json")
            modelsOk = conn.responseCode == 200
            modelsMs = System.currentTimeMillis() - start
        } catch (_: Exception) {
            modelsOk = false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
        return Diagnosis(
            directUsed = lastDialedIp != null,
            dialedIp = lastDialedIp,
            traceOk = traceOk,
            traceColo = traceColo,
            traceClientIp = traceClientIp,
            modelsOk = modelsOk,
            modelsMs = modelsMs
        )
    }
}
