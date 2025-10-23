package io.odxproxy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OdxClientRequest {
    @JsonProperty("id")
    public String id;

    @JsonProperty("action")
    public String action;

    @JsonProperty("model_id")
    public String modelId;

    @JsonProperty("keyword")
    public OdxClientKeywordRequest keyword;

    @JsonProperty("fn_name")
    public String fnName;

    @JsonProperty("params")
    public List<Object> params;

    @JsonProperty("odoo_instance")
    public OdxInstanceInfo odooInstance;
}
