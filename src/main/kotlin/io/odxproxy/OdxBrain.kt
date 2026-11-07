package io.odxproxy

import com.github.f4b6a3.ulid.UlidCreator
import io.odxproxy.client.OdxProxyClient
import io.odxproxy.exception.OdxServerErrorException
import io.odxproxy.model.OdxBrainEdition
import io.odxproxy.model.OdxBrainFacts
import io.odxproxy.model.OdxBrainFactsSource
import io.odxproxy.model.OdxBrainModelResult
import io.odxproxy.model.OdxBrainSopResult
import io.odxproxy.model.OdxServerResponse
import io.odxproxy.model.OdxV2VersionInfo
import io.odxproxy.model.OdxVersionRequest
import io.odxproxy.model.toJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * ODXBrain: ODXProxy `/brain/v1/...` (ODXProxy 0.10.0+), an **optional** sidecar that
 * answers "which Odoo model holds X?" and "which SOP covers task Y?" for an LLM planning
 * Odoo calls.
 *
 * ```kotlin
 * if (OdxBrain.isAvailable().get()) {
 *     val facts = OdxBrain.facts().get()                     // odooVersion "19.0", edition ENTERPRISE
 *     val res = OdxBrain.resolveModel("which invoice is not paid yet", facts).get().result!!
 *     for (c in res.candidates) { ... }                      // every candidate, in order
 *     val sop = OdxBrain.resolveSop("record a vendor bill from a PO", facts,
 *         installedModules = listOf("account", "purchase")).get().result!!
 *     if (sop.isNoMatch) { ... }                             // stop and ask the user
 * }
 * ```
 *
 * ODXBrain never contacts Odoo and needs no Odoo credentials: these calls send only the
 * proxy `X-Api-Key` and never `odoo_instance` (SYSTEM_ARCHITECTURE.md §4.8–§4.10, §7.2).
 * They use the same `OdxProxy.init(...)` singleton as [OdxProxy] and [OdxProxyV2].
 *
 * Results are ODXBrain's response, unaltered (`result.raw`): every candidate in order,
 * unknown and conditional fields kept. There is deliberately no score threshold and no
 * "best match" accessor: the ordering is the contract, the score scale is not.
 *
 * Errors are [io.odxproxy.exception.OdxBrainException] subclasses. Catch
 * [io.odxproxy.exception.OdxBrainNotInstalledException] to hide the feature on a proxy
 * without ODXBrain; it is never worth retrying. `isRetryable` is `true` only for
 * `-32009` / `-32010`, and then for one retry.
 *
 * Every method returns a [CompletableFuture] and never blocks the caller.
 */
public object OdxBrain {
    private const val PATH_RESOLVE = "/brain/v1/resolve"
    private const val PATH_SOPS_RESOLVE = "/brain/v1/sops/resolve"
    private const val PATH_INFO = "/brain/v1/info"

    /** Never accepted from `extra`: `id` is the envelope's, and `odoo_instance` carries the Odoo API key. */
    private val RESERVED = listOf("id", "odoo_instance")

    private fun client(): OdxProxyClient = OdxProxyClient.getInstance()

    private fun generateId(providedId: String?): String = providedId ?: UlidCreator.getUlid().toString()

    /**
     * Builds a resolve body: [extra] first, then the named [fields] that are non-null,
     * then `query` and `id`. Unset facts are omitted, never sent as `null`: a missing fact
     * filters nothing, while an explicit `null` is ODXBrain's 400. Pure, so tests can
     * check the exact JSON without a network.
     *
     * @throws IllegalArgumentException when [extra] carries `id` or `odoo_instance`.
     */
    internal fun buildBody(
        id: String,
        query: String,
        fields: Map<String, Any?>,
        extra: Map<String, Any?>?
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        if (extra != null) {
            val reserved = RESERVED.filter { it in extra }
            require(reserved.isEmpty()) { "OdxBrain: extra may not carry ${reserved.joinToString()}" }
            for ((key, value) in extra) out[key] = toJsonElement(value)
        }
        for ((key, value) in fields) {
            if (value != null) out[key] = toJsonElement(value)
        }
        out["query"] = JsonPrimitive(query)
        out["id"] = JsonPrimitive(id)
        return JsonObject(out)
    }

    private fun factFields(facts: OdxBrainFacts?): Array<Pair<String, Any?>> = arrayOf(
        "odoo_version" to facts?.odooVersion,
        "edition" to facts?.edition?.wireValue
    )

    private fun post(path: String, query: String, fields: Map<String, Any?>, extra: Map<String, Any?>?, id: String?):
        CompletableFuture<OdxServerResponse<JsonObject>> {
        val body = try {
            buildBody(generateId(id), query, fields, extra)
        } catch (e: IllegalArgumentException) {
            return CompletableFuture<OdxServerResponse<JsonObject>>().apply { completeExceptionally(e) }
        }
        return client().brainRequest(path, body)
    }

    private fun <R> OdxServerResponse<JsonObject>.wrap(view: (JsonObject) -> R): OdxServerResponse<R> =
        OdxServerResponse(jsonrpc = jsonrpc, id = id, result = result?.let(view), error = error)

