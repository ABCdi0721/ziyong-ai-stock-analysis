package com.dsa.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 GitHub 选股策略引擎 JVM 单测
 * 验证 4 个新策略：参数注册、过滤边界、评分档位与权重分支（不依赖网络）
 */
class ScreeningStrategyTest {

    private fun stock(
        code: String = "600000", name: String = "测试",
        price: Double = 10.0, changePct: Double = 2.0,
        volumeRatio: Double = 2.0, turnoverRate: Double = 5.0,
        pe: Double = 20.0, pb: Double = 2.0,
        totalMv: Double = 100.0, amount: Double = 5e8, change60d: Double = 15.0
    ) = ScreenStock(code, name, price, changePct, volumeRatio, turnoverRate, pe, pb, totalMv, amount, change60d)

    private fun strategy(name: String) = Screening.STRATEGIES.first { it.name == name }

    @Test
    fun `策略列表包含4个GitHub新策略`() {
        val names = Screening.STRATEGIES.map { it.name }
        assertTrue("小市值精选", names.contains("小市值精选"))
        assertTrue("涨停捕手", names.contains("涨停捕手"))
        assertTrue("低价动量", names.contains("低价动量"))
        assertTrue("多维量能", names.contains("多维量能"))
        assertEquals(15, Screening.STRATEGIES.size) // 10 旧 + 4 新 + 自定义 = 15
    }

    @Test
    fun `小市值精选过滤生效`() {
        val s = strategy("小市值精选")
        // 市值超上限（>80亿）应被过滤
        val big = stock(totalMv = 200.0)
        val small = stock(code = "000001", totalMv = 50.0, pe = 12.0, pb = 1.5, amount = 8e7)
        val kept = Screening.applyFilters(listOf(big, small), s)
        assertEquals(1, kept.size)
        assertEquals("000001", kept[0].code)
        // 亏损股（pe<0）应被过滤（peMin=0）
        val loss = stock(code = "000002", totalMv = 30.0, pe = -5.0)
        assertEquals(1, Screening.applyFilters(listOf(small, loss), s).size)
    }

    @Test
    fun `涨停捕手过滤生效`() {
        val s = strategy("涨停捕手")
        // 涨幅 5-9.5 保留；涨幅 3%（不够强）与 10%（已涨停）过滤
        val strong = stock(code = "600001", changePct = 7.5, volumeRatio = 3.0, turnoverRate = 8.0, pe = 30.0, amount = 2e8)
        val weak = stock(code = "600002", changePct = 3.0, volumeRatio = 1.0, turnoverRate = 2.0, pe = 30.0, amount = 2e8)
        val limitUp = stock(code = "600003", changePct = 10.0, volumeRatio = 4.0, turnoverRate = 9.0, pe = 30.0, amount = 2e8)
        // 亏损股（pe=-2）过滤
        val loss = stock(code = "600004", changePct = 7.0, volumeRatio = 3.0, turnoverRate = 8.0, pe = -2.0, amount = 2e8)
        val kept = Screening.applyFilters(listOf(strong, weak, limitUp, loss), s)
        assertEquals(1, kept.size)
        assertEquals("600001", kept[0].code)
    }

    @Test
    fun `低价动量过滤生效`() {
        val s = strategy("低价动量")
        // 价格 3-15 保留；高价（>15）过滤
        val low = stock(code = "600010", price = 8.0, changePct = 3.0, volumeRatio = 1.5, amount = 1e8, change60d = 25.0)
        val high = stock(code = "600011", price = 30.0, changePct = 3.0, volumeRatio = 1.5, amount = 1e8, change60d = 25.0)
        val kept = Screening.applyFilters(listOf(low, high), s)
        assertEquals(1, kept.size)
        assertEquals("600010", kept[0].code)
    }

    @Test
    fun `多维量能过滤生效`() {
        val s = strategy("多维量能")
        // 低开（-5%~+1.5%）保留；高开（+3%）过滤
        val openLow = stock(code = "600020", changePct = -1.0, volumeRatio = 2.0, turnoverRate = 3.0, amount = 2e8)
        val openHigh = stock(code = "600021", changePct = 3.0, volumeRatio = 2.0, turnoverRate = 3.0, amount = 2e8)
        val kept = Screening.applyFilters(listOf(openLow, openHigh), s)
        assertEquals(1, kept.size)
        assertEquals("600020", kept[0].code)
    }

    @Test
    fun `新策略评分与权重分支正常且排序稳定`() {
        val names = listOf("小市值精选", "涨停捕手", "低价动量", "多维量能")
        val pool = listOf(
            stock("600001", changePct = 7.5, volumeRatio = 3.0, turnoverRate = 8.0, pe = 30.0, pb = 2.5, totalMv = 60.0, amount = 2e8, change60d = 40.0),
            stock("600002", changePct = 1.0, volumeRatio = 1.2, turnoverRate = 2.0, pe = 15.0, pb = 1.2, totalMv = 30.0, amount = 6e7, change60d = 5.0),
            stock("600003", changePct = -2.0, volumeRatio = 2.4, turnoverRate = 3.0, pe = 25.0, pb = 2.0, totalMv = 45.0, amount = 1.5e8, change60d = -8.0)
        )
        for (name in names) {
            val s = strategy(name)
            val filtered = Screening.applyFilters(pool, s)
            val scored = Screening.score(filtered, s)
            assertTrue("$name 评分未崩溃", scored.isNotEmpty())
            // 分数按降序
            for (i in 1 until scored.size) {
                assertTrue("$name 排序异常", scored[i - 1].second >= scored[i].second)
            }
        }
    }

    @Test
    fun `默认策略评分不回归`() {
        val s = strategy("双低选股")
        val pool = listOf(
            stock("600100", price = 8.0, pe = 10.0, pb = 1.0, totalMv = 100.0, amount = 1e8, changePct = 1.0),
            stock("600101", price = 50.0, pe = 60.0, pb = 6.0, totalMv = 500.0, amount = 2e8, changePct = 3.0)
        )
        val scored = Screening.score(Screening.applyFilters(pool, s), s)
        assertEquals(1, scored.size)
        assertEquals("600100", scored[0].first.code)
    }

    @Test
    fun `TickFlow symbol 转换正确`() {
        assertEquals("600519.SH", TickFlowApi.tickSymbol("600519"))
        assertEquals("000001.SZ", TickFlowApi.tickSymbol("000001"))
        assertEquals("300750.SZ", TickFlowApi.tickSymbol("300750"))
        assertEquals("688981.SH", TickFlowApi.tickSymbol("688981"))
        assertEquals("830799.BJ", TickFlowApi.tickSymbol("830799"))
        assertEquals("430047.BJ", TickFlowApi.tickSymbol("430047"))
    }

    @Test
    fun `TickFlow 粗筛忽略量比后完整过滤仍生效`() {
        val s = strategy("涨停捕手")
        // 粗筛（忽略量比）：涨幅达标即可进入候选
        val pool = listOf(
            stock("600001", changePct = 7.5, volumeRatio = 0.0, turnoverRate = 8.0, pe = 30.0, amount = 2e8),
            stock("600002", changePct = 7.0, volumeRatio = 3.0, turnoverRate = 8.0, pe = 30.0, amount = 2e8)
        )
        val pre = Screening.applyFilters(pool, s.copy(vrMin = null, vrMax = null))
        assertEquals(2, pre.size)
        // 完整过滤（补算量比后）：量比不足者剔除
        val final = Screening.applyFilters(pre, s)
        assertEquals(1, final.size)
        assertEquals("600002", final[0].code)
    }
}
