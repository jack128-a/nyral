package io.github.aixtin.nyral

import android.content.Context

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

    // ============ 2. 工具白名单 ============
    /**
     * 仅向模型暴露的 7 个核心业务工具（其余 21 个不下发、不激活、执行侧也查不到）。
     * 命名以 LocalEngine.kt 的 toolRegistry 实测为准（氢气原方案的 web_search / fetch_webpage /
     * read_pdf / read_image / schedule_task / generate_image 是 2026-11-11 合并前的旧名，现已不存在）：
     * - web_search + fetch_webpage -> web（联网三合一 search/fetch/download 同属一个工具）
     * - read_pdf -> pdf_page；read_image -> image_view；schedule_task -> timer_task_create；
     *   generate_image -> gen_image。
     * - 第 7 个补 browser：web 的描述本身写明“用户想看页面请改用 browser”，两者是配套的。
     * - memory_search：氢气 Step 4 原方案裁掉，用户 2026-11-16 拍板加回来（保留本地记忆检索）。
     * - tool_detail：v2 去掉（白名单过滤后仅 7 个业务工具，Top-N 装载不触发冷门折叠，无冷门工具可激活）。
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
        "memory_search" // 本地记忆检索
    )

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
     * - BuildConfig 带 Key 且已存的大肥鱼 Key 为空时，调原版 saveProvider 加密写入 SharedPreferences
     *   （不覆盖用户手动改过的 Key；Key 此后只活在加密存储里）；
     * - 从未选过供应商（全新安装）时默认切到大肥鱼；之后绝不覆盖用户的手动选择。
     */
    fun seedDefaults(context: Context) {
        val key = BuildConfig.DAFEIYU_API_KEY.trim()
        if (key.isNotEmpty()) {
            val stored = ApiConfig.providerById(DEFAULT_PROVIDER_ID)?.key.orEmpty()
            if (stored.isBlank()) {
                ApiConfig.saveProvider(
                    DEFAULT_PROVIDER_ID, DEFAULT_BASE_URL, key, DEFAULT_MODEL_ID,
                    setAsCurrent = false
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
