package com.example.ethernetconfig.model

/**
 * 配置应用结果密封类，表示配置操作的三种可能状态。
 *
 * Success - 配置应用成功
 * Failure - 配置应用失败，包含失败原因
 * Loading - 配置正在应用中
 */
sealed class ConfigResult {
    object Success : ConfigResult()
    data class Failure(val reason: String) : ConfigResult()
    object Loading : ConfigResult()
}
