package com.example.ethernetconfig.network

import android.content.Context
import android.util.Log

/**
 * 通过 root 权限以 system 身份调用 EthernetManager Java API。
 *
 * 原理：将配置参数传给一个 shell 脚本，该脚本用 app_process 以 root 身份
 * 运行我们 app 的 dex 代码，从而获得调用 EthernetManager 的权限。
 */
object RootEthernetHelper {

    private const val TAG = "RootEthernetHelper"

    /**
     * 通过 root 以 system 身份调用 EthernetManager 设置静态 IP。
     */
    fun setStaticIp(
        context: Context,
        interfaceName: String,
        ip: String,
        prefixLength: Int,
        gateway: String,
        dns1: String,
        dns2: String
    ): Result<Unit> {
        return try {
            // 获取我们 app 的 APK 路径作为 classpath
            val apkPath = context.packageCodePath

            // 构造 app_process 命令，以 root 身份运行 EthernetConfigurator
            val args = listOf(
                "STATIC", interfaceName, ip, prefixLength.toString(),
                gateway.ifEmpty { "none" },
                dns1.ifEmpty { "none" },
                dns2.ifEmpty { "none" }
            ).joinToString(" ")

            val cmd = "CLASSPATH=$apkPath app_process / com.example.ethernetconfig.network.EthernetConfigurator $args"
            Log.d(TAG, "Executing: su -c '$cmd'")

            val output = execRoot(cmd)
            Log.i(TAG, "EthernetConfigurator output: $output")

            if (output.contains("SUCCESS")) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException(output.ifEmpty { "Unknown error from EthernetConfigurator" }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set static IP via app_process", e)
            Result.failure(e)
        }
    }

    /**
     * 通过 root 以 system 身份调用 EthernetManager 设置 DHCP。
     */
    fun setDhcp(
        context: Context,
        interfaceName: String
    ): Result<Unit> {
        return try {
            val apkPath = context.packageCodePath
            val cmd = "CLASSPATH=$apkPath app_process / com.example.ethernetconfig.network.EthernetConfigurator DHCP $interfaceName"
            Log.d(TAG, "Executing: su -c '$cmd'")

            val output = execRoot(cmd)
            Log.i(TAG, "EthernetConfigurator output: $output")

            if (output.contains("SUCCESS")) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException(output.ifEmpty { "Unknown error from EthernetConfigurator" }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set DHCP via app_process", e)
            Result.failure(e)
        }
    }

    private fun execRoot(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        val combined = (output + "\n" + error).trim()
        if (exitCode != 0 && combined.isNotEmpty()) {
            throw RuntimeException("Exit $exitCode: $combined")
        }
        return combined
    }
}
