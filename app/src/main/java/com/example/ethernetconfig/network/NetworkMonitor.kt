package com.example.ethernetconfig.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.NetworkStatus
import java.net.Inet4Address

/**
 * 网络状态监听器，使用 ConnectivityManager 监听以太网连接状态变化。
 *
 * 通过 NetworkCapabilities.TRANSPORT_ETHERNET 过滤以太网事件，
 * 处理 onAvailable、onLost、onCapabilitiesChanged、onLinkPropertiesChanged 回调，
 * 并通过 LiveData 向上层提供响应式的网络状态更新。
 *
 * @param context 应用上下文，用于获取 ConnectivityManager
 */
class NetworkMonitor(private val context: Context) {

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkStatus = MutableLiveData(
        NetworkStatus(
            isConnected = false,
            currentMode = null,
            currentIpAddress = null,
            currentSubnetMask = null,
            currentGateway = null,
            currentDns = null
        )
    )

    private var currentNetwork: Network? = null
    private var isMonitoring = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            currentNetwork = network
            updateNetworkStatus(network)
        }

        override fun onLost(network: Network) {
            if (currentNetwork == network) {
                currentNetwork = null
                _networkStatus.postValue(
                    NetworkStatus(
                        isConnected = false,
                        currentMode = null,
                        currentIpAddress = null,
                        currentSubnetMask = null,
                        currentGateway = null,
                        currentDns = null
                    )
                )
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            if (currentNetwork == network) {
                updateNetworkStatus(network)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (currentNetwork == network) {
                updateNetworkStatusFromLinkProperties(linkProperties)
            }
        }
    }

    /**
     * 开始监听以太网网络状态变化。
     *
     * 使用 ConnectivityManager.registerNetworkCallback 注册回调，
     * 通过 TRANSPORT_ETHERNET 过滤仅监听以太网事件。
     *
     * @return LiveData<NetworkStatus> 可观察的网络状态
     */
    fun startMonitoring(): LiveData<NetworkStatus> {
        if (!isMonitoring) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isMonitoring = true
        }
        return _networkStatus
    }

    /**
     * 停止监听以太网网络状态变化，注销回调。
     */
    fun stopMonitoring() {
        if (isMonitoring) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isMonitoring = false
        }
    }

    /**
     * 获取当前以太网网络状态的快照。
     *
     * @return 当前的 NetworkStatus
     */
    fun getCurrentStatus(): NetworkStatus {
        return _networkStatus.value ?: NetworkStatus(
            isConnected = false,
            currentMode = null,
            currentIpAddress = null,
            currentSubnetMask = null,
            currentGateway = null,
            currentDns = null
        )
    }

    /**
     * 根据当前网络更新网络状态。从 ConnectivityManager 获取 LinkProperties
     * 并解析 IP 地址、子网掩码、网关和 DNS 信息。
     */
    private fun updateNetworkStatus(network: Network) {
        val linkProperties = connectivityManager.getLinkProperties(network)
        if (linkProperties != null) {
            updateNetworkStatusFromLinkProperties(linkProperties)
        } else {
            _networkStatus.postValue(
                NetworkStatus(
                    isConnected = true,
                    currentMode = null,
                    currentIpAddress = null,
                    currentSubnetMask = null,
                    currentGateway = null,
                    currentDns = null
                )
            )
        }
    }

    /**
     * 从 LinkProperties 解析网络参数并更新 NetworkStatus。
     *
     * 解析内容包括：
     * - IPv4 地址和前缀长度（转换为子网掩码）
     * - 网关地址（取第一个 IPv4 网关）
     * - DNS 服务器列表（仅 IPv4）
     * - 配置模式推断（有 LinkAddress 视为已配置，通过 DHCP 服务器信息判断模式）
     */
    private fun updateNetworkStatusFromLinkProperties(linkProperties: LinkProperties) {
        val ipv4Address = linkProperties.linkAddresses.firstOrNull { linkAddress ->
            linkAddress.address is Inet4Address
        }

        val ipAddress = ipv4Address?.address?.hostAddress
        val prefixLength = ipv4Address?.prefixLength ?: 0
        val subnetMask = prefixLengthToSubnetMask(prefixLength)

        val gateway = linkProperties.routes
            .filter { route -> route.gateway is Inet4Address }
            .firstOrNull { route -> route.isDefaultRoute }
            ?.gateway?.hostAddress

        val dnsServers = linkProperties.dnsServers
            .filterIsInstance<Inet4Address>()
            .mapNotNull { dns -> dns.hostAddress }

        val configMode = inferConfigMode(linkProperties)

        _networkStatus.postValue(
            NetworkStatus(
                isConnected = true,
                currentMode = configMode,
                currentIpAddress = ipAddress,
                currentSubnetMask = subnetMask,
                currentGateway = gateway,
                currentDns = dnsServers.ifEmpty { null },
                interfaceName = linkProperties.interfaceName
            )
        )
    }

    /**
     * 推断当前网络配置模式。
     *
     * 在 API 30+ 上通过检查 LinkProperties 中的 DHCP 服务器地址来判断：
     * 如果存在 DHCP 服务器地址，则为 DHCP 模式；否则为 STATIC 模式。
     * 在 API 30 以下，无法可靠区分模式，返回 null。
     * 如果没有 IPv4 地址信息，返回 null。
     */
    private fun inferConfigMode(linkProperties: LinkProperties): ConfigMode? {
        val hasIpv4 = linkProperties.linkAddresses.any { it.address is Inet4Address }
        if (!hasIpv4) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dhcpServer = linkProperties.dhcpServerAddress
            return if (dhcpServer != null) ConfigMode.DHCP else ConfigMode.STATIC
        }

        // API < 30: dhcpServerAddress 不可用，无法可靠推断模式
        return null
    }

    /**
     * 将前缀长度转换为点分十进制子网掩码。
     *
     * 例如：24 -> "255.255.255.0"，16 -> "255.255.0.0"
     *
     * @param prefixLength 前缀长度（0-32）
     * @return 点分十进制子网掩码字符串
     */
    private fun prefixLengthToSubnetMask(prefixLength: Int): String {
        val mask = if (prefixLength == 0) {
            0L
        } else {
            (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        }
        return "${(mask shr 24) and 0xFF}.${(mask shr 16) and 0xFF}.${(mask shr 8) and 0xFF}.${mask and 0xFF}"
    }
}
