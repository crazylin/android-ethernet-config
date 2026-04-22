package com.example.ethernetconfig.model

/**
 * 网络状态数据类，表示当前以太网接口的连接状态和网络参数。
 *
 * @param isConnected 以太网接口是否已连接
 * @param currentMode 当前使用的网络模式（DHCP 或 STATIC），未连接时为 null
 * @param currentIpAddress 当前生效的 IP 地址，未连接时为 null
 * @param currentSubnetMask 当前生效的子网掩码，未连接时为 null
 * @param currentGateway 当前生效的网关地址，未连接时为 null
 * @param currentDns 当前生效的 DNS 服务器地址列表，未连接时为 null
 */
data class NetworkStatus(
    val isConnected: Boolean,
    val currentMode: ConfigMode?,
    val currentIpAddress: String?,
    val currentSubnetMask: String?,
    val currentGateway: String?,
    val currentDns: List<String>?,
    val interfaceName: String? = null
)
