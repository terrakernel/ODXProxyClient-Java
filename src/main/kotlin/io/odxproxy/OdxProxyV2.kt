package io.odxproxy

import com.github.f4b6a3.ulid.UlidCreator
import io.odxproxy.client.OdxProxyClient
import io.odxproxy.exception.OdxServerErrorException
import io.odxproxy.model.OdxServerResponse
import io.odxproxy.model.OdxV2Instance
import io.odxproxy.model.OdxV2Request
import io.odxproxy.model.OdxV2VersionInfo
import io.odxproxy.model.OdxVersionRequest
import io.odxproxy.model.toJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * The v2 data API: ODXProxy `/v2/odoo/execute` (ODXProxy 0.9.0+), which reaches Odoo
 * over its JSON-2 API instead of `/jsonrpc`. Needs **Odoo 19+**. Odoo 22 removes
 * `/jsonrpc`, so from then on v2 is the only way in.
 *
 * Uses the same singleton as [OdxProxy] (`OdxProxy.init(...)`), so it is still one
 * Odoo instance per process. The instance's `userId` is not sent: JSON-2 derives
 * the user from the API key, which must be an Odoo **API key**, not a password.
 *
 * Differences from [OdxProxy] (v1):
 * - **Named arguments only.** Each method sends its arguments under Odoo's Python
 *   parameter names (`domain`, `fields`, `vals_list`, `ids`, ...). There are no
 *   positional `params` and no keyword request. `domain` is the domain list itself,
 *   e.g. `listOf(listOf("is_company", "=", true))`, without v1's extra wrapping list.
 * - Arguments left `null` are omitted, so Odoo's own defaults apply.
 * - [create] always returns a list of ids; use [createOne] for a single id.
 * - `context` is merged over `OdxProxyClientInfo.defaultContext` (call keys win).
 * - Errors are [OdxServerErrorException] as in v1. For Odoo-side errors,
 *   `odooStatus` holds Odoo's HTTP status (e.g. 422). `isJson2Unavailable` (-32006)
 *   and `isInvalidRequest` (-32007) cover the v2-specific proxy errors.
 *
 * Every method returns a [CompletableFuture] and never blocks the caller.
 */
public object OdxProxyV2 {
    private fun client(): OdxProxyClient = OdxProxyClient.getInstance()

    private fun generateId(providedId: String?): String = providedId ?: UlidCreator.getUlid().toString()

