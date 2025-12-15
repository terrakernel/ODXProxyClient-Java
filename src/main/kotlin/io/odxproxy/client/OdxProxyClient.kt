package io.odxproxy.client

import io.odxproxy.exception.OdxServerErrorException
import io.odxproxy.model.OdxClientRequest
import io.odxproxy.model.OdxInstanceInfo
import io.odxproxy.model.OdxServerResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

public class OdxProxyClient private constructor(options: OdxProxyClientInfo) {

    public val odooInstance: OdxInstanceInfo = options.instance
    private val apiKey: String = options.odxApiKey
    private val gatewayUrl: String = options.gatewayUrl.removeSuffix("/")

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
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

    public fun <T> postRequest(
        requestData: OdxClientRequest,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<T>> {
        val elementSerializer = json.serializersModule.serializer(resultType)
        @Suppress("UNCHECKED_CAST")
        val safeSerializer = elementSerializer as KSerializer<T>
        return executeCall<T>(requestData, safeSerializer)
    }

    public fun <T> postRequestList(
        requestData: OdxClientRequest,
        resultType: Class<T>
    ): CompletableFuture<OdxServerResponse<List<T>>> {
        val elementSerializer = json.serializersModule.serializer(resultType)
        val listSerializer = ListSerializer(elementSerializer)
        @Suppress("UNCHECKED_CAST")
        val safeSerializer = listSerializer as KSerializer<List<T>>
        return executeCall<List<T>>(requestData, safeSerializer)
    }

    private fun <R> executeCall(
        requestData: OdxClientRequest,
        resultSerializer: KSerializer<R>
    ): CompletableFuture<OdxServerResponse<R>> {
        val future = CompletableFuture<OdxServerResponse<R>>()

        // FIX: Offload Serialization to Background Thread immediately.
        // This prevents the UI thread from freezing if the Request object is huge.
        CompletableFuture.runAsync {
            try {
                // 1. Serialization (CPU Bound) - Now runs in background
                val requestBodyString = json.encodeToString(OdxClientRequest.serializer(), requestData)

                val request = Request.Builder()
                    .url("$gatewayUrl/api/odoo/execute")
                    .header("Accept", "application/json")
                    .header("X-Api-Key", apiKey)
                    .post(requestBodyString.toRequestBody(jsonMediaType))
                    .build()

                // 2. Network Call (IO Bound) - OkHttp handles its own background threads
                httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        future.completeExceptionally(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { resp ->
                            val responseBodyStr = resp.body?.string()

                            // Handle HTTP Protocol Errors
                            if (!resp.isSuccessful) {
                                if (responseBodyStr != null) {
                                    try {
                                        val errorEnvelope = json.decodeFromString(OdxServerResponse.serializer(resultSerializer), responseBodyStr)
                                        if (errorEnvelope.error != null) {
                                            future.completeExceptionally(OdxServerErrorException(errorEnvelope.error))
                                            return
                                        }
                                    } catch (e: Exception) { /* ignore */ }
                                }
                                future.completeExceptionally(OdxServerErrorException(resp.code, resp.message, responseBodyStr))
                                return
                            }

                            if (responseBodyStr == null) {
                                future.completeExceptionally(IOException("Empty response from server"))
                                return
                            }

                            // 3. Deserialization (CPU Bound) - Already on OkHttp background thread
                            try {
                                val envelopeSerializer = OdxServerResponse.serializer(resultSerializer)
                                val serverResponse = json.decodeFromString(envelopeSerializer, responseBodyStr)

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
            } catch (e: Exception) {
                // Catch errors that happened during Serialization
                future.completeExceptionally(e)
            }
        }

        return future
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