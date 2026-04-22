package com.example.ethernetconfig.model

/**
 * 网络配置数据类，包含完整的网络参数配置信息。
 *
 * @param mode 配置模式（DHCP 或 STATIC）
 * @param ipAddress IP 地址（IPv4 格式，如 192.168.1.100）
 * @param subnetMask 子网掩码（如 255.255.255.0）
 * @param gateway 网关地址
 * @param primaryDns 主 DNS 服务器地址
 * @param secondaryDns 备用 DNS 服务器地址
 */
data class NetworkConfiguration(
    val mode: ConfigMode,
    val ipAddress: String = "",
    val subnetMask: String = "",
    val gateway: String = "",
    val primaryDns: String = "",
    val secondaryDns: String = "",
    val interfaceName: String = "eth0"
)
