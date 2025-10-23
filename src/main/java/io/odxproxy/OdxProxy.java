package io.odxproxy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.f4b6a3.ulid.UlidCreator;
import io.odxproxy.client.OdxProxyClient;
import io.odxproxy.client.OdxProxyClientInfo;
import io.odxproxy.model.OdxClientKeywordRequest;
import io.odxproxy.model.OdxClientRequest;
import io.odxproxy.model.OdxServerResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Main entry point for interacting with the ODX Proxy.
 * Provides static methods for common Odoo operations.
 */
public final class OdxProxy {

    private OdxProxy() {} // Prevent instantiation

    /**
     * Initializes the OdxProxyClient singleton with the required configuration.
     * This must be called once before any other methods are used.
     *
     * @param options The configuration options.
     * @return The initialized OdxProxyClient instance.
     */
    public static OdxProxyClient init(OdxProxyClientInfo options) {
        return OdxProxyClient.init(options);
    }

    private static OdxProxyClient client() {
        return OdxProxyClient.getInstance();
    }
    
    private static OdxClientKeywordRequest copyKeywords(OdxClientKeywordRequest keyword) {
        return new OdxClientKeywordRequest(keyword);
    }

    /**
     * Performs a search and returns matching record IDs.
     */
    public static CompletableFuture<OdxServerResponse<List<Integer>>> search(String model, List<Object> params, OdxClientKeywordRequest keyword, String id) {
        OdxClientKeywordRequest kCopy = copyKeywords(keyword);
        kCopy.sort = null;
        kCopy.limit = null;
        kCopy.offset = null;
        kCopy.fields = null;

        OdxClientRequest body = new OdxClientRequest();
        body.id = Optional.ofNullable(id).orElseGet(() -> UlidCreator.getUlid().toString());
        body.action = "search";
        body.modelId = model;
        body.keyword = kCopy;
        body.params = params;
        body.odooInstance = client().getOdooInstance();

        return client().postRequest(body, new TypeReference<OdxServerResponse<List<Integer>>>() {});
    }
    
    /**
     * Performs a search and reads the data of matching records.
     */
    public static <T> CompletableFuture<OdxServerResponse<List<T>>> searchRead(String model, List<Object> params, OdxClientKeywordRequest keyword, String id, Class<T> resultType) {
        OdxClientRequest body = new OdxClientRequest();
        body.id = Optional.ofNullable(id).orElseGet(() -> UlidCreator.getUlid().toString());
        body.action = "search_read";
        body.modelId = model;
        body.keyword = copyKeywords(keyword);
        body.params = params;
        body.odooInstance = client().getOdooInstance();

        return client().postRequest(body, new TypeReference<OdxServerResponse<List<T>>>() {});
    }

    /**
     * Reads the data for a specific set of record IDs.
     */
    public static <T> CompletableFuture<OdxServerResponse<List<T>>> read(String model, List<Integer> ids, OdxClientKeywordRequest keyword, String id, Class<T> resultType) {
        OdxClientKeywordRequest kCopy = copyKeywords(keyword);
        kCopy.sort = null;
        kCopy.limit = null;
        kCopy.offset = null;

        OdxClientRequest body = new OdxClientRequest();
        body.id = Optional.ofNullable(id).orElseGet(() -> UlidCreator.getUlid().toString());
        body.action = "read";
        body.modelId = model;
        body.keyword = kCopy;
        body.params = Collections.singletonList(ids);
        body.odooInstance = client().getOdooInstance();

        return client().postRequest(body, new TypeReference<OdxServerResponse<List<T>>>() {});
    }

