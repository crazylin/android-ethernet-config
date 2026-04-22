package com.example.ethernetconfig.repository

import androidx.lifecycle.LiveData
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.NetworkConfiguration
import com.example.ethernetconfig.model.NetworkStatus
import com.example.ethernetconfig.network.EthernetManagerWrapper
import com.example.ethernetconfig.network.NetworkMonitor
import com.example.ethernetconfig.storage.ConfigStorage

/**
 * 数据仓库，协调 EthernetManagerWrapper、NetworkMonitor 和 ConfigStorage 三个数据源。
 *
 * 负责获取当前网络配置、观察网络状态、读取持久化配置，
 * 以及应用新配置（含回滚和 DHCP 兜底逻辑）。
 *
 * @param ethernetManager 以太网管理器封装，用于读取和应用以太网配置
 * @param networkMonitor 网络状态监听器，用于观察以太网连接状态
 * @param configStorage 配置持久化存储，用于保存和加载网络配置
 */
class EthernetConfigRepository(
    private val ethernetManager: EthernetManagerWrapper,
    private val networkMonitor: NetworkMonitor,
    private val configStorage: ConfigStorage
) {

    /**
     * 获取当前以太网配置。
     *
     * 从 EthernetManagerWrapper 读取当前生效的配置。
     * 如果 EthernetManager 不可用或读取失败，返回默认的 DHCP 配置。
     *
     * @return 当前网络配置
     */
    fun getCurrentConfiguration(): NetworkConfiguration {
        return ethernetManager.getConfiguration()
            ?: NetworkConfiguration(mode = ConfigMode.DHCP)
    }

    /**
     * 观察以太网网络状态变化。
     *
     * 代理 NetworkMonitor 的状态流，返回可观察的 LiveData。
     *
     * @return LiveData<NetworkStatus> 可观察的网络状态
     */
    fun observeNetworkStatus(): LiveData<NetworkStatus> {
        return networkMonitor.startMonitoring()
    }

    /**
     * 获取持久化存储中保存的网络配置。
     *
     * 从 ConfigStorage 读取之前保存的配置。
     *
     * @return 已保存的网络配置，如果没有保存过配置则返回 null
     */
    fun getStoredConfiguration(): NetworkConfiguration? {
        return configStorage.loadConfiguration()
    }

    /**
     * 通过 root 读取指定接口的网络状态（不依赖 DHCP）。
     */
    fun getInterfaceStatusViaRoot(interfaceName: String): NetworkStatus? {
        return ethernetManager.getInterfaceStatusViaRoot(interfaceName)
    }

    /**
     * 获取所有可用的以太网接口名称列表。
     *
     * @return 以太网接口名称列表（如 ["eth0", "eth1"]）
     */
    fun getAvailableInterfaces(): List<String> {
        return ethernetManager.getAvailableInterfaces()
    }

    /**
     * 应用网络配置到以太网接口。
     *
     * 执行流程：
     * 1. 保存当前有效配置快照（用于失败回滚）
     * 2. 调用 EthernetManagerWrapper 应用新配置
     * 3. 成功时通过 ConfigStorage 持久化新配置
     * 4. 失败时使用快照恢复之前的配置
     * 5. 如果恢复也失败，切换到 DHCP 模式作为最终兜底方案
     *
     * @param config 要应用的网络配置
     * @return 成功返回 Result.success，失败返回包含异常信息的 Result.failure
     */
    fun applyConfiguration(config: NetworkConfiguration): Result<Unit> {
        // 1. 保存当前有效配置快照
        val snapshot = ethernetManager.getConfiguration()

        // 2. 应用新配置
        val applyResult = if (config.mode == ConfigMode.DHCP) {
            ethernetManager.setDhcpMode()
        } else {
            ethernetManager.setConfiguration(config)
        }

        // 3. 成功时持久化新配置
        if (applyResult.isSuccess) {
            configStorage.saveConfiguration(config)
            return Result.success(Unit)
        }

        // 4. 失败时使用快照回滚
        val applyError = applyResult.exceptionOrNull()
            ?: Exception("Configuration apply failed")

        if (snapshot != null) {
            val rollbackResult = if (snapshot.mode == ConfigMode.DHCP) {
                ethernetManager.setDhcpMode()
            } else {
                ethernetManager.setConfiguration(snapshot)
            }

            if (rollbackResult.isSuccess) {
                return Result.failure(applyError)
            }
        }

        // 5. 回滚也失败（或无快照），切换到 DHCP 模式兜底
        ethernetManager.setDhcpMode()

        return Result.failure(applyError)
    }
}
