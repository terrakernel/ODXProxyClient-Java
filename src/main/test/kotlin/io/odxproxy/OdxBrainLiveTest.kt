package io.odxproxy

import io.odxproxy.client.OdxProxyClient
import io.odxproxy.client.OdxProxyClientInfo
import io.odxproxy.exception.OdxBrainException
import io.odxproxy.exception.OdxBrainNotInstalledException
import io.odxproxy.exception.OdxBrainRequestException
import io.odxproxy.exception.OdxBrainUnavailableException
import io.odxproxy.exception.OdxServerErrorException
import io.odxproxy.model.OdxBrainEdition
import io.odxproxy.model.OdxBrainFacts
import io.odxproxy.model.OdxBrainFactsSource
import io.odxproxy.model.OdxInstanceInfo
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live ODXBrain tests against ODXProxy 0.10.0+. Read-only: nothing is written to Odoo.
 * Run only when these environment variables are set (e.g. `source ~/odx-it/sdk/env.sh`):
 *
 *     ODX_PROXY_URL, ODX_PROXY_API_KEY, ODOO_URL, ODOO_DB, ODOO_API_KEY
 *     ODX_PROXY_NOBRAIN_URL  (optional: a proxy without ODXBrain)
 *     ODX_PROXY_NOSOPS_URL   (optional: a proxy whose ODXBrain has no SOPs)
 *
 *     ./gradlew test --tests "io.odxproxy.OdxBrainLiveTest"
 */
class OdxBrainLiveTest {
    private val env = System.getenv()

    private fun init(proxyUrl: String) {
        val keys = listOf("ODX_PROXY_URL", "ODX_PROXY_API_KEY", "ODOO_URL", "ODOO_DB", "ODOO_API_KEY")
        Assumptions.assumeTrue(keys.all { !env[it].isNullOrBlank() }, "Skipping ODXBrain live tests: set $keys")
        resetSingleton()
        val instance = OdxInstanceInfo(env["ODOO_URL"]!!, env["ODOO_USER_ID"]?.toIntOrNull() ?: 0, env["ODOO_DB"]!!, env["ODOO_API_KEY"]!!)
        OdxProxy.init(OdxProxyClientInfo(instance, env["ODX_PROXY_API_KEY"]!!, proxyUrl))
    }

    private fun full() = init(env["ODX_PROXY_URL"] ?: "")

    private fun <T> CompletableFuture<T>.await(): T = get(30, TimeUnit.SECONDS)

    private fun <T> failure(future: CompletableFuture<T>): OdxServerErrorException =
        assertThrows<ExecutionException> { future.await() }.cause as OdxServerErrorException

    @Test
    fun `available, info and facts`() {
        full()
        assertTrue(OdxBrain.isAvailable().await())
        val info = OdxBrain.info().await()
        assertNull(info.id)
        assertNotNull(info.result)
        val v2 = OdxBrain.facts(OdxBrainFactsSource.V2, refresh = true).await()
        val v1 = OdxBrain.facts(OdxBrainFactsSource.V1, refresh = true).await()
        assertEquals(v2, v1)
        assertEquals(OdxBrainEdition.ENTERPRISE, v2.edition)
        assertTrue(v2.odooVersion!!.matches(Regex("""\d+\.\d+""")), v2.odooVersion)
        println("facts=$v2 info=${info.result}")
    }

    @Test
    fun `resolveModel with facts and without`() {
        full()
        val facts = OdxBrain.facts().await()
        val res = OdxBrain.resolveModel("which invoice is not paid yet", facts, id = "java-live-1").await()
        assertEquals("java-live-1", res.id)
        val r = res.result!!
        assertEquals(3, r.candidates.size)
        assertTrue(r.candidates.all { it.model != null && it.score != null })
        assertNotNull(r.model)
        assertNotNull(r.dim)
        assertNotNull(r.corpusVersion)
        println("resolve(facts)=${r.raw}")

        val unfiltered = OdxBrain.resolveModel("which invoice is not paid yet", k = 5).await().result!!
        assertEquals(5, unfiltered.candidates.size)
        assertEquals(false, unfiltered.filtered)
    }

    @Test
    fun `resolveSop`() {
        full()
        val facts = OdxBrain.facts().await()
        val r = OdxBrain.resolveSop("record a vendor bill from a purchase order", facts,
            installedModules = listOf("account", "purchase")).await().result!!
        assertNotNull(r.sops?.root)
        println("sops=${r.raw}")
    }

    @Test
    fun `ODXBrain input errors are typed and keep data`() {
        full()
        val empty = failure(OdxBrain.resolveModel(""))
        assertIs<OdxBrainRequestException>(empty)
        assertNotNull((empty as OdxBrainException).type)
        assertEquals(200, empty.httpStatus)
        println("empty query -> ${empty.type}: ${empty.data}")

        val badK = failure(OdxBrain.resolveModel("q", k = 0))
        assertIs<OdxBrainRequestException>(badK)

        val nullFact = failure(OdxBrain.resolveModel("q", extra = mapOf("odoo_version" to null)))
        assertIs<OdxBrainRequestException>(nullFact)

        val tooOld = failure(OdxBrain.resolveModel("q", OdxBrainFacts("13.0")))
        assertIs<OdxBrainRequestException>(tooOld)
        println("13.0 -> ${(tooOld as OdxBrainException).type}")
    }

    @Test
    fun `proxy auth stays a plain auth error on brain routes`() {
        full()
        resetSingleton()
        val instance = OdxInstanceInfo(env["ODOO_URL"]!!, 0, env["ODOO_DB"]!!, env["ODOO_API_KEY"]!!)
        OdxProxy.init(OdxProxyClientInfo(instance, "wrong-key", env["ODX_PROXY_URL"]!!))
        val e = failure(OdxBrain.resolveModel("q"))
        assertFalse(e is OdxBrainException)
        assertEquals(OdxServerErrorException.AUTH_FAILED, e.code)
        assertEquals(401, e.httpStatus)
    }

    @Test
    fun `proxy without ODXBrain`() {
        val url = env["ODX_PROXY_NOBRAIN_URL"]
        Assumptions.assumeTrue(!url.isNullOrBlank(), "set ODX_PROXY_NOBRAIN_URL")
        init(url!!)
        assertFalse(OdxBrain.isAvailable().await())
        for (call in listOf(OdxBrain.resolveModel("q"), OdxBrain.resolveSop("q"), OdxBrain.info())) {
            val e = failure(call)
            assertIs<OdxBrainNotInstalledException>(e)
            assertEquals(-32008, e.code)
            assertEquals(501, e.httpStatus)
            assertFalse(e.isRetryable)
        }
    }

    @Test
    fun `ODXBrain without SOPs`() {
        val url = env["ODX_PROXY_NOSOPS_URL"]
        Assumptions.assumeTrue(!url.isNullOrBlank(), "set ODX_PROXY_NOSOPS_URL")
        init(url!!)
        val e = failure(OdxBrain.resolveSop("record a vendor bill"))
        assertIs<OdxBrainUnavailableException>(e)
        assertEquals("sops_unavailable", (e as OdxBrainException).type)
        assertEquals(503, e.code)
        assertFalse(e.isRetryable)
        // Models still resolve on that deployment.
        assertTrue(OdxBrain.resolveModel("customer addresses").await().result!!.candidates.isNotEmpty())
    }

    private fun resetSingleton() {
        try {
            val field = OdxProxyClient::class.java.getDeclaredField("instanceRef")
            field.isAccessible = true
            (field.get(null) as AtomicReference<*>).set(null)
        } catch (e: Exception) {}
    }
}