    /**
     * Retrieves metadata for the fields of a model.
     */
     public static <T> CompletableFuture<OdxServerResponse<T>> fieldsGet(String model, OdxClientKeywordRequest keyword, String id) {
        OdxClientKeywordRequest kCopy = copyKeywords(keyword);
        kCopy.sort = null;
        kCopy.limit = null;
        kCopy.offset = null;
        kCopy.fields = null;
        
        OdxClientRequest body = new OdxClientRequest();
        body.id = Optional.ofNullable(id).orElseGet(() -> UlidCreator.getUlid().toString());
        body.action = "fields_get";
        body.modelId = model;
        body.keyword = kCopy;
        body.params = Collections.emptyList();
        body.odooInstance = client().getOdooInstance();
        
        return client().postRequest(body, new TypeReference<OdxServerResponse<T>>() {});
    }

    /**
     * Counts the number of records matching the search criteria.
     */
    public static CompletableFuture<OdxServerResponse<Integer>> searchCount(String model, List<Object> params, OdxClientKeywordRequest keyword, String id) {
        OdxClientRequest body = new OdxClientRequest();
        body.id = Optional.ofNullable(id).orElseGet(() -> UlidCreator.getUlid().toString());
        body.action = "search_count";
        body.modelId = model;
        body.keyword = copyKeywords(keyword);
        body.params = params;
        body.odooInstance = client().getOdooInstance();

        return client().postRequest(body, new TypeReference<OdxServerResponse<Integer>>() {});
    }

    /**
     * Creates a new record.
     */
    public static <T> CompletableFuture<OdxServerResponse<T>> create(String model, List<Object> params, OdxClientKeywordRequest keyword, String id) {
        OdxClientRequest body = new OdxClientRequest();
        body.id = Optional.ofNullable(id).orElseGet(() -> UlidCreator.getUlid().toString());
        body.action = "create";
        body.modelId = model;
        body.keyword = copyKeywords(keyword);
        body.params = params;
        body.odooInstance = client().getOdooInstance();

        return client().postRequest(body, new TypeReference<OdxServerResponse<T>>() {});
    }

    /**
     * Updates existing records.
     */
    public static CompletableFuture<OdxServerResponse<Boolean>> write(String model, List<Integer> ids, Object values, OdxClientKeywordRequest keyword, String id) {
        OdxClientRequest body = new OdxClientRequest();
        body.id = Optional.ofNullable(id).orElseGet(() -> UlidCreator.getUlid().toString());
        body.action = "write";
        body.modelId = model;
        body.keyword = copyKeywords(keyword);
        body.params = Arrays.asList(ids, values);
        body.odooInstance = client().getOdooInstance();

        return client().postRequest(body, new TypeReference<OdxServerResponse<Boolean>>() {});
    }
    
    /**
     * Deletes records by their IDs.
     */
    public static CompletableFuture<OdxServerResponse<Boolean>> remove(String model, List<Integer> ids, OdxClientKeywordRequest keyword, String id) {
        OdxClientRequest body = new OdxClientRequest();
        body.id = Optional.ofNullable(id).orElseGet(() -> UlidCreator.getUlid().toString());
        body.action = "unlink";
        body.modelId = model;
        body.keyword = copyKeywords(keyword);
        body.params = Collections.singletonList(ids);
        body.odooInstance = client().getOdooInstance();

        return client().postRequest(body, new TypeReference<OdxServerResponse<Boolean>>() {});
    }
    
    /**
     * Calls an arbitrary method on a model. On some action you may need to subclass OdxClientKeywordRequest to add extra fields required by the method.
     */
    public static <T> CompletableFuture<OdxServerResponse<T>> callMethod(String model, String functionName, List<Object> params, OdxClientKeywordRequest keyword, String id) {
        OdxClientRequest body = new OdxClientRequest();
        body.id = Optional.ofNullable(id).orElseGet(() -> UlidCreator.getUlid().toString());
        body.action = "call_method";
        body.modelId = model;
        body.fnName = functionName;
        body.keyword = copyKeywords(keyword);
        body.params = params;
        body.odooInstance = client().getOdooInstance();

        return client().postRequest(body, new TypeReference<OdxServerResponse<T>>() {});
    }
}
