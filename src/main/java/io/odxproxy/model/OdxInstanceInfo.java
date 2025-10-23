package io.odxproxy.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OdxInstanceInfo {
    @JsonProperty("url")
    public String url;

    @JsonProperty("user_id")
    public int userId;

    @JsonProperty("db")
    public String db;

    @JsonProperty("api_key")
    public String apiKey;

    public OdxInstanceInfo(String url, int userId, String db, String apiKey) {
        this.url = url;
        this.userId = userId;
        this.db = db;
        this.apiKey = apiKey;
    }

    // A copy constructor to ensure immutability when passing the object around
    public OdxInstanceInfo(OdxInstanceInfo other) {
        this.url = other.url;
        this.userId = other.userId;
        this.db = other.db;
        this.apiKey = other.apiKey;
    }
}
