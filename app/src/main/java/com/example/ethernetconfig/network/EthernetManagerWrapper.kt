package com.example.ethernetconfig.network

import android.content.Context
import android.util.Log
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.NetworkConfiguration
import java.net.InetAddress

/**
 * EthernetManager 反射调用封装。
 *
 * EthernetManager 是 Android 隐藏 API，普通应用无法直接访问。
 * 本类通过反射机制获取 EthernetManager 实例并调用其方法，
 * 实现以太网接口的 DHCP / 静态 IP 配置切换。
 *
 * 需要 android.permission.CONNECTIVITY_INTERNAL 或系统签名权限。
 *
 * @param context Android 上下文，用于获取系统服务
 */
class EthernetManagerWrapper(private val context: Context) {

    private val ethernetManager: Any? = try {
        context.getSystemService("ethernet")
    } catch (e: Exception) {
        Log.w(TAG, "Failed to get EthernetManager service", e)
        null
    }

    /**
     * 读取当前以太网配置。
     *
     * 通过反射调用 EthernetManager.getConfiguration() 获取当前的
     * IpConfiguration，然后解析其中的 IP 地址、子网掩码、网关和 DNS 信息。
     *
     * @return 当前网络配置，如果 EthernetManager 不可用或读取失败则返回 null
     */
    fun getConfiguration(): NetworkConfiguration? {
        // 方案 1：通过 EthernetManager 反射
        val manager = ethernetManager
        if (manager != null) {
            try {
                val getConfigMethod = manager.javaClass.getMethod("getConfiguration")
                val ipConfiguration = getConfigMethod.invoke(manager)
                if (ipConfiguration != null) {
                    return parseIpConfiguration(ipConfiguration)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get config via EthernetManager", e)
            }
        }

        // 方案 2：通过 root + ip 命令读取当前配置
        if (hasRootAccess()) {
            return getConfigurationViaRoot()
        }

        return null
    }

    /**
     * 通过 root 权限读取指定接口的网络状态（不依赖系统 NetworkCallback）。
     * 即使没有 DHCP 服务器，只要物理连接了就能读取链路状态。
     */
    fun getInterfaceStatusViaRoot(interfaceName: String): NetworkStatus? {
        if (!hasRootAccess()) return null
        return try {
            val linkOutput = execRoot("ip link show $interfaceName")

            // 检查接口是否存在
            if (linkOutput.isEmpty() || linkOutput.contains("does not exist")) return null

            // 检查物理连接状态：LOWER_UP 表示网线已插入
            val isPhysicallyConnected = linkOutput.contains("LOWER_UP")
            // UP 表示接口已启用
            val isUp = linkOutput.contains(",UP") || linkOutput.contains("<UP")

            // 读取 IP 地址
            val addrOutput = try { execRoot("ip -4 addr show $interfaceName") } catch (_: Exception) { "" }
            val inetRegex = Regex("""inet (\d+\.\d+\.\d+\.\d+)/(\d+)""")
            val inetMatch = inetRegex.find(addrOutput)

            val ipAddress = inetMatch?.groupValues?.get(1)
            val prefix = inetMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val subnetMask = if (prefix > 0) prefixLengthToSubnetMask(prefix) else null

            // 读取网关
            val routeOutput = try { execRoot("ip route show default dev $interfaceName") } catch (_: Exception) { "" }
            val gwRegex = Regex("""default via (\d+\.\d+\.\d+\.\d+)""")
            val gateway = gwRegex.find(routeOutput)?.groupValues?.get(1)

            // 读取 DNS
            val dnsOutput = try { execRoot("getprop net.dns1") } catch (_: Exception) { "" }
            val dns = if (dnsOutput.isNotEmpty()) listOf(dnsOutput) else null

            NetworkStatus(
                isConnected = isPhysicallyConnected || ipAddress != null,
                currentMode = if (ipAddress != null) ConfigMode.STATIC else null,
                currentIpAddress = ipAddress,
                currentSubnetMask = subnetMask,
                currentGateway = gateway,
                currentDns = dns,
                interfaceName = interfaceName
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get interface status via root", e)
            null
        }
    }

    /**
     * 通过 root 权限读取当前网络配置。
     */
    private fun getConfigurationViaRoot(): NetworkConfiguration? {
        return try {
            // 找到第一个以太网接口
            val interfaces = getAvailableInterfaces()
            val iface = interfaces.firstOrNull() ?: return null

            val addrOutput = execRoot("ip addr show $iface")

            // 解析 IP 地址：inet 192.168.1.100/24
            val inetRegex = Regex("""inet (\d+\.\d+\.\d+\.\d+)/(\d+)""")
            val inetMatch = inetRegex.find(addrOutput)

            if (inetMatch != null) {
                val ip = inetMatch.groupValues[1]
                val prefix = inetMatch.groupValues[2].toInt()
                val mask = prefixLengthToSubnetMask(prefix)

                // 解析网关
                val routeOutput = try { execRoot("ip route show default dev $iface") } catch (_: Exception) { "" }
                val gwRegex = Regex("""default via (\d+\.\d+\.\d+\.\d+)""")
                val gateway = gwRegex.find(routeOutput)?.groupValues?.get(1) ?: ""

                NetworkConfiguration(
                    mode = ConfigMode.STATIC,
                    ipAddress = ip,
                    subnetMask = mask,
                    gateway = gateway,
                    interfaceName = iface
                )
            } else {
                NetworkConfiguration(mode = ConfigMode.DHCP, interfaceName = iface)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get config via root", e)
            null
        }
    }

    /**
     * 将静态 IP 配置应用到以太网接口（默认接口）。
     */
    fun setConfiguration(config: NetworkConfiguration): Result<Unit> {
        return setConfiguration(config, config.interfaceName)
    }

    /**
     * 将以太网接口切换到 DHCP 模式（默认接口）。
     */
    fun setDhcpMode(): Result<Unit> {
        return setDhcpMode("eth0")
    }

    /**
     * 检查是否有 root 权限。
     */
    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查 EthernetManager 是否可用。
     *
     * @return 如果 EthernetManager 实例获取成功则返回 true，否则返回 false
     */
    fun isAvailable(): Boolean = ethernetManager != null

    /**
     * 获取所有可用的以太网接口名称列表。
     *
     * 优先通过反射调用 EthernetManager.getAvailableInterfaces()，
     * 如果不可用则通过 NetworkInterface 枚举以太网接口。
     *
     * @return 以太网接口名称列表（如 ["eth0", "eth1"]），无可用接口时返回空列表
     */
    fun getAvailableInterfaces(): List<String> {
        // 合并所有方法的结果，去重排序
        val all = mutableSetOf<String>()
        all.addAll(getInterfacesFromEthernetManager())
        // 优先用 root 执行 ip link（能看到所有接口，包括没有 DHCP 的）
        all.addAll(getInterfacesFromIpCommandRoot())
        all.addAll(getInterfacesFromIpCommand())
        all.addAll(getInterfacesFromSysfs())
        all.addAll(getInterfacesFromNetworkInterface())
        return all.sorted()
    }

    /** 方法 1：通过 EthernetManager 反射获取 */
    private fun getInterfacesFromEthernetManager(): List<String> {
        val manager = ethernetManager ?: return emptyList()
        return try {
            val method = manager.javaClass.getMethod("getAvailableInterfaces")
            @Suppress("UNCHECKED_CAST")
            val interfaces = method.invoke(manager) as? Array<String>
            interfaces?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 方法 2：通过 root 执行 `ip link show` 枚举所有接口。
     * 能看到没有 DHCP、没有 IP 的接口（只要物理连接了就能看到）。
     */
    private fun getInterfacesFromIpCommandRoot(): List<String> {
        if (!hasRootAccess()) return emptyList()
        return try {
            val output = execRoot("ip link show")
            val regex = Regex("""^\d+:\s+(\S+):""", RegexOption.MULTILINE)
            regex.findAll(output)
                .map { it.groupValues[1] }
                .filter { name ->
                    val lower = name.lowercase()
                    lower.startsWith("eth") || lower.startsWith("en") || lower.startsWith("usb")
                }
                .toList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate interfaces via root ip command", e)
            emptyList()
        }
    }

    /**
     * 方法 3：通过普通权限 `ip link show` 命令。
     */
    private fun getInterfacesFromIpCommand(): List<String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("ip", "link", "show"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // 解析 ip link show 输出，格式如：
            // 1: lo: <LOOPBACK,UP,LOWER_UP> ...
            // 2: eth0: <BROADCAST,MULTICAST> ...
            // 3: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> ...
            val regex = Regex("""^\d+:\s+(\S+):""", RegexOption.MULTILINE)
            regex.findAll(output)
                .map { it.groupValues[1] }
                .filter { name ->
                    val lower = name.lowercase()
                    lower.startsWith("eth") || lower.startsWith("en") || lower.startsWith("usb")
                }
                .toList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate interfaces via ip command", e)
            emptyList()
        }
    }

    /** 方法 3：通过 /sys/class/net/ 读取（可能被 SELinux 限制） */
    private fun getInterfacesFromSysfs(): List<String> {
        return try {
            val netDir = java.io.File("/sys/class/net")
            if (netDir.exists() && netDir.isDirectory) {
                netDir.listFiles()
                    ?.map { it.name }
                    ?.filter { name ->
                        val lower = name.lowercase()
                        lower.startsWith("eth") || lower.startsWith("en") || lower.startsWith("usb")
                    }
                    ?.filter { name ->
                        val typeFile = java.io.File("/sys/class/net/$name/type")
                        try {
                            typeFile.readText().trim() == "1" // ARPHRD_ETHER
                        } catch (_: Exception) {
                            true
                        }
                    }
                    ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate interfaces from /sys/class/net", e)
            emptyList()
        }
    }

    /** 方法 4：通过 NetworkInterface（只能看到 UP 的接口） */
    private fun getInterfacesFromNetworkInterface(): List<String> {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { iface ->
                    val name = iface.name.lowercase()
                    !iface.isLoopback && !iface.isVirtual &&
                    (name.startsWith("eth") || name.startsWith("en") || name.startsWith("usb"))
                }
                ?.map { it.name }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate network interfaces", e)
            emptyList()
        }
    }

    /**
     * 将静态 IP 配置应用到指定的以太网接口。
     *
     * @param config 要应用的网络配置
     * @param interfaceName 目标接口名称（如 "eth0"）
     * @return 成功返回 Result.success，失败返回包含异常信息的 Result.failure
     */
    fun setConfiguration(config: NetworkConfiguration, interfaceName: String): Result<Unit> {
        // 方案 1：通过 root + ip 命令配置（最可靠）
        if (hasRootAccess()) {
            return setConfigurationViaRoot(config, interfaceName)
        }

        // 方案 2：通过 EthernetManager API/反射
        val manager = ethernetManager
            ?: return Result.failure(IllegalStateException("EthernetManager is not available and no root access"))
        return try {
            val ipConfiguration = buildStaticIpConfiguration(config)

            // Android 13+ (API 33): 优先使用官方 updateConfiguration API
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    val updateRequestClass = Class.forName("android.net.EthernetNetworkUpdateRequest")
                    val builderClass = Class.forName("android.net.EthernetNetworkUpdateRequest\$Builder")
                    val builder = builderClass.getDeclaredConstructor().newInstance()

                    val setIpConfig = builderClass.getMethod("setIpConfiguration", ipConfiguration.javaClass)
                    setIpConfig.invoke(builder, ipConfiguration)

                    val buildMethod = builderClass.getMethod("build")
                    val request = buildMethod.invoke(builder)

                    val updateMethod = manager.javaClass.getMethod(
                        "updateConfiguration",
                        String::class.java,
                        updateRequestClass,
                        java.util.concurrent.Executor::class.java,
                        Class.forName("android.os.OutcomeReceiver")
                    )
                    // 使用同步方式：传 null callback，用 try-catch 捕获
                    updateMethod.invoke(manager, interfaceName, request,
                        context.mainExecutor, null)
                    return Result.success(Unit)
                } catch (e: Exception) {
                    Log.d(TAG, "updateConfiguration failed: ${e.message}, trying setConfiguration")
                }
            }

            // 回退：setConfiguration(String, IpConfiguration)
            try {
                val setConfigMethod = manager.javaClass.getMethod(
                    "setConfiguration", String::class.java, ipConfiguration.javaClass
                )
                setConfigMethod.invoke(manager, interfaceName, ipConfiguration)
            } catch (_: NoSuchMethodException) {
                val setConfigMethod = manager.javaClass.getMethod(
                    "setConfiguration", ipConfiguration.javaClass
                )
                setConfigMethod.invoke(manager, ipConfiguration)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set ethernet configuration on $interfaceName", e)
            Result.failure(e)
        }
    }

    /**
     * 将指定以太网接口切换到 DHCP 模式。
     *
     * @param interfaceName 目标接口名称（如 "eth0"）
     * @return 成功返回 Result.success，失败返回包含异常信息的 Result.failure
     */
    fun setDhcpMode(interfaceName: String): Result<Unit> {
        // 方案 1：通过 root + dhcpcd/ip 命令
        if (hasRootAccess()) {
            return setDhcpViaRoot(interfaceName)
        }

        // 方案 2：通过 EthernetManager API/反射
        val manager = ethernetManager
            ?: return Result.failure(IllegalStateException("EthernetManager is not available and no root access"))
        return try {
            val ipConfiguration = buildDhcpIpConfiguration()

            // Android 13+ (API 33): 优先使用官方 updateConfiguration API
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    val updateRequestClass = Class.forName("android.net.EthernetNetworkUpdateRequest")
                    val builderClass = Class.forName("android.net.EthernetNetworkUpdateRequest\$Builder")
                    val builder = builderClass.getDeclaredConstructor().newInstance()

                    val setIpConfig = builderClass.getMethod("setIpConfiguration", ipConfiguration.javaClass)
                    setIpConfig.invoke(builder, ipConfiguration)

                    val buildMethod = builderClass.getMethod("build")
                    val request = buildMethod.invoke(builder)

                    val updateMethod = manager.javaClass.getMethod(
                        "updateConfiguration",
                        String::class.java,
                        updateRequestClass,
                        java.util.concurrent.Executor::class.java,
                        Class.forName("android.os.OutcomeReceiver")
                    )
                    updateMethod.invoke(manager, interfaceName, request,
                        context.mainExecutor, null)
                    return Result.success(Unit)
                } catch (e: Exception) {
                    Log.d(TAG, "updateConfiguration DHCP failed: ${e.message}, trying setConfiguration")
                }
            }

            // 回退
            try {
                val setConfigMethod = manager.javaClass.getMethod(
                    "setConfiguration", String::class.java, ipConfiguration.javaClass
                )
                setConfigMethod.invoke(manager, interfaceName, ipConfiguration)
            } catch (_: NoSuchMethodException) {
                val setConfigMethod = manager.javaClass.getMethod(
                    "setConfiguration", ipConfiguration.javaClass
                )
                setConfigMethod.invoke(manager, ipConfiguration)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set DHCP mode on $interfaceName", e)
            Result.failure(e)
        }
    }

    // ---- Private helpers ----

    /**
     * 解析 IpConfiguration 对象，提取网络配置参数。
     */
    private fun parseIpConfiguration(ipConfiguration: Any): NetworkConfiguration {
        val ipAssignment = getIpAssignment(ipConfiguration)
        val mode = if (ipAssignment == "STATIC") ConfigMode.STATIC else ConfigMode.DHCP

        if (mode == ConfigMode.DHCP) {
            return NetworkConfiguration(mode = ConfigMode.DHCP)
        }

        val staticConfig = getStaticIpConfiguration(ipConfiguration)
            ?: return NetworkConfiguration(mode = ConfigMode.STATIC)

        val ipAddress = extractIpAddress(staticConfig)
        val prefixLength = extractPrefixLength(staticConfig)
        val subnetMask = prefixLengthToSubnetMask(prefixLength)
        val gateway = extractGateway(staticConfig)
        val dnsServers = extractDnsServers(staticConfig)

        return NetworkConfiguration(
            mode = ConfigMode.STATIC,
            ipAddress = ipAddress,
            subnetMask = subnetMask,
            gateway = gateway,
            primaryDns = dnsServers.getOrElse(0) { "" },
            secondaryDns = dnsServers.getOrElse(1) { "" }
        )
    }

    /**
     * 获取 IpConfiguration 的 ipAssignment 字段值。
     */
    private fun getIpAssignment(ipConfiguration: Any): String {
        return try {
            val field = ipConfiguration.javaClass.getField("ipAssignment")
            field.get(ipConfiguration)?.toString() ?: "DHCP"
        } catch (_: NoSuchFieldException) {
            try {
                val field = ipConfiguration.javaClass.getDeclaredField("ipAssignment")
                field.isAccessible = true
                field.get(ipConfiguration)?.toString() ?: "DHCP"
            } catch (_: Exception) {
                "DHCP"
            }
        } catch (_: Exception) {
            "DHCP"
        }
    }

    /**
     * 获取 IpConfiguration 的 staticIpConfiguration 字段。
     */
    private fun getStaticIpConfiguration(ipConfiguration: Any): Any? {
        return try {
            val method = ipConfiguration.javaClass.getMethod("getStaticIpConfiguration")
            method.invoke(ipConfiguration)
        } catch (e: NoSuchMethodException) {
            try {
                val field = ipConfiguration.javaClass.getField("staticIpConfiguration")
                field.get(ipConfiguration)
            } catch (e2: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 StaticIpConfiguration 中提取 IP 地址字符串。
     */
    private fun extractIpAddress(staticConfig: Any): String {
        return try {
            val ipAddressField = staticConfig.javaClass.getField("ipAddress")
            val linkAddress = ipAddressField.get(staticConfig) ?: return ""
            val getAddressMethod = linkAddress.javaClass.getMethod("getAddress")
            val inetAddress = getAddressMethod.invoke(linkAddress) as? InetAddress
            inetAddress?.hostAddress ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 从 StaticIpConfiguration 中提取前缀长度。
     */
    private fun extractPrefixLength(staticConfig: Any): Int {
        return try {
            val ipAddressField = staticConfig.javaClass.getField("ipAddress")
            val linkAddress = ipAddressField.get(staticConfig) ?: return 24
            val getPrefixLengthMethod = linkAddress.javaClass.getMethod("getPrefixLength")
            getPrefixLengthMethod.invoke(linkAddress) as? Int ?: 24
        } catch (e: Exception) {
            24
        }
    }

    /**
     * 从 StaticIpConfiguration 中提取网关地址。
     */
    private fun extractGateway(staticConfig: Any): String {
        return try {
            val gatewayField = staticConfig.javaClass.getField("gateway")
            val inetAddress = gatewayField.get(staticConfig) as? InetAddress
            inetAddress?.hostAddress ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 从 StaticIpConfiguration 中提取 DNS 服务器列表。
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractDnsServers(staticConfig: Any): List<String> {
        return try {
            val dnsServersField = staticConfig.javaClass.getField("dnsServers")
            val dnsList = dnsServersField.get(staticConfig) as? List<InetAddress>
                ?: return emptyList()
            dnsList.mapNotNull { it.hostAddress }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 构造静态 IP 模式的 IpConfiguration 对象。
     * Android 13+: 使用官方 Builder API
     * Android 12-: 使用反射
     */
    private fun buildStaticIpConfiguration(config: NetworkConfiguration): Any {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return buildStaticIpConfigurationWithBuilder(config)
        }
        return buildStaticIpConfigurationWithReflection(config)
    }

    /** Android 13+ 使用官方 Builder API 构造 IpConfiguration */
    private fun buildStaticIpConfigurationWithBuilder(config: NetworkConfiguration): Any {
        val prefixLength = subnetMaskToPrefixLength(config.subnetMask)
        val ipAddress = parseIpv4Address(config.ipAddress)

        // StaticIpConfiguration.Builder
        val staticBuilderClass = Class.forName("android.net.StaticIpConfiguration\$Builder")
        val staticBuilder = staticBuilderClass.getDeclaredConstructor().newInstance()

        // setIpAddress(LinkAddress)
        val linkAddressClass = Class.forName("android.net.LinkAddress")
        val linkAddress = linkAddressClass
            .getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
            .newInstance(ipAddress, prefixLength)
        staticBuilderClass.getMethod("setIpAddress", linkAddressClass)
            .invoke(staticBuilder, linkAddress)

        // setGateway(InetAddress)
        if (config.gateway.isNotEmpty()) {
            staticBuilderClass.getMethod("setGateway", InetAddress::class.java)
                .invoke(staticBuilder, parseIpv4Address(config.gateway))
        }

        // setDnsServers(List<InetAddress>)
        val dnsList = mutableListOf<InetAddress>()
        if (config.primaryDns.isNotEmpty()) dnsList.add(parseIpv4Address(config.primaryDns))
        if (config.secondaryDns.isNotEmpty()) dnsList.add(parseIpv4Address(config.secondaryDns))
        staticBuilderClass.getMethod("setDnsServers", List::class.java)
            .invoke(staticBuilder, dnsList as List<InetAddress>)

        val staticIpConfig = staticBuilderClass.getMethod("build").invoke(staticBuilder)

        // IpConfiguration.Builder
        val ipConfigBuilderClass = Class.forName("android.net.IpConfiguration\$Builder")
        val ipConfigBuilder = ipConfigBuilderClass.getDeclaredConstructor().newInstance()

        val staticIpConfigClass = Class.forName("android.net.StaticIpConfiguration")
        ipConfigBuilderClass.getMethod("setStaticIpConfiguration", staticIpConfigClass)
            .invoke(ipConfigBuilder, staticIpConfig)

        return ipConfigBuilderClass.getMethod("build").invoke(ipConfigBuilder)!!
    }

    /** Android 12- 使用反射构造 IpConfiguration */
    private fun buildStaticIpConfigurationWithReflection(config: NetworkConfiguration): Any {
        // 创建 StaticIpConfiguration 实例
        val staticIpConfigClass = Class.forName("android.net.StaticIpConfiguration")
        val staticIpConfig = staticIpConfigClass.getDeclaredConstructor().newInstance()

        // 设置 IP 地址和前缀长度（通过 LinkAddress）
        val prefixLength = subnetMaskToPrefixLength(config.subnetMask)
        val ipAddress = parseIpv4Address(config.ipAddress)
        val linkAddressClass = Class.forName("android.net.LinkAddress")
        val linkAddress = linkAddressClass
            .getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
            .newInstance(ipAddress, prefixLength)
        staticIpConfigClass.getField("ipAddress").set(staticIpConfig, linkAddress)

        // 设置网关
        if (config.gateway.isNotEmpty()) {
            val gateway = parseIpv4Address(config.gateway)
            staticIpConfigClass.getField("gateway").set(staticIpConfig, gateway)
        }

        // 设置 DNS 服务器
        @Suppress("UNCHECKED_CAST")
        val dnsServers = staticIpConfigClass.getField("dnsServers")
            .get(staticIpConfig) as ArrayList<InetAddress>
        if (config.primaryDns.isNotEmpty()) {
            dnsServers.add(parseIpv4Address(config.primaryDns))
        }
        if (config.secondaryDns.isNotEmpty()) {
            dnsServers.add(parseIpv4Address(config.secondaryDns))
        }

        // 创建 IpConfiguration 并设置为 STATIC 模式
        val ipConfigClass = Class.forName("android.net.IpConfiguration")
        val ipConfig = createIpConfiguration(ipConfigClass, "STATIC", staticIpConfig)

        return ipConfig
    }

    /**
     * 构造 DHCP 模式的 IpConfiguration 对象。
     */
    private fun buildDhcpIpConfiguration(): Any {
        // Android 13+: 使用 IpConfiguration.Builder（不设置 staticIpConfiguration 即为 DHCP）
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                val ipConfigBuilderClass = Class.forName("android.net.IpConfiguration\$Builder")
                val builder = ipConfigBuilderClass.getDeclaredConstructor().newInstance()
                return ipConfigBuilderClass.getMethod("build").invoke(builder)!!
            } catch (e: Exception) {
                Log.d(TAG, "IpConfiguration.Builder for DHCP failed: ${e.message}")
            }
        }

        // 回退：反射
        val ipConfigClass = Class.forName("android.net.IpConfiguration")
        return createIpConfiguration(ipConfigClass, "DHCP", null)
    }

    /**
     * 创建 IpConfiguration 实例。
     * 优先使用 4 参数构造函数（跨版本最可靠），回退到无参构造 + setter/字段。
     */
    private fun createIpConfiguration(
        ipConfigClass: Class<*>,
        assignmentName: String,
        staticIpConfig: Any?
    ): Any {
        val enumMap = getIpConfigurationEnumMap(ipConfigClass)
        val ipAssignmentValue = enumMap["IpAssignment.$assignmentName"]
            ?: throw IllegalStateException("Cannot find IpAssignment.$assignmentName")
        val proxyNoneValue = enumMap["ProxySettings.NONE"]
            ?: enumMap["ProxySettings.UNASSIGNED"]

        // 方法 1：4 参数构造函数 IpConfiguration(IpAssignment, ProxySettings, StaticIpConfiguration, ProxyInfo)
        try {
            val staticIpConfigClass = Class.forName("android.net.StaticIpConfiguration")
            val proxyInfoClass = Class.forName("android.net.ProxyInfo")

            if (proxyNoneValue != null) {
                val constructor = ipConfigClass.getConstructor(
                    ipAssignmentValue.javaClass, proxyNoneValue.javaClass,
                    staticIpConfigClass, proxyInfoClass
                )
                return constructor.newInstance(ipAssignmentValue, proxyNoneValue, staticIpConfig, null)
            }
        } catch (e: Exception) {
            Log.d(TAG, "4-arg constructor failed: ${e.message}, trying setter approach")
        }

        // 方法 2：无参构造 + setter/字段
        val ipConfig = ipConfigClass.getDeclaredConstructor().newInstance()
        setIpAssignment(ipConfig, ipConfigClass, assignmentName)
        if (staticIpConfig != null) {
            setStaticIpConfig(ipConfig, ipConfigClass,
                Class.forName("android.net.StaticIpConfiguration"), staticIpConfig)
        }
        return ipConfig
    }

    /**
     * 设置 IpConfiguration 的 ipAssignment 字段。
     * 通过遍历 IpConfiguration 内部枚举类获取枚举值，兼容所有 Android 版本。
     * 参考：https://juejin.cn/post/7122626970596638734
     */
    private fun setIpAssignment(ipConfig: Any, ipConfigClass: Class<*>, assignmentName: String) {
        // 遍历 IpConfiguration 的所有内部类，找到枚举值
        val enumMap = getIpConfigurationEnumMap(ipConfigClass)

        // 设置 ipAssignment
        val ipAssignmentValue = enumMap["IpAssignment.$assignmentName"]
            ?: throw IllegalStateException("Cannot find IpAssignment.$assignmentName")

        try {
            val field = ipConfigClass.getField("ipAssignment")
            field.set(ipConfig, ipAssignmentValue)
        } catch (_: NoSuchFieldException) {
            // 尝试私有字段
            val field = ipConfigClass.getDeclaredField("ipAssignment")
            field.isAccessible = true
            field.set(ipConfig, ipAssignmentValue)
        }

        // 同时设置 proxySettings 为 NONE（部分 ROM 要求）
        val proxyNone = enumMap["ProxySettings.NONE"]
        if (proxyNone != null) {
            try {
                val proxyField = ipConfigClass.getField("proxySettings")
                proxyField.set(ipConfig, proxyNone)
            } catch (_: Exception) {
                try {
                    val proxyField = ipConfigClass.getDeclaredField("proxySettings")
                    proxyField.isAccessible = true
                    proxyField.set(ipConfig, proxyNone)
                } catch (_: Exception) { /* 忽略，不是所有版本都有 */ }
            }
        }
    }

    /**
     * 遍历 IpConfiguration 的所有内部枚举类，构建 "类名.枚举值" -> 枚举对象 的映射。
     * 例如：{"IpAssignment.DHCP" -> DHCP, "IpAssignment.STATIC" -> STATIC, "ProxySettings.NONE" -> NONE, ...}
     */
    private fun getIpConfigurationEnumMap(ipConfigClass: Class<*>): Map<String, Any> {
        val enumMap = mutableMapOf<String, Any>()
        for (innerClass in ipConfigClass.declaredClasses) {
            val enumConstants = innerClass.enumConstants ?: continue
            for (enumValue in enumConstants) {
                enumMap["${innerClass.simpleName}.$enumValue"] = enumValue
            }
        }
        return enumMap
    }

    /**
     * 设置 IpConfiguration 的 staticIpConfiguration。
     * 兼容不同 Android 版本。
     */
    private fun setStaticIpConfig(
        ipConfig: Any,
        ipConfigClass: Class<*>,
        @Suppress("UNUSED_PARAMETER") staticIpConfigClass: Class<*>,
        staticIpConfig: Any
    ) {
        // 方法 1：公开字段
        try {
            val field = ipConfigClass.getField("staticIpConfiguration")
            field.set(ipConfig, staticIpConfig)
            return
        } catch (_: Exception) { }

        // 方法 2：setter 方法
        try {
            val setter = ipConfigClass.getDeclaredMethod("setStaticIpConfiguration",
                Class.forName("android.net.StaticIpConfiguration"))
            setter.invoke(ipConfig, staticIpConfig)
            return
        } catch (_: Exception) { }

        // 方法 3：私有字段 mStaticIpConfiguration
        try {
            val field = ipConfigClass.getDeclaredField("mStaticIpConfiguration")
            field.isAccessible = true
            field.set(ipConfig, staticIpConfig)
            return
        } catch (_: Exception) { }

        Log.w(TAG, "Cannot set staticIpConfiguration, config may not work correctly")
    }

    /**
     * 将子网掩码字符串转换为前缀长度。
     * 例如 "255.255.255.0" -> 24
     */
    internal fun subnetMaskToPrefixLength(mask: String): Int {
        if (mask.isEmpty()) return 24
        return try {
            val parts = mask.split(".")
            if (parts.size != 4) return 24
            parts.sumOf { Integer.bitCount(it.toInt()) }
        } catch (e: Exception) {
            24
        }
    }

    /**
     * 将前缀长度转换为子网掩码字符串。
     * 例如 24 -> "255.255.255.0"
     */
    internal fun prefixLengthToSubnetMask(prefixLength: Int): String {
        val mask = if (prefixLength == 0) {
            0
        } else {
            -1 shl (32 - prefixLength)
        }
        return "${(mask shr 24) and 0xFF}.${(mask shr 16) and 0xFF}" +
            ".${(mask shr 8) and 0xFF}.${mask and 0xFF}"
    }

    /**
     * 将 IPv4 地址字符串解析为 InetAddress，不经过 DNS 解析。
     * 使用 InetAddress.getByAddress() 直接从字节数组构造，
     * 避免在无网络环境下 InetAddress.getByName() 触发 DNS 查找失败。
     *
     * @param ip IPv4 地址字符串（如 "192.168.1.100"）
     * @return InetAddress 实例
     * @throws IllegalArgumentException 如果 IP 格式无效
     */
    private fun parseIpv4Address(ip: String): InetAddress {
        val parts = ip.split(".")
        if (parts.size != 4) {
            throw IllegalArgumentException("Invalid IPv4 address: $ip")
        }
        val bytes = ByteArray(4) { i ->
            val value = parts[i].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid IPv4 address: $ip")
            if (value < 0 || value > 255) {
                throw IllegalArgumentException("Invalid IPv4 address: $ip")
            }
            value.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    // ---- Root-based configuration (最可靠，不依赖 EthernetManager) ----

    /**
     * 通过 root 权限使用 ip 命令配置静态 IP。
     * 同时通过 settings 写入配置，让系统 EthernetService 也知道是静态模式。
     */
    private fun setConfigurationViaRoot(config: NetworkConfiguration, interfaceName: String): Result<Unit> {
        val prefixLength = subnetMaskToPrefixLength(config.subnetMask)
        return RootEthernetHelper.setStaticIp(
            context = context,
            interfaceName = interfaceName,
            ip = config.ipAddress,
            prefixLength = prefixLength,
            gateway = config.gateway,
            dns1 = config.primaryDns,
            dns2 = config.secondaryDns
        )
    }

    private fun setDhcpViaRoot(interfaceName: String): Result<Unit> {
        return RootEthernetHelper.setDhcp(context, interfaceName)
    }

    /**
     * 以 root 权限执行 shell 命令。
     */
    private fun execRoot(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0 && error.isNotEmpty()) {
            throw RuntimeException("Command failed ($exitCode): $command\n$error")
        }
        return output.trim()
    }

    companion object {
        private const val TAG = "EthernetManagerWrapper"
    }
}
