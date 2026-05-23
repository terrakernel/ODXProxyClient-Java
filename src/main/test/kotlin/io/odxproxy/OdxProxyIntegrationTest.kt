package io.odxproxy

import io.odxproxy.client.OdxProxyClient
import io.odxproxy.client.OdxProxyClientInfo
import io.odxproxy.exception.OdxServerErrorException
import io.odxproxy.model.OdxClientKeywordRequest
import io.odxproxy.model.OdxInstanceInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OdxProxyIntegrationTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        resetSingleton()
        val instance = OdxInstanceInfo("http://upstream.example.com/", 1, "demo", "odoo-key")
        val clientInfo = OdxProxyClientInfo(instance, "odx-api-key", "http://localhost:${server.port}")
        OdxProxy.init(clientInfo)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ---------------- Request shape ----------------

    @Test
    fun `request hits gateway endpoint with correct headers and api key`() {
        server.enqueue(okResponse("""{"jsonrpc":"2.0","result":[]}"""))

        OdxProxy.search("res.partner", emptyList(), OdxClientKeywordRequest(), null).get()

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/odoo/execute", recorded.path)
        assertEquals("odx-api-key", recorded.getHeader("X-Api-Key"))
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `request body encodes action, model_id, params and odoo_instance`() {
        server.enqueue(okResponse("""{"jsonrpc":"2.0","result":[]}"""))

        OdxProxy.search(
            "res.partner",
            listOf(listOf("active", "=", true)),
            OdxClientKeywordRequest(fields = listOf("name"), limit = 10),
            "fixed-id"
        ).get()

        val body = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
        val obj = json.parseToJsonElement(body).jsonObject

        assertEquals("fixed-id", obj["id"]!!.jsonPrimitive.content)
        assertEquals("search", obj["action"]!!.jsonPrimitive.content)
        assertEquals("res.partner", obj["model_id"]!!.jsonPrimitive.content)
        val instance = obj["odoo_instance"]!!.jsonObject
        assertEquals("demo", instance["db"]!!.jsonPrimitive.content)
        assertEquals("odoo-key", instance["api_key"]!!.jsonPrimitive.content)
        assertEquals(1, instance["user_id"]!!.jsonPrimitive.int)

        // `search` resets pagination — limit/fields should be absent from the keyword.
        val keyword = obj["keyword"]?.jsonObject
        assertTrue(keyword == null || keyword["limit"] == null)
        assertTrue(keyword == null || keyword["fields"] == null)
    }

    @Test
    fun `searchRead preserves keyword pagination`() {
        server.enqueue(okResponse("""{"jsonrpc":"2.0","result":[]}"""))

        OdxProxy.searchRead(
            "res.partner",
            emptyList(),
            OdxClientKeywordRequest(fields = listOf("name"), limit = 5, offset = 10, order = "id desc"),
            null,
            JsonObject::class.java
        ).get()

        val obj = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val keyword = obj["keyword"]!!.jsonObject
        assertEquals(5, keyword["limit"]!!.jsonPrimitive.int)
        assertEquals(10, keyword["offset"]!!.jsonPrimitive.int)
        assertEquals("id desc", keyword["order"]!!.jsonPrimitive.content)
        assertEquals("name", keyword["fields"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `null id is auto-generated as a non-empty ULID-like string`() {
        server.enqueue(okResponse("""{"jsonrpc":"2.0","result":[]}"""))

        OdxProxy.search("res.partner", emptyList(), OdxClientKeywordRequest(), null).get()

        val obj = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val id = obj["id"]!!.jsonPrimitive.content
        assertEquals(26, id.length) // ULID canonical length
    }

    // ---------------- API surface coverage ----------------

    @Test
    fun `searchRead deserializes typed records`() {
        server.enqueue(okResponse("""
            {"jsonrpc":"2.0","result":[
              {"id":1,"name":"Alice","company_id":[7,"ACME"],"ref":"R1","email":"a@x"},
              {"id":2,"name":"Bob","company_id":false,"ref":false,"email":false}
            ]}
        """.trimIndent()))

        val result = OdxProxy.searchRead(
            "res.partner", emptyList(), OdxClientKeywordRequest(), null, ResPartner::class.java
        ).get().result!!

        assertEquals(2, result.size)
        assertEquals(7, result[0].company.id)
        assertEquals("ACME", result[0].company.name)
        assertEquals("R1", result[0].ref.value)
        assertNull(result[1].company.id)
        assertNull(result[1].ref.value)
        assertNull(result[1].email.value)
    }

    @Test
    fun `searchCount returns scalar int`() {
        server.enqueue(okResponse("""{"jsonrpc":"2.0","result":42}"""))

        val count = OdxProxy.searchCount("res.partner", emptyList(), OdxClientKeywordRequest(), null).get().result
        assertEquals(42, count)
    }

    @Test
    fun `create returns new id and sends action=create`() {
        server.enqueue(okResponse("""{"jsonrpc":"2.0","result":99}"""))

        val values = mapOf("name" to "New", "active" to true)
        val id = OdxProxy.create(
            "res.partner", listOf(values), OdxClientKeywordRequest(), null, Int::class.javaObjectType
        ).get().result
        assertEquals(99, id)

        val obj = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("create", obj["action"]!!.jsonPrimitive.content)
        val params = obj["params"]!!.jsonArray
        assertEquals("New", params[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(params[0].jsonObject["active"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `write packages ids and values and returns true`() {
        server.enqueue(okResponse("""{"jsonrpc":"2.0","result":true}"""))

        val ok = OdxProxy.write(
            "res.partner", listOf(1, 2, 3), mapOf("name" to "X"), OdxClientKeywordRequest(), null
        ).get().result
        assertEquals(true, ok)

        val obj = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("write", obj["action"]!!.jsonPrimitive.content)
        val params = obj["params"]!!.jsonArray
        assertEquals(listOf(1, 2, 3), params[0].jsonArray.map { it.jsonPrimitive.int })
        assertEquals("X", params[1].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `remove maps to unlink action`() {
        server.enqueue(okResponse("""{"jsonrpc":"2.0","result":true}"""))

        OdxProxy.remove("res.partner", listOf(5), OdxClientKeywordRequest(), null).get()

        val obj = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("unlink", obj["action"]!!.jsonPrimitive.content)
        assertEquals(5, obj["params"]!!.jsonArray[0].jsonArray[0].jsonPrimitive.int)
    }

    @Test
    fun `callMethod sends fn_name and arbitrary return type`() {
        server.enqueue(okResponse("""{"jsonrpc":"2.0","result":true}"""))

        val result = OdxProxy.callMethod(
            "res.partner", "action_archive",
            listOf(listOf(5)),
            OdxClientKeywordRequest(),
            null,
            Boolean::class.javaObjectType
        ).get().result
        assertEquals(true, result)

        val obj = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("call_method", obj["action"]!!.jsonPrimitive.content)
        assertEquals("action_archive", obj["fn_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fieldsGet sends empty params and decodes a JsonObject`() {
        server.enqueue(okResponse("""{"jsonrpc":"2.0","result":{"name":{"type":"char","string":"Name"}}}"""))

        val fields = OdxProxy.fieldsGet(
            "res.partner", OdxClientKeywordRequest(), null, JsonObject::class.java
        ).get().result!!
        assertNotNull(fields["name"])

        val obj = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("fields_get", obj["action"]!!.jsonPrimitive.content)
        assertTrue(obj["params"]!!.jsonArray.isEmpty())
    }

    // ---------------- Error paths ----------------

    @Test
    fun `5xx with JSON-RPC error envelope surfaces OdxServerErrorException`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","error":{"code":42,"message":"boom"}}""")
        )

        val ex = assertThrows<ExecutionException> {
            OdxProxy.search("res.partner", emptyList(), OdxClientKeywordRequest(), null).get()
        }
        val cause = ex.cause as OdxServerErrorException
        assertEquals(42, cause.code)
        assertEquals("boom", cause.message)
    }

    @Test
    fun `5xx with non-JSON body still surfaces OdxServerErrorException with http code`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream unavailable"))

        val ex = assertThrows<ExecutionException> {
            OdxProxy.search("res.partner", emptyList(), OdxClientKeywordRequest(), null).get()
        }
        val cause = ex.cause as OdxServerErrorException
        assertEquals(503, cause.code)
    }

    @Test
    fun `200 with error envelope still throws`() {
        server.enqueue(okResponse("""{"jsonrpc":"2.0","error":{"code":7,"message":"odoo said no"}}"""))

        val ex = assertThrows<ExecutionException> {
            OdxProxy.search("res.partner", emptyList(), OdxClientKeywordRequest(), null).get()
        }
        assertEquals("odoo said no", (ex.cause as OdxServerErrorException).message)
    }

    @Test
    fun `connection failure completes the future exceptionally`() {
        server.shutdown() // socket is closed; next request will fail.

        val ex = assertThrows<ExecutionException> {
            OdxProxy.search("res.partner", emptyList(), OdxClientKeywordRequest(), null).get()
        }
        assertTrue(ex.cause is java.io.IOException)
    }

    // ---------------- Concurrency / cache behavior ----------------

    @Test
    fun `parallel requests are handled independently and do not cross-contaminate results`() {
        val total = 25
        val counter = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = counter.incrementAndGet()
                return okResponse("""{"jsonrpc":"2.0","result":$n}""")
            }
        }

        val futures = (1..total).map {
            OdxProxy.searchCount("res.partner", emptyList(), OdxClientKeywordRequest(), null)
        }
        CompletableFuture.allOf(*futures.toTypedArray()).get(15, TimeUnit.SECONDS)

        // Every request got *some* unique-ish integer back. We assert the multiset matches 1..N,
        // which proves results are not being aliased across futures by a shared serializer/cache.
        val seen = futures.map { it.get().result!! }.toSet()
        assertEquals((1..total).toSet(), seen)
    }

    @Test
    fun `serializer cache is reused across calls for the same type`() {
        // Two sequential typed searchRead calls of the same T. We can't introspect the cache
        // directly, but if the cache were broken we'd at least see ClassCast or NPE here. This
        // also doubles as a smoke test that the streaming decoder handles back-to-back bodies.
        repeat(2) {
            server.enqueue(okResponse("""
                {"jsonrpc":"2.0","result":[{"id":1,"name":"A","company_id":false,"ref":false,"email":false}]}
            """.trimIndent()))
        }
        repeat(2) {
            val r = OdxProxy.searchRead(
                "res.partner", emptyList(), OdxClientKeywordRequest(), null, ResPartner::class.java
            ).get().result!!
            assertEquals(1, r[0].id)
        }
    }

    // ---------------- Streaming sanity ----------------

    @Test
    fun `large response body is streamed and decoded`() {
        val n = 2_000
        val rows = (1..n).joinToString(",") {
            """{"id":$it,"name":"P$it","company_id":false,"ref":false,"email":false}"""
        }
        server.enqueue(okResponse("""{"jsonrpc":"2.0","result":[$rows]}"""))

        val result = OdxProxy.searchRead(
            "res.partner", emptyList(), OdxClientKeywordRequest(), null, ResPartner::class.java
        ).get().result!!
        assertEquals(n, result.size)
        assertEquals("P$n", result[n - 1].name)
    }

    // ---------------- Helpers ----------------

    private fun okResponse(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun resetSingleton() {
        try {
            val field = OdxProxyClient::class.java.getDeclaredField("instanceRef")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as AtomicReference<Any?>).set(null)
        } catch (_: Exception) { /* ignore */ }
    }

}
