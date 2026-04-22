package com.example.ethernetconfig.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DataModelTest {

    @Test
    fun `ConfigMode has DHCP and STATIC values`() {
        val values = ConfigMode.values()
        assertEquals(2, values.size)
        assertTrue(values.contains(ConfigMode.DHCP))
        assertTrue(values.contains(ConfigMode.STATIC))
    }

    @Test
    fun `Field enum has all five fields`() {
        val values = Field.values()
        assertEquals(5, values.size)
        assertTrue(values.contains(Field.IP_ADDRESS))
        assertTrue(values.contains(Field.SUBNET_MASK))
        assertTrue(values.contains(Field.GATEWAY))
        assertTrue(values.contains(Field.PRIMARY_DNS))
        assertTrue(values.contains(Field.SECONDARY_DNS))
    }

    @Test
    fun `NetworkConfiguration defaults to empty strings`() {
        val config = NetworkConfiguration(mode = ConfigMode.DHCP)
        assertEquals(ConfigMode.DHCP, config.mode)
        assertEquals("", config.ipAddress)
        assertEquals("", config.subnetMask)
        assertEquals("", config.gateway)
        assertEquals("", config.primaryDns)
        assertEquals("", config.secondaryDns)
    }

    @Test
    fun `NetworkConfiguration stores all fields correctly`() {
        val config = NetworkConfiguration(
            mode = ConfigMode.STATIC,
            ipAddress = "192.168.1.100",
            subnetMask = "255.255.255.0",
            gateway = "192.168.1.1",
            primaryDns = "8.8.8.8",
            secondaryDns = "8.8.4.4"
        )
        assertEquals(ConfigMode.STATIC, config.mode)
        assertEquals("192.168.1.100", config.ipAddress)
        assertEquals("255.255.255.0", config.subnetMask)
        assertEquals("192.168.1.1", config.gateway)
        assertEquals("8.8.8.8", config.primaryDns)
        assertEquals("8.8.4.4", config.secondaryDns)
    }

    @Test
    fun `NetworkConfiguration data class equality works`() {
        val config1 = NetworkConfiguration(
            mode = ConfigMode.STATIC,
            ipAddress = "10.0.0.1",
            subnetMask = "255.0.0.0",
            gateway = "10.0.0.254",
            primaryDns = "1.1.1.1",
            secondaryDns = ""
        )
        val config2 = config1.copy()
        assertEquals(config1, config2)
    }

    @Test
    fun `NetworkStatus stores all fields correctly`() {
        val status = NetworkStatus(
            isConnected = true,
            currentMode = ConfigMode.DHCP,
            currentIpAddress = "192.168.1.50",
            currentSubnetMask = "255.255.255.0",
            currentGateway = "192.168.1.1",
            currentDns = listOf("8.8.8.8", "8.8.4.4")
        )
        assertTrue(status.isConnected)
        assertEquals(ConfigMode.DHCP, status.currentMode)
        assertEquals("192.168.1.50", status.currentIpAddress)
        assertEquals("255.255.255.0", status.currentSubnetMask)
        assertEquals("192.168.1.1", status.currentGateway)
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), status.currentDns)
    }

    @Test
    fun `NetworkStatus disconnected state has null fields`() {
        val status = NetworkStatus(
            isConnected = false,
            currentMode = null,
            currentIpAddress = null,
            currentSubnetMask = null,
            currentGateway = null,
            currentDns = null
        )
        assertFalse(status.isConnected)
        assertNull(status.currentMode)
        assertNull(status.currentIpAddress)
        assertNull(status.currentSubnetMask)
        assertNull(status.currentGateway)
        assertNull(status.currentDns)
    }

    @Test
    fun `ValidationResult Valid is singleton`() {
        val result = ValidationResult.Valid
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `ValidationResult Invalid carries error message`() {
        val result = ValidationResult.Invalid("请输入有效的 IPv4 地址")
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("请输入有效的 IPv4 地址", result.errorMessage)
    }

    @Test
    fun `ValidationResult Warning carries warning message`() {
        val result = ValidationResult.Warning("网关不在子网范围内")
        assertTrue(result is ValidationResult.Warning)
        assertEquals("网关不在子网范围内", result.warningMessage)
    }

    @Test
    fun `ConfigResult Success is singleton`() {
        val result = ConfigResult.Success
        assertTrue(result is ConfigResult.Success)
    }

    @Test
    fun `ConfigResult Failure carries reason`() {
        val result = ConfigResult.Failure("权限不足")
        assertTrue(result is ConfigResult.Failure)
        assertEquals("权限不足", result.reason)
    }

    @Test
    fun `ConfigResult Loading is singleton`() {
        val result = ConfigResult.Loading
        assertTrue(result is ConfigResult.Loading)
    }

    @Test
    fun `ValidationResult sealed class exhaustive when check`() {
        val results = listOf(
            ValidationResult.Valid,
            ValidationResult.Invalid("error"),
            ValidationResult.Warning("warning")
        )
        results.forEach { result ->
            val message = when (result) {
                is ValidationResult.Valid -> "valid"
                is ValidationResult.Invalid -> result.errorMessage
                is ValidationResult.Warning -> result.warningMessage
            }
            assertNotNull(message)
        }
    }

    @Test
    fun `ConfigResult sealed class exhaustive when check`() {
        val results = listOf(
            ConfigResult.Success,
            ConfigResult.Failure("fail"),
            ConfigResult.Loading
        )
        results.forEach { result ->
            val message = when (result) {
                is ConfigResult.Success -> "success"
                is ConfigResult.Failure -> result.reason
                is ConfigResult.Loading -> "loading"
            }
            assertNotNull(message)
        }
    }
}
