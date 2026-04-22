package com.example.ethernetconfig.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.NetworkConfiguration
import com.example.ethernetconfig.model.NetworkStatus
import com.example.ethernetconfig.repository.EthernetConfigRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.Tag
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk

/**
 * Feature: android-ethernet-config, Property 4 & 5: ViewModel 模式切换属性测试
 *
 * 属性 4：切换到 DHCP 模式清除静态参数
 * Validates: Requirements 1.4
 *
 * 属性 5：切换到 STATIC 模式预填充 DHCP 参数
 * Validates: Requirements 1.5
 */
class ViewModelModeSwitchPropertyTest : FunSpec({

    tags(
        Tag("Feature: android-ethernet-config"),
        Tag("Property 4: 切换到 DHCP 模式清除静态参数"),
        Tag("Property 5: 切换到 STATIC 模式预填充 DHCP 参数")
    )

    // Use InstantTaskExecutorRule to make LiveData work synchronously in tests.
    // Since Kotest FunSpec doesn't support JUnit rules directly, we invoke
    // the protected starting/finished methods via reflection.
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

    val minIterations = PropTestConfig(iterations = 20)

    // --- Generators ---

    /** 有效 IPv4 octet: 0-255 */
    val validOctet: Arb<Int> = Arb.int(0..255)

    /** 有效 IPv4 地址生成器 */
    val validIpv4: Arb<String> = Arb.bind(validOctet, validOctet, validOctet, validOctet) { a, b, c, d ->
        "$a.$b.$c.$d"
    }

    /** 有效 STATIC 模式 NetworkConfiguration 生成器（用于属性 4） */
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

    /** 有效 DHCP 已连接 NetworkStatus 生成器（用于属性 5） */
    val dhcpConnectedStatusArb: Arb<NetworkStatus> = arbitrary {
        NetworkStatus(
            isConnected = true,
            currentMode = ConfigMode.DHCP,
            currentIpAddress = validIpv4.bind(),
            currentSubnetMask = validIpv4.bind(),
            currentGateway = validIpv4.bind(),
            currentDns = listOf(validIpv4.bind(), validIpv4.bind())
        )
    }

    // --- Helper: create ViewModel with mocked dependencies ---

    fun createViewModel(
        networkStatusLiveData: MutableLiveData<NetworkStatus> = MutableLiveData(
            NetworkStatus(
                isConnected = false, currentMode = null, currentIpAddress = null,
                currentSubnetMask = null, currentGateway = null, currentDns = null
            )
        ),
        storedConfig: NetworkConfiguration? = null
    ): EthernetConfigViewModel {
        val application = mockk<Application>(relaxed = true)
        val repository = mockk<EthernetConfigRepository>()

        every { repository.observeNetworkStatus() } returns networkStatusLiveData
        every { repository.getStoredConfiguration() } returns storedConfig
        every { repository.getAvailableInterfaces() } returns emptyList()

        return EthernetConfigViewModel(application, repository)
    }

    // --- Property 4: 切换到 DHCP 模式清除静态参数 ---

    /**
     * **Validates: Requirements 1.4**
     *
     * 对任意有效的 STATIC 模式配置，当执行 setConfigMode(DHCP) 后，
     * ViewModel 中的所有手动配置字段应被清空。
     */
    test("Property 4: setConfigMode(DHCP) clears all static configuration fields") {
        checkAll(minIterations, staticConfigArb) { config ->
            val viewModel = createViewModel()

            // Pre-fill the ViewModel with random STATIC config values
            viewModel.ipAddress.value = config.ipAddress
            viewModel.subnetMask.value = config.subnetMask
            viewModel.gateway.value = config.gateway
            viewModel.primaryDns.value = config.primaryDns
            viewModel.secondaryDns.value = config.secondaryDns

            // Switch to DHCP
            viewModel.setConfigMode(ConfigMode.DHCP)

            // All fields should be cleared
            viewModel.ipAddress.value shouldBe ""
            viewModel.subnetMask.value shouldBe ""
            viewModel.gateway.value shouldBe ""
            viewModel.primaryDns.value shouldBe ""
            viewModel.secondaryDns.value shouldBe ""
            viewModel.configMode.value shouldBe ConfigMode.DHCP
        }
    }

    // --- Property 5: 切换到 STATIC 模式预填充 DHCP 参数 ---

    /**
     * **Validates: Requirements 1.5**
     *
     * 对任意 NetworkStatus（处于已连接的 DHCP 模式），当执行 setConfigMode(STATIC) 后，
     * ViewModel 中的配置字段应包含 NetworkStatus 中对应的当前参数值。
     */
    test("Property 5: setConfigMode(STATIC) prefills fields from DHCP NetworkStatus") {
        checkAll(minIterations, dhcpConnectedStatusArb) { status ->
            val networkStatusLiveData = MutableLiveData(status)
            val viewModel = createViewModel(networkStatusLiveData = networkStatusLiveData)

            // Switch to STATIC
            viewModel.setConfigMode(ConfigMode.STATIC)

            // Fields should be prefilled from NetworkStatus
            viewModel.ipAddress.value shouldBe (status.currentIpAddress ?: "")
            viewModel.subnetMask.value shouldBe (status.currentSubnetMask ?: "")
            viewModel.gateway.value shouldBe (status.currentGateway ?: "")
            viewModel.primaryDns.value shouldBe (status.currentDns?.getOrNull(0) ?: "")
            viewModel.secondaryDns.value shouldBe (status.currentDns?.getOrNull(1) ?: "")
            viewModel.configMode.value shouldBe ConfigMode.STATIC
        }
    }
})
