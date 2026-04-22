# 实现计划：Android 以太网配置工具

## 概述

基于 Kotlin + MVVM 架构，分层实现 Android 以太网（有线网卡）IP 和 DNS 配置工具。从数据模型和验证逻辑开始，逐步构建 Repository、ViewModel 和 UI 层，最终完成组件集成和端到端测试。每个阶段都包含对应的测试任务，确保增量验证。

## Tasks

- [x] 1. 搭建项目结构和定义数据模型
  - [x] 1.1 创建项目目录结构和基础依赖配置
    - 在 `app/build.gradle` 中添加 Kotest、MockK、JUnit 5 等测试依赖
    - 创建包结构：`model`、`viewmodel`、`repository`、`network`、`storage`、`validator`、`ui`
    - _需求：全局_
  - [x] 1.2 定义所有数据模型类
    - 创建 `ConfigMode` 枚举（DHCP、STATIC）
    - 创建 `NetworkConfiguration` 数据类（mode、ipAddress、subnetMask、gateway、primaryDns、secondaryDns）
    - 创建 `NetworkStatus` 数据类（isConnected、currentMode、currentIpAddress、currentSubnetMask、currentGateway、currentDns）
    - 创建 `ValidationResult` 密封类（Valid、Invalid、Warning）
    - 创建 `ConfigResult` 密封类（Success、Failure、Loading）
    - 创建 `Field` 枚举（IP_ADDRESS、SUBNET_MASK、GATEWAY、PRIMARY_DNS、SECONDARY_DNS）
    - _需求：1.1, 2.1, 2.2, 2.3, 3.1, 3.2, 5.1, 5.2, 5.3_

- [x] 2. 实现网络参数验证器（NetworkConfigValidator）
  - [x] 2.1 实现 IPv4 地址验证逻辑
    - 实现 `validateIpAddress(ip: String): ValidationResult`，验证四组 0-255 的十进制数字以点号分隔
    - 实现 `validateDnsServer(dns: String): ValidationResult`，复用 IPv4 验证逻辑
    - _需求：2.4, 2.6, 3.3_
  - [x] 2.2 编写属性测试：IPv4 地址验证正确性
    - **属性 1：IPv4 地址验证正确性**
    - 使用 Kotest Property Testing 编写生成器：有效 IPv4 生成器（四组 0-255 随机数字）和无效 IPv4 生成器（超范围数字、非数字字符、错误分隔符、错误段数）
    - 验证对任意有效 IPv4 字符串返回 Valid，对任意无效字符串返回 Invalid
    - **验证需求：2.4, 2.6, 3.3**
  - [x] 2.3 实现子网掩码验证逻辑
    - 实现 `validateSubnetMask(mask: String): ValidationResult`，验证连续高位 1 和低位 0 组成的 32 位二进制数对应的点分十进制
    - 实现 `isValidSubnetMask(mask: String): Boolean` 辅助方法
    - _需求：2.5_
  - [x] 2.4 编写属性测试：子网掩码验证正确性
    - **属性 2：子网掩码验证正确性**
    - 使用 Kotest Property Testing 编写生成器：有效子网掩码生成器（从前缀长度 0-32 生成点分十进制）和无效子网掩码生成器
    - 验证对任意有效子网掩码返回 Valid，对任意无效子网掩码返回 Invalid
    - **验证需求：2.5**
  - [x] 2.5 实现网关子网范围检查逻辑
    - 实现 `isGatewayInSubnet(gateway: String, ip: String, mask: String): Boolean`，检查 (gateway & mask) == (ip & mask)
    - 实现 `validateGateway(gateway: String, ip: String, mask: String): ValidationResult`，格式验证 + 子网范围检查（不在子网内返回 Warning）
    - _需求：2.7_
  - [x] 2.6 编写属性测试：网关子网范围检查正确性
    - **属性 3：网关子网范围检查正确性**
    - 使用 Kotest Property Testing 生成随机有效 IP、掩码和网关
    - 验证 `isGatewayInSubnet` 返回 true 当且仅当 (gateway & mask) == (ip & mask)
    - **验证需求：2.7**

- [x] 3. 检查点 - 验证器测试通过
  - 确保所有验证器相关测试通过，如有问题请向用户确认。

