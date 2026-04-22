package com.example.ethernetconfig.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.ConfigResult
import com.example.ethernetconfig.model.Field
import com.example.ethernetconfig.model.NetworkStatus
import com.example.ethernetconfig.model.ValidationResult
import com.example.ethernetconfig.repository.EthernetConfigRepository
import com.example.ethernetconfig.validator.NetworkConfigValidator

/**
 * 以太网配置 ViewModel，管理 UI 状态和业务逻辑。
 *
 * 负责配置模式切换、字段验证、表单状态管理和配置应用。
 * 通过 LiveData 向 UI 层提供响应式的状态更新。
 *
 * @param application Android Application 实例
 * @param repository 数据仓库，协调底层数据源
 */
class EthernetConfigViewModel(
    application: Application,
    private val repository: EthernetConfigRepository
) : AndroidViewModel(application) {

    // ---- 配置模式 ----

    private val _configMode = MutableLiveData(ConfigMode.DHCP)

    /** 当前配置模式（DHCP 或 STATIC） */
    val configMode: LiveData<ConfigMode> get() = _configMode

    // ---- 接口选择 ----

    private val _availableInterfaces = MutableLiveData<List<String>>(emptyList())

    /** 可用的以太网接口列表 */
    val availableInterfaces: LiveData<List<String>> get() = _availableInterfaces

    private val _selectedInterface = MutableLiveData("eth0")

    /** 当前选中的以太网接口名称 */
    val selectedInterface: LiveData<String> get() = _selectedInterface

    // ---- 网络状态 ----

    /** 网络连接状态，由 Repository 代理 NetworkMonitor 提供 */
    val networkStatus: LiveData<NetworkStatus> = repository.observeNetworkStatus()

    /** 通过 root 读取的网络状态（补充系统 NetworkMonitor 无法检测的情况） */
    private val _rootNetworkStatus = MutableLiveData<NetworkStatus>()
    val rootNetworkStatus: LiveData<NetworkStatus> get() = _rootNetworkStatus

    // ---- 表单字段 ----

    /** IP 地址输入 */
    val ipAddress = MutableLiveData("")

    /** 子网掩码输入 */
    val subnetMask = MutableLiveData("")

    /** 网关输入 */
    val gateway = MutableLiveData("")

    /** 主 DNS 输入 */
    val primaryDns = MutableLiveData("")

    /** 备用 DNS 输入 */
    val secondaryDns = MutableLiveData("")

    // ---- 验证状态 ----

    private val _validationErrors = MutableLiveData<Map<Field, String>>(emptyMap())

    /** 各字段的验证错误信息 */
    val validationErrors: LiveData<Map<Field, String>> get() = _validationErrors

    private val _isFormValid = MediatorLiveData<Boolean>().apply { value = false }

    /** 表单是否有效（静态模式下所有必填字段非空且格式验证通过） */
    val isFormValid: LiveData<Boolean> get() = _isFormValid

    // ---- 配置结果 ----

    private val _configResult = MutableLiveData<ConfigResult>()

    /** 配置应用结果 */
    val configResult: LiveData<ConfigResult> get() = _configResult

    init {
        // 加载持久化配置
        loadStoredConfiguration()

        // 加载可用以太网接口
        refreshInterfaces()

        // 监听网络状态变化：自动刷新接口列表、自动选中已连接的接口
        _isFormValid.addSource(networkStatus) { status ->
            // 网络状态变化时刷新接口列表
            refreshInterfaces()

            // 如果有接口连接上了，自动选中该接口
            if (status != null && status.isConnected && status.interfaceName != null) {
                _selectedInterface.value = status.interfaceName
            }

            updateFormValidity()
        }

        // 监听表单字段变化，实时更新表单有效性
        val formSources = listOf(ipAddress, subnetMask, gateway, primaryDns, secondaryDns)
        for (source in formSources) {
            _isFormValid.addSource(source) { updateFormValidity() }
        }
        _isFormValid.addSource(_configMode) { updateFormValidity() }
        _isFormValid.addSource(_validationErrors) { updateFormValidity() }
    }

    /**
     * 切换配置模式。
     *
     * - 切换到 DHCP 时：清空所有手动配置字段
     * - 切换到 STATIC 时：从当前 NetworkStatus 中读取 DHCP 获取的参数，预填充到对应字段
     *
     * @param mode 目标配置模式
     */
    fun setConfigMode(mode: ConfigMode) {
        _configMode.value = mode

        when (mode) {
            ConfigMode.DHCP -> {
                // 清空所有手动配置字段
                ipAddress.value = ""
                subnetMask.value = ""
                gateway.value = ""
                primaryDns.value = ""
                secondaryDns.value = ""
                // 清空验证错误
                _validationErrors.value = emptyMap()
            }
            ConfigMode.STATIC -> {
                // 从当前 NetworkStatus 中预填充 DHCP 获取的参数
                val status = networkStatus.value
                if (status != null && status.isConnected) {
                    ipAddress.value = status.currentIpAddress ?: ""
                    subnetMask.value = status.currentSubnetMask ?: ""
                    gateway.value = status.currentGateway ?: ""
                    val dnsList = status.currentDns
                    primaryDns.value = dnsList?.getOrNull(0) ?: ""
                    secondaryDns.value = dnsList?.getOrNull(1) ?: ""
                }
            }
        }
    }

    /**
     * 验证单个字段。
     *
     * @param field 要验证的字段
     * @param value 字段值
     * @return 验证结果
     */
    fun validateField(field: Field, value: String): ValidationResult {
        val result = when (field) {
            Field.IP_ADDRESS -> NetworkConfigValidator.validateIpAddress(value)
            Field.SUBNET_MASK -> NetworkConfigValidator.validateSubnetMask(value)
            Field.GATEWAY -> NetworkConfigValidator.validateGateway(
                value,
                ipAddress.value ?: "",
                subnetMask.value ?: ""
            )
            Field.PRIMARY_DNS -> NetworkConfigValidator.validateDnsServer(value)
            Field.SECONDARY_DNS -> NetworkConfigValidator.validateDnsServer(value)
        }

        // 更新验证错误 map
        val currentErrors = _validationErrors.value?.toMutableMap() ?: mutableMapOf()
        when (result) {
            is ValidationResult.Invalid -> currentErrors[field] = result.errorMessage
            is ValidationResult.Valid -> currentErrors.remove(field)
            is ValidationResult.Warning -> currentErrors.remove(field)
        }
        _validationErrors.value = currentErrors

        return result
    }

    /**
     * 选择以太网接口。
     *
     * @param interfaceName 接口名称（如 "eth0"）
     */
    fun selectInterface(interfaceName: String) {
        _selectedInterface.value = interfaceName
    }

    /**
     * 刷新可用的以太网接口列表。
     * 同时通过 root 读取接口状态（不依赖 DHCP）。
     */
    fun refreshInterfaces() {
        val interfaces = repository.getAvailableInterfaces()
        _availableInterfaces.value = interfaces
        // 如果当前选中的接口不在列表中，自动选择第一个
        val current = _selectedInterface.value
        if (interfaces.isNotEmpty() && (current == null || current !in interfaces)) {
            _selectedInterface.value = interfaces[0]
        }

        // 如果系统 NetworkMonitor 没有检测到连接（没有 DHCP），
        // 用 root 读取接口状态来补充
        val systemStatus = networkStatus.value
        if (systemStatus == null || !systemStatus.isConnected) {
            val selectedIface = _selectedInterface.value ?: return
            val rootStatus = repository.getInterfaceStatusViaRoot(selectedIface)
            if (rootStatus != null && rootStatus.isConnected) {
                // 通过 _rootNetworkStatus 通知 UI 更新
                _rootNetworkStatus.value = rootStatus
            }
        }
    }

    /**
     * 应用当前配置到以太网接口。
     *
     * 应用前设置 configResult 为 Loading，
     * 成功时设置为 Success，失败时设置为 Failure。
     */
    fun applyConfiguration() {
        val mode = _configMode.value ?: ConfigMode.DHCP

        val config = com.example.ethernetconfig.model.NetworkConfiguration(
            mode = mode,
            ipAddress = ipAddress.value ?: "",
            subnetMask = subnetMask.value ?: "",
            gateway = gateway.value ?: "",
            primaryDns = primaryDns.value ?: "",
            secondaryDns = secondaryDns.value ?: "",
            interfaceName = _selectedInterface.value ?: "eth0"
        )

        _configResult.value = ConfigResult.Loading

        val result = repository.applyConfiguration(config)
        if (result.isSuccess) {
            _configResult.value = ConfigResult.Success
        } else {
            val reason = result.exceptionOrNull()?.message ?: "未知错误"
            _configResult.value = ConfigResult.Failure(reason)
        }
    }

    /**
     * 从持久化存储加载配置，恢复上次保存的状态。
     */
    private fun loadStoredConfiguration() {
        val stored = repository.getStoredConfiguration() ?: return

        _configMode.value = stored.mode
        ipAddress.value = stored.ipAddress
        subnetMask.value = stored.subnetMask
        gateway.value = stored.gateway
        primaryDns.value = stored.primaryDns
        secondaryDns.value = stored.secondaryDns
    }

    /**
     * 更新表单有效性。
     *
     * DHCP 模式下表单始终有效。
     * STATIC 模式下，所有必填字段（IP、掩码、网关、主 DNS）非空且无验证错误时为有效。
     */
    private fun updateFormValidity() {
        val mode = _configMode.value
        if (mode == ConfigMode.DHCP) {
            _isFormValid.value = true
            return
        }

        val ip = ipAddress.value ?: ""
        val mask = subnetMask.value ?: ""
        val gw = gateway.value ?: ""
        val dns = primaryDns.value ?: ""

        // 所有必填字段非空
        val allFilled = ip.isNotEmpty() && mask.isNotEmpty() && gw.isNotEmpty() && dns.isNotEmpty()

        // 无验证错误
        val noErrors = _validationErrors.value?.isEmpty() ?: true

        // 各必填字段格式验证通过
        val ipValid = NetworkConfigValidator.validateIpAddress(ip) is ValidationResult.Valid
        val maskValid = NetworkConfigValidator.validateSubnetMask(mask) is ValidationResult.Valid
        val gwValid = NetworkConfigValidator.validateGateway(gw, ip, mask).let {
            it is ValidationResult.Valid || it is ValidationResult.Warning
        }
        val dnsValid = NetworkConfigValidator.validateDnsServer(dns) is ValidationResult.Valid

        _isFormValid.value = allFilled && noErrors && ipValid && maskValid && gwValid && dnsValid
    }

    /**
     * ViewModelProvider.Factory，用于创建 EthernetConfigViewModel 实例。
     *
     * @param application Android Application 实例
     * @param repository 数据仓库
     */
    class Factory(
        private val application: Application,
        private val repository: EthernetConfigRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(EthernetConfigViewModel::class.java)) {
                return EthernetConfigViewModel(application, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
