package io.odxproxy

import io.odxproxy.client.OdxProxyClient
import io.odxproxy.client.OdxProxyClientInfo
import io.odxproxy.exception.OdxBrainConfigException
import io.odxproxy.exception.OdxBrainException
import io.odxproxy.exception.OdxBrainNotInstalledException
import io.odxproxy.exception.OdxBrainRequestException
import io.odxproxy.exception.OdxBrainTimeoutException
import io.odxproxy.exception.OdxBrainUnavailableException
import io.odxproxy.exception.OdxServerErrorException
import io.odxproxy.model.OdxBrainEdition
import io.odxproxy.model.OdxBrainFacts
import io.odxproxy.model.OdxBrainFactsSource
import io.odxproxy.model.OdxInstanceInfo
import io.odxproxy.model.OdxV2VersionInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline tests for [OdxBrain] against the ODXProxy 0.10.0 contract
 * (SYSTEM_ARCHITECTURE.md §4.4, §4.8–§4.10, §6, §7.2). The `DoD n` tags refer to the
 * nine-item definition-of-done list at the end of §7.2.
 */
class OdxBrainMockTest {
    private lateinit var server: MockWebServer

    private val modelResult = """{"candidates":[{"model":"account.move","score":0.513},
        {"model":"account.payment","score":0.492,"provisional":true},{"model":"account.payment.term","score":0.462}],
        "model":"bge-m3","dim":1024,"corpus_version":"2026.09.4","usage":{"prompt_tokens":10},
        "excluded":[{"model":"account.invoice","reason":"removed","successor":"account.move"}],
        "version_unaudited":"19.0","future_field":{"x":1}}"""

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        resetSingleton()
        val info = OdxInstanceInfo("https://erp.example.com", 2, "prod", "odoo-key")
        OdxProxy.init(OdxProxyClientInfo(info, "proxy-key", "http://localhost:${server.port}"))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(status: Int, body: String, contentType: String = "application/json") {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", contentType).setBody(body))
    }

    private fun ok(result: String, id: String = "r1") = respond(200, """{"jsonrpc":"2.0","id":"$id","result":$result}""")

    private fun err(status: Int, code: Int, data: String? = null, id: String? = "r1") {
        val dataPart = if (data != null) ""","data":$data""" else ""
        val idPart = if (id == null) "null" else "\"$id\""
        respond(status, """{"jsonrpc":"2.0","id":$idPart,"error":{"code":$code,"message":"boom"$dataPart}}""")
    }

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun bodyOf(req: RecordedRequest): JsonObject = Json.parseToJsonElement(req.body.readUtf8()).jsonObject

    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun <T> failure(future: CompletableFuture<T>): OdxServerErrorException =
        assertThrows<ExecutionException> { future.get(5, TimeUnit.SECONDS) }.cause as OdxServerErrorException

    // --- Requests ------------------------------------------------------------------

    @Test
    fun `resolveModel posts id, query and set facts with the proxy key only`() {
        ok(modelResult)
        OdxBrain.resolveModel("which invoice is not paid yet", OdxBrainFacts("19.0", OdxBrainEdition.ENTERPRISE), id = "r1").get()
        val req = take()
        assertEquals("POST", req.method)
        assertEquals("/brain/v1/resolve", req.path)
        assertEquals("proxy-key", req.getHeader("X-Api-Key"))
        assertEquals(
            json("""{"odoo_version":"19.0","edition":"enterprise","query":"which invoice is not paid yet","id":"r1"}"""),
            bodyOf(req)
        )
    }

    @Test
    fun `DoD 6 - unset facts are omitted, never sent as null`() {
        ok(modelResult)
        OdxBrain.resolveModel("customer addresses", id = "r1").get()
        assertEquals(json("""{"query":"customer addresses","id":"r1"}"""), bodyOf(take()))

        ok(modelResult)
        OdxBrain.resolveModel("customer addresses", OdxBrainFacts(odooVersion = "20.0"), k = 5, id = "r1").get()
        assertEquals(json("""{"odoo_version":"20.0","k":5,"query":"customer addresses","id":"r1"}"""), bodyOf(take()))
    }

