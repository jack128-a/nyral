package all

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Cloudflare 优选 IP 池状态管理器（v8，Detekt 拆分后瘦身版）。
 *
 * 只管 IP 池状态：持久化、最佳 IP 选择、失败惩罚、上报。
 * CIDR 采样/测速/诊断在 CloudflareIpProber，直连建连在 CloudflareSniSocketFactory。
 * 单例持有全部池状态（poolCache / failures / lastDialedIp），保证故障切换实时生效。
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

    /** 池子容量 */
    private const val POOL_MAX = 8

    /** 连续失败几次后该 IP 暂不再当首选（内存计数，重启清零） */
    private const val FAIL_LIMIT = 3
    private const val FAIL_CAP = 10

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

    /** 直连拨号 IP（建连时写入、诊断与上报时读取；跨文件共享故 internal） */
    @Volatile internal var lastDialedIp: String? = null

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
        val usable = pool.filter { (failures[it.ip] ?: 0) < FAIL_LIMIT }
        return usable.minWithOrNull(
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

    // ============ IP 池持久化 ============

    private fun loadPool() {
        val raw = prefs()?.getString(KEY_POOL, null) ?: return
        val list = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).asSequence()
                .mapNotNull { arr.optJSONObject(it) }
                .filter { o -> CloudflareIpProber.isValidIpv4(o.optString("ip")) }
                .map { o ->
                    OptIp(
                        ip = o.optString("ip"),
                        latencyMs = o.optInt("latencyMs", Int.MAX_VALUE),
                        lastTestedAt = o.optLong("lastTestedAt", 0L)
                    )
                }
                .toList()
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
}
