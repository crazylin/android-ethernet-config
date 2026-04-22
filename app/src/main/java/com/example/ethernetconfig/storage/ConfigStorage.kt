package com.example.ethernetconfig.storage

import android.content.Context
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.NetworkConfiguration

/**
 * 配置持久化存储，使用 SharedPreferences 保存和加载网络配置。
 *
 * @param context Android 上下文，用于获取 SharedPreferences 实例
 */
class ConfigStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 将网络配置持久化到 SharedPreferences。
     *
     * @param config 要保存的网络配置
     */
    fun saveConfiguration(config: NetworkConfiguration) {
        prefs.edit()
            .putString(KEY_CONFIG_MODE, config.mode.name)
            .putString(KEY_IP_ADDRESS, config.ipAddress)
            .putString(KEY_SUBNET_MASK, config.subnetMask)
            .putString(KEY_GATEWAY, config.gateway)
            .putString(KEY_PRIMARY_DNS, config.primaryDns)
            .putString(KEY_SECONDARY_DNS, config.secondaryDns)
            .putString(KEY_INTERFACE_NAME, config.interfaceName)
            .apply()
    }

    /**
     * 从 SharedPreferences 加载已保存的网络配置。
     *
     * @return 已保存的网络配置，如果没有保存过配置则返回 null
     */
    fun loadConfiguration(): NetworkConfiguration? {
        val modeName = prefs.getString(KEY_CONFIG_MODE, null) ?: return null
        val mode = try {
            ConfigMode.valueOf(modeName)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return NetworkConfiguration(
            mode = mode,
            ipAddress = prefs.getString(KEY_IP_ADDRESS, "") ?: "",
            subnetMask = prefs.getString(KEY_SUBNET_MASK, "") ?: "",
            gateway = prefs.getString(KEY_GATEWAY, "") ?: "",
            primaryDns = prefs.getString(KEY_PRIMARY_DNS, "") ?: "",
            secondaryDns = prefs.getString(KEY_SECONDARY_DNS, "") ?: "",
            interfaceName = prefs.getString(KEY_INTERFACE_NAME, "eth0") ?: "eth0"
        )
    }

    /**
     * 清除所有已保存的网络配置。
     */
    fun clearConfiguration() {
        prefs.edit()
            .remove(KEY_CONFIG_MODE)
            .remove(KEY_IP_ADDRESS)
            .remove(KEY_SUBNET_MASK)
            .remove(KEY_GATEWAY)
            .remove(KEY_PRIMARY_DNS)
            .remove(KEY_SECONDARY_DNS)
            .remove(KEY_INTERFACE_NAME)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ethernet_config"
        private const val KEY_CONFIG_MODE = "config_mode"
        private const val KEY_IP_ADDRESS = "ip_address"
        private const val KEY_SUBNET_MASK = "subnet_mask"
        private const val KEY_GATEWAY = "gateway"
        private const val KEY_PRIMARY_DNS = "primary_dns"
        private const val KEY_SECONDARY_DNS = "secondary_dns"
        private const val KEY_INTERFACE_NAME = "interface_name"
    }
}
