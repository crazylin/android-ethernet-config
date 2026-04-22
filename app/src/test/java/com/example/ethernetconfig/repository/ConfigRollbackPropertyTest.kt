package com.example.ethernetconfig.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.NetworkConfiguration
import com.example.ethernetconfig.model.NetworkStatus
import com.example.ethernetconfig.network.EthernetManagerWrapper
import com.example.ethernetconfig.network.NetworkMonitor
import com.example.ethernetconfig.storage.ConfigStorage
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.Tag
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify

/**
 * Feature: android-ethernet-config, Property 7: 配置失败回滚
 *
 * Validates: Requirements 4.5
 *
 * 对任意初始有效配置和任意新配置，当新配置应用失败时，
 * 系统当前生效的配置应与应用前的初始配置完全一致。
 */
class ConfigRollbackPropertyTest : FunSpec({

    tags(Tag("Feature: android-ethernet-config"), Tag("Property 7: 配置失败回滚"))

    val minIterations = PropTestConfig(iterations = 20)

    // --- Generators ---

    /** 有效 IPv4 octet: 0-255 */
    val validOctet: Arb<Int> = Arb.int(0..255)

    /** 有效 IPv4 地址生成器 */
    val validIpv4: Arb<String> = Arb.bind(validOctet, validOctet, validOctet, validOctet) { a, b, c, d ->
        "$a.$b.$c.$d"
    }

    /** 有效 STATIC 模式 NetworkConfiguration 生成器 */
    val staticConfigArb: Arb<NetworkConfiguration> = arbitrary {
        NetworkConfiguration(
            mode = ConfigMode.STATIC,
            ipAddress = validIpv4.bind(),
            subnetMask = validIpv4.bind(),
            gateway = validIpv4.bind(),
            primaryDns = validIpv4.bind(),
            secondaryDns = validIpv4.bind()
        )
    }

    // --- Property Tests ---

    test("applyConfiguration rolls back to initial config when new config fails") {
        checkAll(minIterations, staticConfigArb, staticConfigArb) { initialConfig, newConfig ->
            // Arrange: mock dependencies
            val ethernetManager = mockk<EthernetManagerWrapper>()
            val networkMonitor = mockk<NetworkMonitor>()
            val configStorage = mockk<ConfigStorage>()

            // getConfiguration returns the initial config (snapshot for rollback)
            every { ethernetManager.getConfiguration() } returns initialConfig

            // setConfiguration fails for the new config
            every { ethernetManager.setConfiguration(newConfig) } returns
                Result.failure(RuntimeException("Configuration apply failed"))

            // setConfiguration succeeds for rollback (restoring initial config)
            every { ethernetManager.setConfiguration(initialConfig) } returns Result.success(Unit)

            val repository = EthernetConfigRepository(ethernetManager, networkMonitor, configStorage)

            // Act
            val result = repository.applyConfiguration(newConfig)

            // Assert: the apply should fail
            result.isFailure shouldBe true

            // Assert: rollback was attempted with the initial config
            verify(exactly = 1) { ethernetManager.setConfiguration(initialConfig) }
        }
    }

    test("applyConfiguration does not persist config when apply fails") {
        checkAll(minIterations, staticConfigArb, staticConfigArb) { initialConfig, newConfig ->
            val ethernetManager = mockk<EthernetManagerWrapper>()
            val networkMonitor = mockk<NetworkMonitor>()
            val configStorage = mockk<ConfigStorage>()

            every { ethernetManager.getConfiguration() } returns initialConfig
            every { ethernetManager.setConfiguration(newConfig) } returns
                Result.failure(RuntimeException("Configuration apply failed"))
            every { ethernetManager.setConfiguration(initialConfig) } returns Result.success(Unit)

            val repository = EthernetConfigRepository(ethernetManager, networkMonitor, configStorage)

            repository.applyConfiguration(newConfig)

            // saveConfiguration should never be called on failure
            verify(exactly = 0) { configStorage.saveConfiguration(any()) }
        }
    }

    test("applyConfiguration falls back to DHCP when both apply and rollback fail") {
        checkAll(minIterations, staticConfigArb, staticConfigArb) { initialConfig, newConfig ->
            val ethernetManager = mockk<EthernetManagerWrapper>()
            val networkMonitor = mockk<NetworkMonitor>()
            val configStorage = mockk<ConfigStorage>()

            every { ethernetManager.getConfiguration() } returns initialConfig

            // Both new config and rollback fail
            every { ethernetManager.setConfiguration(any()) } returns
                Result.failure(RuntimeException("Configuration failed"))

            // DHCP fallback succeeds
            every { ethernetManager.setDhcpMode() } returns Result.success(Unit)

            val repository = EthernetConfigRepository(ethernetManager, networkMonitor, configStorage)

            val result = repository.applyConfiguration(newConfig)

            // Assert: the apply should fail
            result.isFailure shouldBe true

            // Assert: DHCP fallback was attempted
            verify(atLeast = 1) { ethernetManager.setDhcpMode() }
        }
    }
})
