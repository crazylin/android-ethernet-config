package com.example.ethernetconfig.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.ethernetconfig.R
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.ConfigResult
import com.example.ethernetconfig.model.Field
import com.example.ethernetconfig.model.NetworkStatus
import com.example.ethernetconfig.model.ValidationResult
import com.example.ethernetconfig.network.EthernetManagerWrapper
import com.example.ethernetconfig.network.NetworkMonitor
import com.example.ethernetconfig.repository.EthernetConfigRepository
import com.example.ethernetconfig.storage.ConfigStorage
import com.example.ethernetconfig.viewmodel.EthernetConfigViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 以太网配置主界面 Activity。
 *
 * 负责 UI 展示和用户交互，通过 ViewModel 管理状态。
 * 包含网络模式切换、静态 IP 配置表单、网络状态显示面板和配置应用功能。
 */
class EthernetConfigActivity : AppCompatActivity() {

    private lateinit var viewModel: EthernetConfigViewModel
    private lateinit var networkMonitor: NetworkMonitor

    // Mode switch
    private lateinit var radioGroupMode: RadioGroup
    private lateinit var radioDhcp: MaterialRadioButton
    private lateinit var radioStatic: MaterialRadioButton

    // Interface selection
    private lateinit var actvInterfaceName: AutoCompleteTextView
    private lateinit var btnRefreshInterfaces: MaterialButton

    // Static config form
    private lateinit var cardStaticConfig: MaterialCardView
    private lateinit var tilIpAddress: TextInputLayout
    private lateinit var tilSubnetMask: TextInputLayout
    private lateinit var tilGateway: TextInputLayout
    private lateinit var tilPrimaryDns: TextInputLayout
    private lateinit var tilSecondaryDns: TextInputLayout
    private lateinit var etIpAddress: TextInputEditText
    private lateinit var etSubnetMask: TextInputEditText
    private lateinit var etGateway: TextInputEditText
    private lateinit var etPrimaryDns: TextInputEditText
    private lateinit var etSecondaryDns: TextInputEditText

    // Apply button + progress
    private lateinit var btnApplyConfig: MaterialButton
    private lateinit var progressIndicator: LinearProgressIndicator

    // Network status panel
    private lateinit var viewConnectionIndicator: View
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvCurrentMode: TextView
    private lateinit var tvCurrentIp: TextView
    private lateinit var tvCurrentMask: TextView
    private lateinit var tvCurrentGateway: TextView
    private lateinit var tvCurrentDns: TextView

