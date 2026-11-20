package com.dsa.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** 本地存储：设置 / 自选股 / 历史报告 */
object Prefs {

    private fun sp(ctx: Context) = ctx.getSharedPreferences("dsa_mobile", Context.MODE_PRIVATE)

    // ---- 设置 ----
    fun tushareToken(ctx: Context): String = sp(ctx).getString("tushare_token", "") ?: ""

    // ---- v2.12.8 大盘页自动刷新策略 ----
    /** entry=每次进入；30/60/120/180=间隔分钟；never=从不（只手动刷新） */
    fun marketRefreshMode(ctx: Context): String =
        sp(ctx).getString("market_refresh_mode", "entry") ?: "entry"

    fun setMarketRefreshMode(ctx: Context, mode: String) {
        sp(ctx).edit().putString("market_refresh_mode", mode).apply()
    }

    /** 上次联网刷新成功的时间戳（毫秒），0 表示从未成功刷新过 */
    fun marketLastRefresh(ctx: Context): Long = sp(ctx).getLong("market_last_refresh", 0L)

    fun setMarketLastRefresh(ctx: Context, time: Long) {
        sp(ctx).edit().putLong("market_last_refresh", time).apply()
    }

    // ---- v2.12.25 排行榜：数据源选择 + 自动刷新档位 ----
    /** 榜单数据源：THS=同花顺 / EM=东方财富（默认）/ TX=腾讯 */
    fun rankSource(ctx: Context): String = sp(ctx).getString("rank_source", "EM") ?: "EM"

    fun saveRankSource(ctx: Context, source: String) {
        sp(ctx).edit().putString("rank_source", source).apply()
    }

    /** entry=每次进入；30/60/120/180=间隔分钟；never=从不（只手动刷新） */
    fun rankRefreshMode(ctx: Context): String =
        sp(ctx).getString("rank_refresh_mode", "entry") ?: "entry"

    fun saveRankRefreshMode(ctx: Context, mode: String) {
        sp(ctx).edit().putString("rank_refresh_mode", mode).apply()
    }

    /** 排行榜上次联网刷新成功的时间戳（毫秒），0 表示从未成功刷新过 */
    fun rankLastRefresh(ctx: Context): Long = sp(ctx).getLong("rank_last_refresh", 0L)

    fun setRankLastRefresh(ctx: Context, time: Long) {
        sp(ctx).edit().putLong("rank_last_refresh", time).apply()
    }

    // ---- v2.8.2 多 AI 模型配置 ----
    /** 一套 AI 模型配置 */
    data class LlmProfile(
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val fallback: String,
        val temperature: Double
    )

