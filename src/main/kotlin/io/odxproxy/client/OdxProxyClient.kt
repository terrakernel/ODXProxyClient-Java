package io.odxproxy.client

import io.odxproxy.exception.OdxBrainConfigException
import io.odxproxy.exception.OdxBrainException
import io.odxproxy.exception.OdxBrainNotInstalledException
import io.odxproxy.exception.OdxBrainRequestException
import io.odxproxy.exception.OdxBrainTimeoutException
import io.odxproxy.exception.OdxBrainUnavailableException
import io.odxproxy.exception.OdxServerErrorException
import io.odxproxy.model.OdxServerErrorResponse
import io.odxproxy.model.OdxClientRequest
import io.odxproxy.model.OdxInstanceInfo
import io.odxproxy.model.OdxServerResponse
import io.odxproxy.model.OdxV2Request
import io.odxproxy.model.OdxV2VersionInfo
import io.odxproxy.model.OdxVersionRequest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalSerializationApi::class)
public class OdxProxyClient private constructor(options: OdxProxyClientInfo) {

    public val odooInstance: OdxInstanceInfo = options.instance
    /** v2 only: context merged into every [io.odxproxy.OdxProxyV2] call. */
    public val defaultContext: Map<String, Any?>? = options.defaultContext
    private val apiKey: String = options.odxApiKey
    private val gatewayUrl: String = options.gatewayUrl.removeSuffix("/")

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val endpoint: HttpUrl = "$gatewayUrl/api/odoo/execute".toHttpUrl()
    private val v2Endpoint: HttpUrl = "$gatewayUrl/v2/odoo/execute".toHttpUrl()
    private val v2VersionEndpoint: HttpUrl = "$gatewayUrl/v2/odoo/version".toHttpUrl()
    private val v1VersionEndpoint: HttpUrl = "$gatewayUrl/api/odoo/version".toHttpUrl()
    private val aboutEndpoint: HttpUrl = "$gatewayUrl/_/about".toHttpUrl()
    private val baseHeaders: Headers = Headers.headersOf(
        "Accept", "application/json",
        "X-Api-Key", apiKey
    )

