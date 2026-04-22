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
 * Feature: android-ethernet-config, Property 2: 子网掩码验证正确性
 *
 * Validates: Requirements 2.5
 *
 * 对任意前缀长度 n（0 ≤ n ≤ 32），由连续 n 个高位 1 和 (32-n) 个低位 0
 * 组成的 32 位二进制数转换为点分十进制后，validateSubnetMask 函数应返回 Valid；
 * 对任意不符合此规则的点分十进制字符串，应返回 Invalid。
 */
class SubnetMaskValidationPropertyTest : FunSpec({

    tags(Tag("Feature: android-ethernet-config"), Tag("Property 2: 子网掩码验证正确性"))

    val minIterations = PropTestConfig(iterations = 20)

    // --- Helper ---

    /** 将前缀长度 (0-32) 转换为点分十进制子网掩码 */
    fun prefixToMask(prefixLength: Int): String {
        val bits = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        return listOf(
            (bits shr 24) and 0xFF,
            (bits shr 16) and 0xFF,
            (bits shr 8) and 0xFF,
            bits and 0xFF
        ).joinToString(".")
    }

    // --- Generators ---

    /** 有效子网掩码生成器：从前缀长度 0-32 生成对应的点分十进制掩码 */
    val validSubnetMask: Arb<String> = Arb.int(0..32).map { prefixLength ->
        prefixToMask(prefixLength)
    }

    /** 无效子网掩码生成器 - 不满足连续高位 1 规则（如 255.0.255.0） */
    val invalidSubnetMaskNonContiguous: Arb<String> = arbitrary {
        // Generate a 32-bit value that has non-contiguous 1s
        // Strategy: pick two bit positions where we force a 0-then-1 pattern
        // e.g., set some high bits, clear a middle bit, set some low bits
        val highBits = Arb.int(1..31).bind()  // number of leading 1s
        val mask = ((0xFFFFFFFFL shl (32 - highBits)) and 0xFFFFFFFFL)
        // Clear one bit in the leading 1s region and set one bit in the trailing 0s region
        // This guarantees non-contiguous 1s
        val clearPos = Arb.int(32 - highBits until 32).bind()  // position in the 1s region
        val setPos = Arb.int(0 until (32 - highBits)).bind()    // position in the 0s region
        val broken = (mask and (1L shl clearPos).inv()) or (1L shl setPos)
        val finalMask = broken and 0xFFFFFFFFL
        listOf(
            (finalMask shr 24) and 0xFF,
            (finalMask shr 16) and 0xFF,
            (finalMask shr 8) and 0xFF,
            finalMask and 0xFF
        ).joinToString(".")
    }

    /** 无效子网掩码生成器 - 无效 IPv4 格式（超范围 octet） */
    val invalidSubnetMaskBadFormat: Arb<String> = arbitrary {
        val octets = List(4) { Arb.int(0..255).bind() }.toMutableList()
        val pos = Arb.int(0..3).bind()
        octets[pos] = Arb.int(256..999).bind()
        octets.joinToString(".")
    }

    /** 无效子网掩码生成器 - 错误段数（不是 4 段） */
    val invalidSubnetMaskWrongSegments: Arb<String> = arbitrary {
        val count = Arb.element(1, 2, 3, 5, 6).bind()
        List(count) { Arb.int(0..255).bind().toString() }.joinToString(".")
    }

    /** 无效子网掩码生成器 - 非数字字符 */
    val invalidSubnetMaskNonDigit: Arb<String> = arbitrary {
        val octets = List(4) { Arb.int(0..255).bind().toString() }.toMutableList()
        val pos = Arb.int(0..3).bind()
        val nonDigitChar = Arb.element('a'..'z').bind()
        octets[pos] = "${octets[pos]}$nonDigitChar"
        octets.joinToString(".")
    }

    // --- Property Tests ---

    test("validateSubnetMask returns Valid for any valid subnet mask from prefix length 0-32") {
        checkAll(minIterations, validSubnetMask) { mask ->
            val result = NetworkConfigValidator.validateSubnetMask(mask)
            result.shouldBeInstanceOf<ValidationResult.Valid>()
        }
    }

    test("isValidSubnetMask returns true for any valid subnet mask from prefix length 0-32") {
        checkAll(minIterations, validSubnetMask) { mask ->
            NetworkConfigValidator.isValidSubnetMask(mask) shouldBe true
        }
    }

    test("validateSubnetMask returns Invalid for non-contiguous bit masks") {
        checkAll(minIterations, invalidSubnetMaskNonContiguous) { mask ->
            val result = NetworkConfigValidator.validateSubnetMask(mask)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
        }
    }

    test("isValidSubnetMask returns false for non-contiguous bit masks") {
        checkAll(minIterations, invalidSubnetMaskNonContiguous) { mask ->
            NetworkConfigValidator.isValidSubnetMask(mask) shouldBe false
        }
    }

    test("validateSubnetMask returns Invalid for masks with out-of-range octets") {
        checkAll(minIterations, invalidSubnetMaskBadFormat) { mask ->
            val result = NetworkConfigValidator.validateSubnetMask(mask)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
        }
    }

    test("validateSubnetMask returns Invalid for masks with wrong segment count") {
        checkAll(minIterations, invalidSubnetMaskWrongSegments) { mask ->
            val result = NetworkConfigValidator.validateSubnetMask(mask)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
        }
    }

    test("validateSubnetMask returns Invalid for masks with non-digit characters") {
        checkAll(minIterations, invalidSubnetMaskNonDigit) { mask ->
            val result = NetworkConfigValidator.validateSubnetMask(mask)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
        }
    }

    test("validateSubnetMask returns Invalid for empty string") {
        val result = NetworkConfigValidator.validateSubnetMask("")
        result.shouldBeInstanceOf<ValidationResult.Invalid>()
    }

    test("validateSubnetMask Invalid result contains correct error message") {
        checkAll(minIterations, invalidSubnetMaskNonContiguous) { mask ->
            val result = NetworkConfigValidator.validateSubnetMask(mask)
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            (result as ValidationResult.Invalid).errorMessage shouldBe "请输入有效的子网掩码"
        }
    }

    test("all 33 valid prefix-length masks are accepted") {
        // Exhaustive check for all valid subnet masks (prefix 0 through 32)
        for (prefix in 0..32) {
            val mask = prefixToMask(prefix)
            val result = NetworkConfigValidator.validateSubnetMask(mask)
            result.shouldBeInstanceOf<ValidationResult.Valid>()
        }
    }
})
