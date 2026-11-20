package com.dsa.mobile

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.dsa.mobile.databinding.ActivitySettingsBinding

/**
 * 数据源 & AI 配置（v2.5.0：所有修改自动实时保存，无需点保存按钮）
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // 下拉选项（保存索引）
    private val adjModes = listOf("none", "qfq", "hfq")
    private val sources = listOf("自动（东财失败切Tushare）", "东方财富", "Tushare", "TickFlow", "腾讯（免费）", "MiaoXiang", "Futu")
    private val dayOptions = listOf("1", "3", "7", "10")
    private val futuPrio = listOf("futu", "longbridge", "akshare", "yfinance")
    // v2.9.4 新闻检索优先源（顺序与 Prefs.newsProvider 的取值一一对应）
    private val newsProviderLabels = listOf(
        "自动（Bocha优先，逐源兜底）", "Bocha", "Tavily", "SerpAPI", "Brave", "SearXNG"
    )
    private val newsProviderValues = listOf("auto", "bocha", "tavily", "serpapi", "brave", "searxng")
    // v2.9.5 Token 节省模式（顺序与 Prefs.tokenMode 取值一一对应）
    private val tokenModeLabels = listOf(
        "标准（共享前缀，缓存命中率高）", "省流（新闻与报告再压缩，最省 token）"
    )
    private val tokenModeValues = listOf("standard", "lean")

    /** v2.12.0 主流 AI 服务商预设（只填接口地址与模型名，Key 由用户自己填） */
    private data class LlmPreset(val label: String, val baseUrl: String, val model: String)

    private val llmPresets = listOf(
        LlmPreset("手动填写（不自动填充）", "", ""),
        LlmPreset("DeepSeek（deepseek-chat）", "https://api.deepseek.com", "deepseek-chat"),
        LlmPreset("OpenAI GPT（gpt-4o-mini）", "https://api.openai.com", "gpt-4o-mini"),
        LlmPreset("豆包 / 火山方舟（接入点 ID）", "https://ark.cn-beijing.volces.com/api/v3", ""),
        LlmPreset("Kimi / Moonshot", "https://api.moonshot.cn", "moonshot-v1-8k"),
        LlmPreset("通义千问 DashScope", "https://dashscope.aliyuncs.com/compatible-mode", "qwen-plus"),
        LlmPreset("智谱 GLM-4", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        LlmPreset("硅基流动 SiliconFlow", "https://api.siliconflow.cn", "deepseek-v3"),
        LlmPreset("MiniMax", "https://api.minimax.chat", "abab6.5s-chat")
    )
    private var suppressPreset = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "数据源 & AI 配置"

        // ---- 回填当前值 ----
        binding.etTushareToken.setText(Prefs.tushareToken(this))
        binding.etTickflowKey.setText(Prefs.tickflowKey(this))
        binding.etMxApiKey.setText(Prefs.mxApiKey(this))
        binding.etFutuHost.setText(Prefs.futuHost(this))
        binding.etZhituToken.setText(Prefs.zhituToken(this))
        binding.swRemoteIndex.isChecked = Prefs.remoteIndexEnabled(this)

        binding.spTickflowAdj.adapter = ArrayAdapter(this, R.layout.spinner_item, adjModes)
        binding.spTickflowAdj.setSelection(adjModes.indexOf(Prefs.tickflowAdj(this)).coerceAtLeast(0))

        binding.swTickflowPrefetch.isChecked = Prefs.tickflowBatchPrefetch(this)
        binding.etTickflowBatch.setText(Prefs.tickflowBatchSize(this).toString())

        binding.etLlmTemperature.setText(fmtTemp(Prefs.llmTemperature(this)))

        binding.spScreenSource.adapter = ArrayAdapter(this, R.layout.spinner_item, sources)
        binding.spScreenSource.setSelection(
            when (Prefs.screenSource(this)) {
                "eastmoney" -> 1; "tushare" -> 2; "tickflow" -> 3; "tencent" -> 4; "miaoxiang" -> 5; "futu" -> 6
                else -> 0
            }
        )

        binding.spNewsDays.adapter = ArrayAdapter(this, R.layout.spinner_item, dayOptions)
        binding.spNewsDays.setSelection(dayOptions.indexOf(Prefs.newsDays(this).toString()).coerceAtLeast(0))

        binding.spNewsProvider.adapter = ArrayAdapter(this, R.layout.spinner_item, newsProviderLabels)
        binding.spNewsProvider.setSelection(
            newsProviderValues.indexOf(Prefs.newsProvider(this)).coerceAtLeast(0)
        )

        binding.spTokenMode.adapter = ArrayAdapter(this, R.layout.spinner_item, tokenModeLabels)
        binding.spTokenMode.setSelection(
            tokenModeValues.indexOf(Prefs.tokenMode(this)).coerceAtLeast(0)
        )

        binding.spFutuPriority.adapter = ArrayAdapter(this, R.layout.spinner_item, futuPrio)
        binding.spFutuPriority.setSelection(futuPrio.indexOf(Prefs.futuPriority(this)).coerceAtLeast(0))

        binding.swChipEnabled.isChecked = Prefs.chipEnabled(this)
        binding.etRiskWeight.setText(fmtTemp(Prefs.riskWeight(this).toDouble()))
        binding.etCostWeight.setText(fmtTemp(Prefs.costWeight(this).toDouble()))
        binding.etShortFactorW.setText(fmtTemp(Prefs.shortFactorWeight(this).toDouble()))
        binding.etNewsBocha.setText(Prefs.bochaKeys(this))
        binding.etNewsTavily.setText(Prefs.tavilyKeys(this))
        binding.etNewsSerpapi.setText(Prefs.serpapiKeys(this))
        binding.etNewsBrave.setText(Prefs.braveKeys(this))
        binding.etNewsMinimax.setText(Prefs.minimaxKeys(this))
        binding.etNewsSearxng.setText(Prefs.searxngUrls(this))

        // ---- 实时保存：文本变化即落盘 ----
        listOf(
            binding.etTushareToken, binding.etTickflowKey, binding.etMxApiKey, binding.etFutuHost,
            binding.etTickflowBatch,
            binding.etLlmBaseUrl, binding.etLlmKey, binding.etLlmModel, binding.etLlmFallback,
            binding.etLlmTemperature,
            binding.etNewsBocha, binding.etNewsTavily, binding.etNewsSerpapi,
            binding.etNewsBrave, binding.etNewsMinimax, binding.etNewsSearxng,
            binding.etRiskWeight, binding.etCostWeight, binding.etShortFactorW
        ).forEach { et ->
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = persistQuietly(et)
            })
        }

        // ---- v2.12.8 大盘页自动刷新（未到时间不联网更新，只保留上次数据） ----
        val refreshRb = when (Prefs.marketRefreshMode(this)) {
            "30" -> binding.rbMarket30
            "60" -> binding.rbMarket60
            "120" -> binding.rbMarket120
            "180" -> binding.rbMarket180
            "never" -> binding.rbMarketNever
            else -> binding.rbMarketEntry
        }
        refreshRb.isChecked = true
        binding.rgMarketRefresh.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_market_30 -> "30"
                R.id.rb_market_60 -> "60"
                R.id.rb_market_120 -> "120"
                R.id.rb_market_180 -> "180"
                R.id.rb_market_never -> "never"
                else -> "entry"
            }
            Prefs.setMarketRefreshMode(this, mode)
        }

        // ---- 实时保存：开关 / 下拉 ----
        binding.swRemoteIndex.setOnCheckedChangeListener { _, _ -> persistQuietly(null) }
        binding.swTickflowPrefetch.setOnCheckedChangeListener { _, _ -> persistQuietly(null) }
        binding.swChipEnabled.setOnCheckedChangeListener { _, _ -> persistQuietly(null) }
        binding.spTickflowAdj.onItemSelectedListener = simpleSelected { persistQuietly(null) }
        binding.spScreenSource.onItemSelectedListener = simpleSelected { persistQuietly(null) }
        binding.spNewsDays.onItemSelectedListener = simpleSelected { persistQuietly(null) }
        binding.spNewsProvider.onItemSelectedListener = simpleSelected {
            val idx = binding.spNewsProvider.selectedItemPosition
            if (idx in newsProviderValues.indices) {
                Prefs.saveNewsProvider(this@SettingsActivity, newsProviderValues[idx])
            }
        }
        binding.spTokenMode.onItemSelectedListener = simpleSelected {
            val idx = binding.spTokenMode.selectedItemPosition
            if (idx in tokenModeValues.indices) {
                Prefs.saveTokenMode(this@SettingsActivity, tokenModeValues[idx])
            }
        }
        binding.spFutuPriority.onItemSelectedListener = simpleSelected { persistQuietly(null) }

        // ---- v2.8.2 多 AI 模型配置：下拉切换 = 手动选择当前使用哪一套 ----
        binding.spLlmProfile.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long
                ) {
                    if (suppressLlmRefresh) return
                    val profiles = Prefs.llmProfiles(this@SettingsActivity)
                    if (profiles.isEmpty()) return
                    pendingNew = false
                    Prefs.saveLlmProfiles(this@SettingsActivity, profiles, pos)
                    fillLlmInputs(profiles[pos])
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }

        // ---- 按钮：保存（覆盖当前配置）/ 新建空白配置 / 删除当前 ----
        binding.btnLlmAdd.setOnClickListener { onLlmSave() }
        binding.btnLlmNew.setOnClickListener { onLlmNew() }
        binding.btnLlmDelete.setOnClickListener { onLlmDelete() }
        binding.btnLlmTest.setOnClickListener { onLlmTest() }

        // ---- 预设服务商：选择后只填接口地址与模型名 ----
        binding.spLlmPreset.adapter =
            ArrayAdapter(this, R.layout.spinner_item, llmPresets.map { it.label })
        suppressPreset = true
        binding.spLlmPreset.setSelection(0)
        binding.spLlmPreset.onItemSelectedListener = simpleSelected {
            if (!suppressPreset) onPresetSelected()
        }
        binding.spLlmPreset.post { suppressPreset = false }

        // 最后统一回填 LLM 输入框并选中当前配置
        refreshLlmProfileSpinner()
        lastProfileSignature = profileSignature()
    }

    private var suppressLlmRefresh = false
    private var lastProfileSignature = ""

    /** 点「新建」清空表单后置 true：此时点「保存」新建一套；否则「保存」覆盖当前选中配置 */
    private var pendingNew = false

    /** 配置集合 + 当前选中下标的指纹，用于判断是否需要在返回页面时刷新 */
    private fun profileSignature(): String =
        Prefs.llmProfileNames(this).joinToString("|") + "#" + Prefs.llmActiveIndex(this)

    override fun onResume() {
        super.onResume()
        // 详情页可能切换过当前使用的配置，回到设置页时同步（输入中途离开不会覆盖已填内容）
        val sig = profileSignature()
        if (sig != lastProfileSignature) {
            lastProfileSignature = sig
            refreshLlmProfileSpinner()
        }
    }

    /**
     * Temperature 显示格式：最多两位小数并去掉尾零。
     * 兼容历史用 Float 存储留下的 0.699999988079071 这类长尾。
     */
    private fun fmtTemp(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        if (r == r.toLong().toDouble()) return r.toLong().toString()
        return String.format(java.util.Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
    }

    /** 回填 LLM 输入框（初始化 / 切换 / 新增 / 删除后） */
    private fun fillLlmInputs(p: Prefs.LlmProfile) {
        binding.etLlmName.setText(p.name)
        binding.etLlmBaseUrl.setText(p.baseUrl)
        binding.etLlmKey.setText(p.apiKey)
        binding.etLlmModel.setText(p.model)
        binding.etLlmFallback.setText(p.fallback)
        binding.etLlmTemperature.setText(fmtTemp(p.temperature))
    }

    /** 重建下拉列表并选中当前配置 */
    private fun refreshLlmProfileSpinner() {
        suppressLlmRefresh = true
        val names = Prefs.llmProfileNames(this)
        binding.spLlmProfile.adapter = ArrayAdapter(this, R.layout.spinner_item, names)
        binding.spLlmProfile.setSelection(Prefs.llmActiveIndex(this))
        suppressLlmRefresh = false
        val active = Prefs.llmProfiles(this).getOrNull(Prefs.llmActiveIndex(this))
        if (active != null) fillLlmInputs(active)
    }

    /** 读取输入框并校验必填，通过返回 true */
    private fun llmInputsValid(): Boolean {
        val base = binding.etLlmBaseUrl.text.toString().trim()
        val key = binding.etLlmKey.text.toString().trim()
        val model = binding.etLlmModel.text.toString().trim()
        if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
            android.widget.Toast.makeText(
                this, "请先填写 API 地址 / API Key / 主模型名", android.widget.Toast.LENGTH_SHORT
            ).show()
            return false
        }
        return true
    }

    private fun llmInputTemp(): Double =
        binding.etLlmTemperature.text.toString().toDoubleOrNull()?.takeIf { it in 0.0..2.0 } ?: 0.7

    /**
     * 保存：默认覆盖更新下拉当前选中的那套配置；
     * 若刚点过「新建」（pendingNew），则把表单内容新建为一套配置并切换。
     */
    private fun onLlmSave() {
        if (!llmInputsValid()) return
        val name = binding.etLlmName.text.toString()
        val base = binding.etLlmBaseUrl.text.toString()
        val key = binding.etLlmKey.text.toString()
        val model = binding.etLlmModel.text.toString()
        val fallback = binding.etLlmFallback.text.toString()
        val temp = llmInputTemp()
        if (pendingNew) {
            Prefs.addLlmProfile(this, name, base, key, model, fallback, temp)
            pendingNew = false
            refreshLlmProfileSpinner()
            android.widget.Toast.makeText(this, "已新建配置并切换使用", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val idx = Prefs.llmActiveIndex(this)
            Prefs.updateLlmProfile(this, idx, name, base, key, model, fallback, temp)
            refreshLlmProfileSpinner()
            android.widget.Toast.makeText(this, "已覆盖保存当前配置", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 新建：清空全部输入框进入空白填写表单，置待新建状态；填写后点「保存」才新增一套。
     * 直接改当前表单再点「保存」则覆盖更新当前配置，不会产生重复配置。
     */
    private fun onLlmNew() {
        pendingNew = true
        binding.etLlmName.setText("")
        binding.etLlmBaseUrl.setText("")
        binding.etLlmKey.setText("")
        binding.etLlmModel.setText("")
        binding.etLlmFallback.setText("")
        binding.etLlmTemperature.setText("0.7")
        binding.spLlmPreset.setSelection(0)
        binding.etLlmName.requestFocus()
        android.widget.Toast.makeText(
            this, "已打开空白配置，填写后点「保存」新建一套", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /** 预设服务商：填入接口地址与模型名，不改动已填的 Key */
    private fun onPresetSelected() {
        val idx = binding.spLlmPreset.selectedItemPosition
        val p = llmPresets.getOrNull(idx) ?: return
        if (p.baseUrl.isBlank()) return
        binding.etLlmBaseUrl.setText(p.baseUrl)
        if (p.model.isNotBlank()) {
            binding.etLlmModel.setText(p.model)
            android.widget.Toast.makeText(
                this, "已填入 ${p.label} 的接口地址与模型名", android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            binding.etLlmModel.setText("")
            binding.etLlmModel.requestFocus()
            android.widget.Toast.makeText(
                this, "已填入火山方舟接口地址，模型名请填推理接入点 ID（ep-xxxx）",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 最少 token 的连通性测试：max_tokens=8 + 单轮 ping，不写入配置 */
    private fun onLlmTest() {
        val base = binding.etLlmBaseUrl.text.toString().trim()
        val key = binding.etLlmKey.text.toString().trim()
        val model = binding.etLlmModel.text.toString().trim()
        if (base.isBlank() || key.isBlank() || model.isBlank()) {
            android.widget.Toast.makeText(
                this, "请先填写 API 地址 / API Key / 主模型名", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val btn = binding.btnLlmTest
        btn.isEnabled = false
        btn.text = "测试中…"
        binding.tvLlmTestResult.text = "正在用最少 token 测试：$model"

        Thread {
            val items = try {
                LlmApi.pingAll(base, key, model, 0.0)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "测试连接（最少 token 验证是否接通）"
                if (items.isEmpty()) {
                    binding.tvLlmTestResult.text = "未填写模型名，无法测试"
                    return@runOnUiThread
                }
                val okCount = items.count { it.ok }
                val sb = StringBuilder()
                sb.append(
                    if (okCount == items.size) "✅ 全部接通（$okCount/${items.size}）"
                    else "⚠️ 接通 $okCount/${items.size}"
                )
                items.forEach { r ->
                    sb.append('\n')
                    if (r.ok) {
                        sb.append("· ${r.model}：接口已连通，耗时 ${r.latencyMs} ms")
                        if (r.tokens >= 0) sb.append("，本次用量 ${r.tokens} tokens")
                    } else {
                        sb.append("· ${r.model}：${r.message}")
                    }
                }
                sb.append("\n\n测试用 max_tokens=8 + 单轮 ping，单次约 20-30 tokens。")
                binding.tvLlmTestResult.text = sb.toString()
                binding.tvLlmTestResult.setTextColor(
                    if (okCount == items.size) 0xFF2E7D32.toInt() else 0xFFE8710A.toInt()
                )
            }
        }.start()
    }

    /** 删除当前选中配置（至少保留一套） */
    private fun onLlmDelete() {
        val profiles = Prefs.llmProfiles(this)
        if (profiles.size <= 1) {
            android.widget.Toast.makeText(this, "至少保留一套配置", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val idx = binding.spLlmProfile.selectedItemPosition
        val name = profiles[idx].name
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除当前配置")
            .setMessage("确定删除「$name」？删除后将从当前使用列表中移除。")
            .setPositiveButton("删除") { _, _ ->
                val newIdx = Prefs.deleteLlmProfile(this, idx)
                if (newIdx >= 0) {
                    refreshLlmProfileSpinner()
                    android.widget.Toast.makeText(this, "已删除", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Spinner 监听器包装 */
    private fun simpleSelected(onSel: () -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = onSel()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

    /**
     * 静默实时保存：Temperature/批量大小等数值在输入中间态（未解析成功）时
     * 保持已存值不写入，避免输入过程闪变；其余字段正常落盘。
     */
    private fun persistQuietly(changed: EditText?) {
        try {
            persist()
        } catch (e: Exception) {
            // 保存失败静默，不打断输入
        }
    }

    private fun persist() {
        // 数值字段保护：解析失败/越界时保持 Prefs 旧值
        val temp = binding.etLlmTemperature.text.toString().toDoubleOrNull()
            ?.takeIf { it in 0.0..2.0 } ?: Prefs.llmTemperature(this)
        val batch = binding.etTickflowBatch.text.toString().toIntOrNull()
            ?.takeIf { it > 0 } ?: Prefs.tickflowBatchSize(this)

        // v2.12.36：选股权重，解析失败/越界保持旧值
        val riskW = binding.etRiskWeight.text.toString().toFloatOrNull()
            ?.takeIf { it in 0f..0.6f } ?: Prefs.riskWeight(this)
        val costW = binding.etCostWeight.text.toString().toFloatOrNull()
            ?.takeIf { it in 0f..0.4f } ?: Prefs.costWeight(this)
        val shortW = binding.etShortFactorW.text.toString().toFloatOrNull()
            ?.takeIf { it in 0f..1f } ?: Prefs.shortFactorWeight(this)
        Prefs.saveWeights(this, riskW, costW, shortW)

        Prefs.saveSettings(
            this,
            binding.etTushareToken.text.toString(),
            binding.etLlmBaseUrl.text.toString(),
            binding.etLlmKey.text.toString(),
            binding.etLlmModel.text.toString(),
            temp,
            binding.etLlmFallback.text.toString(),
            binding.etTickflowKey.text.toString(),
            binding.etMxApiKey.text.toString(),
            binding.etFutuHost.text.toString(),
            binding.etZhituToken.text.toString(),
            binding.swRemoteIndex.isChecked,
            adjModes[binding.spTickflowAdj.selectedItemPosition],
            binding.swTickflowPrefetch.isChecked,
            batch
        )
        Prefs.saveExtendedSettings(
            this,
            binding.etNewsTavily.text.toString(),
            binding.etNewsSerpapi.text.toString(),
            binding.etNewsBrave.text.toString(),
            binding.etNewsBocha.text.toString(),
            binding.etNewsMinimax.text.toString(),
            binding.etNewsSearxng.text.toString(),
            dayOptions[binding.spNewsDays.selectedItemPosition].toInt(),
            when (binding.spScreenSource.selectedItemPosition) {
                1 -> "eastmoney"; 2 -> "tushare"; 3 -> "tickflow"; 4 -> "tencent"; 5 -> "miaoxiang"; 6 -> "futu"
                else -> "auto"
            },
            futuPrio[binding.spFutuPriority.selectedItemPosition],
            binding.swChipEnabled.isChecked
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
