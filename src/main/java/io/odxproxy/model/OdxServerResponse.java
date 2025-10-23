package io.odxproxy.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OdxServerResponse<T> {
    @JsonProperty("jsonrpc")
    public String jsonrpc;

    @JsonProperty("id")
    public String id;

    @JsonProperty("result")
    public T result;

    @JsonProperty("error")
    public OdxServerErrorResponse error;
}
