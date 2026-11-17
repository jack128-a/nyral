package io.github.aixtin.nyral

import android.content.Context
import android.util.Base64
import org.json.JSONArray

/**
 * 扩展线路预设（三号、四号及以后）：仅由 DAFEIYU_EXTRA_NODES 的 JSON 数组解析而来，
 * 绝不硬编码。
 */
data class NodePreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val apiKey: String
)

/**
 * 大肥鱼 fork 的集中配置（隔离扩展法）。
 * 品牌常量、默认端点、工具白名单、Qwen3 适配全部收口于此，原版文件只做最小行数的入口挂载，
 * 方便以后合上游（git fetch upstream && git merge upstream/main）。
 *
 * 红线：真实隧道地址与任何真实 API Key 绝不写进源码（公开 fork！）。
 * 端点与 Key 一律走 BuildConfig 注入（CI 由 GitHub Secrets 经环境变量 / local.properties 注入，
 * 本地构建由 local.properties 注入）；本文件只留占位符。
 */
object DafeiyuConfig {

    // ============ 1. 默认供应商 ============

    const val DEFAULT_PROVIDER_ID = "dafeiyu"
    const val DEFAULT_PROVIDER_NAME = "大肥鱼"
    const val DEFAULT_MODEL_ID = "my-qwen-27b-private"

    /** 默认端点：BuildConfig 注入（DAFEIYU_BASE_URL），空时为占位符，绝不写真实地址进源码 */
    val DEFAULT_BASE_URL: String = BuildConfig.DAFEIYU_BASE_URL.ifBlank { "https://YOUR_TUNNEL_URL" }

    // ============ 1b. 二号线供应商（v6：双线路内置预设） ============

    const val NODE2_PROVIDER_ID = "dafeiyu_line2"
    const val NODE2_PROVIDER_NAME = "大肥鱼二号线"
    const val NODE2_MODEL_ID = "my-qwen-27b-line2"

    /** 二号线端点：BuildConfig 注入（DAFEIYU_NODE2_BASE_URL），缺 /v1 自动补；空时不注册该线路 */
    val NODE2_BASE_URL: String = normalizeV1BaseUrl(BuildConfig.DAFEIYU_NODE2_BASE_URL)

