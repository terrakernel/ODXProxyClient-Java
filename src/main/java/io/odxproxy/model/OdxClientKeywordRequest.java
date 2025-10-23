package io.odxproxy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OdxClientKeywordRequest {
    @JsonProperty("fields")
    public List<String> fields;

    @JsonProperty("order")
    public String order;

    @JsonProperty("limit")
    public Integer limit;

    @JsonProperty("offset")
    public Integer offset;

    @JsonProperty("context")
    public OdxClientRequestContext context;

    // A copy constructor to allow safe modification
    public OdxClientKeywordRequest(OdxClientKeywordRequest other) {
        this.fields = other.fields;
        this.order = other.order;
        this.limit = other.limit;
        this.offset = other.offset;
        this.context = other.context;
    }
    
    public OdxClientKeywordRequest() {}
}
