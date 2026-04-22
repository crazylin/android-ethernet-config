# 需求文档

## 简介

本功能为 Android 设备提供有线网卡（以太网）的 IP 和 DNS 配置工具。用户可以通过该工具在 DHCP 自动获取和静态 IP 手动配置两种模式之间切换，并配置 IP 地址、子网掩码、网关、DNS 等网络参数。该工具面向需要在 Android 设备上管理以太网连接的用户，提供直观、可靠的网络配置体验。

## 术语表

- **Ethernet_Config_Tool**: 以太网配置工具，本功能的核心应用模块，负责管理以太网接口的网络参数配置
- **DHCP_Mode**: 动态主机配置协议模式，设备自动从 DHCP 服务器获取 IP 地址及相关网络参数
- **Static_IP_Mode**: 静态 IP 模式，用户手动指定 IP 地址及相关网络参数
- **IP_Address**: 互联网协议地址，用于标识网络中设备的唯一地址（IPv4 格式，如 192.168.1.100）
- **Subnet_Mask**: 子网掩码，用于划分网络地址和主机地址的参数（如 255.255.255.0）
- **Gateway**: 网关，网络数据包转发的下一跳地址
- **DNS_Server**: 域名系统服务器，负责将域名解析为 IP 地址
- **Ethernet_Interface**: 以太网接口，Android 设备上的有线网络硬件接口（通常为 eth0）
- **Network_Configuration**: 网络配置，包含 IP 地址、子网掩码、网关、DNS 等参数的集合

## 需求

### 需求 1：网络模式切换

**用户故事：** 作为 Android 设备用户，我希望能够在 DHCP 和静态 IP 模式之间切换，以便根据网络环境选择合适的配置方式。

#### 验收标准

1. THE Ethernet_Config_Tool SHALL 提供 DHCP_Mode 和 Static_IP_Mode 两种网络配置模式供用户选择
2. WHEN 用户选择 DHCP_Mode 时，THE Ethernet_Config_Tool SHALL 将 Ethernet_Interface 配置为自动从 DHCP 服务器获取 Network_Configuration
3. WHEN 用户选择 Static_IP_Mode 时，THE Ethernet_Config_Tool SHALL 显示手动配置 Network_Configuration 的输入界面
4. WHEN 用户从 Static_IP_Mode 切换到 DHCP_Mode 时，THE Ethernet_Config_Tool SHALL 清除之前手动配置的参数并启用自动获取
5. WHEN 用户从 DHCP_Mode 切换到 Static_IP_Mode 时，THE Ethernet_Config_Tool SHALL 将当前 DHCP 获取的参数预填充到手动配置输入框中

### 需求 2：静态 IP 参数配置

**用户故事：** 作为 Android 设备用户，我希望能够手动配置 IP 地址、子网掩码、网关等网络参数，以便在没有 DHCP 服务器或需要固定 IP 的环境中使用。

#### 验收标准

1. WHILE 处于 Static_IP_Mode 时，THE Ethernet_Config_Tool SHALL 提供 IP_Address 输入字段
2. WHILE 处于 Static_IP_Mode 时，THE Ethernet_Config_Tool SHALL 提供 Subnet_Mask 输入字段
3. WHILE 处于 Static_IP_Mode 时，THE Ethernet_Config_Tool SHALL 提供 Gateway 输入字段
4. WHEN 用户输入 IP_Address 时，THE Ethernet_Config_Tool SHALL 验证输入格式为有效的 IPv4 地址（四组 0-255 的数字，以点号分隔）
5. WHEN 用户输入 Subnet_Mask 时，THE Ethernet_Config_Tool SHALL 验证输入为有效的子网掩码（连续的高位 1 和低位 0 组成的 32 位二进制数对应的点分十进制表示）
6. WHEN 用户输入 Gateway 时，THE Ethernet_Config_Tool SHALL 验证输入格式为有效的 IPv4 地址
7. WHEN 用户提交的 Gateway 不在 IP_Address 和 Subnet_Mask 定义的子网范围内时，THE Ethernet_Config_Tool SHALL 显示警告提示信息

