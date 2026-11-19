package com.dsa.mobile

/**
 * v2.12.36 网络 / 解析错误分级（F2）。
 * 把原来笼统的 RuntimeException 拆成可识别类别，便于编排层区分提示与降级。
 */
enum class ErrKind {
    /** 网络不可达 / 超时 / DNS 失败 */
    NETWORK,
    /** 限流 / WAF 拦截（429、403 等） */
    RATE_LIMIT,
    /** 服务端错误（5xx、非 2xx） */
    SERVER,
    /** 返回结构变更 / 字段解析失败 */
    PARSE,
    /** 接口成功但无数据 */
    EMPTY
}

class NetException(
    val kind: ErrKind,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    /** 面向用户的简短中文提示 */
    fun display(): String = when (kind) {
        ErrKind.NETWORK -> "网络不可用或超时，请检查连接后重试"
        ErrKind.RATE_LIMIT -> "接口限流或被拦截，请稍后再试"
        ErrKind.SERVER -> "服务端返回异常，请稍后再试"
        ErrKind.PARSE -> "数据结构变更，解析失败"
        ErrKind.EMPTY -> "暂无符合条件的数据"
    }
}
