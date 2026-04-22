package com.example.ethernetconfig.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.ethernetconfig.model.ConfigMode
import com.example.ethernetconfig.model.NetworkConfiguration
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
 * 简单的内存 SharedPreferences 实现，用于单元测试中替代 Android 真实实现。
 */
class InMemorySharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? =
        if (data.containsKey(key)) data[key] as? String else defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        if (data.containsKey(key)) @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) else defValues
    override fun getInt(key: String?, defValue: Int): Int =
        if (data.containsKey(key)) data[key] as? Int ?: defValue else defValue
    override fun getLong(key: String?, defValue: Long): Long =
        if (data.containsKey(key)) data[key] as? Long ?: defValue else defValue
    override fun getFloat(key: String?, defValue: Float): Float =
        if (data.containsKey(key)) data[key] as? Float ?: defValue else defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        if (data.containsKey(key)) data[key] as? Boolean ?: defValue else defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener?.let { listeners.add(it) }
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener?.let { listeners.remove(it) }
    }

    override fun edit(): SharedPreferences.Editor = InMemoryEditor(data)

    private class InMemoryEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            key?.let { pending[it] = values }
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { removals.add(it) }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun commit(): Boolean {
            applyChanges()
            return true
        }
        override fun apply() {
            applyChanges()
        }
        private fun applyChanges() {
            if (clearAll) data.clear()
            removals.forEach { data.remove(it) }
            data.putAll(pending)
        }
    }
}

/**
 * Feature: android-ethernet-config, Property 6: 配置持久化往返
 *
 * Validates: Requirements 4.2
 */
class ConfigStoragePropertyTest : FunSpec({

    tags(Tag("Feature: android-ethernet-config"), Tag("Property 6: 配置持久化往返"))

    val minIterations = PropTestConfig(iterations = 20)

    // --- Generators ---

    val configModeArb: Arb<ConfigMode> = Arb.element(ConfigMode.DHCP, ConfigMode.STATIC)
    val validOctet: Arb<Int> = Arb.int(0..255)
    val validIpv4: Arb<String> = Arb.bind(validOctet, validOctet, validOctet, validOctet) { a, b, c, d ->
        "$a.$b.$c.$d"
    }
    val networkConfigArb: Arb<NetworkConfiguration> = arbitrary {
        NetworkConfiguration(
            mode = configModeArb.bind(),
            ipAddress = validIpv4.bind(),
            subnetMask = validIpv4.bind(),
            gateway = validIpv4.bind(),
            primaryDns = validIpv4.bind(),
            secondaryDns = validIpv4.bind()
        )
    }

    fun createConfigStorage(): ConfigStorage {
        val sharedPrefs = InMemorySharedPreferences()
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
        return ConfigStorage(context)
    }

    // --- Property Tests ---

    test("saveConfiguration then loadConfiguration returns equivalent NetworkConfiguration") {
        checkAll(minIterations, networkConfigArb) { config ->
            val storage = createConfigStorage()
            storage.saveConfiguration(config)
            val loaded = storage.loadConfiguration()
            loaded shouldBe config
        }
    }

    test("saveConfiguration overwrites previous configuration correctly") {
        checkAll(minIterations, networkConfigArb, networkConfigArb) { first, second ->
            val storage = createConfigStorage()
            storage.saveConfiguration(first)
            storage.saveConfiguration(second)
            val loaded = storage.loadConfiguration()
            loaded shouldBe second
        }
    }

    test("loadConfiguration returns null when no configuration has been saved") {
        val storage = createConfigStorage()
        storage.loadConfiguration() shouldBe null
    }

    test("clearConfiguration then loadConfiguration returns null") {
        checkAll(minIterations, networkConfigArb) { config ->
            val storage = createConfigStorage()
            storage.saveConfiguration(config)
            storage.clearConfiguration()
            storage.loadConfiguration() shouldBe null
        }
    }
})
