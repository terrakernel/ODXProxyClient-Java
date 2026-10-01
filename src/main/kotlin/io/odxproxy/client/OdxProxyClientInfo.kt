package io.odxproxy.client

import io.odxproxy.model.OdxInstanceInfo

public data class OdxProxyClientInfo @JvmOverloads public constructor(
    public val instance: OdxInstanceInfo,
    public val odxApiKey: String,
    public val gatewayUrl: String = "https://gateway.odxproxy.io",
    /**
     * v2 only ([io.odxproxy.OdxProxyV2]): Odoo context merged into every v2 call,
     * e.g. `lang`, `tz`, `allowed_company_ids`. A call's own context keys win.
     * Odoo applies no company selection unless `allowed_company_ids` is sent.
     * Does not affect v1 ([io.odxproxy.OdxProxy]) calls.
     */
    public val defaultContext: Map<String, Any?>? = null
)