    @Test
    fun `resolveSop sends installed_modules, company and k, and never odoo_instance`() {
        ok("""{"candidates":[],"model":"bge-m3","dim":1024,"sops":{"generation":1,"root":"ab"}}""")
        OdxBrain.resolveSop(
            "record a vendor bill", OdxBrainFacts("19.0", OdxBrainEdition.COMMUNITY),
            installedModules = listOf("account", "purchase"), company = "acme", k = 4, id = "r1"
        ).get()
        val req = take()
        assertEquals("/brain/v1/sops/resolve", req.path)
        val body = bodyOf(req)
        assertEquals(
            json("""{"odoo_version":"19.0","edition":"community","installed_modules":["account","purchase"],
                "company":"acme","k":4,"query":"record a vendor bill","id":"r1"}"""),
            body
        )
        assertFalse("odoo_instance" in body)
    }

    @Test
    fun `DoD 7 - unknown request fields pass through extra, named arguments win, reserved keys refused`() {
        ok(modelResult)
        OdxBrain.resolveModel(
            "q", OdxBrainFacts("19.0"),
            extra = mapOf("model" to "bge-m3", "odoo_version" to "17.0", "lang" to null), id = "r1"
        ).get()
        assertEquals(json("""{"model":"bge-m3","odoo_version":"19.0","lang":null,"query":"q","id":"r1"}"""), bodyOf(take()))

        for (key in listOf("id", "odoo_instance")) {
            val e = assertThrows<ExecutionException> { OdxBrain.resolveModel("q", extra = mapOf(key to "x")).get() }
            assertIs<IllegalArgumentException>(e.cause)
        }
        assertEquals(1, server.requestCount)
    }

    // --- Results -------------------------------------------------------------------

    @Test
    fun `DoD 7 and 8 - result is unaltered, every candidate in order, conditional fields surfaced`() {
        ok(modelResult)
        val res = OdxBrain.resolveModel("q", id = "r1").get()
        assertEquals("r1", res.id)
        val result = res.result!!
        assertEquals(json(modelResult), result.raw)
        assertEquals(json("""{"x":1}"""), result.raw["future_field"])
        assertEquals(listOf("account.move", "account.payment", "account.payment.term"), result.candidates.map { it.model })
        assertEquals(listOf(0.513, 0.492, 0.462), result.candidates.map { it.score })
        assertEquals(listOf(false, true, false), result.candidates.map { it.isProvisional })
        assertEquals("bge-m3", result.model)
        assertEquals(1024, result.dim)
        assertEquals("2026.09.4", result.corpusVersion)
        assertEquals("19.0", result.versionUnaudited)
        assertNull(result.filtered)
        assertEquals("account.move", result.excluded.single().successor)
    }

    @Test
    fun `SOP result keeps sops root, match none and facts_missing`() {
        val sop = """{"candidates":[{"id":"vendor-bill-from-po","version":"181c84a3b7906648",
            "title":"Record a vendor bill","score":0.80}],"model":"bge-m3","dim":1024,
            "sops":{"generation":1,"root":"0123abcd"},"match":"none","facts_missing":["installed_modules"],
            "excluded":[{"id":"x","reason":"module_missing"}]}"""
        ok(sop)
        val result = OdxBrain.resolveSop("q", id = "r1").get().result!!
        assertEquals(json(sop), result.raw)
        assertTrue(result.isNoMatch)
        assertEquals("0123abcd", result.sops!!.root)
        assertEquals(1L, result.sops!!.generation)
        assertEquals(listOf("installed_modules"), result.factsMissing)
        assertEquals("181c84a3b7906648", result.candidates.single().version)
        assertEquals("x", result.excluded.single().id)
    }

    @Test
    fun `DoD 9 - info is a GET with no body and a null id parses`() {
        respond(200, """{"jsonrpc":"2.0","id":null,"result":{"model":"bge-m3","dim":1024}}""")
        val res = OdxBrain.info().get()
        assertNull(res.id)
        assertEquals(JsonPrimitive("bge-m3"), res.result!!["model"])
        val req = take()
        assertEquals("GET", req.method)
        assertEquals("/brain/v1/info", req.path)
        assertEquals(0L, req.bodySize)
        assertEquals("proxy-key", req.getHeader("X-Api-Key"))
    }

