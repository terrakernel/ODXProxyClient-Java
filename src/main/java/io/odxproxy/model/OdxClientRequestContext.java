package io.odxproxy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OdxClientRequestContext {
    @JsonProperty("allowed_company_ids")
    public List<Integer> allowedCompanyIds;

    @JsonProperty("default_company_id")
    public Integer defaultCompanyId;

    @JsonProperty("tz")
    public String tz;
}
