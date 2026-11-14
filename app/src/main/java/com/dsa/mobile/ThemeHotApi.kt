package com.dsa.mobile

import android.content.Context
import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v2.12.10：热门题材（近 30 日市场共识 + 交易活跃度 + 话题度，运行时现取、非固定名单）
 *
 * 口径（三段）：
 * 1) 候选题材：新浪财经概念/行业板块里，当日涨幅前 5 ∪ 当日成交额前 5，去重并剔除情绪噪声板块。
 *    当日数据只用来圈定候选范围，最终排序不看当日涨幅。
 * 2) 近 30 日市场共识：每个候选取成交额最大的 4 只成分股作代表股（腾讯日 K，前复权 32 根），
 *    用「30 日涨幅中位数」衡量中期共识，用「近 5 日涨幅中位数」衡量共识是否还在延续。
 * 3) 交易活跃度：板块当日平均单股成交额（板块成交额 ÷ 成分股数）+ 代表股近 5 日均额 / 近 20 日均额
 *    的放大倍数（资金是否在加速流入）。
 * 4) 话题度：题材名近 N 日新闻命中条数（复用 App 内新闻检索；未配置或不可用时按行情热度估算并标注）。
 *
 * 综合分 heat = 0.45 × 共识分 + 0.30 × 活跃分 + 0.25 × 话题分，取前 limit 个题材。
 * 全部取不到时返回 null，由调用方降级为「中小市值 + 资金博弈」全市场筛选。
 *
 * 数据源说明：东方财富 push2 / push2his 在部分网络与设备上会被对端断连，因此板块与成分股走
 * 新浪财经（板块接口为 GBK）、代表股日 K 走腾讯（web.ifzq.gtimg.cn，失败切备用域）。
 */
object ThemeHotApi {

    private const val TAG = "ThemeHotApi"

    private const val SINA_CONCEPT =
        "https://vip.stock.finance.sina.com.cn/q/view/newFLJK.php?param=class"

    private const val SINA_INDUSTRY =
        "https://vip.stock.finance.sina.com.cn/q/view/newSinaHy.php"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val REFERER = "https://finance.sina.com.cn/"

    /** 每个候选取几只代表股算 30 日共识 */
    private const val REPRESENT = 4

    /** 候选圈定：当日涨幅前 N ∪ 当日成交额前 N（当日只圈范围，不参与最终排序） */
    private const val CANDIDATE_TOP = 7

    /** 不复用连接：部分行情 CDN 会提前关闭 keep-alive，复用旧连接会 EOF */
    private val client: OkHttpClient = Http.client.newBuilder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    /** 情绪/统计类噪声板块（不是真实题材，命中即剔除） */
    private val NOISE = listOf(
        "昨日", "涨停", "跌停", "连板", "首板", "二板", "三板", "打板", "炸板", "封板",
        "竞价", "龙虎榜", "ST", "融资融券", "转债", "次新"
    )

    data class Theme(
        val code: String,
        val name: String,
        /** 当日涨幅（只用于圈候选与降级时的行情热度，不参与最终排序） */
        val changePct: Double,
        /** 当日板块成交额（元） */
        val amount: Double,
        /** 板块成分股数 */
        val memberCount: Int,
        /** 领涨股 */
        val leaderName: String,
        /** 代表股 30 日涨幅中位数（%） */
        val cons30: Double,
        /** 代表股近 5 日涨幅中位数（%） */
        val cons5: Double,
        /** 近 5 日均额 / 近 20 日均额 */
        val amp: Double,
        /** 新闻命中条数 */
        val newsCount: Int,
        /** 话题度是否来自真实新闻检索 */
        val topicFromNews: Boolean,
        /** 综合分（0-100） */
        val heat: Double
    )

    /**
     * 热门题材池
     * @param themes 本次入选的热门题材（已按综合分降序）
     * @param themeByCode 股票代码 → 所属题材名（同一股票命中多个题材时取综合分最高的那个）
     * @param themeHeat 题材名 → 综合分（个股热度分直接用这个值）
     * @param codes 去重后的成分股代码
     * @param topicNote 话题度口径说明（用于状态栏提示）
     */
    class Universe(
        val themes: List<Theme>,
        val themeByCode: Map<String, String>,
        val themeHeat: Map<String, Double>,
        val codes: List<String>,
        val topicNote: String
    )

