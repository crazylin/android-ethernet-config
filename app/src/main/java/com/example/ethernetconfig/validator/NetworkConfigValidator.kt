package com.example.ethernetconfig.validator

import com.example.ethernetconfig.model.ValidationResult

/**
 * 网络参数验证工具类（单例）。
 * 负责 IPv4 地址、子网掩码、网关和 DNS 的格式验证。
 */
object NetworkConfigValidator {

    /**
     * 验证 IPv4 地址格式。
     * 有效格式：四组 0-255 的十进制数字，以点号分隔（如 192.168.1.100）。
     *
     * @param ip 待验证的 IP 地址字符串
     * @return ValidationResult.Valid 或 ValidationResult.Invalid
     */
    fun validateIpAddress(ip: String): ValidationResult {
        return if (isValidIpv4(ip)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("请输入有效的 IPv4 地址")
        }
    }

    /**
     * 验证 DNS 服务器地址格式，复用 IPv4 验证逻辑。
     *
     * @param dns 待验证的 DNS 服务器地址字符串
     * @return ValidationResult.Valid 或 ValidationResult.Invalid
     */
    fun validateDnsServer(dns: String): ValidationResult {
        return if (isValidIpv4(dns)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("请输入有效的 DNS 服务器地址")
        }
    }

    /**
     * 验证子网掩码格式。
     * 有效子网掩码：连续的高位 1 和低位 0 组成的 32 位二进制数对应的点分十进制表示。
     * 有效值示例：255.255.255.0、255.255.0.0、255.0.0.0、0.0.0.0、255.255.255.255
     *
     * @param mask 待验证的子网掩码字符串
     * @return ValidationResult.Valid 或 ValidationResult.Invalid
     */
    fun validateSubnetMask(mask: String): ValidationResult {
        return if (isValidSubnetMask(mask)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("请输入有效的子网掩码")
        }
    }

    /**
     * 检查字符串是否为有效的子网掩码。
     * 有效条件：首先是有效的 IPv4 格式，然后转换为 32 位二进制后，
     * 必须是连续的高位 1 后跟连续的低位 0（如 11111111.11111111.11110000.00000000）。
     */
    fun isValidSubnetMask(mask: String): Boolean {
        // 首先必须是有效的 IPv4 格式
        if (!isValidIpv4(mask)) return false

        val parts = mask.split(".")
        // 将四个 octet 组合成一个 32 位整数
        val bits = parts.fold(0L) { acc, part ->
            (acc shl 8) or part.toLong()
        }

        // 特殊情况：全 0（0.0.0.0）是有效的子网掩码（前缀长度 0）
        if (bits == 0L) return true

        // 取反后加 1，如果结果是 2 的幂次，则说明原始值是连续 1 后跟连续 0
        // 例如：11111111.11110000.00000000.00000000 取反后为 00000000.00001111.11111111.11111111
        // 加 1 后为 00000000.00010000.00000000.00000000，是 2 的幂次
        val inverted = bits.inv() and 0xFFFFFFFFL
        return (inverted and (inverted + 1)) == 0L
    }

    /**
     * 检查网关是否在 IP 地址和子网掩码定义的子网范围内。
     * 判断条件：(gateway & mask) == (ip & mask)
     *
     * @param gateway 网关地址
     * @param ip IP 地址
     * @param mask 子网掩码
     * @return true 如果网关在子网范围内
     */
    fun isGatewayInSubnet(gateway: String, ip: String, mask: String): Boolean {
        if (!isValidIpv4(gateway) || !isValidIpv4(ip) || !isValidIpv4(mask)) return false

        val gatewayLong = ipToLong(gateway)
        val ipLong = ipToLong(ip)
        val maskLong = ipToLong(mask)

        return (gatewayLong and maskLong) == (ipLong and maskLong)
    }

    /**
     * 验证网关地址：格式验证 + 子网范围检查。
     * - 格式无效返回 Invalid
     * - 格式有效但不在子网内返回 Warning
     * - 格式有效且在子网内返回 Valid
     *
     * @param gateway 网关地址
     * @param ip IP 地址（用于子网范围检查）
     * @param mask 子网掩码（用于子网范围检查）
     * @return ValidationResult
     */
    fun validateGateway(gateway: String, ip: String, mask: String): ValidationResult {
        if (!isValidIpv4(gateway)) {
            return ValidationResult.Invalid("请输入有效的 IPv4 地址")
        }
        if (isValidIpv4(ip) && isValidSubnetMask(mask) && !isGatewayInSubnet(gateway, ip, mask)) {
            return ValidationResult.Warning("网关不在子网范围内")
        }
        return ValidationResult.Valid
    }

    /**
     * 将点分十进制 IPv4 地址转换为 Long 值。
     */
    private fun ipToLong(ip: String): Long {
        return ip.split(".").fold(0L) { acc, part ->
            (acc shl 8) or part.toLong()
        }
    }

    /**
     * 检查字符串是否为有效的 IPv4 地址。
     * 有效条件：恰好四组以点号分隔的十进制数字，每组值在 0-255 范围内，
     * 且不包含前导零（除了单独的 "0"）。
     */
    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            // 不能为空
            if (part.isEmpty()) return@all false
            // 不能包含非数字字符
            if (!part.all { it.isDigit() }) return@all false
            // 不能有前导零（"0" 本身除外）
            if (part.length > 1 && part[0] == '0') return@all false
            // 值必须在 0-255 范围内
            val value = part.toIntOrNull() ?: return@all false
            value in 0..255
        }
    }
}
