package com.example.ethernetconfig.validator

import com.example.ethernetconfig.model.ValidationResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.Tag
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll

/**
 * Feature: android-ethernet-config, Property 3: 网关子网范围检查正确性
 *
 * Validates: Requirements 2.7
 *
 * 对任意有效的 IP 地址、子网掩码和网关地址，isGatewayInSubnet 函数返回 true
 * 当且仅当 (gateway & mask) == (ip & mask)，即网关与 IP 地址在同一子网内。
 */
class GatewaySubnetPropertyTest : FunSpec({

    tags(Tag("Feature: android-ethernet-config"), Tag("Property 3: 网关子网范围检查正确性"))

    val minIterations = PropTestConfig(iterations = 20)

    // --- Helpers ---

    /** 将前缀长度 (0-32) 转换为 32 位掩码 Long 值 */
    fun prefixToMaskLong(prefixLength: Int): Long {
        return if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
    }

    /** 将 32 位 Long 值转换为点分十进制字符串 */
    fun longToIp(value: Long): String {
        return listOf(
            (value shr 24) and 0xFF,
            (value shr 16) and 0xFF,
            (value shr 8) and 0xFF,
            value and 0xFF
        ).joinToString(".")
    }

    /** 将点分十进制字符串转换为 32 位 Long 值 */
    fun ipToLong(ip: String): Long {
        return ip.split(".").fold(0L) { acc, part ->
            (acc shl 8) or part.toLong()
        }
    }

    // --- Generators ---

    /** 有效 IPv4 octet: 0-255 */
    val validOctet: Arb<Int> = Arb.int(0..255)

    /** 有效 IPv4 地址生成器 */
    val validIpv4: Arb<String> = Arb.bind(validOctet, validOctet, validOctet, validOctet) { a, b, c, d ->
        "$a.$b.$c.$d"
    }

    /** 有效子网掩码前缀长度生成器 */
    val validPrefixLength: Arb<Int> = Arb.int(0..32)

    /** 有效子网掩码生成器 */
    val validSubnetMask: Arb<String> = validPrefixLength.map { prefix ->
        longToIp(prefixToMaskLong(prefix))
    }

    /**
     * 生成同一子网内的网关地址。
     * 策略：取 IP 的网络部分，然后随机生成主机部分。
     */
    data class SubnetTestCase(
        val ip: String,
        val mask: String,
        val gateway: String,
        val prefixLength: Int
    )

    val gatewayInSubnet: Arb<SubnetTestCase> = arbitrary {
        val prefix = Arb.int(0..32).bind()
        val maskLong = prefixToMaskLong(prefix)
        val maskStr = longToIp(maskLong)

        // Generate a random IP address
        val ipLong = Arb.long(0L..0xFFFFFFFFL).bind()
        val ipStr = longToIp(ipLong)

        // Compute network portion from IP
        val networkPart = ipLong and maskLong

        // Generate random host bits and combine with network portion
        val hostMask = maskLong.inv() and 0xFFFFFFFFL
        val hostBits = Arb.long(0L..hostMask).bind()
        val gatewayLong = networkPart or hostBits
        val gatewayStr = longToIp(gatewayLong)

        SubnetTestCase(ipStr, maskStr, gatewayStr, prefix)
    }

    /**
     * 生成不在同一子网内的网关地址。
     * 策略：取 IP 的网络部分，然后翻转网络部分中的至少一个 bit。
     */
    val gatewayNotInSubnet: Arb<SubnetTestCase> = arbitrary {
        // Use prefix 1-31 to ensure there are both network and host bits to work with
        val prefix = Arb.int(1..31).bind()
        val maskLong = prefixToMaskLong(prefix)
        val maskStr = longToIp(maskLong)

        // Generate a random IP address
        val ipLong = Arb.long(0L..0xFFFFFFFFL).bind()
        val ipStr = longToIp(ipLong)

        // Compute network portion from IP
        val networkPart = ipLong and maskLong

        // Flip at least one bit in the network portion to create a different subnet
        val bitPosition = Arb.int((32 - prefix) until 32).bind()
        val flippedNetwork = networkPart xor (1L shl bitPosition)
        val maskedFlippedNetwork = flippedNetwork and maskLong

        // Add random host bits
        val hostMask = maskLong.inv() and 0xFFFFFFFFL
        val hostBits = Arb.long(0L..hostMask).bind()
        val gatewayLong = (maskedFlippedNetwork or hostBits) and 0xFFFFFFFFL
        val gatewayStr = longToIp(gatewayLong)

        SubnetTestCase(ipStr, maskStr, gatewayStr, prefix)
    }

    // --- Property Tests ---

    test("isGatewayInSubnet returns true when gateway is in the same subnet") {
        checkAll(minIterations, gatewayInSubnet) { case ->
            NetworkConfigValidator.isGatewayInSubnet(case.gateway, case.ip, case.mask) shouldBe true
        }
    }

    test("isGatewayInSubnet returns false when gateway is in a different subnet") {
        checkAll(minIterations, gatewayNotInSubnet) { case ->
            // Verify our generator actually produced a different network portion
            val gatewayNetwork = ipToLong(case.gateway) and ipToLong(case.mask)
            val ipNetwork = ipToLong(case.ip) and ipToLong(case.mask)
            // The network portions must differ for this test to be meaningful
            if (gatewayNetwork != ipNetwork) {
                NetworkConfigValidator.isGatewayInSubnet(case.gateway, case.ip, case.mask) shouldBe false
            }
        }
    }

    test("isGatewayInSubnet iff (gateway & mask) == (ip & mask) for random inputs") {
        checkAll(minIterations, validIpv4, validSubnetMask, validIpv4) { ip, mask, gateway ->
            val ipLong = ipToLong(ip)
            val maskLong = ipToLong(mask)
            val gatewayLong = ipToLong(gateway)

            val expectedInSubnet = (gatewayLong and maskLong) == (ipLong and maskLong)
            NetworkConfigValidator.isGatewayInSubnet(gateway, ip, mask) shouldBe expectedInSubnet
        }
    }

    test("validateGateway returns Valid when gateway is in subnet") {
        checkAll(minIterations, gatewayInSubnet) { case ->
            // Skip prefix 0 and 32 edge cases where mask is all-zeros or all-ones
            // as they may have special behavior
            val result = NetworkConfigValidator.validateGateway(case.gateway, case.ip, case.mask)
            result.shouldBeInstanceOf<ValidationResult.Valid>()
        }
    }

    test("validateGateway returns Warning when gateway is not in subnet") {
        checkAll(minIterations, gatewayNotInSubnet) { case ->
            val gatewayNetwork = ipToLong(case.gateway) and ipToLong(case.mask)
            val ipNetwork = ipToLong(case.ip) and ipToLong(case.mask)
            // Only assert when network portions actually differ
            if (gatewayNetwork != ipNetwork) {
                val result = NetworkConfigValidator.validateGateway(case.gateway, case.ip, case.mask)
                result.shouldBeInstanceOf<ValidationResult.Warning>()
                (result as ValidationResult.Warning).warningMessage shouldBe "网关不在子网范围内"
            }
        }
    }

    test("isGatewayInSubnet returns false for invalid IP inputs") {
        NetworkConfigValidator.isGatewayInSubnet("invalid", "192.168.1.1", "255.255.255.0") shouldBe false
        NetworkConfigValidator.isGatewayInSubnet("192.168.1.1", "invalid", "255.255.255.0") shouldBe false
        NetworkConfigValidator.isGatewayInSubnet("192.168.1.1", "192.168.1.1", "invalid") shouldBe false
    }

    test("validateGateway returns Invalid for invalid gateway format") {
        val result = NetworkConfigValidator.validateGateway("not.an.ip", "192.168.1.1", "255.255.255.0")
        result.shouldBeInstanceOf<ValidationResult.Invalid>()
        (result as ValidationResult.Invalid).errorMessage shouldBe "请输入有效的 IPv4 地址"
    }
})
