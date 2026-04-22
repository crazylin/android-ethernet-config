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
 * Feature: android-ethernet-config, Property 1: IPv4 地址验证正确性
 *
 * Validates: Requirements 2.4, 2.6, 3.3
 *
 * 对任意由四组 0-255 范围内的十进制数字以点号分隔组成的字符串，
 * validateIpAddress 函数应返回 Valid；
 * 对任意不符合此格式的字符串，应返回 Invalid。
 */
class Ipv4ValidationPropertyTest : FunSpec({

    tags(Tag("Feature: android-ethernet-config"), Tag("Property 1: IPv4 地址验证正确性"))

    val minIterations = PropTestConfig(iterations = 20)

    // --- Generators ---

    /** 有效 IPv4 octet: 0-255 */
    val validOctet: Arb<Int> = Arb.int(0..255)

    /** 有效 IPv4 地址生成器：四组 0-255 的随机数字，以点号连接 */
    val validIpv4: Arb<String> = Arb.bind(validOctet, validOctet, validOctet, validOctet) { a, b, c, d ->
        "$a.$b.$c.$d"
    }

    /** 无效 IPv4 生成器 - 超范围数字（至少一个 octet > 255） */
    val invalidIpv4OutOfRange: Arb<String> = arbitrary {
        val octets = List(4) { Arb.int(0..255).bind() }.toMutableList()
        // Pick a random position and set it to an out-of-range value (256-999)
        val pos = Arb.int(0..3).bind()
        octets[pos] = Arb.int(256..999).bind()
        octets.joinToString(".")
    }

    /** 无效 IPv4 生成器 - 非数字字符 */
    val invalidIpv4NonDigit: Arb<String> = arbitrary {
        val octets = List(4) { Arb.int(0..255).bind().toString() }.toMutableList()
        val pos = Arb.int(0..3).bind()
        // Replace one octet with a string containing non-digit characters
        val nonDigitChar = Arb.element('a'..'z').bind()
        octets[pos] = "${octets[pos]}$nonDigitChar"
        octets.joinToString(".")
    }

    /** 无效 IPv4 生成器 - 错误分隔符（使用非点号分隔符） */
    val invalidIpv4WrongSeparator: Arb<String> = arbitrary {
        val octets = List(4) { Arb.int(0..255).bind().toString() }
        val separator = Arb.element(',', ':', ';', '/', '-', ' ').bind()
        octets.joinToString(separator.toString())
    }

    /** 无效 IPv4 生成器 - 错误段数（不是 4 段） */
    val invalidIpv4WrongSegmentCount: Arb<String> = arbitrary {
        // Generate 1-3 or 5-8 segments (anything but 4)
        val count = Arb.element(1, 2, 3, 5, 6, 7, 8).bind()
        val octets = List(count) { Arb.int(0..255).bind().toString() }
        octets.joinToString(".")
    }

    /** 无效 IPv4 生成器 - 前导零 */
    val invalidIpv4LeadingZeros: Arb<String> = arbitrary {
        val octets = List(4) { Arb.int(0..255).bind().toString() }.toMutableList()
        val pos = Arb.int(0..3).bind()
        // Add a leading zero to a value that is not "0" itself
        val value = Arb.int(1..255).bind()
        octets[pos] = "0$value"
        octets.joinToString(".")
    }

    // --- Property Tests ---

    test("validateIpAddress returns Valid for any valid IPv4 address") {
        checkAll(minIterations, validIpv4) { ip ->
            val result = NetworkConfigValidator.validateIpAddress(ip)
            result.shouldBeInstanceOf<ValidationResult.Valid>()
        }
    }

    test("validateDnsServer returns Valid for any valid IPv4 address") {
        checkAll(minIterations, validIpv4) { ip ->
            val result = NetworkConfigValidator.validateDnsServer(ip)
            result.shouldBeInstanceOf<ValidationResult.Valid>()
        }
    }

    test("validateIpAddress returns Invalid for IPv4 with out-of-range octets") {
        checkAll(minIterations, invalidIpv4OutOfRange) { ip ->
            val result = NetworkConfigValidator.validateIpAddress(ip)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
        }
    }

    test("validateIpAddress returns Invalid for IPv4 with non-digit characters") {
        checkAll(minIterations, invalidIpv4NonDigit) { ip ->
            val result = NetworkConfigValidator.validateIpAddress(ip)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
        }
    }

    test("validateIpAddress returns Invalid for IPv4 with wrong separators") {
        checkAll(minIterations, invalidIpv4WrongSeparator) { ip ->
            val result = NetworkConfigValidator.validateIpAddress(ip)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
        }
    }

    test("validateIpAddress returns Invalid for IPv4 with wrong segment count") {
        checkAll(minIterations, invalidIpv4WrongSegmentCount) { ip ->
            val result = NetworkConfigValidator.validateIpAddress(ip)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
        }
    }

    test("validateIpAddress returns Invalid for IPv4 with leading zeros") {
        checkAll(minIterations, invalidIpv4LeadingZeros) { ip ->
            val result = NetworkConfigValidator.validateIpAddress(ip)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
        }
    }

    test("validateIpAddress returns Invalid for empty string") {
        val result = NetworkConfigValidator.validateIpAddress("")
        result.shouldBeInstanceOf<ValidationResult.Invalid>()
    }

    test("validateDnsServer returns Invalid for any invalid IPv4 string") {
        checkAll(minIterations, invalidIpv4OutOfRange) { ip ->
            val result = NetworkConfigValidator.validateDnsServer(ip)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
        }
    }

    test("validateIpAddress Invalid result contains error message") {
        checkAll(minIterations, invalidIpv4OutOfRange) { ip ->
            val result = NetworkConfigValidator.validateIpAddress(ip)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            (result as ValidationResult.Invalid).errorMessage shouldBe "请输入有效的 IPv4 地址"
        }
    }

    test("validateDnsServer Invalid result contains DNS-specific error message") {
        checkAll(minIterations, invalidIpv4OutOfRange) { ip ->
            val result = NetworkConfigValidator.validateDnsServer(ip)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            (result as ValidationResult.Invalid).errorMessage shouldBe "请输入有效的 DNS 服务器地址"
        }
    }
})
