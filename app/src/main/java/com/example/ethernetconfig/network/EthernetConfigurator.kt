@file:JvmName("EthernetConfigurator")
package com.example.ethernetconfig.network

import android.os.IBinder
import android.os.Looper
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.Executor

/**
 * Standalone program run via: CLASSPATH=apk app_process / ...EthernetConfigurator
 * Uses pure reflection to call EthernetManager hidden APIs with root privileges.
 */
fun main(args: Array<String>) {
    try {
        if (args.size < 2) {
            println("ERROR: Usage: STATIC iface ip prefix gw dns1 dns2 | DHCP iface")
            return
        }
        if (Looper.myLooper() == null) Looper.prepareMainLooper()

        val mode = args[0]
        val iface = args[1]

        // Get IEthernetManager binder
        val smClass = Class.forName("android.os.ServiceManager")
        val binder = smClass.getMethod("getService", String::class.java)
            .invoke(null, "ethernet") as IBinder
        val stubClass = Class.forName("android.net.IEthernetManager\$Stub")
        val em = stubClass.getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!

        // Build IpConfiguration via Builder (reflection)
        val ipConfigBuilderClass = Class.forName("android.net.IpConfiguration\$Builder")
        val ipConfigBuilder = ipConfigBuilderClass.getDeclaredConstructor().newInstance()

        if (mode == "STATIC" && args.size >= 5) {
            val ip = args[2]
            val prefix = args[3].toInt()
            val gw = args[4]
            val dns1 = if (args.size > 5) args[5] else "none"
            val dns2 = if (args.size > 6) args[6] else "none"

            // Build StaticIpConfiguration via Builder
            val staticBuilderClass = Class.forName("android.net.StaticIpConfiguration\$Builder")
            val sb = staticBuilderClass.getDeclaredConstructor().newInstance()

            val linkAddrClass = Class.forName("android.net.LinkAddress")
            val laCtor = linkAddrClass.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
            val linkAddr = laCtor.newInstance(InetAddress.getByAddress(parseIp(ip)), prefix)
            staticBuilderClass.getMethod("setIpAddress", linkAddrClass).invoke(sb, linkAddr)

            if (gw != "none" && gw.isNotEmpty()) {
                staticBuilderClass.getMethod("setGateway", InetAddress::class.java)
                    .invoke(sb, InetAddress.getByAddress(parseIp(gw)))
            }

            val dnsList = mutableListOf<InetAddress>()
            if (dns1 != "none" && dns1.isNotEmpty()) dnsList.add(InetAddress.getByAddress(parseIp(dns1)))
            if (dns2 != "none" && dns2.isNotEmpty()) dnsList.add(InetAddress.getByAddress(parseIp(dns2)))
            staticBuilderClass.getMethod("setDnsServers", Iterable::class.java).invoke(sb, dnsList as Iterable<*>)

            val staticConfig = staticBuilderClass.getMethod("build").invoke(sb)
            val staticIpClass = Class.forName("android.net.StaticIpConfiguration")
            ipConfigBuilderClass.getMethod("setStaticIpConfiguration", staticIpClass)
                .invoke(ipConfigBuilder, staticConfig)
        }

        val ipConfig = ipConfigBuilderClass.getMethod("build").invoke(ipConfigBuilder)!!
        var success = false

        // Try updateConfiguration (Android 13+)
        try {
            val reqBuilderClass = Class.forName("android.net.EthernetNetworkUpdateRequest\$Builder")
            val reqBuilder = reqBuilderClass.getDeclaredConstructor().newInstance()
            val ipConfigClass = Class.forName("android.net.IpConfiguration")
            reqBuilderClass.getMethod("setIpConfiguration", ipConfigClass).invoke(reqBuilder, ipConfig)
            val request = reqBuilderClass.getMethod("build").invoke(reqBuilder)!!

            val reqClass = Class.forName("android.net.EthernetNetworkUpdateRequest")
            val outcomeClass = Class.forName("android.os.OutcomeReceiver")

            val done = booleanArrayOf(false)
            var errorMsg: String? = null
            val receiver = Proxy.newProxyInstance(
                outcomeClass.classLoader, arrayOf(outcomeClass)
            ) { _, method, mArgs ->
                when (method.name) {
                    "onResult" -> done[0] = true
                    "onError" -> { errorMsg = mArgs?.get(0)?.toString(); done[0] = true }
                }
                null
            }

            em.javaClass.getMethod("updateConfiguration",
                String::class.java, reqClass, Executor::class.java, outcomeClass)
                .invoke(em, iface, request, Executor { it.run() }, receiver)

            for (i in 0 until 50) { if (done[0]) break; Thread.sleep(100) }

            if (errorMsg != null) {
                println("WARN: updateConfiguration error: $errorMsg")
            } else {
                success = true
            }
        } catch (e: Exception) {
            println("WARN: updateConfiguration failed: ${e.message}")
        }

        // Fallback: setConfiguration
        if (!success) {
            try {
                val ipConfigClass = Class.forName("android.net.IpConfiguration")
                try {
                    em.javaClass.getMethod("setConfiguration", String::class.java, ipConfigClass)
                        .invoke(em, iface, ipConfig)
                } catch (_: NoSuchMethodException) {
                    em.javaClass.getMethod("setConfiguration", ipConfigClass)
                        .invoke(em, ipConfig)
                }
                success = true
            } catch (e: Exception) {
                println("ERROR: setConfiguration: ${e.message}")
            }
        }

        println(if (success) "SUCCESS" else "ERROR: all methods failed")

    } catch (e: Exception) {
        println("ERROR: ${e.message}")
        e.printStackTrace(System.out)
    }
}

private fun parseIp(ip: String): ByteArray {
    val p = ip.split(".")
    return byteArrayOf(p[0].toInt().toByte(), p[1].toInt().toByte(), p[2].toInt().toByte(), p[3].toInt().toByte())
}