    // --- Availability --------------------------------------------------------------

    @Test
    fun `DoD 2 - isAvailable reads features odxbrain, and a missing features reads as false`() {
        respond(200, """{"jsonrpc":"2.0","id":"about","result":{"build":"b","version":"0.10.0","features":{"odxbrain":true}}}""")
        assertTrue(OdxBrain.isAvailable().get())
        val req = take()
        assertEquals("/_/about", req.path)
        assertNull(req.getHeader("X-Api-Key"))

        respond(200, """{"jsonrpc":"2.0","id":"about","result":{"build":"b","version":"0.10.0","features":{"odxbrain":false}}}""")
        assertFalse(OdxBrain.isAvailable().get())
        respond(200, """{"jsonrpc":"2.0","id":"about","result":{"build":"b","version":"0.9.0"}}""")
        assertFalse(OdxBrain.isAvailable().get())
        respond(200, """{"jsonrpc":"2.0","id":"about","result":{"features":{}}}""")
        assertFalse(OdxBrain.isAvailable().get())
    }

    // --- Errors --------------------------------------------------------------------

    @Test
    fun `DoD 1 - 501 -32008 and a bare 404 are both NotInstalled, never retryable`() {
        err(501, -32008, """{"type":"odxbrain_not_installed"}""")
        val e1 = failure(OdxBrain.resolveModel("q"))
        assertIs<OdxBrainNotInstalledException>(e1)
        assertEquals("odxbrain_not_installed", (e1 as OdxBrainException).type)
        assertFalse(e1.isRetryable)
        assertEquals(501, e1.httpStatus)

        respond(404, "Not Found", "text/plain")
        val e2 = failure(OdxBrain.resolveSop("q"))
        assertIs<OdxBrainNotInstalledException>(e2)
        assertEquals(-32008, e2.code)
        assertEquals(404, e2.httpStatus)
        assertFalse(e2.isRetryable)

        respond(404, "")
        assertIs<OdxBrainNotInstalledException>(failure(OdxBrain.info()))
        assertEquals(3, server.requestCount) // one request per call: no retries
    }

    @Test
    fun `unreachable and timeout are retryable once, -32011 is a config error and not auth`() {
        err(502, -32009)
        val unreachable = failure(OdxBrain.resolveModel("q"))
        assertIs<OdxBrainUnavailableException>(unreachable)
        assertTrue(unreachable.isRetryable)

        err(504, -32010)
        val timeout = failure(OdxBrain.resolveModel("q"))
        assertIs<OdxBrainTimeoutException>(timeout)
        assertTrue(timeout.isRetryable)

        // DoD 4
        err(502, -32011)
        val config = failure(OdxBrain.resolveModel("q"))
        assertIs<OdxBrainConfigException>(config)
        assertFalse(config.isRetryable)
        assertTrue(config.code != OdxServerErrorException.AUTH_FAILED)

        err(401, -32000, id = null)
        val auth = failure(OdxBrain.resolveModel("q"))
        assertFalse(auth is OdxBrainException)
        assertEquals(OdxServerErrorException.AUTH_FAILED, auth.code)
        assertEquals(401, auth.httpStatus)

        err(403, 0)
        val license = failure(OdxBrain.resolveModel("q"))
        assertFalse(license is OdxBrainException)
        assertTrue(license.isLicenseError)
    }

    @Test
    fun `DoD 3 - an HTTP 200 carrying error raises with ODXBrain's data, never an empty result`() {
        err(200, 400, """{"message":"query must not be empty","type":"invalid_request_error"}""")
        val bad = failure(OdxBrain.resolveModel(""))
        assertIs<OdxBrainRequestException>(bad)
        assertEquals("invalid_request_error", (bad as OdxBrainException).type)
        assertEquals(json("""{"message":"query must not be empty","type":"invalid_request_error"}"""), bad.data)
        assertNull(bad.odooStatus) // 400 on /brain is ODXBrain's, not Odoo's
        assertFalse(bad.isRetryable)

        err(200, 503, """{"message":"no SOPs loaded","type":"sops_unavailable"}""")
        val noSops = failure(OdxBrain.resolveSop("q"))
        assertIs<OdxBrainUnavailableException>(noSops)
        assertEquals("sops_unavailable", (noSops as OdxBrainException).type)
        assertFalse(noSops.isRetryable)

        err(200, 500, """{"message":"x","type":"internal_error"}""")
        val internal = failure(OdxBrain.resolveModel("q"))
        assertEquals(OdxBrainException::class, internal::class)
        assertEquals(500, internal.code)
        assertNull(internal.odooStatus)
    }

