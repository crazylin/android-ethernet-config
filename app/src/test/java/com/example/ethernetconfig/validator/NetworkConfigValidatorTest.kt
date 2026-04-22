package com.example.ethernetconfig.validator

import com.example.ethernetconfig.model.ValidationResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NetworkConfigValidatorTest {

    @Nested
    inner class ValidateIpAddress {

        @Test
        fun `valid standard IP address returns Valid`() {
            val result = NetworkConfigValidator.validateIpAddress("192.168.1.100")
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `valid IP with all zeros returns Valid`() {
            val result = NetworkConfigValidator.validateIpAddress("0.0.0.0")
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `valid IP with max values returns Valid`() {
            val result = NetworkConfigValidator.validateIpAddress("255.255.255.255")
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `valid IP with mixed values returns Valid`() {
            val result = NetworkConfigValidator.validateIpAddress("10.0.255.1")
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `empty string returns Invalid`() {
            val result = NetworkConfigValidator.validateIpAddress("")
            assertTrue(result is ValidationResult.Invalid)
            assertEquals("请输入有效的 IPv4 地址", (result as ValidationResult.Invalid).errorMessage)
        }

        @Test
        fun `too few groups returns Invalid`() {
            val result = NetworkConfigValidator.validateIpAddress("192.168.1")
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `too many groups returns Invalid`() {
            val result = NetworkConfigValidator.validateIpAddress("192.168.1.1.1")
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `value above 255 returns Invalid`() {
            val result = NetworkConfigValidator.validateIpAddress("192.168.1.256")
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `negative value returns Invalid`() {
            val result = NetworkConfigValidator.validateIpAddress("192.168.1.-1")
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `leading zeros returns Invalid`() {
            val result = NetworkConfigValidator.validateIpAddress("192.168.01.1")
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `non-numeric characters returns Invalid`() {
            val result = NetworkConfigValidator.validateIpAddress("192.168.1.abc")
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `spaces in address returns Invalid`() {
            val result = NetworkConfigValidator.validateIpAddress("192.168.1. 1")
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `trailing dot returns Invalid`() {
            val result = NetworkConfigValidator.validateIpAddress("192.168.1.1.")
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `leading dot returns Invalid`() {
            val result = NetworkConfigValidator.validateIpAddress(".192.168.1.1")
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `empty group returns Invalid`() {
            val result = NetworkConfigValidator.validateIpAddress("192..168.1")
            assertTrue(result is ValidationResult.Invalid)
        }
    }

    @Nested
    inner class ValidateDnsServer {

        @Test
        fun `valid DNS address returns Valid`() {
            val result = NetworkConfigValidator.validateDnsServer("8.8.8.8")
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `valid secondary DNS returns Valid`() {
            val result = NetworkConfigValidator.validateDnsServer("8.8.4.4")
            assertTrue(result is ValidationResult.Valid)
        }

        @Test
        fun `invalid DNS returns Invalid with DNS-specific message`() {
            val result = NetworkConfigValidator.validateDnsServer("invalid")
            assertTrue(result is ValidationResult.Invalid)
            assertEquals("请输入有效的 DNS 服务器地址", (result as ValidationResult.Invalid).errorMessage)
        }

        @Test
        fun `empty DNS returns Invalid`() {
            val result = NetworkConfigValidator.validateDnsServer("")
            assertTrue(result is ValidationResult.Invalid)
            assertEquals("请输入有效的 DNS 服务器地址", (result as ValidationResult.Invalid).errorMessage)
        }

        @Test
        fun `DNS reuses IPv4 validation - value above 255 returns Invalid`() {
            val result = NetworkConfigValidator.validateDnsServer("8.8.8.256")
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `DNS reuses IPv4 validation - too few groups returns Invalid`() {
            val result = NetworkConfigValidator.validateDnsServer("8.8.8")
            assertTrue(result is ValidationResult.Invalid)
        }
    }
}
