package com.example.ethernetconfig.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.NetworkStatus
import com.example.ethernetconfig.model.ValidationResult
import com.example.ethernetconfig.repository.EthernetConfigRepository
import com.example.ethernetconfig.validator.NetworkConfigValidator
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
 * Feature: android-ethernet-config, Property 8: 表单验证完整性
 *
 * Validates: Requirements 6.3, 6.4
 *
 * 对任意静态 IP 模式下的表单状态，isFormValid 返回 true 当且仅当
 * 所有必填字段（IP 地址、子网掩码、网关、主 DNS）均非空且各字段通过对应的格式验证。
 */

/** Field value category: valid, invalid, or empty */
enum class FieldCategory { VALID, INVALID, EMPTY }

/** Data class representing a random form state with categories and values for each field */
data class FormState(
    val ipCategory: FieldCategory,
    val maskCategory: FieldCategory,
    val gwCategory: FieldCategory,
    val dnsCategory: FieldCategory,
    val ipValue: String,
    val maskValue: String,
    val gwValue: String,
    val dnsValue: String
)

class FormValidationPropertyTest : FunSpec({

    tags(Tag("Feature: android-ethernet-config"), Tag("Property 8: 表单验证完整性"))

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

    // --- Setup helpers ---

    fun createViewModel(): EthernetConfigViewModel {
        val application = mockk<Application>(relaxed = true)
        val repository = mockk<EthernetConfigRepository>(relaxed = true)
        val networkStatusLiveData = MutableLiveData(
            NetworkStatus(
                isConnected = false,
                currentMode = null,
                currentIpAddress = null,
                currentSubnetMask = null,
                currentGateway = null,
                currentDns = null
            )
        )
        every { repository.observeNetworkStatus() } returns networkStatusLiveData
        every { repository.getStoredConfiguration() } returns null
        every { repository.getAvailableInterfaces() } returns emptyList()
        val vm = EthernetConfigViewModel(application, repository)
        // Observe isFormValid to activate the MediatorLiveData
        vm.isFormValid.observeForever {}
        return vm
    }

    // --- Generators ---

    val validOctet: Arb<Int> = Arb.int(0..255)

    val validIpv4: Arb<String> = Arb.bind(validOctet, validOctet, validOctet, validOctet) { a, b, c, d ->
        "$a.$b.$c.$d"
    }

    val validSubnetMask: Arb<String> = Arb.int(0..32).map { prefixLen ->
        val bits = if (prefixLen == 0) 0L else ((1L shl 32) - (1L shl (32 - prefixLen)))
        val o1 = (bits shr 24) and 0xFF
        val o2 = (bits shr 16) and 0xFF
        val o3 = (bits shr 8) and 0xFF
        val o4 = bits and 0xFF
        "$o1.$o2.$o3.$o4"
    }

    val invalidIpv4: Arb<String> = arbitrary {
        val octets = List(4) { Arb.int(0..255).bind() }.toMutableList()
        val pos = Arb.int(0..3).bind()
        octets[pos] = Arb.int(256..999).bind()
        octets.joinToString(".")
    }

    val emptyString: Arb<String> = Arb.constant("")

    val fieldCategoryArb: Arb<FieldCategory> = Arb.enum<FieldCategory>()

    fun ipValueForCategory(category: FieldCategory): Arb<String> = when (category) {
        FieldCategory.VALID -> validIpv4
        FieldCategory.INVALID -> invalidIpv4
        FieldCategory.EMPTY -> emptyString
    }

    fun maskValueForCategory(category: FieldCategory): Arb<String> = when (category) {
        FieldCategory.VALID -> validSubnetMask
        FieldCategory.INVALID -> invalidIpv4
        FieldCategory.EMPTY -> emptyString
    }

    fun dnsValueForCategory(category: FieldCategory): Arb<String> = when (category) {
        FieldCategory.VALID -> validIpv4
        FieldCategory.INVALID -> invalidIpv4
        FieldCategory.EMPTY -> emptyString
    }

    val formStateArb: Arb<FormState> = arbitrary {
        val ipCat = fieldCategoryArb.bind()
        val maskCat = fieldCategoryArb.bind()
        val gwCat = fieldCategoryArb.bind()
        val dnsCat = fieldCategoryArb.bind()

        val ipVal = ipValueForCategory(ipCat).bind()
        val maskVal = maskValueForCategory(maskCat).bind()
        val gwVal = ipValueForCategory(gwCat).bind()
        val dnsVal = dnsValueForCategory(dnsCat).bind()

        FormState(ipCat, maskCat, gwCat, dnsCat, ipVal, maskVal, gwVal, dnsVal)
    }

    /**
     * Compute expected form validity independently of the ViewModel.
     * In STATIC mode, form is valid iff all required fields are non-empty AND pass format validation.
     */
    fun expectedFormValid(ip: String, mask: String, gw: String, dns: String): Boolean {
        val allFilled = ip.isNotEmpty() && mask.isNotEmpty() && gw.isNotEmpty() && dns.isNotEmpty()
        if (!allFilled) return false

        val ipValid = NetworkConfigValidator.validateIpAddress(ip) is ValidationResult.Valid
        val maskValid = NetworkConfigValidator.validateSubnetMask(mask) is ValidationResult.Valid
        val gwResult = NetworkConfigValidator.validateGateway(gw, ip, mask)
        val gwValid = gwResult is ValidationResult.Valid || gwResult is ValidationResult.Warning
        val dnsValid = NetworkConfigValidator.validateDnsServer(dns) is ValidationResult.Valid

        return ipValid && maskValid && gwValid && dnsValid
    }

    // --- Property Tests ---

    test("STATIC mode: isFormValid is true iff all required fields non-empty and format-valid") {
        checkAll(minIterations, formStateArb) { state ->
            val viewModel = createViewModel()
            viewModel.setConfigMode(ConfigMode.STATIC)

            viewModel.ipAddress.value = state.ipValue
            viewModel.subnetMask.value = state.maskValue
            viewModel.gateway.value = state.gwValue
            viewModel.primaryDns.value = state.dnsValue

            val expected = expectedFormValid(state.ipValue, state.maskValue, state.gwValue, state.dnsValue)
            viewModel.isFormValid.value shouldBe expected
        }
    }

    test("DHCP mode: isFormValid is always true regardless of field values") {
        checkAll(minIterations, formStateArb) { state ->
            val viewModel = createViewModel()
            // Default mode is DHCP

            viewModel.ipAddress.value = state.ipValue
            viewModel.subnetMask.value = state.maskValue
            viewModel.gateway.value = state.gwValue
            viewModel.primaryDns.value = state.dnsValue

            viewModel.isFormValid.value shouldBe true
        }
    }

    test("STATIC mode with all valid fields: isFormValid is true") {
        checkAll(minIterations, validIpv4, validSubnetMask, validIpv4, validIpv4) { ip, mask, gw, dns ->
            val viewModel = createViewModel()
            viewModel.setConfigMode(ConfigMode.STATIC)

            viewModel.ipAddress.value = ip
            viewModel.subnetMask.value = mask
            viewModel.gateway.value = gw
            viewModel.primaryDns.value = dns

            viewModel.isFormValid.value shouldBe true
        }
    }

    test("STATIC mode with any empty required field: isFormValid is false") {
        val atLeastOneEmptyFormState: Arb<FormState> = arbitrary {
            val categories = MutableList(4) { fieldCategoryArb.bind() }
            val forceEmptyPos = Arb.int(0..3).bind()
            categories[forceEmptyPos] = FieldCategory.EMPTY

            val ipVal = ipValueForCategory(categories[0]).bind()
            val maskVal = maskValueForCategory(categories[1]).bind()
            val gwVal = ipValueForCategory(categories[2]).bind()
            val dnsVal = dnsValueForCategory(categories[3]).bind()

            FormState(categories[0], categories[1], categories[2], categories[3], ipVal, maskVal, gwVal, dnsVal)
        }

        checkAll(minIterations, atLeastOneEmptyFormState) { state ->
            val viewModel = createViewModel()
            viewModel.setConfigMode(ConfigMode.STATIC)

            viewModel.ipAddress.value = state.ipValue
            viewModel.subnetMask.value = state.maskValue
            viewModel.gateway.value = state.gwValue
            viewModel.primaryDns.value = state.dnsValue

            viewModel.isFormValid.value shouldBe false
        }
    }
})
