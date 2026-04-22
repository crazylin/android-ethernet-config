package com.example.ethernetconfig.model

/**
 * 网络配置模式枚举。
 *
 * DHCP - 自动获取 IP 地址及相关网络参数
 * STATIC - 用户手动指定 IP 地址及相关网络参数
 */
enum class ConfigMode {
    DHCP,
    STATIC
}