    /** 已显式保存的多套配置；null 表示从未保存过（回退旧单字段） */
    private fun storedLlmProfiles(ctx: Context): List<LlmProfile>? {
        val raw = sp(ctx).getString("llm_profiles", "") ?: ""
        if (raw.isBlank()) return null
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LlmProfile(
                    o.optString("name").ifBlank { "配置 ${i + 1}" },
                    o.optString("base_url"),
                    o.optString("api_key"),
                    o.optString("model"),
                    o.optString("fallback"),
                    o.optDouble("temperature", 0.7)
                )
            }
        } catch (e: Exception) { null }
    }

    /** 全部 AI 配置（未保存过多套时返回旧字段组成的"默认配置"） */
    fun llmProfiles(ctx: Context): List<LlmProfile> =
        storedLlmProfiles(ctx) ?: listOf(legacyLlmProfile(ctx))

    /** 配置名列表（设置页下拉用） */
    fun llmProfileNames(ctx: Context): List<String> = llmProfiles(ctx).map { it.name }

    /** 当前选中配置下标 */
    fun llmActiveIndex(ctx: Context): Int {
        val list = storedLlmProfiles(ctx) ?: return 0
        if (list.isEmpty()) return 0
        return sp(ctx).getInt("llm_active_index", 0).coerceIn(0, list.size - 1)
    }

    private fun activeLlmProfileOrNull(ctx: Context): LlmProfile? {
        val list = storedLlmProfiles(ctx) ?: return null
        if (list.isEmpty()) return null
        return list[llmActiveIndex(ctx)]
    }

    private fun legacyLlmProfile(ctx: Context) = LlmProfile(
        "默认配置",
        rawLlmBaseUrl(ctx), rawLlmApiKey(ctx), rawLlmModel(ctx),
        rawLlmFallback(ctx), rawLlmTemperature(ctx)
    )

    private fun rawLlmBaseUrl(ctx: Context): String = sp(ctx).getString("llm_base_url", "") ?: ""
    private fun rawLlmApiKey(ctx: Context): String = sp(ctx).getString("llm_api_key", "") ?: ""
    private fun rawLlmModel(ctx: Context): String = sp(ctx).getString("llm_model", "") ?: ""
    private fun rawLlmFallback(ctx: Context): String = sp(ctx).getString("llm_fallback_model", "") ?: ""
    private fun rawLlmTemperature(ctx: Context): Double {
        val p = sp(ctx)
        // 兼容旧版本以 Float 存储的 llm_temperature
        return try {
            p.getString("llm_temperature", null)?.toDoubleOrNull()
                ?: p.getFloat("llm_temperature", 0.7f).toDouble()
        } catch (e: Exception) {
            p.getFloat("llm_temperature", 0.7f).toDouble()
        }
    }

    /** 以下读取接口保持原签名：始终返回「当前选中配置」的值（旧单字段自动回退），调用方无需改动 */
    fun llmBaseUrl(ctx: Context): String {
        val p = activeLlmProfileOrNull(ctx)
        return p?.baseUrl?.ifBlank { null } ?: rawLlmBaseUrl(ctx)
    }
    fun llmApiKey(ctx: Context): String {
        val p = activeLlmProfileOrNull(ctx)
        return p?.apiKey?.ifBlank { null } ?: rawLlmApiKey(ctx)
    }
    fun llmModel(ctx: Context): String {
        val p = activeLlmProfileOrNull(ctx)
        return p?.model?.ifBlank { null } ?: rawLlmModel(ctx)
    }
    fun llmTemperature(ctx: Context): Double {
        val p = activeLlmProfileOrNull(ctx)
        return p?.temperature ?: rawLlmTemperature(ctx)
    }
    fun llmFallbackModel(ctx: Context): String {
        val p = activeLlmProfileOrNull(ctx)
        return p?.fallback?.ifBlank { null } ?: rawLlmFallback(ctx)
    }

    /** 保存整套配置列表并选中 index；同步回写旧单字段保持兼容 */
    fun saveLlmProfiles(ctx: Context, profiles: List<LlmProfile>, activeIndex: Int) {
        val idx = activeIndex.coerceIn(0, (profiles.size - 1).coerceAtLeast(0))
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject()
                    .put("name", p.name)
                    .put("base_url", p.baseUrl)
                    .put("api_key", p.apiKey)
                    .put("model", p.model)
                    .put("fallback", p.fallback)
                    .put("temperature", p.temperature)
            )
        }
        val cur = profiles.getOrNull(idx)
        sp(ctx).edit()
            .putString("llm_profiles", arr.toString())
            .putInt("llm_active_index", idx)
            .putString("llm_base_url", cur?.baseUrl ?: "")
            .putString("llm_api_key", cur?.apiKey ?: "")
            .putString("llm_model", cur?.model ?: "")
            .putString("llm_fallback_model", cur?.fallback ?: "")
            .putString("llm_temperature", (cur?.temperature ?: 0.7).toString())
            .apply()
    }

    /** 新增一套配置并选中，返回新配置下标 */
    fun addLlmProfile(
        ctx: Context, name: String, baseUrl: String, apiKey: String,
        model: String, fallback: String, temperature: Double
    ): Int {
        val profiles = llmProfiles(ctx).toMutableList()
        val idx = profiles.size
        profiles.add(
            LlmProfile(
                name.trim().ifBlank { "配置 ${idx + 1}" },
                baseUrl.trim().trimEnd('/'),
                apiKey.trim(),
                model.trim(),
                fallback.trim(),
                temperature
            )
        )
        saveLlmProfiles(ctx, profiles, idx)
        return idx
    }

    /** 覆盖更新 index 处配置（名称留空时保留原名称） */
    fun updateLlmProfile(
        ctx: Context, index: Int, name: String, baseUrl: String, apiKey: String,
        model: String, fallback: String, temperature: Double
    ) {
        val profiles = llmProfiles(ctx).toMutableList()
        val i = index.coerceIn(0, profiles.size - 1)
        profiles[i] = LlmProfile(
            name.trim().ifBlank { profiles[i].name },
            baseUrl.trim().trimEnd('/'),
            apiKey.trim(),
            model.trim(),
            fallback.trim(),
            temperature
        )
        saveLlmProfiles(ctx, profiles, llmActiveIndex(ctx))
    }

    /** 删除 index 处配置；只剩一套时不允许删除。返回新的选中下标，-1 表示未删除 */
    fun deleteLlmProfile(ctx: Context, index: Int): Int {
        val profiles = llmProfiles(ctx).toMutableList()
        if (profiles.size <= 1) return -1
        val removed = index.coerceIn(0, profiles.size - 1)
        val oldActive = llmActiveIndex(ctx)
        profiles.removeAt(removed)
        val newActive = when {
            removed < oldActive -> oldActive - 1
            removed == oldActive -> oldActive.coerceIn(0, profiles.size - 1)
            else -> oldActive
        }
        saveLlmProfiles(ctx, profiles, newActive)
        return newActive
    }

    // 数据源扩展字段（图2：TickFlow / 妙想 / Futu / 索引远程更新）
    fun tickflowKey(ctx: Context): String = sp(ctx).getString("tickflow_key", "") ?: ""
    fun mxApiKey(ctx: Context): String = sp(ctx).getString("mx_api_key", "") ?: ""
    fun futuHost(ctx: Context): String = sp(ctx).getString("futu_host", "") ?: ""
    fun zhituToken(ctx: Context): String = sp(ctx).getString("zhitu_token", "") ?: ""
    fun remoteIndexEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("remote_index_enabled", false)
    fun tickflowAdj(ctx: Context): String = sp(ctx).getString("tickflow_adj", "none") ?: "none"
    fun tickflowBatchPrefetch(ctx: Context): Boolean =
        sp(ctx).getBoolean("tickflow_batch_prefetch", true)
    fun tickflowBatchSize(ctx: Context): Int = sp(ctx).getInt("tickflow_batch_size", 50)

    // ---- v1.3.0 扩展：新闻检索 / 数据源选择 / 筹码分布 ----
    fun tavilyKeys(ctx: Context): String = sp(ctx).getString("news_tavily", "") ?: ""
    fun serpapiKeys(ctx: Context): String = sp(ctx).getString("news_serpapi", "") ?: ""
    fun braveKeys(ctx: Context): String = sp(ctx).getString("news_brave", "") ?: ""
    fun bochaKeys(ctx: Context): String = sp(ctx).getString("news_bocha", "") ?: ""
    fun minimaxKeys(ctx: Context): String = sp(ctx).getString("news_minimax", "") ?: ""
    fun searxngUrls(ctx: Context): String = sp(ctx).getString("news_searxng", "") ?: ""
    /** v2.9.4 新闻检索优先源：auto(按序兜底) / bocha / tavily / serpapi / brave / searxng */
    fun newsProvider(ctx: Context): String = sp(ctx).getString("news_provider", "auto") ?: "auto"
    fun saveNewsProvider(ctx: Context, p: String) {
        sp(ctx).edit().putString("news_provider", p).apply()
    }
    /** 新闻时效天数：1/3/7/10 */
    fun newsDays(ctx: Context): Int = sp(ctx).getInt("news_days", 3)
    /**
     * v2.9.5 Token 节省模式：
     * standard=标准（共享前缀 + 前缀缓存友好，零质量损失）
     * lean=省流（新闻条数与摘要再压缩、下游只传结论，输出上限收紧）
     */
    fun tokenMode(ctx: Context): String = sp(ctx).getString("token_mode", "standard") ?: "standard"
    fun saveTokenMode(ctx: Context, m: String) {
        sp(ctx).edit().putString("token_mode", m).apply()
    }
    fun isLean(ctx: Context): Boolean = tokenMode(ctx) == "lean"
    /** 选股数据源：auto / eastmoney / tushare / tickflow / tencent / miaoxiang / futu */
    fun screenSource(ctx: Context): String = sp(ctx).getString("screen_source", "auto") ?: "auto"

    /** 选股数据源下拉文案与取值（顺序一一对应；设置页与选股页快捷下拉共用，避免两处漂移） */
    val SCREEN_SOURCE_LABELS = listOf(
        "自动（东财失败切Tushare）", "东方财富", "Tushare", "TickFlow",
        "腾讯（免费）", "MiaoXiang", "Futu"
    )
    val SCREEN_SOURCE_VALUES = listOf(
        "auto", "eastmoney", "tushare", "tickflow", "tencent", "miaoxiang", "futu"
    )

    /** 选股页快捷下拉直接写回数据源（与设置页同一键 screen_source） */
    fun saveScreenSource(ctx: Context, source: String) {
        sp(ctx).edit().putString("screen_source", source).apply()
    }
    /** v2.6.2 上次使用的选股策略下标（进入选股页恢复，保证策略不变） */
    fun screenStrategyIdx(ctx: Context): Int = sp(ctx).getInt("screen_strategy_idx", 0)
    fun saveScreenStrategyIdx(ctx: Context, idx: Int) {
        sp(ctx).edit().putInt("screen_strategy_idx", idx).apply()
    }
    /**
     * v2.12.10 上次使用的选股策略名（排序后下标会错位，所以按名称恢复选中项）
     */
    fun screenStrategyName(ctx: Context): String = sp(ctx).getString("screen_strategy_name", "") ?: ""
    fun saveScreenStrategyName(ctx: Context, name: String) {
        sp(ctx).edit().putString("screen_strategy_name", name).apply()
    }
    /**
     * v2.12.10 用户自定义的策略顺序（策略名列表，用换行分隔；空串表示默认顺序）
     */
    fun screenStrategyOrder(ctx: Context): List<String> {
        val raw = sp(ctx).getString("screen_strategy_order", "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    fun saveScreenStrategyOrder(ctx: Context, names: List<String>) {
        sp(ctx).edit().putString("screen_strategy_order", names.joinToString("\n")).apply()
    }
    /** v2.6.2 上次的信号筛选选择（0=全部信号 / 1=仅强烈买入 / 2=仅买入） */
    fun screenSignalFilter(ctx: Context): Int = sp(ctx).getInt("screen_signal_filter", 0)
    fun saveScreenSignalFilter(ctx: Context, idx: Int) {
        sp(ctx).edit().putInt("screen_signal_filter", idx).apply()
    }
    /** Futu 港股实时数据源优先级：futu / longbridge / akshare / yfinance */
    fun futuPriority(ctx: Context): String = sp(ctx).getString("futu_priority", "futu") ?: "futu"
    /** 启用筹码分布分析 */
    fun chipEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("chip_enabled", true)

    // ---- v2.12.36 E3：选股惩罚权重与短线混合口径（出厂默认，可恢复默认） ----
    const val DEFAULT_RISK_WEIGHT = 0.35f
    const val DEFAULT_COST_WEIGHT = 0.15f
    const val DEFAULT_SHORT_FACTOR_W = 0.6f

    /** 风险惩罚权重（0..0.6，默认 0.35） */
    fun riskWeight(ctx: Context): Float =
        sp(ctx).getFloat("risk_weight", DEFAULT_RISK_WEIGHT).coerceIn(0f, 0.6f)

    /** 成本惩罚权重（0..0.4，默认 0.15） */
    fun costWeight(ctx: Context): Float =
        sp(ctx).getFloat("cost_weight", DEFAULT_COST_WEIGHT).coerceIn(0f, 0.4f)

    /** 短线混合口径中截面因子权重（0..1，默认 0.6；信号权重 = 1 − 因子权重） */
    fun shortFactorWeight(ctx: Context): Float =
        sp(ctx).getFloat("short_factor_w", DEFAULT_SHORT_FACTOR_W).coerceIn(0f, 1f)

    fun saveWeights(ctx: Context, risk: Float, cost: Float, shortFactor: Float) {
        sp(ctx).edit()
            .putFloat("risk_weight", risk.coerceIn(0f, 0.6f))
            .putFloat("cost_weight", cost.coerceIn(0f, 0.4f))
            .putFloat("short_factor_w", shortFactor.coerceIn(0f, 1f))
            .apply()
    }

    fun resetWeights(ctx: Context) {
        sp(ctx).edit()
            .remove("risk_weight")
            .remove("cost_weight")
            .remove("short_factor_w")
            .apply()
    }

    fun saveExtendedSettings(
        ctx: Context,
        tavily: String, serpapi: String, brave: String, bocha: String,
        minimax: String, searxng: String,
        days: Int, screenSource: String, futuPriority: String, chipEnabled: Boolean
    ) {
        sp(ctx).edit()
            .putString("news_tavily", tavily.trim())
            .putString("news_serpapi", serpapi.trim())
            .putString("news_brave", brave.trim())
            .putString("news_bocha", bocha.trim())
            .putString("news_minimax", minimax.trim())
            .putString("news_searxng", searxng.trim())
            .putInt("news_days", days)
            .putString("screen_source", screenSource)
            .putString("futu_priority", futuPriority)
            .putBoolean("chip_enabled", chipEnabled)
            .apply()
    }

    // ---- 历史选股 ----
    fun screenHistory(ctx: Context): List<ScreenHistory> {
        val raw = sp(ctx).getString("screen_history", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val stocksArr = o.optJSONArray("stocks") ?: JSONArray()
            val stocks = (0 until stocksArr.length()).map { j ->
                val s = stocksArr.getJSONObject(j)
                ScreenHistoryStock(s.optString("code"), s.optString("name"), s.optDouble("score", 0.0))
            }
            ScreenHistory(
                time = o.optString("time"),
                strategy = o.optString("strategy"),
                source = o.optString("source"),
                total = o.optInt("total", 0),
                matched = o.optInt("matched", 0),
                stocks = stocks
            )
        }
    }

    fun saveScreenHistory(ctx: Context, h: ScreenHistory) {
        val list = screenHistory(ctx).toMutableList()
        list.add(0, h)
        if (list.size > 50) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { r ->
            val stocksArr = JSONArray()
            r.stocks.forEach { s ->
                stocksArr.put(
                    JSONObject()
                        .put("code", s.code)
                        .put("name", s.name)
                        .put("score", s.score)
                )
            }
            arr.put(
                JSONObject()
                    .put("time", r.time)
                    .put("strategy", r.strategy)
                    .put("source", r.source)
                    .put("total", r.total)
                    .put("matched", r.matched)
                    .put("stocks", stocksArr)
            )
        }
        sp(ctx).edit().putString("screen_history", arr.toString()).apply()
    }

    /** 清除 days 天以前的历史选股记录（仅历史选股，不影响自选/配置/报告），返回清除条数 */
    fun clearScreenHistoryBefore(ctx: Context, days: Int): Int {
        val cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val all = screenHistory(ctx)
        val kept = all.filter { h ->
            val t = try { fmt.parse(h.time)?.time } catch (e: Exception) { null }
            // 时间解析失败时保守保留，避免误删
            if (t == null) true else t >= cutoff
        }
        val removed = all.size - kept.size
        if (removed > 0) saveScreenHistoryList(ctx, kept)
        return removed
    }

    private fun saveScreenHistoryList(ctx: Context, list: List<ScreenHistory>) {
        val arr = JSONArray()
        list.forEach { r ->
            val stocksArr = JSONArray()
            r.stocks.forEach { s ->
                stocksArr.put(
                    JSONObject()
                        .put("code", s.code)
                        .put("name", s.name)
                        .put("score", s.score)
                )
            }
            arr.put(
                JSONObject()
                    .put("time", r.time)
                    .put("strategy", r.strategy)
                    .put("source", r.source)
                    .put("total", r.total)
                    .put("matched", r.matched)
                    .put("stocks", stocksArr)
            )
        }
        sp(ctx).edit().putString("screen_history", arr.toString()).apply()
    }

    /** v2.5.0：按选中项删除历史选股记录（单选/多选），返回是否全部删除成功 */
    fun deleteScreenHistory(ctx: Context, targets: List<ScreenHistory>): Boolean {
        val list = screenHistory(ctx).toMutableList()
        val keys = targets.map { it.time to it.strategy }.toSet()
        list.removeAll { (it.time to it.strategy) in keys }
        saveScreenHistoryList(ctx, list)
        return true
    }

    // Tushare 交易日缓存（避免每天多次调用受限频接口）
    fun tushareCalDate(ctx: Context): String = sp(ctx).getString("tushare_cal_date", "") ?: ""
    fun tushareCalTd(ctx: Context): String = sp(ctx).getString("tushare_cal_td", "") ?: ""
    fun tushareCalDay60(ctx: Context): String = sp(ctx).getString("tushare_cal_day60", "") ?: ""
    fun saveTushareCal(ctx: Context, calDate: String, td: String, day60: String) {
        sp(ctx).edit()
            .putString("tushare_cal_date", calDate)
            .putString("tushare_cal_td", td)
            .putString("tushare_cal_day60", day60)
            .apply()
    }

    fun saveSettings(
        ctx: Context,
        token: String, baseUrl: String, apiKey: String, model: String,
        temperature: Double, fallbackModel: String,
        tickflowKey: String, mxApiKey: String, futuHost: String, zhituToken: String,
        remoteIndex: Boolean, adjMode: String, batchPrefetch: Boolean, batchSize: Int
    ) {
        sp(ctx).edit()
            .putString("tushare_token", token.trim())
            .putString("llm_base_url", baseUrl.trim().trimEnd('/'))
            .putString("llm_api_key", apiKey.trim())
            .putString("llm_model", model.trim())
            .putString("llm_temperature", temperature.toString())
            .putString("llm_fallback_model", fallbackModel.trim())
            .putString("tickflow_key", tickflowKey.trim())
            .putString("mx_api_key", mxApiKey.trim())
            .putString("futu_host", futuHost.trim())
            .putString("zhitu_token", zhituToken.trim())
            .putBoolean("remote_index_enabled", remoteIndex)
            .putString("tickflow_adj", adjMode)
            .putBoolean("tickflow_batch_prefetch", batchPrefetch)
            .putInt("tickflow_batch_size", batchSize)
            .apply()
    }

    fun hasLlmConfig(ctx: Context): Boolean =
        llmApiKey(ctx).isNotBlank() && llmModel(ctx).isNotBlank()

    // ---- v2.8.0 主题外观 ----
    /** 预设主题色：rose / blue / red / green / purple / orange / teal（v2.12.4 默认玫瑰） */
    fun themePreset(ctx: Context): String = sp(ctx).getString("theme_preset", "rose") ?: "rose"
    /**
     * 自定义主色。v2.12.6 已下线"自定义颜色"功能，恒定返回 -1；
     * 读取前顺手清掉历史版本可能残留的偏好值，保证老用户直接回落到预设主题。
     */
    fun themeCustomColor(ctx: Context): Int {
        val s = sp(ctx)
        if (s.contains("theme_custom_color")) {
            s.edit().remove("theme_custom_color").apply()
        }
        return -1
    }
    /** 自定义背景图片文件名（存于 filesDir），"none" 表示未启用 */
    fun themeBgImage(ctx: Context): String = sp(ctx).getString("theme_bg_image", "none") ?: "none"
    /** 背景图片透明度 0-100（默认 30） */
    fun themeBgAlpha(ctx: Context): Int = sp(ctx).getInt("theme_bg_alpha", 30)
    /** 背景图片模糊半径 0-25（默认 0） */
    fun themeBgBlur(ctx: Context): Int = sp(ctx).getInt("theme_bg_blur", 0)

    /** v2.12.4：毛玻璃模糊开关（默认关；打开后对背景图做一次真实高斯模糊并缓存） */
    fun themeBgBlurOn(ctx: Context): Boolean = sp(ctx).getBoolean("theme_bg_blur_on", false)
    fun saveThemeBgBlurOn(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("theme_bg_blur_on", on).apply()
        ThemeManager.clearBgCache()
    }

    // ---- v2.12.11 标签背景透明度（0-100，默认 100 不透明） ----
    /** 卡片上胶囊/徽章类标签的背景透明度，0 全透 ~ 100 不透明 */
    fun tagAlpha(ctx: Context): Int =
        sp(ctx).getInt("tag_alpha", 100).coerceIn(0, 100)

    fun saveTagAlpha(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("tag_alpha", v.coerceIn(0, 100)).apply()
    }

    // ---- v2.12.11 吉祥物：全局显示开关 + 缩放比例 ----
    /** 全局显示：默认关（仅多智能体分析页显示）；开启后主界面各页签也悬浮同一宠物 */
    fun mascotGlobal(ctx: Context): Boolean = sp(ctx).getBoolean("mascot_global", false)
    fun saveMascotGlobal(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("mascot_global", on).apply()
    }

    /** 宠物缩放比例（0.6 ~ 1.8，默认 1.0），捏合缩放后持久化 */
    fun mascotScale(ctx: Context): Float =
        sp(ctx).getFloat("mascot_scale", 1f).coerceIn(0.6f, 1.8f)
    fun saveMascotScale(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat("mascot_scale", v.coerceIn(0.6f, 1.8f)).apply()
    }

    /** 宠物拖动位置（相对右下角默认位的 translation，px；默认 0,0），拖动后持久化 */
    fun mascotPos(ctx: Context): Pair<Float, Float> =
        sp(ctx).getFloat("mascot_pos_x", 0f) to sp(ctx).getFloat("mascot_pos_y", 0f)
    fun saveMascotPos(ctx: Context, x: Float, y: Float) {
        sp(ctx).edit().putFloat("mascot_pos_x", x).putFloat("mascot_pos_y", y).apply()
    }

    /** v2.12.4 一次性迁移标记：升级后把仍停留在默认蓝白的用户切到玫瑰预设 */
    fun themeMigrated24(ctx: Context): Boolean = sp(ctx).getBoolean("theme_migrated_24", false)
    fun markThemeMigrated24(ctx: Context) {
        sp(ctx).edit().putBoolean("theme_migrated_24", true).apply()
    }
    /** 深色模式：follow / light / dark */
    fun themeDarkMode(ctx: Context): String = sp(ctx).getString("theme_dark_mode", "follow") ?: "follow"
    /** 应用标题（点击标题可自定义，默认 v2.8.0 新文案） */
    fun appTitle(ctx: Context): String = sp(ctx).getString("app_title", "老实赚钱很枯燥吧") ?: "老实赚钱很枯燥吧"

    // ---- v2.9.3 手势切 tab 滑动动画：slide=纯平移 / slide_fade=平移+淡入 / none=无动画 ----
    fun switchAnim(ctx: Context): String = sp(ctx).getString("switch_anim", "slide") ?: "slide"
    fun saveSwitchAnim(ctx: Context, mode: String) {
        sp(ctx).edit().putString("switch_anim", mode).apply()
    }

    fun saveTheme(
        ctx: Context,
        preset: String, customColor: Int,
        bgImage: String, alpha: Int, blur: Int, darkMode: String
    ) {
        sp(ctx).edit()
            .putString("theme_preset", preset)
            .putInt("theme_custom_color", customColor)
            .putString("theme_bg_image", bgImage)
            .putInt("theme_bg_alpha", alpha)
            .putInt("theme_bg_blur", blur)
            .putString("theme_dark_mode", darkMode)
            .apply()
    }

    fun saveAppTitle(ctx: Context, title: String) {
        val t = title.trim().ifBlank { "老实赚钱很枯燥吧" }
        sp(ctx).edit().putString("app_title", t).apply()
    }

    // ---- 自定义选股策略 ----
    fun customStrategy(ctx: Context): ScreenStrategy? {
        val raw = sp(ctx).getString("custom_strategy", "") ?: ""
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            fun opt(name: String): Double? =
                if (o.has(name) && !o.isNull(name)) o.optDouble(name) else null
            ScreenStrategy(
                name = "自定义策略...",
                techWeight = 0.35,
                priceMin = opt("price_min"), priceMax = opt("price_max"),
                mvMin = opt("mv_min"), mvMax = opt("mv_max"),
                peMax = opt("pe_max"), peMin = opt("pe_min"), pbMax = opt("pb_max"),
                vrMin = opt("vr_min"), vrMax = opt("vr_max"),
                turnoverMin = opt("turnover_min"), turnoverMax = opt("turnover_max"),
                changeMin = opt("change_min"), changeMax = opt("change_max"),
                amountMin = opt("amount_min")
            )
        } catch (e: Exception) { null }
    }

    fun saveCustomStrategy(ctx: Context, s: ScreenStrategy) {
        val o = JSONObject()
            .put("price_min", s.priceMin).put("price_max", s.priceMax)
            .put("mv_min", s.mvMin).put("mv_max", s.mvMax)
            .put("pe_max", s.peMax).put("pe_min", s.peMin).put("pb_max", s.pbMax)
            .put("vr_min", s.vrMin).put("vr_max", s.vrMax)
            .put("turnover_min", s.turnoverMin).put("turnover_max", s.turnoverMax)
            .put("change_min", s.changeMin).put("change_max", s.changeMax)
            .put("amount_min", s.amountMin)
        sp(ctx).edit().putString("custom_strategy", o.toString()).apply()
    }

    // ---- 自选股 ----
    fun watchlist(ctx: Context): List<String> {
        val raw = sp(ctx).getString("watchlist", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun addWatch(ctx: Context, code: String) {
        val list = watchlist(ctx).toMutableList()
        if (code !in list) {
            list.add(code)
            sp(ctx).edit().putString("watchlist", JSONArray(list).toString()).apply()
        }
    }

    fun removeWatch(ctx: Context, code: String) {
        val list = watchlist(ctx).filter { it != code }
        sp(ctx).edit().putString("watchlist", JSONArray(list).toString()).apply()
        // v2.12.32：联动取消该股已收藏的分析报告（仅取消收藏，不删除报告本体）
        val hist = history(ctx).toMutableList()
        var touched = false
        for (i in hist.indices) {
            if (hist[i].code == code && hist[i].favorite) {
                hist[i] = hist[i].copy(favorite = false)
                touched = true
            }
        }
        if (touched) persist(ctx, hist)
    }

    fun inWatchlist(ctx: Context, code: String): Boolean = code in watchlist(ctx)

    // ---- 历史报告 ----
    /** 历史报告入库最低评分：≤50 仅展示本次结果，不写入历史报告 */
    const val HISTORY_MIN_SCORE = 50

    /** 是否值得存入历史报告：评分高于阈值才保存 */
    fun keepHistory(score: Int): Boolean = score > HISTORY_MIN_SCORE

    /** 当前时间 HH:mm:ss，用于历史报告逐条标注 */
    fun clock(): String =
        java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

    fun history(ctx: Context): List<HistoryReport> {
        val raw = sp(ctx).getString("history", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryReport(
                code = o.optString("code"),
                name = o.optString("name"),
                date = o.optString("date"),
                score = o.optInt("score"),
                signal = o.optString("signal"),
                report = o.optString("report"),
                time = o.optString("time", ""),
                id = o.optLong("id", 0L),
                favorite = o.optBoolean("favorite", false),
                direction = o.optString("direction", ""),
                position = o.optString("position", ""),
                entryPrice = o.optString("entry_price", ""),
                stopPrice = o.optString("stop_price", ""),
                targetPrice = o.optString("target_price", ""),
                riskLevel = o.optString("risk_level", ""),
                horizon = o.optString("horizon", ""),
                holdCost = o.optString("hold_cost", ""),
                holdShares = o.optString("hold_shares", ""),
                holdCash = o.optString("hold_cash", "")
            )
        }
        // v2.9.6 迁移：给旧记录补一个稳定的 id（按列表位置生成，只补一次）
        if (list.any { it.id == 0L }) {
            val fixed = list.mapIndexed { i, h ->
                if (h.id == 0L) h.copy(id = -1_000_000L - i) else h
            }
            persist(ctx, fixed)
            return fixed
        }
        return list
    }

    /** 整表写回（删除、迁移用） */
    fun replaceHistory(ctx: Context, list: List<HistoryReport>) = persist(ctx, list)

    private fun persist(ctx: Context, list: List<HistoryReport>) {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(
                JSONObject()
                    .put("code", h.code)
                    .put("name", h.name)
                    .put("date", h.date)
                    .put("score", h.score)
                    .put("signal", h.signal)
                    .put("report", h.report)
                    .put("time", h.time)
                    .put("id", h.id)
                    .put("favorite", h.favorite)
                    .put("direction", h.direction)
                    .put("position", h.position)
                    .put("entry_price", h.entryPrice)
                    .put("stop_price", h.stopPrice)
                    .put("target_price", h.targetPrice)
                    .put("risk_level", h.riskLevel)
                    .put("horizon", h.horizon)
                    .put("hold_cost", h.holdCost)
                    .put("hold_shares", h.holdShares)
                    .put("hold_cash", h.holdCash)
            )
        }
        sp(ctx).edit().putString("history", arr.toString()).apply()
    }

    /**
     * v2.9.6：同一只股票的每次分析都独立保留，不再按「同股同日」覆盖。
     * 仅当 id 完全相同（同一条记录被重复写入）时才替换。
     */
    fun saveHistory(ctx: Context, r: HistoryReport): Long {
        val list = history(ctx).toMutableList()
        val id = if (r.id == 0L) System.currentTimeMillis() else r.id
        val item = r.copy(id = id)
        val existed = list.indexOfFirst { it.id == id }
        if (existed >= 0) list[existed] = item else list.add(0, item)
        // 上限调至 300 条，超出删最早的一条（列表尾部是最旧）
        if (list.size > 300) list.removeAt(list.size - 1)
        persist(ctx, list)
        return id
    }

    // ---- v2.12.43 个股持仓录入（可选；成本/股数有效才视为有持仓，总资金可缺） ----

    /** 读取该股已录入持仓；未填或无效返回 null（按无持仓视角分析） */
    fun loadHolding(ctx: Context, code: String): Holding? {
        val raw = sp(ctx).getString("holding_$code", "") ?: ""
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            val h = Holding(
                cost = o.optDouble("cost", 0.0),
                shares = o.optDouble("shares", 0.0),
                cash = o.optDouble("cash", 0.0)
            )
            if (h.valid) h else null
        } catch (e: Exception) { null }
    }

    /** 保存持仓；传 null 或无效则清除记录 */
    fun saveHolding(ctx: Context, code: String, h: Holding?) {
        val key = "holding_$code"
        if (h == null || !h.valid) {
            sp(ctx).edit().remove(key).apply()
            return
        }
        val o = JSONObject()
            .put("cost", h.cost)
            .put("shares", h.shares)
            .put("cash", h.cash)
        sp(ctx).edit().putString(key, o.toString()).apply()
    }

    // ---- v2.12.1 收藏 AI 分析报告 ----

    /** 收藏的全部报告（按写入顺序，最新在前） */
    fun favorites(ctx: Context): List<HistoryReport> = history(ctx).filter { it.favorite }

    fun favoriteCount(ctx: Context): Int = favorites(ctx).size

    fun isFavorite(ctx: Context, id: Long): Boolean =
        id != 0L && history(ctx).any { it.id == id && it.favorite }

    /** 设置/取消收藏，返回操作后是否处于收藏态 */
    fun setFavorite(ctx: Context, id: Long, favorite: Boolean): Boolean {
        if (id == 0L) return false
        val list = history(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        list[i] = list[i].copy(favorite = favorite)
        persist(ctx, list)
        return favorite
    }

    // ---- v2.12.32 报错报告检测 ----

    /** 报错/异常特征标记（统一小写；命中任一即视为报错报告） */
    private val errorMarkers = listOf(
        // 中文报错
        "分析失败", "生成失败", "请求失败", "调用失败", "获取失败", "保存失败",
        "检索异常", "检索失败", "接口报错", "网络错误", "返回异常", "解析失败",
        "空响应", "未配置模型", "请先在大模型设置", "llm 调用失败", "llm 返回异常",
        "错误码", "连接失败", "断流", "超时",
        // 英文异常 / 堆栈
        "exception", "traceback", "nullpointer", "runtimeexception", "stacktrace",
        "caused by", "at java.", "at kotlin.", "at android.", "classcastexception",
        "illegalstate", "indexoutofbound", "parse error", "malformed",
        // HTTP / 网络
        "http 4", "http 5", "http 429", "connection refused", "connection reset",
        "broken pipe", "failed to connect", "network is unreachable", "timeout",
        "timed out", "unexpected end of stream", "stream was reset", "socket",
        "ssl handshake",
        // 鉴权 / 限流 / 服务端错误
        "unauthorized", "forbidden", "rate limit", "internal server error",
        "bad gateway", "service unavailable", "gateway timeout", "not found",
        "{\"error", "\"error\":", "internal error", "unknown error",
        "invalid api key", "invalid_request_error"
    )

    private val cjkRegex = Regex("[一-鿿]")
    private val latinRegex = Regex("[A-Za-z]")

    /**
     * 判断一条历史报告是否为报错/空报告。
     * 口径：① 正文为空；② 命中报错/异常/HTTP/堆栈等特征标记；
     * ③ 整段以英文为主（中文极少、英文长串），通常是接口把英文错误信息直接落库。
     */
    fun isErrorReport(r: HistoryReport): Boolean {
        val text = r.report
        if (text.isBlank()) return true
        val lower = text.lowercase()
        if (errorMarkers.any { lower.contains(it) }) return true
        val cjk = cjkRegex.findAll(text).count()
        val latin = latinRegex.findAll(text).count()
        return cjk < 10 && latin >= 40
    }

    /** 扫描全量历史报告，返回其中被判为报错/空报告的条目 */
    fun detectErrorReports(ctx: Context): List<HistoryReport> =
        history(ctx).filter { isErrorReport(it) }

    /**
     * 报告页可能只拿到内容、拿不到 id（自动保存的场景）：
     * 按「代码 + 日期 + 正文」反查唯一 id，查不到返回 0。
     */
    fun findHistoryId(ctx: Context, code: String, date: String, report: String): Long {
        if (report.isBlank()) return 0L
        val list = history(ctx)
        return list.firstOrNull {
            it.code == code && it.date == date && it.report == report
        }?.id ?: 0L
    }

    // ---- v2.12.15：WebDAV 备份与恢复 ----

    fun webdavUrl(ctx: Context): String = sp(ctx).getString("webdav_url", "") ?: ""
    fun webdavUser(ctx: Context): String = sp(ctx).getString("webdav_user", "") ?: ""
    fun webdavPass(ctx: Context): String = sp(ctx).getString("webdav_pass", "") ?: ""
    fun webdavFolder(ctx: Context): String = sp(ctx).getString("webdav_folder", "") ?: ""
    fun webdavDevice(ctx: Context): String = sp(ctx).getString("webdav_device", "") ?: ""

    fun saveWebDav(
        ctx: Context, url: String, user: String, pass: String,
        folder: String, device: String
    ) {
        sp(ctx).edit()
            .putString("webdav_url", url.trim())
            .putString("webdav_user", user.trim())
            .putString("webdav_pass", pass)
            .putString("webdav_folder", folder.trim())
            .putString("webdav_device", device.trim())
            .apply()
    }

    /** 不纳入备份的内部键（一次性迁移标志等） */
    private fun backupExcluded(key: String): Boolean = key == "theme_migrated_24"

    /**
     * 导出全部可备份设置为 JSON：数据源 API、新闻检索 API、AI 模型配置、主题与基础设置，
     * 以及自选股 / 历史选股等业务数据；值按原始类型写入，恢复时按类型还原。
     */
    fun exportBackup(ctx: Context): JSONObject {
        val root = JSONObject()
        val meta = JSONObject()
        meta.put("app", "com.dsa.mobile")
        meta.put("version", 1)
        meta.put("time", System.currentTimeMillis())
        root.put("meta", meta)

        val data = JSONObject()
        sp(ctx).all.forEach { (key, value) ->
            if (backupExcluded(key)) return@forEach
            when (value) {
                is String -> data.put(key, value)
                is Boolean -> data.put(key, value)
                is Int -> data.put(key, value)
                is Long -> data.put(key, value)
                is Float -> data.put(key, value.toDouble())
                is Double -> data.put(key, value)
                is Set<*> -> data.put(key, JSONArray(value.toList().map { it.toString() }))
                else -> data.put(key, value.toString())
            }
        }
        // 业务数据兜底：自选股列表 / 历史报告（全量遍历已带，这里显式保证不被遗漏）
        if (!data.has("watchlist")) data.put("watchlist", sp(ctx).getString("watchlist", "[]"))
        if (!data.has("history")) data.put("history", sp(ctx).getString("history", "[]"))
        root.put("data", data)
        return root
    }

    /**
     * 从备份 JSON 还原设置；缺失的键保持现状，类型不匹配时容错跳过。
     * 返回还原成功的键数量。
     */
    fun restoreBackup(ctx: Context, root: JSONObject): Int {
        val data = root.optJSONObject("data") ?: return 0
        val editor = sp(ctx).edit()
        var count = 0
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (backupExcluded(key)) continue
            when (val v = data.opt(key)) {
                is String -> editor.putString(key, v)
                is Boolean -> editor.putBoolean(key, v)
                is Int -> editor.putInt(key, v)
                is Long -> editor.putLong(key, v)
                is Double -> editor.putFloat(key, v.toFloat())
                is JSONArray -> {
                    val set = LinkedHashSet<String>()
                    for (i in 0 until v.length()) set.add(v.optString(i))
                    editor.putStringSet(key, set)
                }
                else -> continue
            }
            count++
        }
        editor.apply()
        return count
    }
}
