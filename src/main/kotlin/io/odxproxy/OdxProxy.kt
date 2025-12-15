package io.odxproxy

import com.github.f4b6a3.ulid.UlidCreator
import io.odxproxy.client.OdxProxyClient
import io.odxproxy.client.OdxProxyClientInfo
import io.odxproxy.model.OdxClientKeywordRequest
import io.odxproxy.model.OdxClientRequest
import io.odxproxy.model.OdxServerResponse
import io.odxproxy.model.toJsonElement
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.CompletableFuture

public object OdxProxy {
    private fun generateId(providedId: String?): String {
        return providedId ?: UlidCreator.getUlid().toString()
    }
    private fun client(): OdxProxyClient = OdxProxyClient.getInstance()

    @JvmStatic
    public fun init(options: OdxProxyClientInfo): OdxProxyClient {
        return OdxProxyClient.init(options)
    }

    @JvmStatic
    public fun search(
        model: String,
        params: List<Any?>,
        keyword: OdxClientKeywordRequest,
        id: String?
    ): CompletableFuture<OdxServerResponse<List<Int>>> {
        val request = OdxClientRequest(
            id = generateId(id),
            action = "search",
            modelId = model,
            keyword = keyword.resetPagination(),
            params = toJsonElement(params),
            odooInstance = client().odooInstance
        )
        return client().postRequestList(request, Int::class.javaObjectType)
    }

    @JvmStatic
    public fun <T> searchRead(
        model: String,
        params: List<Any?>,
        keyword: OdxClientKeywordRequest,
        id: String?,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<List<T>>> {
        val request = OdxClientRequest(
            id = generateId(id),
            action = "search_read",
            modelId = model,
            keyword = keyword,
            params = toJsonElement(params),
            odooInstance = client().odooInstance
        )
        return client().postRequestList(request, resultType)
    }

    @JvmStatic
    public fun <T> read(
        model: String,
        ids: List<Int>,
        keyword: OdxClientKeywordRequest,
        id: String?,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<List<T>>> {
        val request = OdxClientRequest(
            id = generateId(id),
            action = "read",
            modelId = model,
            keyword = keyword.resetPagination(),
            params = toJsonElement(listOf(ids)),
            odooInstance = client().odooInstance
        )
        return client().postRequestList(request, resultType)
    }

    @JvmStatic
    public fun searchCount(
        model: String,
        params: List<Any?>,
        keyword: OdxClientKeywordRequest,
        id: String?
    ): CompletableFuture<OdxServerResponse<Int>> {
        val request = OdxClientRequest(
            id = generateId(id),
            action = "search_count",
            modelId = model,
            keyword = keyword,
            params = toJsonElement(params),
            odooInstance = client().odooInstance
        )
        return client().postRequest(request, Int::class.javaObjectType)
    }

    @JvmStatic
    public fun <T> create(
        model: String,
        params: List<Any?>,
        keyword: OdxClientKeywordRequest,
        id: String?,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<T>> {
        val request = OdxClientRequest(
            id = generateId(id),
            action = "create",
            modelId = model,
            keyword = keyword.resetPagination(),
            params = toJsonElement(params),
            odooInstance = client().odooInstance
        )
        return client().postRequest(request, resultType)
    }

    @JvmStatic
    public fun write(
        model: String,
        ids: List<Int>,
        values: Any,
        keyword: OdxClientKeywordRequest,
        id: String?
    ): CompletableFuture<OdxServerResponse<Boolean>> {
        val request = OdxClientRequest(
            id = generateId(id),
            action = "write",
            modelId = model,
            keyword = keyword.resetPagination(),
            params = toJsonElement(listOf(ids, values)),
            odooInstance = client().odooInstance
        )
        return client().postRequest(request, Boolean::class.javaObjectType)
    }

    @JvmStatic
    public fun remove(
        model: String,
        ids: List<Int>,
        keyword: OdxClientKeywordRequest,
        id: String?
    ): CompletableFuture<OdxServerResponse<Boolean>> {
        val request = OdxClientRequest(
            id = generateId(id),
            action = "unlink",
            modelId = model,
            keyword = keyword.resetPagination(),
            params = toJsonElement(listOf(ids)),
            odooInstance = client().odooInstance
        )
        return client().postRequest(request, Boolean::class.javaObjectType)
    }

    @JvmStatic
    public fun <T> fieldsGet(
        model: String,
        keyword: OdxClientKeywordRequest,
        id: String?,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<T>> {
        val request = OdxClientRequest(
            id = generateId(id),
            action = "fields_get",
            modelId = model,
            keyword = keyword.resetPagination(),
            params = JsonArray(emptyList()),
            odooInstance = client().odooInstance
        )
        return client().postRequest(request, resultType)
    }

    @JvmStatic
    public fun <T> callMethod(
        model: String,
        functionName: String,
        params: List<Any?>,
        keyword: OdxClientKeywordRequest,
        id: String?,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<T>> {
        val request = OdxClientRequest(
            id = generateId(id),
            action = "call_method",
            modelId = model,
            fnName = functionName,
            keyword = keyword,
            params = toJsonElement(params),
            odooInstance = client().odooInstance
        )
        return client().postRequest(request, resultType)
    }
}