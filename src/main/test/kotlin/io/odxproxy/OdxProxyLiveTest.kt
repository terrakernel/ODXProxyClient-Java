package io.odxproxy

import io.odxproxy.client.OdxProxyClientInfo
import io.odxproxy.model.OdxClientKeywordRequest
import io.odxproxy.model.OdxInstanceInfo
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

    @BeforeAll
    fun setup() {
        if (configFile.exists()) {
            FileInputStream(configFile).use { props.load(it) }
        }
        Assumptions.assumeTrue(configFile.exists(), "Skipping Live Tests")
        
        val instance = OdxInstanceInfo(
            props.getProperty("odoo.url"),
            props.getProperty("odoo.user.id").toInt(),
            props.getProperty("odoo.db"),
            props.getProperty("odoo.api.key")
        )
        val config = OdxProxyClientInfo(instance, props.getProperty("odx.api.key"), props.getProperty("odx.gateway.url"))
        try { OdxProxy.init(config) } catch (e: Exception) {}
    }

    @Test
    fun `Live Lifecycle`() {
        Assumptions.assumeTrue(props.containsKey("odoo.url"))
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