- [x] 4. 实现数据源层组件
  - [x] 4.1 实现 ConfigStorage（配置持久化存储）
    - 使用 SharedPreferences 实现 `saveConfiguration(config: NetworkConfiguration)`
    - 实现 `loadConfiguration(): NetworkConfiguration?`
    - 实现 `clearConfiguration()`
    - 存储键包括：config_mode、ip_address、subnet_mask、gateway、primary_dns、secondary_dns
    - _需求：4.2_
  - [x] 4.2 编写属性测试：配置持久化往返
    - **属性 6：配置持久化往返**
    - 使用 Kotest Property Testing 生成随机有效 NetworkConfiguration 对象
    - 验证 saveConfiguration 后 loadConfiguration 返回等价对象
    - **验证需求：4.2**
  - [x] 4.3 实现 EthernetManagerWrapper（EthernetManager 反射调用封装）
    - 通过反射获取 `EthernetManager` 实例
    - 实现 `getConfiguration(): NetworkConfiguration?`，读取当前以太网配置
    - 实现 `setConfiguration(config: NetworkConfiguration): Result<Unit>`，构造 `IpConfiguration` 和 `StaticIpConfiguration` 对象并调用 `setConfiguration`
    - 实现 `setDhcpMode(): Result<Unit>`
    - 实现 `isAvailable(): Boolean`，检查 EthernetManager 是否可用
    - _需求：1.2, 4.1_
  - [x] 4.4 实现 NetworkMonitor（网络状态监听器）
    - 使用 `ConnectivityManager.registerNetworkCallback` 注册回调
    - 通过 `NetworkCapabilities.TRANSPORT_ETHERNET` 过滤以太网事件
    - 处理 `onAvailable`、`onLost`、`onCapabilitiesChanged`、`onLinkPropertiesChanged` 回调
    - 实现 `startMonitoring(): LiveData<NetworkStatus>`、`stopMonitoring()`、`getCurrentStatus(): NetworkStatus`
    - _需求：5.1, 5.2, 5.3, 5.4_

- [x] 5. 实现 Repository 层
  - [x] 5.1 实现 EthernetConfigRepository 核心逻辑
    - 实现 `getCurrentConfiguration(): NetworkConfiguration`，从 EthernetManagerWrapper 获取当前配置
    - 实现 `observeNetworkStatus(): LiveData<NetworkStatus>`，代理 NetworkMonitor 的状态流
    - 实现 `getStoredConfiguration(): NetworkConfiguration?`，从 ConfigStorage 读取持久化配置
    - _需求：5.1, 5.2, 5.3_
  - [x] 5.2 实现配置应用与回滚逻辑
    - 实现 `applyConfiguration(config: NetworkConfiguration): Result<Unit>`
    - 应用前保存当前有效配置快照
    - 调用 EthernetManagerWrapper 应用新配置
    - 成功时通过 ConfigStorage 持久化新配置
    - 失败时使用快照恢复之前的配置；恢复也失败则切换到 DHCP 模式兜底
    - _需求：4.1, 4.2, 4.5_
  - [x] 5.3 编写属性测试：配置失败回滚
    - **属性 7：配置失败回滚**
    - 使用 MockK 模拟 EthernetManagerWrapper 的 setConfiguration 失败
    - 使用 Kotest Property Testing 生成随机初始配置和新配置
    - 验证应用失败后系统配置与初始配置一致
    - **验证需求：4.5**

- [x] 6. 检查点 - 数据源层和 Repository 层测试通过
  - 确保所有数据源层和 Repository 层相关测试通过，如有问题请向用户确认。

- [x] 7. 实现 ViewModel 层
  - [x] 7.1 实现 EthernetConfigViewModel 基础结构
    - 继承 `AndroidViewModel`，注入 `EthernetConfigRepository`
    - 定义所有 LiveData 属性：configMode、networkStatus、ipAddress、subnetMask、gateway、primaryDns、secondaryDns、validationErrors、isFormValid、configResult
    - 在初始化时启动网络状态监听，加载持久化配置
    - _需求：1.1, 5.1, 5.2, 5.3_
  - [x] 7.2 实现模式切换逻辑
    - 实现 `setConfigMode(mode: ConfigMode)`
    - 切换到 DHCP 时：清空所有手动配置字段（ipAddress、subnetMask、gateway、primaryDns、secondaryDns）
    - 切换到 STATIC 时：从当前 NetworkStatus 中读取 DHCP 获取的参数，预填充到对应字段
    - _需求：1.2, 1.3, 1.4, 1.5_
  - [x] 7.3 编写属性测试：切换到 DHCP 清除静态参数
    - **属性 4：切换到 DHCP 模式清除静态参数**
    - 使用 Kotest Property Testing 生成随机有效的 STATIC 模式配置
    - 验证 setConfigMode(DHCP) 后所有手动配置字段被清空
    - **验证需求：1.4**
  - [x] 7.4 编写属性测试：切换到 STATIC 预填充 DHCP 参数
    - **属性 5：切换到 STATIC 模式预填充 DHCP 参数**
    - 使用 Kotest Property Testing 生成随机有效的 NetworkStatus（DHCP 已连接状态）
    - 验证 setConfigMode(STATIC) 后配置字段包含 NetworkStatus 中的对应参数值
    - **验证需求：1.5**
  - [x] 7.5 实现字段验证和表单状态管理
    - 实现 `validateField(field: Field, value: String): ValidationResult`，调用 NetworkConfigValidator 对应方法
    - 实现 `isFormValid` 计算逻辑：静态模式下所有必填字段（IP、掩码、网关、主 DNS）非空且格式验证通过时为 true
    - 输入变化时实时更新 validationErrors 和 isFormValid
    - _需求：6.1, 6.2, 6.3, 6.4_
  - [x] 7.6 编写属性测试：表单验证完整性
    - **属性 8：表单验证完整性**
    - 使用 Kotest Property Testing 生成随机表单状态（有效/无效/空字段的组合）
    - 验证 isFormValid 返回 true 当且仅当所有必填字段非空且格式验证通过
    - **验证需求：6.3, 6.4**
  - [x] 7.7 实现配置应用逻辑
    - 实现 `applyConfiguration()`，调用 Repository 的 applyConfiguration
    - 应用前设置 configResult 为 Loading
    - 成功时设置 configResult 为 Success
    - 失败时设置 configResult 为 Failure（包含失败原因）
    - _需求：4.1, 4.3, 4.4, 4.5_