    /** Flag to suppress TextWatcher callbacks during programmatic updates. */
    private var suppressTextWatchers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ethernet_config)

        // --- Dependency injection (Task 10.1) ---
        val ethernetManager = EthernetManagerWrapper(applicationContext)
        networkMonitor = NetworkMonitor(applicationContext)
        val configStorage = ConfigStorage(applicationContext)
        val repository = EthernetConfigRepository(ethernetManager, networkMonitor, configStorage)
        val factory = EthernetConfigViewModel.Factory(application, repository)
        viewModel = ViewModelProvider(this, factory)[EthernetConfigViewModel::class.java]

        bindViews()
        setupInterfaceSelector()
        setupModeSwitch()
        setupFormInputListeners()
        setupApplyButton()
        observeViewModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Manage NetworkMonitor lifecycle (Task 10.1)
        networkMonitor.stopMonitoring()
    }

    // ---- View binding ----

    private fun bindViews() {
        radioGroupMode = findViewById(R.id.radioGroupMode)
        radioDhcp = findViewById(R.id.radioDhcp)
        radioStatic = findViewById(R.id.radioStatic)

        actvInterfaceName = findViewById(R.id.actvInterfaceName)
        btnRefreshInterfaces = findViewById(R.id.btnRefreshInterfaces)

        cardStaticConfig = findViewById(R.id.cardStaticConfig)
        tilIpAddress = findViewById(R.id.tilIpAddress)
        tilSubnetMask = findViewById(R.id.tilSubnetMask)
        tilGateway = findViewById(R.id.tilGateway)
        tilPrimaryDns = findViewById(R.id.tilPrimaryDns)
        tilSecondaryDns = findViewById(R.id.tilSecondaryDns)
        etIpAddress = findViewById(R.id.etIpAddress)
        etSubnetMask = findViewById(R.id.etSubnetMask)
        etGateway = findViewById(R.id.etGateway)
        etPrimaryDns = findViewById(R.id.etPrimaryDns)
        etSecondaryDns = findViewById(R.id.etSecondaryDns)

        btnApplyConfig = findViewById(R.id.btnApplyConfig)
        progressIndicator = findViewById(R.id.progressIndicator)

        viewConnectionIndicator = findViewById(R.id.viewConnectionIndicator)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvCurrentMode = findViewById(R.id.tvCurrentMode)
        tvCurrentIp = findViewById(R.id.tvCurrentIp)
        tvCurrentMask = findViewById(R.id.tvCurrentMask)
        tvCurrentGateway = findViewById(R.id.tvCurrentGateway)
        tvCurrentDns = findViewById(R.id.tvCurrentDns)
    }

    // ---- Interface selector ----

    private fun setupInterfaceSelector() {
        btnRefreshInterfaces.setOnClickListener {
            viewModel.refreshInterfaces()
        }

        // 用户手动输入或选择后更新 ViewModel
        actvInterfaceName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val name = s?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    viewModel.selectInterface(name)
                }
            }
        })

        // 观察可用接口列表，更新下拉建议
        viewModel.availableInterfaces.observe(this) { interfaces ->
            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, interfaces)
            actvInterfaceName.setAdapter(adapter)

            // 如果当前输入为空或默认值，且有枚举到接口，自动填入第一个
            val current = actvInterfaceName.text.toString().trim()
            if (interfaces.isNotEmpty() && (current.isEmpty() || current == "eth0")) {
                val selected = viewModel.selectedInterface.value ?: interfaces[0]
                if (selected != current) {
                    actvInterfaceName.setText(selected, false)
                }
            }
        }
    }

    // ---- Mode switch (Task 9.2) ----

    private fun setupModeSwitch() {
        radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.radioStatic) ConfigMode.STATIC else ConfigMode.DHCP
            suppressTextWatchers = true
            viewModel.setConfigMode(mode)
            suppressTextWatchers = false
        }
    }

    // ---- Form input listeners with real-time validation (Task 9.2) ----

    private fun setupFormInputListeners() {
        addFieldWatcher(etIpAddress, Field.IP_ADDRESS)
        addFieldWatcher(etSubnetMask, Field.SUBNET_MASK)
        addFieldWatcher(etGateway, Field.GATEWAY)
        addFieldWatcher(etPrimaryDns, Field.PRIMARY_DNS)
        addFieldWatcher(etSecondaryDns, Field.SECONDARY_DNS)
    }

    private fun addFieldWatcher(editText: TextInputEditText, field: Field) {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatchers) return
                val value = s?.toString() ?: ""
                // Update ViewModel field value
                when (field) {
                    Field.IP_ADDRESS -> viewModel.ipAddress.value = value
                    Field.SUBNET_MASK -> viewModel.subnetMask.value = value
                    Field.GATEWAY -> viewModel.gateway.value = value
                    Field.PRIMARY_DNS -> viewModel.primaryDns.value = value
                    Field.SECONDARY_DNS -> viewModel.secondaryDns.value = value
                }
                // Trigger real-time validation
                if (value.isNotEmpty()) {
                    viewModel.validateField(field, value)
                }
            }
        })
    }

    // ---- Apply button (Task 9.2 / 9.3) ----

    private fun setupApplyButton() {
        btnApplyConfig.setOnClickListener {
            // Check for gateway subnet warning before applying
            val gw = viewModel.gateway.value ?: ""
            val ip = viewModel.ipAddress.value ?: ""
            val mask = viewModel.subnetMask.value ?: ""
            if (viewModel.configMode.value == ConfigMode.STATIC && gw.isNotEmpty()) {
                val gwResult = viewModel.validateField(Field.GATEWAY, gw)
                if (gwResult is ValidationResult.Warning) {
                    showGatewayWarningDialog()
                    return@setOnClickListener
                }
            }
            viewModel.applyConfiguration()
        }
    }

    // ---- Observe ViewModel (Tasks 9.2 + 9.3) ----

    private fun observeViewModel() {
        // Observe config mode
        viewModel.configMode.observe(this) { mode ->
            when (mode) {
                ConfigMode.DHCP -> {
                    radioDhcp.isChecked = true
                    cardStaticConfig.visibility = View.GONE
                }
                ConfigMode.STATIC -> {
                    radioStatic.isChecked = true
                    cardStaticConfig.visibility = View.VISIBLE
                }
                null -> { /* no-op */ }
            }
            // Sync form fields from ViewModel
            syncFormFields()
        }

        // Observe validation errors (Task 9.2)
        viewModel.validationErrors.observe(this) { errors ->
            tilIpAddress.error = errors[Field.IP_ADDRESS]
            tilSubnetMask.error = errors[Field.SUBNET_MASK]
            tilGateway.error = errors[Field.GATEWAY]
            tilPrimaryDns.error = errors[Field.PRIMARY_DNS]
            tilSecondaryDns.error = errors[Field.SECONDARY_DNS]
        }

        // Observe form validity (Task 9.2)
        viewModel.isFormValid.observe(this) { isValid ->
            btnApplyConfig.isEnabled = isValid == true
        }

        // Observe config result (Task 9.3)
        viewModel.configResult.observe(this) { result ->
            when (result) {
                is ConfigResult.Loading -> {
                    progressIndicator.visibility = View.VISIBLE
                    btnApplyConfig.isEnabled = false
                }
                is ConfigResult.Success -> {
                    progressIndicator.visibility = View.GONE
                    btnApplyConfig.isEnabled = viewModel.isFormValid.value == true
                    Toast.makeText(this, R.string.msg_config_success, Toast.LENGTH_SHORT).show()
                }
                is ConfigResult.Failure -> {
                    progressIndicator.visibility = View.GONE
                    btnApplyConfig.isEnabled = viewModel.isFormValid.value == true
                    showErrorDialog(result.reason)
                }
                null -> { /* no-op */ }
            }
        }

        // Observe network status (Task 9.3)
        viewModel.networkStatus.observe(this) { status ->
            updateNetworkStatusPanel(status)
            updateConfigEnabledState(status)

            // 连接上后自动更新接口名输入框
            if (status != null && status.isConnected && status.interfaceName != null) {
                val current = actvInterfaceName.text.toString().trim()
                if (current != status.interfaceName) {
                    actvInterfaceName.setText(status.interfaceName, false)
                }
            }
        }

        // 观察 root 读取的网络状态（补充系统无法检测的情况，如无 DHCP）
        viewModel.rootNetworkStatus.observe(this) { status ->
            // 只在系统 NetworkMonitor 没检测到时使用 root 状态
            val systemStatus = viewModel.networkStatus.value
            if (systemStatus == null || !systemStatus.isConnected) {
                updateNetworkStatusPanel(status)
            }
        }

        // 观察选中接口变化，同步到输入框
        viewModel.selectedInterface.observe(this) { name ->
            if (name != null && name != actvInterfaceName.text.toString().trim()) {
                actvInterfaceName.setText(name, false)
            }
        }
    }

    // ---- Sync form fields from ViewModel (for mode switch pre-fill) ----

    private fun syncFormFields() {
        suppressTextWatchers = true
        etIpAddress.setText(viewModel.ipAddress.value ?: "")
        etSubnetMask.setText(viewModel.subnetMask.value ?: "")
        etGateway.setText(viewModel.gateway.value ?: "")
        etPrimaryDns.setText(viewModel.primaryDns.value ?: "")
        etSecondaryDns.setText(viewModel.secondaryDns.value ?: "")
        suppressTextWatchers = false
    }

    // ---- Network status panel update (Task 9.3) ----

    private fun updateNetworkStatusPanel(status: NetworkStatus?) {
        if (status == null) return

        val na = getString(R.string.value_not_available)

        if (status.isConnected) {
            setConnectionIndicatorColor(android.R.color.holo_green_dark)
            val ifaceName = status.interfaceName ?: ""
            tvConnectionStatus.text = if (ifaceName.isNotEmpty()) {
                "${getString(R.string.status_connected)} ($ifaceName)"
            } else {
                getString(R.string.status_connected)
            }
            tvCurrentMode.text = when (status.currentMode) {
                ConfigMode.DHCP -> getString(R.string.mode_dhcp)
                ConfigMode.STATIC -> getString(R.string.mode_static)
                null -> na
            }
            tvCurrentIp.text = status.currentIpAddress ?: na
            tvCurrentMask.text = status.currentSubnetMask ?: na
            tvCurrentGateway.text = status.currentGateway ?: na
            tvCurrentDns.text = status.currentDns?.joinToString(", ") ?: na
        } else {
            setConnectionIndicatorColor(android.R.color.holo_red_dark)
            tvConnectionStatus.text = getString(R.string.status_disconnected)
            tvCurrentMode.text = na
            tvCurrentIp.text = na
            tvCurrentMask.text = na
            tvCurrentGateway.text = na
            tvCurrentDns.text = na
        }
    }

    private fun setConnectionIndicatorColor(colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        val bg = viewConnectionIndicator.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            viewConnectionIndicator.background = drawable
        }
    }

    // ---- Disable config when network not connected (Task 9.2) ----

    private fun updateConfigEnabledState(status: NetworkStatus?) {
        // 所有控件始终可用 — 用户可以在未连接时预设 DHCP 或静态 IP 配置
        // 插上网线后配置自动生效
        val formValid = viewModel.isFormValid.value == true
        btnApplyConfig.isEnabled = formValid
    }

    // ---- Dialogs (Task 9.3) ----

    private fun showErrorDialog(reason: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_error_title)
            .setMessage(reason)
            .setPositiveButton(R.string.dialog_btn_ok, null)
            .show()
    }

    private fun showGatewayWarningDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_gateway_warning_title)
            .setMessage(R.string.dialog_gateway_warning_message)
            .setPositiveButton(R.string.dialog_btn_continue) { _, _ ->
                viewModel.applyConfiguration()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }
}
