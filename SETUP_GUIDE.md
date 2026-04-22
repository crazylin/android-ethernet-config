# Android 以太网配置工具 - 部署指南

## 概述

本工具用于在 Android 设备上配置以太网（有线网卡）的 IP 和 DNS 参数。
支持 DHCP 自动获取和静态 IP 手动配置两种模式。

**适用设备**: Alldocube iPlay 70 mini Pro (T813, MT6877, Android 15)
**前置条件**: 设备需要 Root (Magisk) + USB 以太网适配器

---

## 一、环境准备

### 1.1 所需工具

| 工具 | 用途 | 获取方式 |
|------|------|----------|
| ADB | 设备调试通信 | Android SDK Platform Tools |
| Fastboot | 刷入 boot 镜像 | 同上 |
| Magisk APK | Root 工具 | github.com/topjohnwu/Magisk |
| 官方固件包 | 提取 init_boot.img | alldocube.com/en/software/16064 |

### 1.2 设备设置

1. 设置 → 关于平板 → 连点版本号 7 次 → 开启开发者选项
2. 设置 → 开发者选项 → 开启 **USB 调试**
3. 设置 → 开发者选项 → 开启 **OEM 解锁**

---

## 二、Root 流程 (Magisk)

### 2.1 解锁 Bootloader

```bash
# 确认设备连接
adb devices

# 重启到 fastboot
adb reboot bootloader

# 等待 15 秒后确认 fastboot 连接
fastboot devices

# 解锁 bootloader (会清除所有数据!)
fastboot flashing unlock

# 确认解锁状态
fastboot getvar unlocked
# 应显示: unlocked: yes

# 重启回系统
fastboot reboot
```

> **注意**: 解锁后需要重新完成初始设置，重新开启 USB 调试和 OEM 解锁。

### 2.2 提取并 Patch init_boot.img

```bash
# 安装 Magisk APK 到设备
adb install Magisk.apk

# 从官方固件包中找到 init_boot.img，推送到设备
# 固件包路径: T813_B15_V4.0.35_20260323/T813_B15_V4.0.35_20260323_user/init_boot.img
adb push init_boot.img /sdcard/Download/init_boot.img
```

在平板上操作:
1. 打开 Magisk app
2. 点 **安装** → **选择并修补一个文件**
3. 选择 Download 文件夹中的 `init_boot.img`
4. 等待完成，生成 `magisk_patched-xxxxx.img`

### 2.3 刷入 Patched Boot

```bash
# 拉取 patched 文件到电脑
adb pull /sdcard/Download/magisk_patched-xxxxx.img magisk_patched.img

# 重启到 fastboot
adb reboot bootloader
fastboot devices

# 刷入 (设备是 A/B 分区，会自动刷到当前 slot)
fastboot flash init_boot magisk_patched.img

# 重启
fastboot reboot
```

### 2.4 验证 Root

```bash
# 重新开启 USB 调试后
# 先在设备上打开一次 Magisk app
# 然后验证:
adb shell "su -c id"
# 应显示: uid=0(root) gid=0(root)
```

---

## 三、安装以太网配置工具

### 3.1 安装 APK

```bash
adb install app-debug.apk
```

### 3.2 安装 Magisk 模块 (系统特权应用)

```bash
# 推送模块 zip 到设备
adb push ethernet-config-module.zip /sdcard/Download/

# 通过 Magisk 命令行安装模块
adb shell "su -c 'magisk --install-module /sdcard/Download/ethernet-config-module.zip'"

# 重启生效
adb reboot
```

> **模块作用**: 将 APK 安装到 `/system/priv-app/` 并添加权限白名单。
> 虽然签名级权限仍无法授予，但 app 通过 root + app_process 方式绕过。

---

## 四、技术架构

### 4.1 配置原理

Android 15 的以太网配置必须通过 `EthernetManager` Java API，
`ip` 命令设置的 IP 会被 ConnectivityService 的 DHCP 立即覆盖。

本工具的解决方案:

```
App (普通权限)
  ↓ 调用
RootEthernetHelper
  ↓ 执行 su -c "CLASSPATH=apk app_process / EthernetConfigurator ..."
EthernetConfigurator (以 root 身份运行的独立 Java 进程)
  ↓ 通过 ServiceManager.getService("ethernet") 获取 binder
IEthernetManager
  ↓ 调用 updateConfiguration(iface, request, executor, callback)
EthernetService (系统服务)
  ↓ 真正应用配置
网络接口 eth0
```

### 4.2 关键文件

| 文件 | 作用 |
|------|------|
| `EthernetManagerWrapper.kt` | 主入口，检测 root 后委托给 helper |
| `RootEthernetHelper.kt` | 构造 app_process 命令并通过 su 执行 |
| `EthernetConfigurator.kt` | 独立 main() 函数，用纯反射调用 EthernetManager API |

### 4.3 为什么其他方案不行

| 方案 | 结果 | 原因 |
|------|------|------|
| `ip addr add` | IP 被秒覆盖 | ConnectivityService 持续监控并重跑 DHCP |
| `EthernetManager` 反射 | 权限拒绝 | Android 15 APEX 模块，字段私有化 |
| `IpConfiguration.Builder` | 权限拒绝 | 需要 MANAGE_ETHERNET_NETWORKS 签名级权限 |
| `pm grant` 权限 | 不可授予 | 签名级权限无法通过 pm grant 授予 |
| `Settings.Global` | 不生效 | Android 15 不读取这些键 |
| `/data/misc/ethernet/ipconfig.txt` | 不生效 | Android 15 不使用此文件 |
| `setprop ctl.restart ethernet` | 权限拒绝 | SELinux 阻止 |
| `app_process` + root | **成功** | 以 root 身份直接调用系统 binder 接口 |

---

## 五、使用说明

1. 插入 USB 以太网适配器
2. 打开「以太网配置」app
3. 在接口选择框中确认 `eth0`（或手动输入）
4. 选择「静态 IP」模式
5. 填写 IP 地址、子网掩码、网关、DNS
6. 点击「应用配置」
7. 首次使用时 Magisk 会弹出 Root 授权请求，点允许
8. 底部网络状态面板会更新为新的 IP

---

## 六、故障排查

### 配置失败: "su: not found"
- Magisk 未正确安装，重新刷入 patched init_boot.img

### 配置失败: "NoSuchMethodException"
- Android 版本 API 变化，检查 EthernetConfigurator.kt 中的反射方法签名

### 配置成功但 IP 没变
- 确认 Magisk Root 授权已允许（检查 Magisk → 超级用户 列表）
- 确认 app_process 命令输出包含 "SUCCESS"

### eth0 不显示
- 确认 USB 以太网适配器已插入
- 点击「刷新」按钮
- 手动输入接口名 `eth0`

### Bootloader 重新锁定
- 恢复出厂后 OEM 解锁开关会重置
- 需要重新进系统开启 OEM 解锁，再执行 fastboot flashing unlock

---

## 七、项目仓库

- GitHub: https://github.com/crazylin/android-ethernet-config
- CI/CD: GitHub Actions 自动编译，APK 在 Actions Artifacts 下载