    /**
     * Which Odoo models hold the data a plain-language [query] is about:
     * `POST /brain/v1/resolve`. Follow up with `fieldsGet` on the chosen model.
     *
     * @param facts the instance's version and edition ([facts]); `null` fields are omitted. Never guess one.
     * @param k number of candidates (integer ≥ 1; ODXBrain's default is 3). ODXBrain validates it.
     * @param extra fields this SDK has no parameter for yet, forwarded unchanged. The named
     *   arguments win over a same-named key; `id` and `odoo_instance` are refused.
     * @param id request id echoed back; a ULID when `null`.
     */
    @JvmStatic
    @JvmOverloads
    public fun resolveModel(
        query: String,
        facts: OdxBrainFacts? = null,
        k: Int? = null,
        extra: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<OdxBrainModelResult>> {
        val fields = linkedMapOf(*factFields(facts), "k" to k)
        return post(PATH_RESOLVE, query, fields, extra, id).thenApply { it.wrap(::OdxBrainModelResult) }
    }

    /**
     * Which approved SOPs match a task description: `POST /brain/v1/sops/resolve`.
     * Before acting, check `isNoMatch` (stop and ask the user) and check the chosen
     * candidate's `title` against the task. The SOP body itself comes from the doc
     * manager, not this proxy.
     *
     * @param installedModules installed module names (`ir.module.module` with state `installed`).
     * @param company the key the SOPs were published under.
     * @param k number of candidates (integer ≥ 1, capped at 10 upstream).
     * @see resolveModel for [facts], [extra] and [id].
     */
    @JvmStatic
    @JvmOverloads
    public fun resolveSop(
        query: String,
        facts: OdxBrainFacts? = null,
        installedModules: List<String>? = null,
        company: String? = null,
        k: Int? = null,
        extra: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<OdxBrainSopResult>> {
        val fields = linkedMapOf(
            *factFields(facts), "installed_modules" to installedModules, "company" to company, "k" to k
        )
        return post(PATH_SOPS_RESOLVE, query, fields, extra, id).thenApply { it.wrap(::OdxBrainSopResult) }
    }

    /**
     * ODXBrain pre-flight, `GET /brain/v1/info`: the embedding model, the corpus and SOP
     * set loaded, and the Odoo range covered, as ODXBrain's raw JSON. Check `model`, `dim`
     * and the corpus version against anything you persisted. The envelope `id` is `null`.
     */
    @JvmStatic
    public fun info(): CompletableFuture<OdxServerResponse<JsonObject>> = client().brainRequest(PATH_INFO, null)

    /**
     * Whether this proxy is configured to forward to ODXBrain: `features.odxbrain` from
     * `GET /_/about` (no API key). A missing `features` (a proxy older than 0.10.0) reads
     * as `false`. `true` means configured, not that ODXBrain is up right now.
     */
    @JvmStatic
    public fun isAvailable(): CompletableFuture<Boolean> = client().getAbout().thenApply { res ->
        val features = res.result?.get("features") as? JsonObject
        (features?.get("odxbrain") as? JsonPrimitive)?.booleanOrNull == true
    }

    // --- Facts -------------------------------------------------------------------

    private val factsCache = ConcurrentHashMap<String, OdxBrainFacts>()

    /**
     * Facts from a `/v2/odoo/version` result: `odooVersion` is the first two
     * `version_info` elements joined with `.`, and the edition comes from `version`, which
     * is not cut at the `+` (`"20.0+e-20260925"` is real). `version_info[5]` is never used.
     */
    @JvmStatic
    public fun factsFromV2(info: OdxV2VersionInfo): OdxBrainFacts {
        if (info.versionInfo.size < 2) {
            throw OdxServerErrorException(-1, "OdxBrain: v2 version result lacks version_info", info.toString(), 200)
        }
        val serie = info.versionInfo.take(2).joinToString(".") { (it as? JsonPrimitive)?.content ?: it.toString() }
        return OdxBrainFacts(serie, OdxBrainEdition.fromOdooVersion(info.version))
    }

    /** Facts from a v1 `/api/odoo/version` result: `server_serie`, and the edition from `server_version`. */
    @JvmStatic
    public fun factsFromV1(result: JsonObject): OdxBrainFacts {
        val serie = (result["server_serie"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val version = (result["server_version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (serie == null || version == null) {
            throw OdxServerErrorException(-1, "OdxBrain: v1 version result lacks server_serie/server_version", result.toString(), 200)
        }
        return OdxBrainFacts(serie, OdxBrainEdition.fromOdooVersion(version))
    }

    /**
     * `odooVersion` and `edition` of the configured Odoo instance, to pass to
     * [resolveModel] / [resolveSop]. Cached per instance URL for the life of the process;
     * errors complete the future exceptionally and are not cached.
     *
     * @param source which version endpoint to ask. [OdxBrainFactsSource.AUTO] tries v2 and
     *   falls back to v1 when the instance has no JSON-2 (Odoo 18 or older).
     * @param refresh ignore the cache and ask again.
     */
    @JvmStatic
    @JvmOverloads
    public fun facts(
        source: OdxBrainFactsSource = OdxBrainFactsSource.AUTO,
        refresh: Boolean = false
    ): CompletableFuture<OdxBrainFacts> {
        val url = client().odooInstance.url
        if (!refresh) factsCache[url]?.let { return CompletableFuture.completedFuture(it) }

        val v1 = { client().postV1Version(OdxVersionRequest(generateId(null), url)).thenApply { factsFromV1(it.result!!) } }
        val v2 = { client().postV2Version(OdxVersionRequest(generateId(null), url)).thenApply { factsFromV2(it.result!!) } }

        val future: CompletableFuture<OdxBrainFacts> = when (source) {
            OdxBrainFactsSource.V1 -> v1()
            OdxBrainFactsSource.V2 -> v2()
            OdxBrainFactsSource.AUTO -> v2().handle { facts, err ->
                val cause = (err as? CompletionException)?.cause ?: err
                when {
                    cause == null -> CompletableFuture.completedFuture(facts)
                    cause is OdxServerErrorException && cause.isJson2Unavailable -> v1()
                    else -> CompletableFuture<OdxBrainFacts>().apply { completeExceptionally(cause) }
                }
            }.thenCompose { it }
        }
        return future.thenApply { facts -> facts.also { factsCache[url] = it } }
    }
}