    /**
     * Builds the wire `kwargs`. Drops `null` values, so Odoo's defaults apply. Merges
     * [defaultContext] < a `"context"` map inside [kwargs] < [context] (later wins),
     * and omits `context` when the result is empty. Pure, so tests can check the
     * exact JSON without a network.
     */
    internal fun buildKwargs(
        kwargs: Map<String, Any?>,
        defaultContext: Map<String, Any?>?,
        context: Map<String, Any?>?
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in kwargs) {
            if (value != null && key != "context") out[key] = toJsonElement(value)
        }
        val merged = LinkedHashMap<String, Any?>()
        defaultContext?.let { merged.putAll(it) }
        (kwargs["context"] as? Map<*, *>)?.forEach { (k, v) -> merged[k.toString()] = v }
        context?.let { merged.putAll(it) }
        if (merged.isNotEmpty()) out["context"] = toJsonElement(merged)
        return JsonObject(out)
    }

    internal fun buildRequest(
        model: String,
        method: String,
        kwargs: Map<String, Any?>,
        context: Map<String, Any?>?,
        id: String?,
        client: OdxProxyClient = client()
    ): OdxV2Request {
        val instance = client.odooInstance
        return OdxV2Request(
            id = generateId(id),
            modelId = model,
            method = method,
            kwargs = buildKwargs(kwargs, client.defaultContext, context),
            odooInstance = OdxV2Instance(url = instance.url, db = instance.db, apiKey = instance.apiKey)
        )
    }

    // --- Data API --------------------------------------------------------------

    /** Ids of the records of [model] matching [domain]. */
    @JvmStatic
    @JvmOverloads
    public fun search(
        model: String,
        domain: List<Any?>,
        offset: Int? = null,
        limit: Int? = null,
        order: String? = null,
        context: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<List<Int>>> {
        val kwargs = mapOf("domain" to domain, "offset" to offset, "limit" to limit, "order" to order)
        return client().postV2RequestList(buildRequest(model, "search", kwargs, context, id), Int::class.javaObjectType)
    }

    /** Records of [model] matching [domain] (all records when `null`), decoded into [resultType]. */
    @JvmStatic
    @JvmOverloads
    public fun <T> searchRead(
        model: String,
        resultType: Class<T>,
        domain: List<Any?>? = null,
        fields: List<String>? = null,
        offset: Int? = null,
        limit: Int? = null,
        order: String? = null,
        context: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<List<T>>> {
        val kwargs = mapOf(
            "domain" to domain, "fields" to fields, "offset" to offset, "limit" to limit, "order" to order
        )
        return client().postV2RequestList(buildRequest(model, "search_read", kwargs, context, id), resultType)
    }

    /** Number of records of [model] matching [domain]. */
    @JvmStatic
    @JvmOverloads
    public fun searchCount(
        model: String,
        domain: List<Any?>,
        limit: Int? = null,
        context: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<Int>> {
        val kwargs = mapOf("domain" to domain, "limit" to limit)
        return client().postV2Request(buildRequest(model, "search_count", kwargs, context, id), Int::class.javaObjectType)
    }

    /** The records [ids] of [model], decoded into [resultType]. */
    @JvmStatic
    @JvmOverloads
    public fun <T> read(
        model: String,
        resultType: Class<T>,
        ids: List<Int>,
        fields: List<String>? = null,
        load: String? = null,
        context: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<List<T>>> {
        val kwargs = mapOf("ids" to ids, "fields" to fields, "load" to load)
        return client().postV2RequestList(buildRequest(model, "read", kwargs, context, id), resultType)
    }

    /** Field descriptions of [model], keyed by field name, decoded into [resultType] (e.g. `JsonObject`). */
    @JvmStatic
    @JvmOverloads
    public fun <T> fieldsGet(
        model: String,
        resultType: Class<T>,
        allfields: List<String>? = null,
        attributes: List<String>? = null,
        context: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<T>> {
        val kwargs = mapOf("allfields" to allfields, "attributes" to attributes)
        return client().postV2Request(buildRequest(model, "fields_get", kwargs, context, id), resultType)
    }

    /**
     * Creates one record per element of [values] (maps keyed by Odoo field name).
     * Always sends `vals_list` and **always returns a list of ids**; see [createOne].
     */
    @JvmStatic
    @JvmOverloads
    public fun create(
        model: String,
        values: List<Map<String, Any?>>,
        context: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<List<Int>>> {
        val kwargs = mapOf("vals_list" to values)
        return client().postV2RequestList(buildRequest(model, "create", kwargs, context, id), Int::class.javaObjectType)
    }

    /** Creates a single record and returns its id. */
    @JvmStatic
    @JvmOverloads
    public fun createOne(
        model: String,
        values: Map<String, Any?>,
        context: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<Int>> {
        return create(model, listOf(values), context, id).thenApply { res ->
            OdxServerResponse(jsonrpc = res.jsonrpc, id = res.id, result = res.result?.firstOrNull(), error = res.error)
        }
    }

    /** Writes [values] to the records [ids]. Returns `true`. */
    @JvmStatic
    @JvmOverloads
    public fun write(
        model: String,
        ids: List<Int>,
        values: Map<String, Any?>,
        context: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<Boolean>> {
        val kwargs = mapOf("ids" to ids, "vals" to values)
        return client().postV2Request(buildRequest(model, "write", kwargs, context, id), Boolean::class.javaObjectType)
    }

    /** Deletes (unlinks) the records [ids]. Returns `true`. */
    @JvmStatic
    @JvmOverloads
    public fun remove(
        model: String,
        ids: List<Int>,
        context: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<Boolean>> {
        val kwargs = mapOf("ids" to ids)
        return client().postV2Request(buildRequest(model, "unlink", kwargs, context, id), Boolean::class.javaObjectType)
    }

    /**
     * Calls any public method of [model], decoding its result into [resultType].
     *
     * [ids] is for record methods only; Odoo rejects it on `@api.model` methods.
     * Every other argument goes in [kwargs] under its Python parameter name. There
     * are no positional arguments in v2:
     * ```
     * OdxProxyV2.callMethod("account.move", "action_post", Boolean::class.javaObjectType, listOf(7))
     * OdxProxyV2.callMethod("res.partner", "name_search", JsonArray::class.java, null, mapOf("name" to "Acm", "limit" to 5))
     * ```
     */
    @JvmStatic
    @JvmOverloads
    public fun <T> callMethod(
        model: String,
        method: String,
        resultType: Class<T>,
        ids: List<Int>? = null,
        kwargs: Map<String, Any?>? = null,
        context: Map<String, Any?>? = null,
        id: String? = null
    ): CompletableFuture<OdxServerResponse<T>> {
        val merged = LinkedHashMap<String, Any?>(kwargs ?: emptyMap())
        merged["ids"] = ids
        return client().postV2Request(buildRequest(model, method, merged, context, id), resultType)
    }

    // --- Version -----------------------------------------------------------------

    /**
     * Odoo's version over JSON-2 (`POST /v2/odoo/version`). Defaults to the configured
     * instance URL. Fails with `isJson2Unavailable` when the server has no JSON-2.
     */
    @JvmStatic
    @JvmOverloads
    public fun version(url: String? = null, id: String? = null): CompletableFuture<OdxServerResponse<OdxV2VersionInfo>> {
        val target = url ?: client().odooInstance.url
        return client().postV2Version(OdxVersionRequest(id = generateId(id), url = target))
    }

    private val supportCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Whether the Odoo at [url] (default: the configured instance) can be reached
     * through v2, i.e. runs Odoo 19+. Cached per URL for the life of the process.
     * Network and proxy errors complete the future exceptionally and are not cached.
     */
    @JvmStatic
    @JvmOverloads
    public fun isSupported(url: String? = null): CompletableFuture<Boolean> {
        val target = url ?: client().odooInstance.url
        supportCache[target]?.let { return CompletableFuture.completedFuture(it) }
        return version(target).handle { res, err ->
            val cause = (err as? CompletionException)?.cause ?: err
            val supported = when {
                cause == null -> (res.result?.major ?: 0) >= 19
                cause is OdxServerErrorException && cause.isJson2Unavailable -> false
                else -> throw CompletionException(cause)
            }
            supportCache[target] = supported
            supported
        }
    }
}
