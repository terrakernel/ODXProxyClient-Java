package io.odxproxy

import io.odxproxy.client.OdxProxyClient
import io.odxproxy.client.OdxProxyClientInfo
import io.odxproxy.model.OdxClientKeywordRequest
import io.odxproxy.model.OdxInstanceInfo
import kotlinx.serialization.json.JsonObject
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.io.FileInputStream
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Read-only smoke test against a live gateway. Unlike [OdxProxyLiveTest] this never mutates
 * Odoo data — it only exercises the transport (TLS, ALPN, connection reuse) and the decode path.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OdxProxySmokeTest {
    private val configFile = File("odx-test.properties")
    private val props = Properties()
    private lateinit var gatewayUrl: String
    private lateinit var apiKey: String

    @BeforeAll
    fun setup() {
        if (configFile.exists()) FileInputStream(configFile).use { props.load(it) }
        Assumptions.assumeTrue(configFile.exists(), "Skipping smoke test: odx-test.properties not found")

        gatewayUrl = props.getProperty("odx.gateway.url").removeSuffix("/")
        apiKey = props.getProperty("odx.api.key")

        val instance = OdxInstanceInfo(
            props.getProperty("odoo.url"),
            props.getProperty("odoo.user.id").toInt(),
            props.getProperty("odoo.db"),
            props.getProperty("odoo.api.key")
        )
        resetSingleton()
        OdxProxy.init(OdxProxyClientInfo(instance, apiKey, gatewayUrl))
        println("\n=== SMOKE TEST TARGET: $gatewayUrl ===\n")
    }

    private fun resetSingleton() {
        try {
            val field = OdxProxyClient::class.java.getDeclaredField("instanceRef")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as AtomicReference<Any?>).set(null)
        } catch (_: Exception) { /* ignore */ }
    }

    /** Raw OkHttp 5 probe: what does the TLS handshake and ALPN actually negotiate? */
    @Test
    fun `transport - protocol, TLS and connection reuse`() {
        val pool = ConnectionPool(16, 5, TimeUnit.MINUTES)
        val client = OkHttpClient.Builder().connectionPool(pool).build()

        val body = """{"action":"search_count","model_id":"res.partner","params":[[]],""" +
            """"odoo_instance":{"url":"${props.getProperty("odoo.url")}",""" +
            """"user_id":${props.getProperty("odoo.user.id")},"db":"${props.getProperty("odoo.db")}",""" +
            """"api_key":"${props.getProperty("odoo.api.key")}"},"id":"smoke-transport"}"""

        repeat(3) { i ->
            val req = Request.Builder()
                .url("$gatewayUrl/api/odoo/execute")
                .headers(Headers.headersOf("Accept", "application/json", "X-Api-Key", apiKey))
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val hs = resp.handshake
                println(
                    "call #${i + 1}: HTTP ${resp.code} | protocol=${resp.protocol} | " +
                        "tls=${hs?.tlsVersion} | cipher=${hs?.cipherSuite} | " +
                        "poolConnections=${pool.connectionCount()} idle=${pool.idleConnectionCount()}"
                )
                assertTrue(resp.code in 200..499, "gateway reachable, got ${resp.code}")
            }
        }
        println("--> connection reuse: pool holds ${pool.connectionCount()} connection(s) after 3 calls")
        assertTrue(pool.connectionCount() == 1, "expected all 3 calls to share one pooled connection")
    }

    @Test
    fun `read-only - searchCount res_partner`() {
        val res = OdxProxy.searchCount("res.partner", listOf<Any>(emptyList<Any>()), OdxClientKeywordRequest(), null).get()
        println("searchCount(res.partner) = ${res.result}")
        assertNotNull(res.result)
    }

    @Test
    fun `read-only - fieldsGet res_partner decodes JsonObject`() {
        val res = OdxProxy.fieldsGet("res.partner", OdxClientKeywordRequest(), null, JsonObject::class.java).get()
        val fields = res.result
        assertNotNull(fields)
        println("fieldsGet(res.partner) -> ${fields.keys.size} fields; sample=${fields.keys.take(8)}")
        assertTrue(fields.keys.isNotEmpty())
    }

    @Test
    fun `read-only - searchRead streams and decodes typed records`() {
        // ResPartner requires company_id/ref/email, so all of them must be requested or the
        // decode fails — this is the OdxMany2One / OdxVariant polymorphism path against real data.
        val kw = OdxClientKeywordRequest(
            fields = listOf("id", "name", "company_id", "ref", "email"),
            limit = 5
        )
        val res = OdxProxy.searchRead(
            "res.partner", listOf<Any>(emptyList<Any>()), kw, null, ResPartner::class.java
        ).get()
        val rows = res.result
        assertNotNull(rows)
        println("searchRead(res.partner, limit=5) -> ${rows.size} rows")
        rows.forEach {
            println("   id=${it.id} name=${it.name} company=${it.company} ref=${it.ref} email=${it.email}")
        }
        assertTrue(rows.size <= 5)
    }

    @Test
    fun `read-only - concurrent calls do not cross-contaminate`() {
        val n = (System.getenv("SMOKE_CONCURRENCY") ?: "3").toInt()
        val futures = (1..n).map {
            OdxProxy.searchCount("res.partner", listOf<Any>(emptyList<Any>()), OdxClientKeywordRequest(), null)
        }
        val results = futures.map { it.get().result }
        println("$n concurrent searchCount results: $results")
        assertTrue(results.all { it != null && it == results[0] }, "all concurrent results should agree")
    }

    /** Exercises the streaming decode path on a genuinely large body, not a mock fixture. */
    @Test
    fun `read-only - large searchRead streams without limit`() {
        val kw = OdxClientKeywordRequest(fields = listOf("id", "name", "company_id", "ref", "email"))
        val started = System.currentTimeMillis()
        val res = OdxProxy.searchRead(
            "res.partner", listOf<Any>(emptyList<Any>()), kw, null, ResPartner::class.java
        ).get()
        val elapsed = System.currentTimeMillis() - started
        val rows = res.result
        assertNotNull(rows)
        val withCompany = rows.count { it.company.id != null }
        val falseEmails = rows.count { it.email.value == null }
        println("large searchRead -> ${rows.size} rows in ${elapsed}ms")
        println("   OdxMany2One resolved: $withCompany/${rows.size} | email decoded as false->null: $falseEmails/${rows.size}")
        assertTrue(rows.size > 100, "expected a large result set, got ${rows.size}")
    }
}
