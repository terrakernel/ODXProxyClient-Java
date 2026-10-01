package io.odxproxy

import io.odxproxy.client.OdxProxyClient
import io.odxproxy.client.OdxProxyClientInfo
import io.odxproxy.exception.OdxServerErrorException
import io.odxproxy.model.OdxInstanceInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline tests for [OdxProxyV2]: the exact `/v2/odoo/execute` body each method sends,
 * and the error helpers. The wire shapes are the ODXProxy contract in
 * SYSTEM_ARCHITECTURE.md §4.6 / §7.1.
 */
class OdxProxyV2MockTest {
    private lateinit var server: MockWebServer
    private val defaultContext = mapOf("lang" to "en_US", "tz" to "Asia/Jakarta")
    private val defaultContextJson = """{"lang":"en_US","tz":"Asia/Jakarta"}"""

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        resetSingleton()
        val info = OdxInstanceInfo("https://erp.example.com", 2, "prod", "odoo-key")
        OdxProxy.init(OdxProxyClientInfo(info, "proxy-key", "http://localhost:${server.port}", defaultContext))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(result: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jsonrpc":"2.0","id":"r","result":$result}"""))
    }

    private fun err(httpStatus: Int, code: Int, data: String? = null) {
        val dataPart = if (data != null) ""","data":$data""" else ""
        server.enqueue(
            MockResponse().setResponseCode(httpStatus)
                .setBody("""{"jsonrpc":"2.0","id":"r","error":{"code":$code,"message":"boom"$dataPart}}""")
        )
    }

    private fun sent(): Pair<String, JsonObject> {
        val req = server.takeRequest()
        return req.path!! to Json.parseToJsonElement(req.body.readUtf8()).jsonObject
    }

    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun <T> failure(future: CompletableFuture<T>): OdxServerErrorException =
        assertThrows<ExecutionException> { future.get() }.cause as OdxServerErrorException

    @Test
    fun `envelope is v2 shaped with no user_id and no v1 fields`() {
        ok("""[{"id":1,"name":"Acme"}]""")
        val res = OdxProxyV2.searchRead(
            "res.partner", JsonObject::class.java,
            domain = listOf(listOf("is_company", "=", true)), fields = listOf("name"), limit = 5, id = "req-7"
        ).get()
        assertEquals(1, res.result!!.size)

        val (path, body) = sent()
        assertEquals("/v2/odoo/execute", path)
        assertEquals(
            json(
                """{"id":"req-7","model_id":"res.partner","method":"search_read",
                   "kwargs":{"domain":[["is_company","=",true]],"fields":["name"],"limit":5,"context":$defaultContextJson},
                   "odoo_instance":{"url":"https://erp.example.com","db":"prod","api_key":"odoo-key"}}"""
            ),
            body
        )
    }

    @Test
    fun `each method sends Odoo's parameter names`() {
        data class Case(val call: () -> CompletableFuture<*>, val method: String, val kwargs: String, val result: String)
        val cases = listOf(
            Case({ OdxProxyV2.search("res.partner", emptyList(), offset = 10, limit = 3, order = "id desc") },
                "search", """{"domain":[],"offset":10,"limit":3,"order":"id desc"}""", "[1]"),
            Case({ OdxProxyV2.searchCount("res.partner", listOf(listOf("active", "=", true))) },
                "search_count", """{"domain":[["active","=",true]]}""", "1"),
            Case({ OdxProxyV2.read("res.partner", JsonObject::class.java, listOf(3, 4), fields = listOf("name")) },
                "read", """{"ids":[3,4],"fields":["name"]}""", "[]"),
            Case({ OdxProxyV2.fieldsGet("res.partner", JsonObject::class.java, allfields = listOf("name"), attributes = listOf("type")) },
                "fields_get", """{"allfields":["name"],"attributes":["type"]}""", "{}"),
            Case({ OdxProxyV2.create("res.partner", listOf(mapOf("name" to "A"), mapOf("name" to "B"))) },
                "create", """{"vals_list":[{"name":"A"},{"name":"B"}]}""", "[1,2]"),
            Case({ OdxProxyV2.createOne("res.partner", mapOf("name" to "A")) },
                "create", """{"vals_list":[{"name":"A"}]}""", "[1]"),
            Case({ OdxProxyV2.write("res.partner", listOf(7), mapOf("comment" to "x")) },
                "write", """{"ids":[7],"vals":{"comment":"x"}}""", "true"),
            Case({ OdxProxyV2.remove("res.partner", listOf(7)) },
                "unlink", """{"ids":[7]}""", "true"),
            Case({ OdxProxyV2.callMethod("account.move", "action_post", Boolean::class.javaObjectType, ids = listOf(9)) },
                "action_post", """{"ids":[9]}""", "true"),
            Case({ OdxProxyV2.callMethod("res.partner", "name_search", JsonArray::class.java, kwargs = mapOf("name" to "Acm", "limit" to 5)) },
                "name_search", """{"name":"Acm","limit":5}""", "[]"),
        )
        for (c in cases) {
            ok(c.result)
            c.call().get()
            val (_, body) = sent()
            assertEquals(c.method, (body["method"] as kotlinx.serialization.json.JsonPrimitive).content, c.method)
            val expected = Json.parseToJsonElement(c.kwargs).jsonObject + ("context" to json(defaultContextJson))
            assertEquals(JsonObject(expected), body["kwargs"], c.method)
        }
    }

    @Test
    fun `null arguments are omitted and api-model methods never get ids`() {
        ok("[]")
        OdxProxyV2.searchRead("res.partner", JsonObject::class.java).get()
        assertEquals(JsonObject(mapOf("context" to json(defaultContextJson))), sent().second["kwargs"])

        // (call, a mock result of the type that call decodes)
        val calls = listOf<Pair<() -> CompletableFuture<*>, String>>(
            { OdxProxyV2.search("res.partner", emptyList()) } to "[1]",
            { OdxProxyV2.searchCount("res.partner", emptyList()) } to "1",
            { OdxProxyV2.fieldsGet("res.partner", JsonObject::class.java) } to "{}",
            { OdxProxyV2.create("res.partner", listOf(mapOf("name" to "A"))) } to "[1]",
            { OdxProxyV2.callMethod("res.partner", "name_search", JsonArray::class.java, kwargs = mapOf("name" to "x")) } to "[]",
        )
        for ((call, result) in calls) {
            ok(result)
            call().get()
            assertFalse("ids" in sent().second["kwargs"]!!.jsonObject)
        }
    }

    @Test
    fun `call context overrides the default context`() {
        ok("[]")
        OdxProxyV2.search("res.partner", emptyList(), context = mapOf("tz" to "UTC", "allowed_company_ids" to listOf(1))).get()
        assertEquals(
            json("""{"lang":"en_US","tz":"UTC","allowed_company_ids":[1]}"""),
            sent().second["kwargs"]!!.jsonObject["context"]
        )
    }

    @Test
    fun `buildKwargs omits context when there is none`() {
        val kwargs = OdxProxyV2.buildKwargs(mapOf("ids" to listOf(1), "limit" to null), null, null)
        assertEquals(json("""{"ids":[1]}"""), kwargs)
    }

    @Test
    fun `createOne returns the first id and create returns the list`() {
        ok("[42]")
        assertEquals(42, OdxProxyV2.createOne("res.partner", mapOf("name" to "A")).get().result)
        ok("[42]")
        assertEquals(listOf(42), OdxProxyV2.create("res.partner", listOf(mapOf("name" to "A"))).get().result)
    }

    @Test
    fun `version posts id and url to v2 version and isSupported caches`() {
        ok("""{"version_info":[20,0,0,"final",0,"e"],"version":"20.0+e"}""")
        val info = OdxProxyV2.version(id = "v-1").get().result!!
        assertEquals(20, info.major)
        val (path, body) = sent()
        assertEquals("/v2/odoo/version", path)
        assertEquals(json("""{"id":"v-1","url":"https://erp.example.com"}"""), body)

        ok("""{"version_info":[19,0,0,"final",0,""],"version":"19.0"}""")
        assertTrue(OdxProxyV2.isSupported("https://nineteen.test").get())
        assertTrue(OdxProxyV2.isSupported("https://nineteen.test").get())
        server.takeRequest()
        assertEquals(2, server.requestCount) // the second isSupported call was cached

        err(200, -32006)
        assertFalse(OdxProxyV2.isSupported("https://seventeen.test").get())
    }

    @Test
    fun `isSupported surfaces proxy errors without caching`() {
        err(401, -32000)
        val e = assertThrows<ExecutionException> { OdxProxyV2.isSupported("https://flaky.test").get() }
        assertEquals(-32000, (e.cause as OdxServerErrorException).code)
        ok("""{"version_info":[20],"version":"20.0"}""")
        assertTrue(OdxProxyV2.isSupported("https://flaky.test").get())
    }

    @Test
    fun `Odoo errors expose odooStatus, odooErrorName and isRetryable`() {
        for (status in listOf(401, 403, 404, 409, 422, 500)) {
            err(200, status, """{"name":"odoo.exceptions.ValidationError","message":"boom"}""")
            val e = failure(OdxProxyV2.search("res.partner", emptyList()))
            assertEquals(status, e.code)
            assertEquals(200, e.httpStatus)
            assertEquals(status, e.odooStatus)
            assertEquals("odoo.exceptions.ValidationError", e.odooErrorName)
            assertEquals(status == 409, e.isRetryable)
        }
    }

    @Test
    fun `v2 proxy codes and the code-0 distinction`() {
        err(200, -32006)
        assertTrue(failure(OdxProxyV2.search("res.partner", emptyList())).isJson2Unavailable)

        err(400, -32007)
        val invalid = failure(OdxProxyV2.search("..", emptyList()))
        assertTrue(invalid.isInvalidRequest)
        assertEquals(400, invalid.httpStatus)
        assertNull(invalid.odooStatus)

        err(403, 0)
        assertTrue(failure(OdxProxyV2.search("res.partner", emptyList())).isLicenseError)

        // Odoo 19+ sends code 0 for every /jsonrpc error, on HTTP 200: not a license error.
        err(200, 0)
        assertFalse(failure(OdxProxyV2.search("res.partner", emptyList())).isLicenseError)

        err(502, -32004)
        assertTrue(failure(OdxProxyV2.search("res.partner", emptyList())).isRetryable)
    }

    @Test
    fun `a non-2xx 422 without an envelope keeps the HTTP status and is not an Odoo error`() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("Failed to deserialize the JSON body"))
        val e = failure(OdxProxyV2.search("res.partner", emptyList()))
        assertEquals(422, e.httpStatus)
        assertNull(e.odooStatus)
    }

    private fun resetSingleton() {
        try {
            val field = OdxProxyClient::class.java.getDeclaredField("instanceRef")
            field.isAccessible = true
            (field.get(null) as AtomicReference<*>).set(null)
        } catch (e: Exception) {}
    }
}
