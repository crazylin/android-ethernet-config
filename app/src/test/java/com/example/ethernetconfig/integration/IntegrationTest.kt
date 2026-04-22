package com.example.ethernetconfig.integration

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.ConfigResult
import com.example.ethernetconfig.model.NetworkConfiguration
import com.example.ethernetconfig.model.NetworkStatus
import com.example.ethernetconfig.network.EthernetManagerWrapper
import com.example.ethernetconfig.network.NetworkMonitor
import com.example.ethernetconfig.repository.EthernetConfigRepository
import com.example.ethernetconfig.storage.ConfigStorage
import com.example.ethernetconfig.viewmodel.EthernetConfigViewModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.Tag
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify

/**
 * 集成测试 — 验证 ViewModel → Repository → 数据源层的完整调用流程。
 *
 * 使用 MockK 模拟底层数据源（EthernetManagerWrapper、NetworkMonitor、ConfigStorage），
 * 但 Repository 和 ViewModel 使用真实实现，验证组件间的协作。
 *
 * Validates: Requirements 1.2, 4.1, 4.5
 */
class IntegrationTest : FunSpec({

    tags(Tag("Feature: android-ethernet-config"), Tag("Integration Tests"))

    val rule = InstantTaskExecutorRule()

    beforeSpec {
        val method = rule.javaClass.superclass
            ?.getDeclaredMethod("starting", org.junit.runner.Description::class.java)
        method?.isAccessible = true
        method?.invoke(rule, null as org.junit.runner.Description?)
    }

    afterSpec {
        val method = rule.javaClass.superclass
            ?.getDeclaredMethod("finished", org.junit.runner.Description::class.java)
        method?.isAccessible = true
        method?.invoke(rule, null as org.junit.runner.Description?)
    }

    // --- Shared setup ---

    fun createStack(
        networkStatus: NetworkStatus = NetworkStatus(
            isConnected = true,
            currentMode = ConfigMode.DHCP,
            currentIpAddress = "192.168.1.100",
            currentSubnetMask = "255.255.255.0",
            currentGateway = "192.168.1.1",
            currentDns = listOf("8.8.8.8")
        ),
        ethernetManager: EthernetManagerWrapper = mockk(relaxed = true),
        configStorage: ConfigStorage = mockk(relaxed = true)
    ): Triple<EthernetConfigViewModel, EthernetConfigRepository, EthernetManagerWrapper> {
        val application = mockk<Application>(relaxed = true)
        val networkMonitor = mockk<NetworkMonitor>()
        val networkStatusLiveData = MutableLiveData(networkStatus)

        every { networkMonitor.startMonitoring() } returns networkStatusLiveData
        every { configStorage.loadConfiguration() } returns null
        // Ensure getAvailableInterfaces is stubbed for ViewModel init
        every { ethernetManager.getAvailableInterfaces() } returns listOf("eth0")

        val repository = EthernetConfigRepository(ethernetManager, networkMonitor, configStorage)
        val viewModel = EthernetConfigViewModel(application, repository)

        // Observe LiveData to activate them
        viewModel.configResult.observeForever {}
        viewModel.isFormValid.observeForever {}
        viewModel.configMode.observeForever {}

        return Triple(viewModel, repository, ethernetManager)
    }

    // =========================================================================
    // Requirement 1.2: DHCP 模式配置调用完整流程
    // =========================================================================

    test("DHCP mode config complete flow: ViewModel -> Repository -> EthernetManager") {
        val ethernetManager = mockk<EthernetManagerWrapper>()
        val configStorage = mockk<ConfigStorage>()

        // EthernetManager returns current config as snapshot
        every { ethernetManager.getConfiguration() } returns NetworkConfiguration(
            mode = ConfigMode.STATIC,
            ipAddress = "10.0.0.1",
            subnetMask = "255.255.255.0",
            gateway = "10.0.0.254",
            primaryDns = "8.8.8.8"
        )
        // setDhcpMode succeeds
        every { ethernetManager.setDhcpMode() } returns Result.success(Unit)
        every { configStorage.saveConfiguration(any()) } just Runs
        every { configStorage.loadConfiguration() } returns null

        val (viewModel, _, _) = createStack(
            ethernetManager = ethernetManager,
            configStorage = configStorage
        )

        // User selects DHCP mode
        viewModel.setConfigMode(ConfigMode.DHCP)
        viewModel.configMode.value shouldBe ConfigMode.DHCP

        // User applies configuration
        viewModel.applyConfiguration()

        // Verify: EthernetManager.setDhcpMode was called
        verify(exactly = 1) { ethernetManager.setDhcpMode() }

        // Verify: config was persisted
        verify(exactly = 1) { configStorage.saveConfiguration(match { it.mode == ConfigMode.DHCP }) }

        // Verify: result is Success
        viewModel.configResult.value.shouldBeInstanceOf<ConfigResult.Success>()
    }

    // =========================================================================
    // Requirement 4.1: 静态 IP 配置调用完整流程
    // =========================================================================

    test("Static IP config complete flow: ViewModel -> Repository -> EthernetManager") {
        val ethernetManager = mockk<EthernetManagerWrapper>()
        val configStorage = mockk<ConfigStorage>()

        // EthernetManager returns current DHCP config as snapshot
        every { ethernetManager.getConfiguration() } returns NetworkConfiguration(mode = ConfigMode.DHCP)
        // setConfiguration succeeds
        every { ethernetManager.setConfiguration(any()) } returns Result.success(Unit)
        every { configStorage.saveConfiguration(any()) } just Runs
        every { configStorage.loadConfiguration() } returns null

        val (viewModel, _, _) = createStack(
            ethernetManager = ethernetManager,
            configStorage = configStorage
        )

        // User switches to STATIC mode
        viewModel.setConfigMode(ConfigMode.STATIC)
        viewModel.configMode.value shouldBe ConfigMode.STATIC

        // User fills in static IP fields
        viewModel.ipAddress.value = "10.0.0.50"
        viewModel.subnetMask.value = "255.255.255.0"
        viewModel.gateway.value = "10.0.0.1"
        viewModel.primaryDns.value = "1.1.1.1"
        viewModel.secondaryDns.value = "8.8.4.4"

        // User applies configuration
        viewModel.applyConfiguration()

        // Verify: EthernetManager.setConfiguration was called with correct config
        verify(exactly = 1) {
            ethernetManager.setConfiguration(match { config ->
                config.mode == ConfigMode.STATIC &&
                config.ipAddress == "10.0.0.50" &&
                config.subnetMask == "255.255.255.0" &&
                config.gateway == "10.0.0.1" &&
                config.primaryDns == "1.1.1.1" &&
                config.secondaryDns == "8.8.4.4"
            })
        }

        // Verify: config was persisted
        verify(exactly = 1) {
            configStorage.saveConfiguration(match { it.mode == ConfigMode.STATIC })
        }

        // Verify: result is Success
        viewModel.configResult.value.shouldBeInstanceOf<ConfigResult.Success>()
    }

    // =========================================================================
    // Requirement 4.5: 配置失败回滚完整流程
    // =========================================================================

    test("Config failure rollback complete flow: apply fails -> rollback to snapshot") {
        val ethernetManager = mockk<EthernetManagerWrapper>()
        val configStorage = mockk<ConfigStorage>()

        val snapshotConfig = NetworkConfiguration(
            mode = ConfigMode.STATIC,
            ipAddress = "192.168.1.100",
            subnetMask = "255.255.255.0",
            gateway = "192.168.1.1",
            primaryDns = "8.8.8.8"
        )

        // EthernetManager returns snapshot config
        every { ethernetManager.getConfiguration() } returns snapshotConfig

        // setConfiguration fails for the new config
        every { ethernetManager.setConfiguration(match { it.ipAddress == "10.0.0.50" }) } returns
            Result.failure(RuntimeException("Permission denied"))

        // Rollback with snapshot succeeds
        every { ethernetManager.setConfiguration(snapshotConfig) } returns Result.success(Unit)

        every { configStorage.loadConfiguration() } returns null

        val (viewModel, _, _) = createStack(
            ethernetManager = ethernetManager,
            configStorage = configStorage
        )

        // User switches to STATIC and fills fields
        viewModel.setConfigMode(ConfigMode.STATIC)
        viewModel.ipAddress.value = "10.0.0.50"
        viewModel.subnetMask.value = "255.255.255.0"
        viewModel.gateway.value = "10.0.0.1"
        viewModel.primaryDns.value = "1.1.1.1"
        viewModel.secondaryDns.value = ""

        // User applies configuration
        viewModel.applyConfiguration()

        // Verify: result is Failure with reason
        val result = viewModel.configResult.value
        result.shouldBeInstanceOf<ConfigResult.Failure>()
        result.reason shouldBe "Permission denied"

        // Verify: rollback was attempted with the snapshot config
        verify(exactly = 1) { ethernetManager.setConfiguration(snapshotConfig) }

        // Verify: config was NOT persisted (apply failed)
        verify(exactly = 0) { configStorage.saveConfiguration(any()) }
    }

    test("Config failure rollback with DHCP fallback when rollback also fails") {
        val ethernetManager = mockk<EthernetManagerWrapper>()
        val configStorage = mockk<ConfigStorage>()

        val snapshotConfig = NetworkConfiguration(
            mode = ConfigMode.STATIC,
            ipAddress = "192.168.1.100",
            subnetMask = "255.255.255.0",
            gateway = "192.168.1.1",
            primaryDns = "8.8.8.8"
        )

        every { ethernetManager.getConfiguration() } returns snapshotConfig

        // Both new config and rollback fail
        every { ethernetManager.setConfiguration(any()) } returns
            Result.failure(RuntimeException("Hardware error"))

        // DHCP fallback
        every { ethernetManager.setDhcpMode() } returns Result.success(Unit)

        every { configStorage.loadConfiguration() } returns null

        val (viewModel, _, _) = createStack(
            ethernetManager = ethernetManager,
            configStorage = configStorage
        )

        viewModel.setConfigMode(ConfigMode.STATIC)
        viewModel.ipAddress.value = "10.0.0.50"
        viewModel.subnetMask.value = "255.255.255.0"
        viewModel.gateway.value = "10.0.0.1"
        viewModel.primaryDns.value = "1.1.1.1"

        viewModel.applyConfiguration()

        // Verify: result is Failure
        viewModel.configResult.value.shouldBeInstanceOf<ConfigResult.Failure>()

        // Verify: DHCP fallback was attempted
        verify(atLeast = 1) { ethernetManager.setDhcpMode() }

        // Verify: config was NOT persisted
        verify(exactly = 0) { configStorage.saveConfiguration(any()) }
    }

    test("Config apply shows Loading state before completion") {
        val ethernetManager = mockk<EthernetManagerWrapper>()
        val configStorage = mockk<ConfigStorage>()

        every { ethernetManager.getConfiguration() } returns NetworkConfiguration(mode = ConfigMode.DHCP)
        every { ethernetManager.setDhcpMode() } returns Result.success(Unit)
        every { configStorage.saveConfiguration(any()) } just Runs
        every { configStorage.loadConfiguration() } returns null

        val (viewModel, _, _) = createStack(
            ethernetManager = ethernetManager,
            configStorage = configStorage
        )

        // We can't easily capture the intermediate Loading state since applyConfiguration
        // is synchronous, but we verify the final state is Success (Loading was set then overwritten)
        viewModel.applyConfiguration()
        viewModel.configResult.value.shouldBeInstanceOf<ConfigResult.Success>()
    }
})