### 需求 3：DNS 服务器配置

**用户故事：** 作为 Android 设备用户，我希望能够配置 DNS 服务器地址，以便控制域名解析的方式和速度。

#### 验收标准

1. WHILE 处于 Static_IP_Mode 时，THE Ethernet_Config_Tool SHALL 提供主 DNS_Server 地址输入字段
2. WHILE 处于 Static_IP_Mode 时，THE Ethernet_Config_Tool SHALL 提供备用 DNS_Server 地址输入字段
3. WHEN 用户输入 DNS_Server 地址时，THE Ethernet_Config_Tool SHALL 验证输入格式为有效的 IPv4 地址
4. THE Ethernet_Config_Tool SHALL 将主 DNS_Server 地址设为必填项
5. THE Ethernet_Config_Tool SHALL 将备用 DNS_Server 地址设为选填项

### 需求 4：配置应用与持久化

**用户故事：** 作为 Android 设备用户，我希望配置的网络参数能够立即生效并在设备重启后保留，以便避免重复配置。

#### 验收标准

1. WHEN 用户确认提交 Network_Configuration 时，THE Ethernet_Config_Tool SHALL 将配置立即应用到 Ethernet_Interface
2. WHEN 用户确认提交 Network_Configuration 时，THE Ethernet_Config_Tool SHALL 将配置持久化存储，确保设备重启后配置保留
3. WHEN 配置应用成功时，THE Ethernet_Config_Tool SHALL 显示配置成功的提示信息
4. IF 配置应用失败，THEN THE Ethernet_Config_Tool SHALL 显示包含失败原因的错误提示信息
5. IF 配置应用失败，THEN THE Ethernet_Config_Tool SHALL 回滚到之前的有效配置

### 需求 5：当前网络状态显示

**用户故事：** 作为 Android 设备用户，我希望能够查看当前以太网的连接状态和配置信息，以便了解网络运行情况。

#### 验收标准

1. THE Ethernet_Config_Tool SHALL 显示 Ethernet_Interface 的当前连接状态（已连接或未连接）
2. WHILE Ethernet_Interface 处于已连接状态时，THE Ethernet_Config_Tool SHALL 显示当前生效的 IP_Address、Subnet_Mask、Gateway 和 DNS_Server 信息
3. THE Ethernet_Config_Tool SHALL 显示当前使用的网络模式（DHCP_Mode 或 Static_IP_Mode）
4. WHEN Ethernet_Interface 的连接状态发生变化时，THE Ethernet_Config_Tool SHALL 在 3 秒内更新显示的状态信息

### 需求 6：输入验证与错误处理

**用户故事：** 作为 Android 设备用户，我希望在输入错误的网络参数时得到清晰的提示，以便快速纠正配置错误。

#### 验收标准

1. WHEN 用户输入的 IP_Address 格式无效时，THE Ethernet_Config_Tool SHALL 在对应输入字段下方显示 "请输入有效的 IPv4 地址" 错误提示
2. WHEN 用户输入的 Subnet_Mask 格式无效时，THE Ethernet_Config_Tool SHALL 在对应输入字段下方显示 "请输入有效的子网掩码" 错误提示
3. WHEN 用户在 Static_IP_Mode 下未填写必填字段（IP_Address、Subnet_Mask、Gateway、主 DNS_Server）时，THE Ethernet_Config_Tool SHALL 禁用提交按钮
4. WHEN 用户输入的参数存在验证错误时，THE Ethernet_Config_Tool SHALL 阻止提交配置
5. IF Ethernet_Interface 未检测到有线网络连接，THEN THE Ethernet_Config_Tool SHALL 显示 "未检测到以太网连接" 的提示信息并禁用配置操作
