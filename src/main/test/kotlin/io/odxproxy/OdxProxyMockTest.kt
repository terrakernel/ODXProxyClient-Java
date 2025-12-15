package io.odxproxy

import io.odxproxy.client.OdxProxyClient
import io.odxproxy.client.OdxProxyClientInfo
import io.odxproxy.exception.OdxServerErrorException
import io.odxproxy.model.OdxClientKeywordRequest
import io.odxproxy.model.OdxInstanceInfo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OdxProxyMockTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        resetSingleton()
        val info = OdxInstanceInfo("http://localhost:${server.port}/", 1, "demo", "secret-key")
        val clientInfo = OdxProxyClientInfo(info, "odx-api-key", "http://localhost:${server.port}")
        OdxProxy.init(clientInfo)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search returns list of integers`() {
        val jsonResponse = """{"jsonrpc": "2.0","id": "123","result": [10, 20, 30]}"""
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))
        val future = OdxProxy.search("res.partner", emptyList(), OdxClientKeywordRequest(), null)
        val result = future.get().result
        assertNotNull(result)
        assertEquals(3, result.size)
        assertEquals(10, result[0])
    }

    @Test
    fun `read handles Odoo Polymorphism`() {
        val jsonResponse = """
            {
                "jsonrpc": "2.0",
                "result": [
                    { "id": 1, "name": "Admin", "company_id": [1, "My Company"], "ref": false, "email": "admin@example.com" },
                    { "id": 2, "name": "Guest", "company_id": false, "ref": "REF123", "email": false }
                ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        val future = OdxProxy.read("res.partner", listOf(1, 2), OdxClientKeywordRequest(), null, ResPartner::class.java)
        val partners = future.get().result!!

        assertEquals("Admin", partners[0].name)
        assertEquals(1, partners[0].company.id)
        assertNull(partners[0].ref.value)
        assertEquals("admin@example.com", partners[0].email.value)

        assertEquals("Guest", partners[1].name)
        assertNull(partners[1].company.id)
        assertEquals("REF123", partners[1].ref.value)
        assertNull(partners[1].email.value)
    }

    @Test
    fun `handles Odoo Server Error`() {
        val errorJson = """{"jsonrpc": "2.0", "error": { "code": 200, "message": "Odoo Server Error", "data": { "name": "Error" } }}"""
        server.enqueue(MockResponse().setBody(errorJson).setResponseCode(200))
        val future = OdxProxy.search("res.partner", emptyList(), OdxClientKeywordRequest(), null)
        val exception = assertThrows<ExecutionException> { future.get() }
        assertTrue(exception.cause is OdxServerErrorException)
    }

    private fun resetSingleton() {
        try {
            val field = OdxProxyClient::class.java.getDeclaredField("instanceRef")
            field.isAccessible = true
            val ref = field.get(null) as AtomicReference<*>
            ref.set(null)
        } catch (e: Exception) {}
    }
}