package com.dsa.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.12.35 选股行情短时缓存（C2）。
 *
 * - 全市场快照：按「数据源 + 交易日」存本地文件（filesDir），盘中 TTL 5 分钟；
 *   命中直接复用，避免每次选股全市场重拉；过期或跨交易日自动刷新。
 * - 候选日K：进程内内存 LRU 缓存（上限 60 只），同一会话内重复复核不再重复请求。
 *
 * 说明：快照缓存不持久化跨日；应用重启后若文件仍是当日且未过 TTL 可复用，超过即丢弃。
 */
object SnapshotCache {

    private const val SNAP_FILE = "snapshot_cache.json"
    private const val TTL_MS = 5 * 60 * 1000L
    private const val KLINE_LRU = 60

    private data class KlineEntry(val savedAt: Long, val bars: List<KLineBar>)

    private val klineMem = LinkedHashMap<String, KlineEntry>()

    private fun todayStr(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ---------- 快照（文件） ----------

    /** 取命中的快照；源不一致 / 非当日 / 过 TTL 返回 null */
    fun getSnapshot(ctx: Context, source: String): List<ScreenStock>? {
        return try {
            val f = java.io.File(ctx.filesDir, SNAP_FILE)
            if (!f.exists()) return null
            val root = JSONObject(f.readText(Charsets.UTF_8))
            if (root.optString("source") != source) return null
            if (root.optString("date") != todayStr()) return null
            val savedAt = root.optLong("savedAt", 0L)
            if (savedAt <= 0 || System.currentTimeMillis() - savedAt > TTL_MS) return null
            val arr = root.optJSONArray("items") ?: return null
            (0 until arr.length()).mapNotNull { parseStock(arr.optJSONObject(it)) }
        } catch (e: Exception) {
            null
        }
    }

    /** 写入快照缓存；序列化失败静默忽略（不影响主流程） */
    fun putSnapshot(ctx: Context, source: String, list: List<ScreenStock>) {
        try {
            val arr = JSONArray()
            list.forEach { st -> arr.put(stockToJson(st)) }
            val root = JSONObject()
                .put("source", source)
                .put("date", todayStr())
                .put("savedAt", System.currentTimeMillis())
                .put("items", arr)
            java.io.File(ctx.filesDir, SNAP_FILE).writeText(root.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // 缓存写失败不影响选股结果
        }
    }

    fun clearSnapshot(ctx: Context) {
        try {
            val f = java.io.File(ctx.filesDir, SNAP_FILE)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    /** 清空进程内日K LRU 缓存（不涉及磁盘文件） */
    fun clearMemory() = synchronized(klineMem) { klineMem.clear() }

    private fun stockToJson(st: ScreenStock): JSONObject = JSONObject().apply {
        put("code", st.code)
        put("name", st.name)
        put("price", st.price)
        put("changePct", st.changePct)
        put("volumeRatio", st.volumeRatio)
        put("turnoverRate", st.turnoverRate)
        put("pe", st.pe)
        put("pb", st.pb)
        put("totalMv", st.totalMv)
        put("amount", st.amount)
        put("change60d", st.change60d)
        put("high", st.high)
        put("low", st.low)
        put("volume", st.volume)
    }

    private fun parseStock(o: JSONObject?): ScreenStock? {
        o ?: return null
        val code = o.optString("code", "")
        if (code.isBlank()) return null
        return ScreenStock(
            code = code,
            name = o.optString("name", code),
            price = o.optDouble("price", 0.0),
            changePct = o.optDouble("changePct", 0.0),
            volumeRatio = o.optDouble("volumeRatio", 1.0),
            turnoverRate = o.optDouble("turnoverRate", 0.0),
            pe = o.optDouble("pe", 0.0),
            pb = o.optDouble("pb", 0.0),
            totalMv = o.optDouble("totalMv", 0.0),
            amount = o.optDouble("amount", 0.0),
            change60d = o.optDouble("change60d", 0.0),
            high = o.optDouble("high", 0.0),
            low = o.optDouble("low", 0.0),
            volume = o.optLong("volume", 0L)
        )
    }

    // ---------- 日K（进程内 LRU） ----------

    fun getKline(code: String): List<KLineBar>? = synchronized(klineMem) {
        val e = klineMem[code] ?: return null
        // 会话内不过期，仅 LRU 淘汰；命中后置到尾部
        klineMem.remove(code)
        klineMem[code] = e
        e.bars
    }

    fun putKline(code: String, bars: List<KLineBar>) = synchronized(klineMem) {
        if (bars.isEmpty()) return
        klineMem.remove(code)
        klineMem[code] = KlineEntry(System.currentTimeMillis(), bars)
        while (klineMem.size > KLINE_LRU) {
            val first = klineMem.keys.firstOrNull() ?: break
            klineMem.remove(first)
        }
    }
}