    @Test
    fun `DoD 5 - plain-text 400 and 422 are kept as text, not parsed as JSON-RPC`() {
        respond(400, "Failed to parse the request body as JSON: expected value at line 1", "text/plain")
        val e400 = failure(OdxBrain.resolveModel("q"))
        assertFalse(e400 is OdxBrainNotInstalledException)
        assertEquals(400, e400.code)
        assertEquals(400, e400.httpStatus)
        assertTrue(e400.message!!.startsWith("Failed to parse the request body"))

        respond(422, "Failed to deserialize the JSON body into the target type: query: invalid type", "text/plain")
        val e422 = failure(OdxBrain.resolveModel("q"))
        assertEquals(422, e422.code)
        assertNull(e422.odooStatus)
        assertTrue(e422.message!!.contains("invalid type"))
    }

    // --- Facts ---------------------------------------------------------------------

    @Test
    fun `facts from v2 keep the full version string and ignore version_info 5`() {
        fun v2(vi: String, v: String) = OdxBrain.factsFromV2(OdxV2VersionInfo(Json.parseToJsonElement(vi).let { (it as kotlinx.serialization.json.JsonArray).toList() }, v))
        assertEquals(OdxBrainFacts("20.0", OdxBrainEdition.ENTERPRISE), v2("""[20,0,0,"final",0,"e"]""", "20.0+e-20260925"))
        assertEquals(OdxBrainFacts("19.0", OdxBrainEdition.COMMUNITY), v2("""[19,0,0,"final",0,"e"]""", "19.0"))
        assertEquals(OdxBrainFacts("20.0", OdxBrainEdition.ENTERPRISE), v2("""[20,0,0,"alpha",1,""]""", "20.0alpha1+e"))
        assertEquals(OdxBrainFacts("saas~18.3", OdxBrainEdition.COMMUNITY), v2("""["saas~18",3,0,"final",0,""]""", "saas~18.3"))
    }

    @Test
    fun `facts from v1 use server_serie and server_version`() {
        val r = json("""{"server_serie":"17.0","server_version":"17.0+e","server_version_info":[17,0,0,"final",0,""]}""").jsonObject
        assertEquals(OdxBrainFacts("17.0", OdxBrainEdition.ENTERPRISE), OdxBrain.factsFromV1(r))
    }

    @Test
    fun `facts AUTO uses v2, falls back to v1 on -32006, and caches per URL`() {
        err(200, -32006)
        ok("""{"server_serie":"18.0","server_version":"18.0+e-20250101"}""")
        assertEquals(OdxBrainFacts("18.0", OdxBrainEdition.ENTERPRISE), OdxBrain.facts(refresh = true).get())
        assertEquals("/v2/odoo/version", take().path)
        val v1 = take()
        assertEquals("/api/odoo/version", v1.path)
        assertEquals("https://erp.example.com", bodyOf(v1)["url"]!!.let { (it as JsonPrimitive).content })

        // Cached: no request.
        assertEquals(OdxBrainFacts("18.0", OdxBrainEdition.ENTERPRISE), OdxBrain.facts().get())
        assertEquals(2, server.requestCount)

        ok("""{"version_info":[20,0,0,"final",0,"e"],"version":"20.0+e"}""")
        assertEquals(OdxBrainFacts("20.0", OdxBrainEdition.ENTERPRISE), OdxBrain.facts(OdxBrainFactsSource.V2, refresh = true).get())

        // Other errors propagate and are not swallowed by the fallback.
        err(502, -32004)
        assertEquals(-32004, failure(OdxBrain.facts(refresh = true)).code)
    }

    private fun resetSingleton() {
        try {
            val field = OdxProxyClient::class.java.getDeclaredField("instanceRef")
            field.isAccessible = true
            (field.get(null) as AtomicReference<*>).set(null)
        } catch (e: Exception) {}
    }
}
