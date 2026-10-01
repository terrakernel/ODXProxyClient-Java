package io.odxproxy

import io.odxproxy.client.OdxProxyClient
import io.odxproxy.client.OdxProxyClientInfo
import io.odxproxy.exception.OdxServerErrorException
import io.odxproxy.model.OdxInstanceInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live v2 tests against ODXProxy 0.9.0+ in front of Odoo 19+.
 *
 * Unlike [OdxProxyLiveTest] (activated by the presence of odx-test.properties), these
 * run only when the ODX_V2_* environment variables are set, so a plain
 * `./gradlew build` never reaches a real Odoo through them:
 *
 *     ODX_V2_GATEWAY_URL, ODX_V2_API_KEY, ODX_V2_ODOO_URL, ODX_V2_ODOO_DB, ODX_V2_ODOO_API_KEY
 *
 * The create/write/remove lifecycle additionally needs ODX_V2_ALLOW_WRITES=1.
 *     ./gradlew test --tests "io.odxproxy.OdxProxyV2LiveTest"
 */
class OdxProxyV2LiveTest {
    private val env = System.getenv()

    @BeforeEach
    fun setUp() {
        val keys = listOf("ODX_V2_GATEWAY_URL", "ODX_V2_API_KEY", "ODX_V2_ODOO_URL", "ODX_V2_ODOO_DB", "ODX_V2_ODOO_API_KEY")
        Assumptions.assumeTrue(keys.all { !env[it].isNullOrBlank() }, "Skipping v2 live tests: set $keys")
        resetSingleton()
        // userId is required by OdxInstanceInfo but never sent by v2.
        val instance = OdxInstanceInfo(env["ODX_V2_ODOO_URL"]!!, 0, env["ODX_V2_ODOO_DB"]!!, env["ODX_V2_ODOO_API_KEY"]!!)
        OdxProxy.init(OdxProxyClientInfo(instance, env["ODX_V2_API_KEY"]!!, env["ODX_V2_GATEWAY_URL"]!!, mapOf("lang" to "en_US")))
    }

    private fun <T> odooStatusOf(future: CompletableFuture<T>): Int? =
        (assertThrows<ExecutionException> { future.get() }.cause as OdxServerErrorException).odooStatus

    @Test
    fun `version and isSupported`() {
        assertTrue((OdxProxyV2.version().get().result!!.major ?: 0) >= 19)
        assertTrue(OdxProxyV2.isSupported().get())
    }

    @Test
    fun `reads agree with each other`() {
        val rows = OdxProxyV2.searchRead("res.partner", JsonObject::class.java, domain = emptyList(),
            fields = listOf("name"), limit = 3, order = "id asc").get().result!!
        assertTrue(rows.isNotEmpty())
        val ids = rows.map { it["id"]!!.jsonPrimitive.int }
        assertEquals(ids, OdxProxyV2.search("res.partner", emptyList(), limit = 3, order = "id asc").get().result)
        assertEquals(ids.size, OdxProxyV2.searchCount("res.partner", listOf(listOf("id", "in", ids))).get().result)
        val read = OdxProxyV2.read("res.partner", JsonObject::class.java, ids.take(1), fields = listOf("name")).get().result!!
        assertEquals(ids.first(), read.first()["id"]!!.jsonPrimitive.int)
        val fields = OdxProxyV2.fieldsGet("res.partner", JsonObject::class.java, allfields = listOf("name"),
            attributes = listOf("type")).get().result!!
        assertEquals("char", fields["name"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val ctx = OdxProxyV2.callMethod("res.users", "context_get", JsonObject::class.java).get().result!!
        assertTrue("lang" in ctx)
    }

    @Test
    fun `Odoo errors carry Odoo's status`() {
        assertEquals(422, odooStatusOf(OdxProxyV2.callMethod("res.partner", "search", JsonArray::class.java,
            ids = listOf(1), kwargs = mapOf("domain" to emptyList<Any>()))))
        assertEquals(422, odooStatusOf(OdxProxyV2.callMethod("res.partner", "search_count", Int::class.javaObjectType,
            kwargs = mapOf("filter" to emptyList<Any>()))))
        assertEquals(404, odooStatusOf(OdxProxyV2.searchCount("no.such.model", emptyList())))
        assertEquals(403, odooStatusOf(OdxProxyV2.callMethod("res.partner", "_compute_display_name",
            Boolean::class.javaObjectType, ids = listOf(1))))
        val invalid = assertThrows<ExecutionException> { OdxProxyV2.searchCount("..", emptyList()).get() }
        assertTrue((invalid.cause as OdxServerErrorException).isInvalidRequest)
    }

    @Test
    fun `create, write, read and remove lifecycle`() {
        Assumptions.assumeTrue(env["ODX_V2_ALLOW_WRITES"] == "1", "Set ODX_V2_ALLOW_WRITES=1 to create/delete records")
        val pid = OdxProxyV2.createOne("res.partner", mapOf("name" to "ODX v2 Java Test Partner")).get().result!!
        try {
            assertTrue(OdxProxyV2.write("res.partner", listOf(pid), mapOf("name" to "ODX v2 Java Test Partner (edited)")).get().result!!)
            val row = OdxProxyV2.read("res.partner", JsonObject::class.java, listOf(pid), fields = listOf("name")).get().result!!.single()
            assertEquals("ODX v2 Java Test Partner (edited)", row["name"]!!.jsonPrimitive.content)
            val many = OdxProxyV2.create("res.partner", listOf(mapOf("name" to "ODX v2 A"), mapOf("name" to "ODX v2 B"))).get().result!!
            assertEquals(2, many.size)
            assertTrue(OdxProxyV2.remove("res.partner", many).get().result!!)
        } finally {
            assertTrue(OdxProxyV2.remove("res.partner", listOf(pid)).get().result!!)
        }
        assertEquals(0, OdxProxyV2.searchCount("res.partner", listOf(listOf("id", "=", pid))).get().result)
    }

    private fun resetSingleton() {
        try {
            val field = OdxProxyClient::class.java.getDeclaredField("instanceRef")
            field.isAccessible = true
            (field.get(null) as AtomicReference<*>).set(null)
        } catch (e: Exception) {}
    }
}