    private val httpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 32 })
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(45))
        .writeTimeout(Duration.ofSeconds(45))
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    // Cache reflective serializer lookups. kotlinx-serialization's serializer(Class) walks
    // the class hierarchy on every call; once cached, requests become reflection-free.
    private val elementSerializerCache = ConcurrentHashMap<Class<*>, KSerializer<*>>()
    private val listSerializerCache = ConcurrentHashMap<Class<*>, KSerializer<*>>()

    private fun <T> elementSerializer(cls: Class<T>): KSerializer<T> {
        // computeIfAbsent is atomic at the bin level on ConcurrentHashMap — only one thread
        // runs the reflective serializer(cls) lookup per key, even under burst contention.
        // kotlin.collections.MutableMap.getOrPut is NOT atomic and would race here.
        @Suppress("UNCHECKED_CAST")
        return elementSerializerCache.computeIfAbsent(cls) {
            json.serializersModule.serializer(it)
        } as KSerializer<T>
    }

    private fun <T> listSerializerFor(cls: Class<T>): KSerializer<List<T>> {
        @Suppress("UNCHECKED_CAST")
        return listSerializerCache.computeIfAbsent(cls) {
            ListSerializer(elementSerializer(it))
        } as KSerializer<List<T>>
    }

    public fun <T> postRequest(
        requestData: OdxClientRequest,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<T>> {
        return executeCall(endpoint, OdxClientRequest.serializer(), requestData, elementSerializer(resultType))
    }

    public fun <T> postRequestList(
        requestData: OdxClientRequest,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<List<T>>> {
        return executeCall(endpoint, OdxClientRequest.serializer(), requestData, listSerializerFor(resultType))
    }

    /** v2: `POST /v2/odoo/execute` (Odoo JSON-2, ODXProxy 0.9.0+). Same envelope and errors as [postRequest]. */
    public fun <T> postV2Request(
        requestData: OdxV2Request,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<T>> {
        return executeCall(v2Endpoint, OdxV2Request.serializer(), requestData, elementSerializer(resultType))
    }

    /** v2 variant of [postRequestList]. */
    public fun <T> postV2RequestList(
        requestData: OdxV2Request,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<List<T>>> {
        return executeCall(v2Endpoint, OdxV2Request.serializer(), requestData, listSerializerFor(resultType))
    }

    /** v2: `POST /v2/odoo/version`, Odoo's `GET /json/version` through the proxy. */
    public fun postV2Version(requestData: OdxVersionRequest): CompletableFuture<OdxServerResponse<OdxV2VersionInfo>> {
        return executeCall(v2VersionEndpoint, OdxVersionRequest.serializer(), requestData, OdxV2VersionInfo.serializer())
    }

    /** v1: `POST /api/odoo/version`, Odoo's `version_info` (`server_serie`, `server_version`, ...). */
    public fun postV1Version(requestData: OdxVersionRequest): CompletableFuture<OdxServerResponse<JsonObject>> {
        return executeCall(v1VersionEndpoint, OdxVersionRequest.serializer(), requestData, JsonObject.serializer())
    }

    /**
     * `GET /_/about` (no API key): `{build, version, features}`. Used by
     * [io.odxproxy.OdxBrain.isAvailable].
     */
    public fun getAbout(): CompletableFuture<OdxServerResponse<JsonObject>> {
        val request = Request.Builder().url(aboutEndpoint).header("Accept", "application/json").get().build()
        return sendRaw(request).thenApply { (code, text) ->
            if (code !in 200..299) throw httpFailure(code, text)
            json.decodeFromString(OdxServerResponse.serializer(JsonObject.serializer()), text)
        }
    }

    /**
     * ODXBrain transport (ODXProxy 0.10.0+): `POST` [body] to, or `GET` when [body] is
     * `null`, the `/brain/v1/...` route [path]. Sends the proxy `X-Api-Key` and nothing
     * else; the body is sent as given (see [io.odxproxy.OdxBrain]). Fails with an
     * [OdxBrainException] subclass, including a bare 404 from a proxy that predates
     * ODXBrain ([OdxBrainNotInstalledException]); the proxy's own 401/403 stay plain
     * [OdxServerErrorException].
     */
    public fun brainRequest(path: String, body: JsonObject?): CompletableFuture<OdxServerResponse<JsonObject>> {
        val builder = Request.Builder().url("$gatewayUrl$path".toHttpUrl()).headers(baseHeaders)
        if (body != null) {
            builder.post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType))
        } else {
            builder.get()
        }
        return sendRaw(builder.build()).thenApply { (code, text) -> decodeBrain(code, text) }
    }

    private fun decodeBrain(httpStatus: Int, text: String): OdxServerResponse<JsonObject> {
        val envelopeSerializer = OdxServerResponse.serializer(JsonObject.serializer())
        // Malformed requests get a plain-text 400/422 and old proxies a bare 404, neither of
        // which is an envelope; only a body that decodes to one with `error` set is used as one.
        val envelope = try {
            json.decodeFromString(envelopeSerializer, text)
        } catch (_: Exception) {
            null
        }
        val error = envelope?.error
        if (httpStatus !in 200..299) {
            if (error != null) throw brainError(error, httpStatus)
            if (httpStatus == 404) {
                throw OdxBrainNotInstalledException(
                    OdxServerErrorResponse(
                        OdxServerErrorException.ODXBRAIN_NOT_INSTALLED,
                        "This proxy has no /brain routes (it predates ODXBrain, proxy < 0.10.0)",
                        JsonObject(mapOf("type" to JsonPrimitive("odxbrain_not_installed")))
                    ),
                    404
                )
            }
            throw httpFailure(httpStatus, text)
        }
        if (envelope == null) {
            throw IOException(if (text.isEmpty()) "Empty response from server" else "Serialization Error: not a JSON-RPC envelope")
        }
        if (error != null) throw brainError(error, httpStatus)
        return envelope
    }

    private fun brainError(error: OdxServerErrorResponse, httpStatus: Int): OdxServerErrorException = when (error.code) {
        OdxServerErrorException.ODXBRAIN_NOT_INSTALLED -> OdxBrainNotInstalledException(error, httpStatus)
        OdxServerErrorException.ODXBRAIN_UNREACHABLE -> OdxBrainUnavailableException(error, httpStatus)
        OdxServerErrorException.ODXBRAIN_TIMEOUT -> OdxBrainTimeoutException(error, httpStatus)
        OdxServerErrorException.ODXBRAIN_CONFIG -> OdxBrainConfigException(error, httpStatus)
        // On a 200, positive codes are ODXBrain's own HTTP status, never Odoo's.
        in 100..599 -> when {
            httpStatus != 200 -> OdxServerErrorException(error, httpStatus)
            error.code == 400 -> OdxBrainRequestException(error, httpStatus)
            error.code == 503 -> OdxBrainUnavailableException(error, httpStatus)
            else -> OdxBrainException(error, httpStatus)
        }
        // The proxy's own codes (401 -32000, 403 license 0, ...) keep the plain type.
        else -> OdxServerErrorException(error, httpStatus)
    }

    /** A non-2xx answer with no JSON-RPC envelope: the body is kept as text, never parsed. */
    private fun httpFailure(httpStatus: Int, text: String): OdxServerErrorException {
        val message = text.trim().take(500).ifEmpty { "HTTP $httpStatus" }
        return OdxServerErrorException(httpStatus, message, text.ifEmpty { null }, httpStatus)
    }

    /** Sends [request] off the caller's thread and completes with (HTTP status, body text). */
    private fun sendRaw(request: Request): CompletableFuture<Pair<Int, String>> {
        val future = CompletableFuture<Pair<Int, String>>()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                future.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { future.complete(it.code to it.body.string()) }
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }
        })
        return future
    }

    private fun <B, R> executeCall(
        url: HttpUrl,
        bodySerializer: KSerializer<B>,
        requestData: B,
        resultSerializer: KSerializer<R>
    ): CompletableFuture<OdxServerResponse<R>> {
        val future = CompletableFuture<OdxServerResponse<R>>()

        // Stream-encode the request body straight into an Okio Buffer — avoids the intermediate
        // Java String (UTF-16) + redundant UTF-8 re-encoding done by encodeToString + toRequestBody.
        val body: RequestBody = try {
            val buffer = Buffer()
            json.encodeToStream(bodySerializer, requestData, buffer.outputStream())
            BufferRequestBody(buffer, jsonMediaType)
        } catch (e: Exception) {
            future.completeExceptionally(e)
            return future
        }

        val request = Request.Builder()
            .url(url)
            .headers(baseHeaders)
            .post(body)
            .build()

        // OkHttp dispatches the network call on its own pool; no extra runAsync hop needed.
        // The Callback fires on a Dispatcher thread, where we then stream-decode the response.
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                future.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val envelopeSerializer = OdxServerResponse.serializer(resultSerializer)
                    // OkHttp 5 made Response.body non-null — an absent body is an empty one,
                    // so emptiness is probed on the source rather than via a null check.
                    val responseBody = resp.body

                    if (!resp.isSuccessful) {
                        // Try to decode an error envelope; if the body isn't JSON, fall back
                        // to a raw HTTP-status exception.
                        try {
                            val errorEnvelope = json.decodeFromStream(envelopeSerializer, responseBody.byteStream())
                            if (errorEnvelope.error != null) {
                                future.completeExceptionally(OdxServerErrorException(errorEnvelope.error, resp.code))
                                return
                            }
                        } catch (_: Exception) { /* not a JSON envelope; fall through */ }
                        future.completeExceptionally(OdxServerErrorException(resp.code, resp.message, null, resp.code))
                        return
                    }

                    // contentLength() is -1 for chunked/unknown-length bodies, so it cannot tell
                    // "empty" from "length not declared". exhausted() blocks until at least one
                    // byte is buffered or the stream ends, and it does not consume what it peeks,
                    // so the decode below still sees the whole body.
                    if (responseBody.source().exhausted()) {
                        future.completeExceptionally(IOException("Empty response from server"))
                        return
                    }

                    try {
                        val serverResponse = json.decodeFromStream(envelopeSerializer, responseBody.byteStream())
                        if (serverResponse.error != null) {
                            future.completeExceptionally(OdxServerErrorException(serverResponse.error, resp.code))
                        } else {
                            future.complete(serverResponse)
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(IOException("Serialization Error: ${e.message}", e))
                    }
                }
            }
        })

        return future
    }

    private class BufferRequestBody(
        private val buffer: Buffer,
        private val contentType: MediaType
    ) : RequestBody() {
        private val length: Long = buffer.size
        override fun contentType(): MediaType = contentType
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            buffer.copyTo(sink.buffer, 0, length)
            sink.emitCompleteSegments()
        }
    }

    public companion object {
        private val instanceRef = AtomicReference<OdxProxyClient>()
        @JvmStatic
        public fun init(options: OdxProxyClientInfo): OdxProxyClient {
            val client = OdxProxyClient(options)
            if (!instanceRef.compareAndSet(null, client)) {
                throw IllegalStateException("OdxProxyClient has already been initialized.")
            }
            return client
        }
        @JvmStatic
        public fun getInstance(): OdxProxyClient {
            return instanceRef.get()
                ?: throw IllegalStateException("OdxProxyClient has not been initialized. Call init() first.")
        }
    }
}