- [x] 8. 检查点 - ViewModel 层测试通过
  - 确保所有 ViewModel 层相关测试通过，如有问题请向用户确认。

- [x] 9. 实现 UI 层
  - [x] 9.1 创建布局文件和 UI 组件
    - 创建 `activity_ethernet_config.xml` 布局文件
    - 包含网络模式切换区域（RadioGroup：DHCP / 静态 IP）
    - 包含静态 IP 配置表单（TextInputLayout：IP 地址、子网掩码、网关、主 DNS、备用 DNS）
    - 包含网络状态显示面板（连接状态、当前模式、当前 IP/掩码/网关/DNS）
    - 包含应用配置按钮和进度指示器
    - 使用 Material Design 组件确保 UI 一致性
    - _需求：1.1, 2.1, 2.2, 2.3, 3.1, 3.2, 5.1, 5.2, 5.3_
  - [x] 9.2 实现 EthernetConfigActivity
    - 绑定 ViewModel，观察所有 LiveData 状态
    - 实现模式切换 UI 交互：RadioGroup 选择变化时调用 `viewModel.setConfigMode()`
    - 实现表单输入监听：TextWatcher 监听输入变化，调用 `viewModel.validateField()` 实时验证
    - 实现错误提示显示：根据 validationErrors 在对应 TextInputLayout 上设置 error 文本
    - 实现提交按钮状态：根据 isFormValid 启用/禁用提交按钮
    - 实现网络未连接时禁用所有配置操作
    - _需求：1.2, 1.3, 6.1, 6.2, 6.3, 6.4, 6.5_
  - [x] 9.3 实现配置结果反馈和网络状态显示
    - 观察 configResult：Success 时显示成功 Toast/Snackbar，Failure 时显示包含原因的错误对话框，Loading 时显示进度指示器
    - 观察 networkStatus：更新连接状态指示器、当前模式标签、当前网络参数显示
    - 实现网关子网警告对话框：当 validateGateway 返回 Warning 时弹出确认对话框
    - _需求：2.7, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4_
  - [x] 9.4 编写 UI 层单元测试
    - 测试 DHCP/STATIC 模式选项存在且可切换
    - 测试静态模式下所有输入字段可见
    - 测试无效 IP 输入时显示 "请输入有效的 IPv4 地址" 错误提示
    - 测试无效子网掩码输入时显示 "请输入有效的子网掩码" 错误提示
    - 测试未连接时显示 "未检测到以太网连接" 并禁用配置操作
    - 测试主 DNS 为必填项、备用 DNS 为选填项
    - _需求：1.1, 1.3, 2.1, 2.2, 2.3, 3.1, 3.2, 3.4, 3.5, 6.1, 6.2, 6.5_

- [x] 10. 组件集成与端到端连接
  - [x] 10.1 完成依赖注入和组件装配
    - 在 EthernetConfigActivity 中完成所有组件的创建和注入
    - 连接 EthernetManagerWrapper、NetworkMonitor、ConfigStorage 到 Repository
    - 连接 Repository 到 ViewModel
    - 确保 Activity 生命周期正确管理 NetworkMonitor 的启动和停止
    - _需求：全局_
  - [x] 10.2 添加 AndroidManifest 配置和权限声明
    - 声明 `android.permission.CONNECTIVITY_INTERNAL` 或系统签名权限
    - 注册 EthernetConfigActivity
    - 配置最低 SDK 版本为 API 24
    - _需求：全局_
  - [x] 10.3 编写集成测试
    - 测试 DHCP 模式配置调用完整流程
    - 测试静态 IP 配置调用完整流程
    - 测试配置失败回滚完整流程
    - _需求：1.2, 4.1, 4.5_

- [x] 11. 最终检查点 - 确保所有测试通过
  - 确保所有测试通过，如有问题请向用户确认。

## 备注

- 标记 `*` 的任务为可选任务，可跳过以加快 MVP 开发速度
- 每个任务引用了具体的需求编号，确保需求可追溯
- 检查点任务确保增量验证，及时发现问题
- 属性基测试使用 Kotest Property Testing 框架，验证设计文档中定义的 8 个正确性属性
- 单元测试使用 JUnit 5 + MockK 框架，验证具体示例和边界情况
- EthernetManager 为 Android 隐藏 API，需要系统签名或特殊权限
