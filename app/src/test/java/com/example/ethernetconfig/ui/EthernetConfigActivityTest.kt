package com.example.ethernetconfig.ui

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.Field
import com.example.ethernetconfig.model.NetworkStatus
import com.example.ethernetconfig.model.ValidationResult
import com.example.ethernetconfig.repository.EthernetConfigRepository
import com.example.ethernetconfig.viewmodel.EthernetConfigViewModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.Tag
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk

/**
 * UI 层单元测试 — 通过 ViewModel 行为验证 UI 驱动逻辑。
 *
 * 由于这是本地 JVM 测试（非 instrumented），我们测试 ViewModel 暴露给 UI 的
 * LiveData 状态和方法行为，而非实际 Android View。
 *
 * Validates: Requirements 1.1, 1.3, 2.1, 2.2, 2.3, 3.1, 3.2, 3.4, 3.5, 6.1, 6.2, 6.5
 */
class EthernetConfigActivityTest : FunSpec({

    tags(Tag("Feature: android-ethernet-config"), Tag("UI Unit Tests"))

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

    // --- Helper ---

    fun createViewModel(
        networkStatus: NetworkStatus = NetworkStatus(
            isConnected = true,
            currentMode = ConfigMode.DHCP,
            currentIpAddress = "192.168.1.100",
            currentSubnetMask = "255.255.255.0",
            currentGateway = "192.168.1.1",
            currentDns = listOf("8.8.8.8", "8.8.4.4")
        )
    ): EthernetConfigViewModel {
        val application = mockk<Application>(relaxed = true)
        val repository = mockk<EthernetConfigRepository>(relaxed = true)
        val networkStatusLiveData = MutableLiveData(networkStatus)

        every { repository.observeNetworkStatus() } returns networkStatusLiveData
        every { repository.getStoredConfiguration() } returns null
        every { repository.getAvailableInterfaces() } returns emptyList()

        val vm = EthernetConfigViewModel(application, repository)
        // Observe LiveData to activate MediatorLiveData
        vm.isFormValid.observeForever {}
        vm.validationErrors.observeForever {}
        vm.configMode.observeForever {}
        return vm
    }

    // =========================================================================
    // Requirement 1.1: DHCP/STATIC 模式选项存在且可切换
    // =========================================================================

    test("configMode defaults to DHCP") {
        val vm = createViewModel()
        vm.configMode.value shouldBe ConfigMode.DHCP
    }

    test("configMode can be switched to STATIC") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)
        vm.configMode.value shouldBe ConfigMode.STATIC
    }

    test("configMode can be switched back to DHCP from STATIC") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)
        vm.setConfigMode(ConfigMode.DHCP)
        vm.configMode.value shouldBe ConfigMode.DHCP
    }

    // =========================================================================
    // Requirement 1.3, 2.1, 2.2, 2.3, 3.1, 3.2: 静态模式下所有输入字段可见
    // (We verify that switching to STATIC pre-fills fields, meaning the UI
    //  should display them. All 5 fields are exposed as LiveData.)
    // =========================================================================

    test("switching to STATIC mode exposes all 5 configuration fields with values") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        // All fields should be pre-filled from NetworkStatus (connected DHCP)
        vm.ipAddress.value shouldBe "192.168.1.100"
        vm.subnetMask.value shouldBe "255.255.255.0"
        vm.gateway.value shouldBe "192.168.1.1"
        vm.primaryDns.value shouldBe "8.8.8.8"
        vm.secondaryDns.value shouldBe "8.8.4.4"
    }

    test("all form fields are accessible LiveData properties in STATIC mode") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        // Verify each field can be set independently
        vm.ipAddress.value = "10.0.0.1"
        vm.subnetMask.value = "255.0.0.0"
        vm.gateway.value = "10.0.0.254"
        vm.primaryDns.value = "1.1.1.1"
        vm.secondaryDns.value = "1.0.0.1"

        vm.ipAddress.value shouldBe "10.0.0.1"
        vm.subnetMask.value shouldBe "255.0.0.0"
        vm.gateway.value shouldBe "10.0.0.254"
        vm.primaryDns.value shouldBe "1.1.1.1"
        vm.secondaryDns.value shouldBe "1.0.0.1"
    }

    // =========================================================================
    // Requirement 6.1: 无效 IP 输入时显示 "请输入有效的 IPv4 地址" 错误提示
    // =========================================================================

    test("invalid IP address produces '请输入有效的 IPv4 地址' error") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        val result = vm.validateField(Field.IP_ADDRESS, "999.999.999.999")

        result.shouldBeInstanceOf<ValidationResult.Invalid>()
        result.errorMessage shouldBe "请输入有效的 IPv4 地址"
    }

    test("invalid IP address is stored in validationErrors map") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        vm.validateField(Field.IP_ADDRESS, "abc.def.ghi.jkl")

        val errors = vm.validationErrors.value!!
        errors[Field.IP_ADDRESS] shouldBe "请输入有效的 IPv4 地址"
    }

    test("valid IP address clears error from validationErrors") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        vm.validateField(Field.IP_ADDRESS, "not-valid")
        vm.validationErrors.value!![Field.IP_ADDRESS] shouldBe "请输入有效的 IPv4 地址"

        vm.validateField(Field.IP_ADDRESS, "192.168.1.50")
        vm.validationErrors.value!!.containsKey(Field.IP_ADDRESS) shouldBe false
    }

    // =========================================================================
    // Requirement 6.2: 无效子网掩码输入时显示 "请输入有效的子网掩码" 错误提示
    // =========================================================================

    test("invalid subnet mask produces '请输入有效的子网掩码' error") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        val result = vm.validateField(Field.SUBNET_MASK, "255.255.128.128")

        result.shouldBeInstanceOf<ValidationResult.Invalid>()
        result.errorMessage shouldBe "请输入有效的子网掩码"
    }

    test("invalid subnet mask is stored in validationErrors map") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        vm.validateField(Field.SUBNET_MASK, "123.456.789.0")

        val errors = vm.validationErrors.value!!
        errors[Field.SUBNET_MASK] shouldBe "请输入有效的子网掩码"
    }

    test("valid subnet mask clears error from validationErrors") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        vm.validateField(Field.SUBNET_MASK, "invalid")
        vm.validationErrors.value!![Field.SUBNET_MASK] shouldBe "请输入有效的子网掩码"

        vm.validateField(Field.SUBNET_MASK, "255.255.255.0")
        vm.validationErrors.value!!.containsKey(Field.SUBNET_MASK) shouldBe false
    }

    // =========================================================================
    // Requirement 6.5: 未连接时显示 "未检测到以太网连接" 并禁用配置操作
    // (We verify the ViewModel exposes disconnected NetworkStatus that the UI
    //  would use to show the message and disable controls.)
    // =========================================================================

    test("disconnected network status is exposed via networkStatus LiveData") {
        val disconnectedStatus = NetworkStatus(
            isConnected = false,
            currentMode = null,
            currentIpAddress = null,
            currentSubnetMask = null,
            currentGateway = null,
            currentDns = null
        )
        val vm = createViewModel(networkStatus = disconnectedStatus)
        vm.networkStatus.observeForever {}

        val status = vm.networkStatus.value!!
        status.isConnected shouldBe false
    }

    test("disconnected status: UI should show '未检测到以太网连接' based on isConnected=false") {
        val disconnectedStatus = NetworkStatus(
            isConnected = false,
            currentMode = null,
            currentIpAddress = null,
            currentSubnetMask = null,
            currentGateway = null,
            currentDns = null
        )
        val vm = createViewModel(networkStatus = disconnectedStatus)
        vm.networkStatus.observeForever {}

        // The UI layer checks networkStatus.isConnected to decide whether to
        // show "未检测到以太网连接" and disable configuration controls.
        val status = vm.networkStatus.value!!
        status.isConnected shouldBe false
        status.currentMode shouldBe null
        status.currentIpAddress shouldBe null
    }

    // =========================================================================
    // Requirement 3.4: 主 DNS 为必填项
    // Requirement 3.5: 备用 DNS 为选填项
    // =========================================================================

    test("primary DNS is required: empty primary DNS makes form invalid in STATIC mode") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        vm.ipAddress.value = "192.168.1.100"
        vm.subnetMask.value = "255.255.255.0"
        vm.gateway.value = "192.168.1.1"
        vm.primaryDns.value = ""  // empty — required field
        vm.secondaryDns.value = ""

        vm.isFormValid.value shouldBe false
    }

    test("primary DNS filled makes form valid (with other valid fields)") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        vm.ipAddress.value = "192.168.1.100"
        vm.subnetMask.value = "255.255.255.0"
        vm.gateway.value = "192.168.1.1"
        vm.primaryDns.value = "8.8.8.8"
        vm.secondaryDns.value = ""  // optional — can be empty

        vm.isFormValid.value shouldBe true
    }

    test("secondary DNS is optional: form is valid with empty secondary DNS") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        vm.ipAddress.value = "10.0.0.1"
        vm.subnetMask.value = "255.255.255.0"
        vm.gateway.value = "10.0.0.254"
        vm.primaryDns.value = "1.1.1.1"
        vm.secondaryDns.value = ""  // optional

        vm.isFormValid.value shouldBe true
    }

    test("secondary DNS is optional: form is also valid with filled secondary DNS") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        vm.ipAddress.value = "10.0.0.1"
        vm.subnetMask.value = "255.255.255.0"
        vm.gateway.value = "10.0.0.254"
        vm.primaryDns.value = "1.1.1.1"
        vm.secondaryDns.value = "8.8.4.4"

        vm.isFormValid.value shouldBe true
    }

    // =========================================================================
    // Additional: invalid DNS validation error message
    // =========================================================================

    test("invalid primary DNS produces validation error") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        val result = vm.validateField(Field.PRIMARY_DNS, "not-a-dns")
        result.shouldBeInstanceOf<ValidationResult.Invalid>()
    }

    test("valid primary DNS returns Valid") {
        val vm = createViewModel()
        vm.setConfigMode(ConfigMode.STATIC)

        val result = vm.validateField(Field.PRIMARY_DNS, "8.8.8.8")
        result.shouldBeInstanceOf<ValidationResult.Valid>()
    }
})