    /**
     * 归一化供应商 Base URL：去首尾空白与末尾斜杠；缺 /v1 时自动补。
     * fork 的 chatUrl() 只在 base 后拼 "/chat/completions"，而 llama-server 的 OpenAI 兼容端点
     * 在 /v1/chat/completions，因此 base 必须以 /v1 结尾。空白输入原样返回空串（调用方据此跳过该线路）。
     */
    fun normalizeV1BaseUrl(raw: String): String {
        val base = raw.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.endsWith("/v1")) base else "$base/v1"
    }

    // ============ 1c. 扩展线路池（v7：通用多线路，形态 C） ============
    /**
     * 解析扩展线路池：DAFEIYU_EXTRA_NODES 是单行 JSON 数组（编译期 Base64 注入为
     * DAFEIYU_EXTRA_NODES_B64，防引号转义）。Secret 未配（空串）、解码失败或 JSON 非法时
     * 一律返回空列表，安静跳过，绝不抛错、绝不影响 1、2 号线。
     * 字段缺失或空白的条目直接丢弃。
     */
    fun parseExtraNodes(): List<NodePreset> {
        val encoded = BuildConfig.DAFEIYU_EXTRA_NODES_B64.trim()
        val json = if (encoded.isBlank()) {
            ""
        } else {
            runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull().orEmpty()
        }
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").trim()
                    val name = obj.optString("name").trim()
                    val baseUrl = normalizeV1BaseUrl(obj.optString("baseUrl"))
                    val modelId = obj.optString("modelId").trim()
                    val apiKey = obj.optString("apiKey")
                    if (listOf(id, name, baseUrl, modelId, apiKey).any { it.isBlank() }) continue
                    add(NodePreset(id = id, name = name, baseUrl = baseUrl, modelId = modelId, apiKey = apiKey))
                }
            }
        }.getOrElse { emptyList() }
    }

    // ============ 2. 工具白名单 ============
    /**
     * 仅向模型暴露的 8 个核心业务工具（其余 20 个不下发、不激活、执行侧也查不到）。
     * 命名以 LocalEngine.kt 的 toolRegistry 实测为准（氢气原方案的 web_search / fetch_webpage /
     * read_pdf / read_image / schedule_task / generate_image 是 2026-11-11 合并前的旧名，现已不存在）：
     * - web_search + fetch_webpage -> web（联网三合一 search/fetch/download 同属一个工具）
     * - read_pdf -> pdf_page；read_image -> image_view；schedule_task -> timer_task_create；
     *   generate_image -> gen_image。
     * - 第 7 个补 browser：web 的描述本身写明“用户想看页面请改用 browser”，两者是配套的。
     * - memory_search：氢气 Step 4 原方案裁掉，用户 2026-11-16 拍板加回来（保留本地记忆检索）。
     * - tool_detail：v3 恢复（用户拍板 8 个工具，v2 误减到 7 个；Top-N 在 ≤8 工具时不触发冷门折叠，保留无害）。
     * 注意：白名单之外还包括 ask_user / workdir / artifact 等常用工具，
     * 按氢气 Step 4 方案一并裁掉；如需恢复，把名字加回本集合即可。
     */
    val ALLOWED_TOOLS: Set<String> = setOf(
        "web", // 联网搜索 + 网页抓取 + 下载（三合一）
        "browser", // 全屏浏览器控制（用户可见页面）
        "pdf_page", // 读取 PDF 文档
        "image_view", // 读取/查看图片
        "timer_task_create", // 定时任务
        "gen_image", // AI 画图
        "memory_search", // 本地记忆检索
        "tool_detail" // 查询未下发工具规格并激活（v3 恢复，用户拍板 8 个）
    )

    // ============ 2b. 定时任务受控例外（v3，氢气定案） ============
    /**
     * 永禁工具：即使定时任务在创建时声明了，也绝不在后台静默执行。
     * 氢气原名 → 本仓库真实工具名映射：
     * - shell / local_shell → sh_run（本机 Shell 执行）
     * - ssh / ssh_exec → ssh_run（SSH 远程执行）
     * - accessibility_click / accessibility_action → app（无障碍控第三方 App）
     * 原名一并保留在集合里做防御（若上游新增同名工具同样禁掉）。
     */
    val FORBIDDEN_FOREVER_TOOLS: Set<String> = setOf(
        "sh_run", "ssh_run", "app",
        "shell", "local_shell", "ssh", "ssh_exec", "accessibility_click", "accessibility_action"
    )

    /**
     * 定时任务上下文：当前静默任务预授权的 allowTools（目录 key，如 workdir:write）；
     * 仅执行线程内有效。
     */
    private val scheduledAllowedTools = ThreadLocal<Set<String>>()

    /**
     * 在定时任务上下文中执行 block：预授权集合进 ThreadLocal，finally 确保清理，
     * 避免泄漏到主对话或下一轮任务。由 TaskService.runTimerTask 包裹 LocalEngine.chat 调用。
     */
    fun <T> runWithScheduledTools(allowTools: Set<String>, block: () -> T): T {
        scheduledAllowedTools.set(allowTools)
        try {
            return block()
        } finally {
            scheduledAllowedTools.remove()
        }
    }

    /**
     * 工具执行门禁（v3 受控例外），供 ToolExecutor.execute 入口调用：
     * 1. 永禁工具一律拒绝；
     * 2. 定时任务上下文：放行 ALLOWED_TOOLS + 本任务预授权的 allowTools；
     *    目录 key 或裸工具名均认。
     * 3. 普通上下文：只放行 ALLOWED_TOOLS。
     */
    fun isToolPermitted(toolName: String): Boolean {
        if (toolName in FORBIDDEN_FOREVER_TOOLS) return false
        val scheduled = scheduledAllowedTools.get()
        return if (scheduled != null) {
            toolName in ALLOWED_TOOLS || toolName in scheduled ||
                scheduled.any { it.substringBefore(':') == toolName }
        } else {
            toolName in ALLOWED_TOOLS
        }
    }

    // ============ 3. Qwen3-27B 去审核版防越权工具调用铁律 ============
    /**
     * 追加到系统提示词末尾。成因：压测 D 项——模型问视频时拿 generate_image 画图顶视频，
     * 根因是去审核版削弱了拒答倾向，遇缺失工具就拿相似工具顶替。
     */
    const val QWEN3_TOOL_GUARDRAIL = """
【工具调用铁律】
1. 严格匹配用户意图，仅在工具列表存在直接对应的功能时才调用工具。
2. 严禁使用相似或低维工具替代缺失的高维工具（例如：用户要求生成视频、音频或控制手机应用，而工具列表只有图片生成工具时，绝不允许调用 generate_image 代替，必须直接用纯文本向用户说明当前不具备该能力，并询问是否需要替代方案）。
3. 记忆检索（memory_search）使用边界：仅当用户提及过往约定、个人偏好、历史对话细节，或当前问题依赖历史背景才能准确回答时，才调用 memory_search 查证；对于通用百科知识、编程代码、数学推理及普通日常闲聊，严禁无故调用 memory_search。
4. 若需调用工具，必须在 </think> 闭合标签之后输出标准调用格式，严禁在思考过程中提前输出工具调用。
"""

    // ============ 4. 字面 <think> 标签剥离（非流式兜底） ============
    /**
     * 剥离残留的字面 <think>...</think> 块（含未闭合半截与孤立闭合标签）。入库前兜底用，幂等。
     * 流式主路径由 LiteralThinkStripper 在 SSE 循环里实时分流，本函数只处理最终文本里的残留。
     */
    fun stripLiteralThinkTags(raw: String): String {
        if (!raw.contains("<think") && !raw.contains("</think>")) return raw
        var s = raw
        // 成对块：非贪婪跨行匹配
        s = Regex("<think>[\\s\\S]*?</think>").replace(s, "")
        // 未闭合半截：从 <think> 到文末一律视为思考内容，直接丢弃
        val openIdx = s.indexOf("<think>")
        if (openIdx >= 0) s = s.substring(0, openIdx)
        // 孤立闭合标签
        s = s.replace("</think>", "")
        return s.trim()
    }

    // ============ 5. 工具 JSON 参数清洗 ============
    /** "```json" 前缀长度（工具参数围栏头） */
    private const val JSON_FENCE_PREFIX_LEN = 7
    /** "```" 围栏长度（工具参数围栏头/尾） */
    private const val CODE_FENCE_LEN = 3

    /**
     * 剥工具参数外层的 ```json / ``` 围栏（去审核版模型常把整段 JSON 包进围栏再交出来）。
     * 仅当围栏内是 JSON 对象时才剥，避免误伤以 ``` 开头的裸文本参数（如代码块正文）。
     */
    fun sanitizeToolJsonArgs(rawArgs: String): String {
        var s = rawArgs.trim()
        val stripped = when {
            s.startsWith("```json", ignoreCase = true) -> s.substring(JSON_FENCE_PREFIX_LEN).trim()
            s.startsWith("```") -> s.substring(CODE_FENCE_LEN).trim()
            else -> return s
        }
        val noTail = if (stripped.endsWith("```")) {
            stripped.substring(0, stripped.length - CODE_FENCE_LEN).trim()
        } else {
            stripped
        }
        return if (noTail.startsWith("{")) noTail else s
    }

    // ============ 6. 字面 <think> 流式剥离器 ============
    /**
     * 字面 <think>...</think> 流式剥离器（Qwen3 去审核版可能把思考写进 content 通道，而非 reasoning_content）。
     * 用法：在 SSE 循环里每拿到一个 content 增量就调 process()；返回的正文部分进原有行缓冲，
     * 思考部分经回调分流进 onThinkingDelta（与 reasoning_content 同一回调）。
     * 内部维护 inThink 状态 + 跨 chunk 截断的标签残尾（tagTail），标签被 chunk 边界切开也不丢字。
     */
    class LiteralThinkStripper {
        /** 当前是否正处字面思考段内 */
        var inThink = false
            private set

        /** 截断未闭合的标签残尾（如 "<th" / "</thi"），下一 chunk 到达时先拼回 */
        private var tagTail = ""

        fun process(
            chunk: String,
            onThinkStart: () -> Unit,
            onThinkDelta: (String) -> Unit,
            onThinkEnd: () -> Unit
        ): String {
            var remaining = tagTail + chunk
            tagTail = ""
            val out = StringBuilder()
            while (remaining.isNotEmpty()) {
                remaining = if (!inThink) {
                    processBodySegment(remaining, out, onThinkStart)
                } else {
                    processThinkSegment(remaining, onThinkDelta, onThinkEnd)
                }
            }
            return out.toString()
        }

        /** 处理一段正文（非思考段）：返回处理后剩余文本（空串表示本 chunk 已消费完） */
        private fun processBodySegment(
            remaining: String,
            out: StringBuilder,
            onThinkStart: () -> Unit
        ): String {
            // 游离的孤立闭合标签直接丢弃（模型偶发孤立 </think>，不进正文）
            val cleaned = remaining.replace("</think>", "")
            val idx = cleaned.indexOf("<think>")
            if (idx < 0) {
                val hold = trailingTagPrefix(cleaned, "<think>")
                val body = if (hold > 0) {
                    tagTail = cleaned.takeLast(hold)
                    cleaned.dropLast(hold)
                } else {
                    cleaned
                }
                out.append(body)
                return ""
            }
            out.append(cleaned.substring(0, idx))
            inThink = true
            onThinkStart()
            return cleaned.substring(idx + "<think>".length)
        }

        /** 处理一段思考：返回处理后剩余文本（空串表示本 chunk 已消费完） */
        private fun processThinkSegment(
            remaining: String,
            onThinkDelta: (String) -> Unit,
            onThinkEnd: () -> Unit
        ): String {
            val idx = remaining.indexOf("</think>")
            if (idx < 0) {
                val hold = trailingTagPrefix(remaining, "</think>")
                val thinkPart = remaining.dropLast(hold)
                if (hold > 0) tagTail = remaining.takeLast(hold)
                if (thinkPart.isNotEmpty()) onThinkDelta(thinkPart)
                return ""
            }
            val thinkPart = remaining.substring(0, idx)
            if (thinkPart.isNotEmpty()) onThinkDelta(thinkPart)
            inThink = false
            onThinkEnd()
            return remaining.substring(idx + "</think>".length)
        }

        /**
         * 流结束时调用：把截断未闭合的开标签残尾当作正文吐回（避免丢字）；
         * 正处思考段内时残尾是闭合标签碎片，直接丢弃。
         */
        fun flushTail(): String {
            val tail = if (inThink) "" else tagTail
            tagTail = ""
            return tail
        }

        /** s 末尾与 tag 开头重合的最长长度（用于识别被 chunk 边界切开的标签） */
        private fun trailingTagPrefix(s: String, tag: String): Int {
            val max = minOf(s.length, tag.length - 1)
            for (len in max downTo 1) {
                if (s.endsWith(tag.substring(0, len))) return len
            }
            return 0
        }
    }

    // ============ 7. 首次启动种子与 Tavily Key ============

    /** 与 ApiConfig.PREF / K_CURRENT 同值（ApiConfig 的两个常量是 private，此处显式复述，改动时须同步） */
    private const val API_CONFIG_PREFS = "api_config"
    private const val API_CONFIG_CURRENT_KEY = "current_provider"

    private const val TAVILY_PREFS = "dafeiyu_config"
    private const val K_TAVILY_KEY = "tavily_api_key"

    /**
     * 首次启动种子（由 ApiConfig.init 调用，每个 Activity.onCreate 都会走一次，本函数幂等）：
     * - 三条线逐条按固定 id 注册（ApiConfig.ensureProvider）：不存在时插入，已存在时仅在 Key 为空时补 Key，
     *   绝不覆盖用户改过的 base/model/label；Key 此后只活在加密存储里；
     * - 二号线 Base 未配置（Secret 未配）时不注册，等配好后的构建自然出现；
     * - 扩展池（v7）同款逐条注册，池为空时不注册任何扩展线路；
     * - 用户若已手动加过同地址的供应商（自定义），不再重复插一条；
     * - 从未选过供应商（全新安装）时默认切到一号线；之后绝不覆盖用户的手动选择。
     */
    fun seedDefaults(context: Context) {
        ApiConfig.ensureProvider(
            DEFAULT_PROVIDER_ID, DEFAULT_PROVIDER_NAME,
            DEFAULT_BASE_URL, BuildConfig.DAFEIYU_API_KEY.trim(), DEFAULT_MODEL_ID
        )
        val node2Key = BuildConfig.DAFEIYU_NODE2_API_KEY.trim()
        val hasManualLine2 = ApiConfig.providers().any {
            it.id != NODE2_PROVIDER_ID && normalizeV1BaseUrl(it.defaultBase) == NODE2_BASE_URL
        }
        if (NODE2_BASE_URL.isNotBlank() && !hasManualLine2) {
            ApiConfig.ensureProvider(
                NODE2_PROVIDER_ID, NODE2_PROVIDER_NAME,
                NODE2_BASE_URL, node2Key, NODE2_MODEL_ID
            )
        }
        // v7: 扩展池逐条注册（与二号线同款幂等 + 手动同地址去重；
        // 池为空时 parseExtraNodes 为空列表）
        for (preset in parseExtraNodes()) {
            val hasManualSameBase = ApiConfig.providers().any {
                it.id != preset.id && normalizeV1BaseUrl(it.defaultBase) == preset.baseUrl
            }
            if (!hasManualSameBase) {
                ApiConfig.ensureProvider(
                    preset.id, preset.name, preset.baseUrl, preset.apiKey, preset.modelId
                )
            }
        }
        val prefs = context.getSharedPreferences(API_CONFIG_PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(API_CONFIG_CURRENT_KEY)) {
            ApiConfig.setCurrent(DEFAULT_PROVIDER_ID)
        }
    }

    /** Tavily Key：优先用户在设置里保存的，其次 BuildConfig 注入 */
    fun tavilyKey(context: Context?): String {
        if (context != null) {
            val saved = context.getSharedPreferences(TAVILY_PREFS, Context.MODE_PRIVATE)
                .getString(K_TAVILY_KEY, "").orEmpty().trim()
            if (saved.isNotEmpty()) return saved
        }
        return BuildConfig.TAVILY_API_KEY.trim()
    }

    /** 保存用户在设置里手动填写的 Tavily Key（供后续设置页接线） */
    fun setTavilyKey(context: Context, key: String) {
        context.getSharedPreferences(TAVILY_PREFS, Context.MODE_PRIVATE)
            .edit().putString(K_TAVILY_KEY, key.trim()).apply()
    }
}
