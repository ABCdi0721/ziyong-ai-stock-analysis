# 自用-ai选股分析

全都都由ai完成

一款本地运行的 A 股 AI 选股与个股分析 Android 应用（包名 `com.dsa.mobile`）。手机端配置自己的 OpenAI 兼容模型接口即可使用，无需本地 Python 环境；选股、评分、回测、封板概率等计算均在 App 内完成。

> ⚠️ **免责声明**：App 内的评分、信号、涨停概率、回测结果等均为本地统计与规则模型的参考输出，**不构成任何投资建议**。股市有风险，决策需自行判断。

## 功能特性

### 自选与大盘
- **自选股**：个股详情页右上角一键加自选，自选列表就近查看。
- **大盘页**：指数实时行情与日 K，三维一体蓝卡、道氏相互验证、123 法则三盏灯、行业板块强弱聚合（红绿强弱条）；指数日 K 按 东财 → 腾讯 → 新浪 三级降级。

### 选股策略
内置 18 套选股因子策略，另支持 App 内自定义保存策略；信号筛选、板块筛选（含仅沪深主板）、策略长按自定义排序，选股结果可筛选、可存入历史选股。

- 短线·今日买入
- 热股榜·短线
- 新闻利好选股
- 双低选股
- 均衡多因子
- 蓝筹收益质量
- 资金热度
- 低波质量
- 趋势质量
- 超跌反转
- 稳健价值
- 缩量回踩
- 放量突破
- 小市值精选
- 涨停捕手
- 低价动量
- 多维量能
- 中小市值·热门题材

### 个股详情
- 日 K / 周 K / 月 K 线，快照技术形态评分与操作信号（趋势 / 乖离 / 量能 / MACD / RSI）。
- 龙虎榜排名、热股榜排名、异动提醒卡片。
- **封板率预测**：本地计算次日涨停（封板）概率、封住概率与逐项因子明细。
- **A 股本地回测**：MA 金叉 / MACD 金叉 / RSI 超卖反弹 / 买入持有，基于日 K 本地算收益、胜率、最大回撤与逐笔交易。
- **新闻分析**：检索近期新闻并做关键词情绪打分；配置模型后生成情绪 / 核心事件 / 利好 / 利空 / 操作参考报告。
- **多智能体分析**：九角色全链路协作分析；**AI 分析报告**生成后自动存入历史报告。
- 「查看该股的历史分析报告」入口，按当前股票代码固定筛选历史记录。

### AI 与数据源
- 多套 OpenAI 兼容模型配置，可手动切换当前使用；Token 节省模式，报告首行显示真实输入 / 输出 / 缓存命中用量。
- 新闻检索优先源可选；API Key 输入框圆点加密、支持眼睛切换明文。
- 数据源：腾讯免费源优先，东财、新浪兜底，Tushare / TickFlow 可选填 Key；全源按序失败自动降级轮换。

### 主题外观
- 6 套预设主题色 + 自定义主色，浅色 / 深色 / 跟随系统；自定义背景图可调透明度与模糊。
- 手势切页动画样式可选；底部液态玻璃胶囊导航；右下角悬浮吉祥物，支持拖拽、缩放，拖动位置自动保存并在下次进入时沿用。

### 历史与备份
- **历史报告**：同一只股票每次分析独立留存、标注具体时间，支持时间范围（含「超过 15 天」）、关键词、股票代码筛选与评分 / 信号 / 时间排序。
- **备份与恢复**：本地全量备份 / 恢复，包含已添加的自选股票、历史分析报告与历史选股；支持 WebDAV 联网备份 / 恢复。

## 技术栈

- Kotlin 2.2.21，AGP 8.7.3，Jetpack Compose 2.2.21 编译器插件
- minSdk 26 / targetSdk 34 / compileSdk 35，Java 17，ViewBinding + Compose
- AndroidX、Material Components、RecyclerView、ViewPager2、ConstraintLayout
- HTTP：OkHttp 4.12.0
- 单元测试：JUnit 4.13.2

## 开源依赖与引用仓库

| 项目 | 坐标 / 仓库 | 用途 | 协议 |
| --- | --- | --- | --- |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | `io.github.kyant0:backdrop:1.0.0` | Compose Multiplatform 液态玻璃原语（blur / lens / vibrancy） | Apache-2.0 |
| [square/okhttp](https://github.com/square/okhttp) | `com.squareup.okhttp3:okhttp:4.12.0` | HTTP 客户端 | Apache-2.0 |
| [AndroidX](https://developer.android.com/jetpack/androidx) | core-ktx / appcompat / recyclerview / viewpager2 / constraintlayout | 基础 UI 与组件库 | Apache-2.0 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | ui / foundation 1.9.4，activity-compose 1.10.1 | 声明式 UI | Apache-2.0 |
| [Material Components for Android](https://github.com/material-components/material-components-android) | material 1.12.0 | Material Design 组件 | Apache-2.0 |
| JUnit | `junit:junit:4.13.2` | 单元测试 | EPL-1.0 / BSD-3-Clause |

### 参考与移植来源（非代码依赖）

- 个股分析器、技术指标口径、选股评分逻辑移植自 Python 项目 `daily_stock_analysis`（`stock_analyzer.py`、`decision_scale.py`、`screening/filter.py`、`scorer.py`、指标计算模块）。
- 部分选股策略思路参考开源量化策略集合 `ashare-quant-strategies`：#034 多维量能、#048 小市值精选、#049 低价动量、#112 涨停捕手。
- 大盘数据源的限流与三级降级策略参考上游 PR#36。
- Material Design 3 主题风格参考 `legado-with-MD3` 的 tonal container 色板与 NavigationBar 胶囊高亮。

## 目录结构

```
ziyong-ai-stock-analysis/
├── app/
│   └── src/main/
│       ├── java/com/dsa/mobile/   # 业务代码（Activity / 数据层 / 策略 / 玻璃组件）
│       │   ├── glass/             # Compose 液态玻璃复用组件
│       │   └── ui/
│       ├── res/                   # 布局、图标、主题色、动画、吉祥物与音效资源
│       └── assets/sound/          # 提示音效
├── build.gradle                   # 根构建脚本
├── settings.gradle
├── gradle.properties
└── 使用说明.md                    # 版本迭代记录与使用说明
```

## 构建说明

- 环境：JDK 17、Android SDK 35、Gradle 8.9（工程未自带 wrapper，使用本机安装的 Gradle）。
- 构建 debug：`gradle assembleDebug`；构建 release：`gradle assembleRelease`。
- release 签名库 `dsa-release.jks` 已在 `.gitignore` 中排除、不进入远端仓库；本地发布构建需自备签名配置，签名口令不要写入公开仓库。

## 已知口径与限制

- 热股榜受公开数据源开放条数限制，覆盖范围为多榜合并去重后的上榜股票，并非全市场约 5000 只排名。
- 背景图真实高斯模糊仅 Android 12+ 生效；边缘透镜折射与彩虹色散仅 Android 13+，低版本回退为半透明底色 + 渐变描边。
- WebDAV 联网备份 / 恢复需填入自己的标准 WebDAV 服务账号与授权码；空态使用中性提示，不暴露失败 / 报错字样。

## 版本

当前版本：**2.12.22**（versionCode 56）。完整版本迭代记录见 [使用说明.md](使用说明.md)。
