package io.odxproxy

import io.odxproxy.client.OdxProxyClient
import io.odxproxy.client.OdxProxyClientInfo
import io.odxproxy.model.OdxClientKeywordRequest
import io.odxproxy.model.OdxInstanceInfo
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.io.FileInputStream
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OdxProxyLiveTest {
    private val configFile = File("odx-test.properties")
    private val props = Properties()

    private val requiredKeys = listOf(
        "odoo.url", "odoo.user.id", "odoo.db", "odoo.api.key",
        "odx.api.key", "odx.gateway.url"
    )

    @BeforeAll
    fun setup() {
        if (configFile.exists()) {
            FileInputStream(configFile).use { props.load(it) }
        }
        Assumptions.assumeTrue(configFile.exists(), "Skipping Live Tests: odx-test.properties not found")
        val missing = requiredKeys.filter { props.getProperty(it).isNullOrBlank() }
        Assumptions.assumeTrue(missing.isEmpty(), "Skipping Live Tests: missing/blank keys $missing")

        val instance = OdxInstanceInfo(
            props.getProperty("odoo.url"),
            props.getProperty("odoo.user.id").toInt(),
            props.getProperty("odoo.db"),
            props.getProperty("odoo.api.key")
        )
        val config = OdxProxyClientInfo(instance, props.getProperty("odx.api.key"), props.getProperty("odx.gateway.url"))
        resetSingleton()
        OdxProxy.init(config)
    }

    private fun resetSingleton() {
        try {
            val field = OdxProxyClient::class.java.getDeclaredField("instanceRef")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as AtomicReference<Any?>).set(null)
        } catch (_: Exception) { /* ignore */ }
    }

    @Test
    fun `Live Lifecycle`() {
        val model = "res.partner"
        val timestamp = System.currentTimeMillis()
        val createVals = mapOf("name" to "ODX_TEST_$timestamp", "email" to "test_$timestamp@odxproxy.io")
        
        val newId = OdxProxy.create(model, listOf(createVals), OdxClientKeywordRequest(), null, Int::class.javaObjectType).get().result!!
        assertNotNull(newId)
        
        val readResult = OdxProxy.read(model, listOf(newId), OdxClientKeywordRequest(), null, ResPartner::class.java).get().result!!
        assertEquals("ODX_TEST_$timestamp", readResult[0].name)
        
        OdxProxy.write(model, listOf(newId), mapOf("name" to "UPDATED"), OdxClientKeywordRequest(), null).get()
        OdxProxy.remove(model, listOf(newId), OdxClientKeywordRequest(), null).get()
    }
}