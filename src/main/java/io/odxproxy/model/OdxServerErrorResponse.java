package io.odxproxy.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OdxServerErrorResponse {
    @JsonProperty("code")
    public int code;

    @JsonProperty("message")
    public String message;

    @JsonProperty("data")
    public Object data;
}
