package io.odxproxy.client;

import io.odxproxy.model.OdxInstanceInfo;

public class OdxProxyClientInfo {
    public final OdxInstanceInfo instance;
    public final String odxApiKey;
    public final String gatewayUrl;

    public OdxProxyClientInfo(OdxInstanceInfo instance, String odxApiKey, String gatewayUrl) {
        this.instance = instance;
        this.odxApiKey = odxApiKey;
        this.gatewayUrl = gatewayUrl;
    }
     public OdxProxyClientInfo(OdxInstanceInfo instance, String odxApiKey) {
        this(instance, odxApiKey, "https://gateway.odxproxy.io");
    }
}
