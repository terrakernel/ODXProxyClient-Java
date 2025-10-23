package io.odxproxy.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.odxproxy.exception.OdxServerErrorException;
import io.odxproxy.model.OdxClientRequest;
import io.odxproxy.model.OdxInstanceInfo;
import io.odxproxy.model.OdxServerResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class OdxProxyClient {

    private static volatile OdxProxyClient instance;
    private static final Object lock = new Object();

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OdxInstanceInfo odooInstance;
    private final String gatewayUrl;
    
    public static final MediaType JSON = MediaType.Companion.parse("application/json; charset=utf-8");

    private OdxProxyClient(OdxProxyClientInfo options) {
        this.odooInstance = options.instance;

        String rawGatewayUrl = options.gatewayUrl;
        if (rawGatewayUrl.endsWith("/")) {
            this.gatewayUrl = rawGatewayUrl.substring(0, rawGatewayUrl.length() - 1);
        } else {
            this.gatewayUrl = rawGatewayUrl;
        }

        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(45))
            .readTimeout(Duration.ofSeconds(45))
            .build();

        this.objectMapper = new ObjectMapper()
            .registerModule(new Jdk8Module()); 
    }

    public static OdxProxyClient init(OdxProxyClientInfo options) {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new OdxProxyClient(options);
                } else {
                     throw new IllegalStateException("OdxProxyClient has already been initialized.");
                }
            }
        } else {
            throw new IllegalStateException("OdxProxyClient has already been initialized.");
        }
        return instance;
    }

    public static OdxProxyClient getInstance() {
        if (instance == null) {
            throw new IllegalStateException("OdxProxyClient has not been initialized. Call init() first.");
        }
        return instance;
    }
    
    public <T> CompletableFuture<OdxServerResponse<T>> postRequest(OdxClientRequest requestData, TypeReference<OdxServerResponse<T>> responseType) {
        CompletableFuture<OdxServerResponse<T>> future = new CompletableFuture<>();

        try {
            String jsonBody = objectMapper.writeValueAsString(requestData);
            RequestBody body = RequestBody.Companion.create(jsonBody, JSON);

            Request request = new Request.Builder()
                .url(this.gatewayUrl + "/api/odoo/execute")
                .header("Accept", "application/json")
                .header("X-Api-Key", instance.odooInstance.apiKey)
                .post(body)
                .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (e instanceof java.net.SocketTimeoutException) {
                         future.completeExceptionally(new OdxServerErrorException(408, "Request Timeout: " + e.getMessage(), null));
                    } else {
                        future.completeExceptionally(e);
                    }
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful() || responseBody == null) {
                             future.completeExceptionally(new OdxServerErrorException(response.code(), response.message(), response.body() != null ? response.body().string() : null));
                            return;
                        }
                        
                        OdxServerResponse<T> serverResponse = objectMapper.readValue(responseBody.byteStream(), responseType);
                        
                        if (serverResponse.error != null) {
                            future.completeExceptionally(new OdxServerErrorException(serverResponse.error));
                        } else {
                            future.complete(serverResponse);
                        }
                    } catch (IOException e) {
                        future.completeExceptionally(e);
                    }
                }
            });

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    public OdxInstanceInfo getOdooInstance() {
        return new OdxInstanceInfo(this.odooInstance);
    }
}