package io.odxproxy.client

import io.odxproxy.model.OdxInstanceInfo

public data class OdxProxyClientInfo @JvmOverloads public constructor(
    public val instance: OdxInstanceInfo,
    public val odxApiKey: String,
    public val gatewayUrl: String = "https://gateway.odxproxy.io"
)