    /**
     * 构建题材池。
     * @param ctx 用于新闻检索（可为 null，此时话题度按行情热度估算）
     */
    fun buildUniverse(ctx: Context?, limit: Int = 6): Universe? {
        val concept = fetchSinaBoards(SINA_CONCEPT)
        val industry = fetchSinaBoards(SINA_INDUSTRY)
        val merged = mutableListOf<Theme>()
        (concept + industry).forEach { t ->
            if (NOISE.any { k -> t.name.contains(k) }) return@forEach
            if (merged.none { it.code == t.code || it.name == t.name }) merged.add(t)
        }
        if (merged.isEmpty()) return null

        val candidates = pickCandidates(merged, CANDIDATE_TOP)
        if (candidates.isEmpty()) return null
        Log.i(TAG, "candidates=${candidates.map { it.name }}")

        // 阶段一：代表股 30 日共识 + 活跃度
        val stats = fetchStats(candidates)

        // 阶段二：话题度（先探测一次新闻源，不可用则整体降级，避免逐个空转拖慢）
        val news = fetchTopicCounts(ctx, candidates)
        val topicFromNews = news != null
        val scored = candidates.map { t ->
            val st = stats[t.code]
            val withStat = if (st == null) {
                t.copy(cons30 = 0.0, cons5 = 0.0, amp = 1.0)
            } else {
                t.copy(cons30 = st.cons30, cons5 = st.cons5, amp = st.amp)
            }
            val cnt = news?.get(t.name) ?: 0
            withStat.copy(newsCount = cnt, topicFromNews = topicFromNews)
        }.map { t -> t.copy(heat = heatOf(t, topicFromNews)) }

        val themes = scored.sortedByDescending { it.heat }.take(limit)
        if (themes.isEmpty()) return null
        themes.forEach { t ->
            Log.i(
                TAG,
                "theme ${t.name} 30日=${fmt(t.cons30)}% 5日=${fmt(t.cons5)}% amp=${fmt(t.amp)} " +
                    "news=${t.newsCount} heat=${fmt(t.heat)}"
            )
        }

        // 阶段三：入选题材的成分股（同一股票归综合分最高的题材）
        val themeByCode = HashMap<String, String>()
        val themeHeat = HashMap<String, Double>()
        val codes = ArrayList<String>()
        val pool = Executors.newFixedThreadPool(minOf(4, themes.size))
        try {
            val futures = themes.map { t -> pool.submit<List<String>> { fetchMemberCodes(t.code, 100) } }
            themes.forEachIndexed { idx, t ->
                themeHeat[t.name] = t.heat
                val members = try {
                    futures[idx].get(20, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    emptyList()
                }
                members.forEach { c ->
                    if (!themeByCode.containsKey(c)) {
                        themeByCode[c] = t.name
                        codes.add(c)
                    }
                }
            }
        } finally {
            pool.shutdown()
        }
        if (codes.isEmpty()) return null

        val note = if (topicFromNews) "话题度取新闻命中数" else "未取到新闻话题度·按行情热度估算"
        Log.i(
            TAG,
            "universe themes=${themes.map { it.name }} codes=${codes.size} note=$note"
        )
        return Universe(themes, themeByCode, themeHeat, codes, note)
    }

    /** 候选：当日涨幅前 N ∪ 当日成交额前 N（去重后按综合强度初排，仅用于圈范围） */
    private fun pickCandidates(all: List<Theme>, top: Int): List<Theme> {
        val byChg = all.sortedByDescending { it.changePct }.take(top)
        val byAmt = all.sortedByDescending { it.amount }.take(top)
        val out = mutableListOf<Theme>()
        (byChg + byAmt).forEach { t -> if (out.none { it.code == t.code }) out.add(t) }
        return out
    }

    private class Stat(val cons30: Double, val cons5: Double, val amp: Double)

    /** 并行算每个候选的代表股指标；单个候选失败不影响其他 */
    private fun fetchStats(candidates: List<Theme>): Map<String, Stat> {
        val out = HashMap<String, Stat>()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = HashMap<String, java.util.concurrent.Future<Stat?>>()
            candidates.forEach { t ->
                futures[t.code] = pool.submit<Stat?> {
                    val members = try {
                        fetchMemberCodes(t.code, REPRESENT)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (members.isEmpty()) null else themeStat(members)
                }
            }
            candidates.forEach { t ->
                val st = try {
                    futures[t.code]?.get(30, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.w(TAG, "stat ${t.name} err=${e.javaClass.simpleName}")
                    null
                }
                if (st != null) out[t.code] = st
            }
        } finally {
            pool.shutdown()
        }
        return out
    }

    /** 代表股 30 日/5 日涨幅中位数 + 近期成交额放大倍数 */
    private fun themeStat(codes: List<String>): Stat? {
        val list30 = mutableListOf<Double>()
        val list5 = mutableListOf<Double>()
        val amps = mutableListOf<Double>()
        codes.forEach { c ->
            val bars = dailyKline(c)
            if (bars.size < 10) return@forEach
            val last = bars.last().close
            val base30 = bars[maxOf(0, bars.size - 30)].close
            if (base30 > 0.0 && last > 0.0) list30.add((last / base30 - 1.0) * 100.0)
            val base5 = bars[maxOf(0, bars.size - 6)].close
            if (base5 > 0.0 && last > 0.0) list5.add((last / base5 - 1.0) * 100.0)

            val recent = bars.takeLast(5)
            val earlier = if (bars.size > 6) bars.dropLast(5).takeLast(20) else emptyList()
            val amtRecent = avgAmount(recent)
            val amtEarlier = avgAmount(earlier)
            if (amtEarlier > 0.0 && amtRecent > 0.0) amps.add(amtRecent / amtEarlier)
        }
        if (list30.isEmpty()) return null
        return Stat(
            cons30 = median(list30),
            cons5 = if (list5.isEmpty()) 0.0 else median(list5),
            amp = if (amps.isEmpty()) 1.0 else median(amps)
        )
    }

    /** 近似成交额：收盘价 × 成交量（腾讯量的单位为手，1 手 = 100 股） */
    private fun avgAmount(bars: List<KLineBar>): Double {
        if (bars.isEmpty()) return 0.0
        var sum = 0.0
        bars.forEach { sum += it.close * it.volume * 100.0 }
        return sum / bars.size
    }

    private fun dailyKline(code: String): List<KLineBar> {
        return try {
            TencentApi.fetchDailyKline(code, 32)
        } catch (e: Exception) {
            try {
                TencentApi.fetchDailyKlineAlt(code, 32)
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * 综合分：共识 45% + 活跃 30% + 话题 25%
     * 共识分：40 起步，30 日涨 20% 满分（+40），近 5 日涨 10% 满分（+20）
     * 活跃分：40 起步，板块平均单股成交额 5 亿满分（+30），近 5 日放量 2 倍满分（+30）
     * 话题分：20 起步，新闻 10 条满分（+80）；无新闻源时用当日涨幅 5% 满分同权替代
     */
    private fun heatOf(t: Theme, fromNews: Boolean): Double {
        val ratio = { v: Double, cap: Double -> (v / cap).coerceIn(0.0, 1.0) }
        val s30 = 40.0 + 40.0 * ratio(t.cons30, 20.0) + 20.0 * ratio(t.cons5, 10.0)
        val avgAmt = t.amount / t.memberCount.coerceAtLeast(1)
        val sAct = 40.0 + 30.0 * ratio(avgAmt, 5e8) + 30.0 * ratio(t.amp - 1.0, 1.0)
        val sTopic = if (fromNews) 20.0 + 80.0 * ratio(t.newsCount.toDouble(), 10.0)
        else 20.0 + 80.0 * ratio(t.changePct, 5.0)
        return (0.45 * s30 + 0.30 * sAct + 0.25 * sTopic).coerceIn(0.0, 100.0)
    }

    /**
     * 话题度：先对一个题材做探测，成功后再并行检索其余；
     * 探测失败（未配置 Key、Key 失效、网络不通）返回 null，由调用方整体降级。
     */
    private fun fetchTopicCounts(ctx: Context?, candidates: List<Theme>): Map<String, Int>? {
        if (ctx == null || candidates.isEmpty()) return null
        if (!NewsApi.hasAnySource(ctx)) return null
        val out = HashMap<String, Int>()
        val probe = candidates.first()
        val probeCount = try {
            NewsApi.search(ctx, probe.name).size
        } catch (e: Exception) {
            Log.w(TAG, "news probe err=${e.javaClass.simpleName}")
            return null
        }
        // 探测返回 0 条时再确认一次：配置了 Key 但所有源都报错则视为不可用
        if (probeCount == 0) {
            val outcome = try {
                NewsApi.searchDetailed(ctx, listOf(probe.name))
            } catch (e: Exception) {
                return null
            }
            if (!outcome.ok && outcome.error.contains("未配置")) return null
        }
        out[probe.name] = probeCount

        val rest = candidates.drop(1)
        if (rest.isEmpty()) return out
        val pool = Executors.newFixedThreadPool(3)
        try {
            val futures = rest.map { t ->
                t.name to pool.submit<Int> {
                    try {
                        NewsApi.search(ctx, t.name).size
                    } catch (e: Exception) {
                        0
                    }
                }
            }
            futures.forEach { (name, f) ->
                out[name] = try {
                    f.get(20, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    0
                }
            }
        } finally {
            pool.shutdown()
        }
        Log.i(TAG, "news counts=${out}")
        return out
    }

    /**
     * 板块列表（新浪返回 GBK 编码的 `var S_Finance_bankuai_xxx = {...}`）
     * 每行格式：代码,名称,成分股数,均价,涨跌额,涨跌幅(%),成交量,成交额,领涨股代码,领涨股涨幅,领涨价,领涨涨跌额,领涨股名
     */
    private fun fetchSinaBoards(url: String): List<Theme> {
        return try {
            val raw = fetchText(url, gbk = true)
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) {
                Log.w(TAG, "boards empty body len=${raw.length}")
                return emptyList()
            }
            val obj = JSONObject(raw.substring(start, end + 1))
            val keys = obj.keys()
            val out = mutableListOf<Theme>()
            while (keys.hasNext()) {
                val line = obj.optString(keys.next(), "")
                val parts = line.split(',')
                if (parts.size < 8) continue
                val code = parts[0].trim()
                val name = parts[1].trim()
                if (code.isEmpty() || name.isEmpty()) continue
                val count = parts[2].trim().toIntOrNull() ?: 0
                val chg = parts[5].trim().toDoubleOrNull() ?: 0.0
                val amount = parts[7].trim().toDoubleOrNull() ?: 0.0
                val leader = if (parts.size >= 13) parts[12].trim() else ""
                out.add(
                    Theme(
                        code = code, name = name, changePct = chg, amount = amount,
                        memberCount = count, leaderName = leader,
                        cons30 = 0.0, cons5 = 0.0, amp = 1.0,
                        newsCount = 0, topicFromNews = false, heat = 0.0
                    )
                )
            }
            Log.i(TAG, "boards ${url.takeLast(24)} -> ${out.size}")
            out
        } catch (e: Exception) {
            Log.w(TAG, "boards err=${e.javaClass.simpleName} ${e.message}")
            emptyList()
        }
    }

    /** 板块成分股代码，按成交额降序（num=100 时取 2 页，代表股只取 1 页） */
    private fun fetchMemberCodes(node: String, limit: Int = 100): List<String> {
        val out = mutableListOf<String>()
        val pages = if (limit <= 10) 1 else 2
        for (page in 1..pages) {
            val url = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
                "Market_Center.getHQNodeData?page=$page&num=$limit&sort=amount&asc=0" +
                "&node=$node&symbol=&_s_r_a=page"
            try {
                val arr = JSONArray(fetchText(url))
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i)?.optString("code", "") ?: continue
                    if (c.isNotBlank()) out.add(normalize(c))
                }
                if (arr.length() < limit) break
            } catch (e: Exception) {
                Log.w(TAG, "member $node p$page err=${e.javaClass.simpleName} ${e.message}")
            }
        }
        return out
    }

    /** 代码统一成 6 位数字（腾讯快照带 sh/sz 前缀，新浪/东财为纯数字） */
    fun normalize(code: String): String {
        val digits = code.filter { it.isDigit() }
        return if (digits.length >= 6) digits.substring(digits.length - 6) else code.trim()
    }

    private fun fmt(v: Double): String = String.format("%.2f", v)

    /** 带 3 次重试的文本请求；gbk=true 时按 GBK 解码（新浪板块接口为 GBK） */
    private fun fetchText(url: String, gbk: Boolean = false): String {
        var lastErr: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val b = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .header("Connection", "close")
                client.newCall(b.build()).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: throw RuntimeException("空响应")
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    return if (gbk) String(bytes, Charset.forName("GBK")) else String(bytes, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                lastErr = e
                if (attempt < 2) {
                    try {
                        Thread.sleep(300L * (attempt + 1))
                    } catch (ignored: InterruptedException) {
                        break
                    }
                }
            }
        }
        throw lastErr ?: RuntimeException("空响应")
    }
}
