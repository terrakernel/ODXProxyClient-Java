package io.odxproxy.client

import io.odxproxy.exception.OdxServerErrorException
import io.odxproxy.model.OdxClientRequest
import io.odxproxy.model.OdxInstanceInfo
import io.odxproxy.model.OdxServerResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    private val apiKey: String = options.odxApiKey
    private val gatewayUrl: String = options.gatewayUrl.removeSuffix("/")

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val endpoint: HttpUrl = "$gatewayUrl/api/odoo/execute".toHttpUrl()
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
        return executeCall(requestData, elementSerializer(resultType))
    }

    public fun <T> postRequestList(
        requestData: OdxClientRequest,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<List<T>>> {
        return executeCall(requestData, listSerializerFor(resultType))
    }

    private fun <R> executeCall(
        requestData: OdxClientRequest,
        resultSerializer: KSerializer<R>
    ): CompletableFuture<OdxServerResponse<R>> {
        val future = CompletableFuture<OdxServerResponse<R>>()

        // Stream-encode the request body straight into an Okio Buffer — avoids the intermediate
        // Java String (UTF-16) + redundant UTF-8 re-encoding done by encodeToString + toRequestBody.
        val body: RequestBody = try {
            val buffer = Buffer()
            json.encodeToStream(OdxClientRequest.serializer(), requestData, buffer.outputStream())
            BufferRequestBody(buffer, jsonMediaType)
        } catch (e: Exception) {
            future.completeExceptionally(e)
            return future
        }

        val request = Request.Builder()
            .url(endpoint)
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
                    val responseBody = resp.body

                    if (!resp.isSuccessful) {
                        // Try to decode an error envelope; if the body isn't JSON, fall back
                        // to a raw HTTP-status exception.
                        if (responseBody != null) {
                            try {
                                val errorEnvelope = json.decodeFromStream(envelopeSerializer, responseBody.byteStream())
                                if (errorEnvelope.error != null) {
                                    future.completeExceptionally(OdxServerErrorException(errorEnvelope.error))
                                    return
                                }
                            } catch (_: Exception) { /* not a JSON envelope; fall through */ }
                        }
                        future.completeExceptionally(OdxServerErrorException(resp.code, resp.message, null))
                        return
                    }

                    if (responseBody == null) {
                        future.completeExceptionally(IOException("Empty response from server"))
                        return
                    }

                    try {
                        val serverResponse = json.decodeFromStream(envelopeSerializer, responseBody.byteStream())
                        if (serverResponse.error != null) {
                            future.completeExceptionally(OdxServerErrorException(serverResponse.error))
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
