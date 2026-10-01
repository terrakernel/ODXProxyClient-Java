package io.odxproxy.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable
public data class OdxInstanceInfo(
    public val url: String,
    @SerialName("user_id") public val userId: Int,
    public val db: String,
    @SerialName("api_key") public val apiKey: String
)

@Serializable
public data class OdxClientRequestContext(
    @SerialName("allowed_company_ids")
    public val allowedCompanyIds: List<Int>? = null,
    @SerialName("default_company_id")
    public val defaultCompanyId: Int? = null,
    public val tz: String? = null,
    public val lang: String? = null
)

@Serializable
public data class OdxClientKeywordRequest(
    public val fields: List<String>? = null,
    public val order: String? = null,
    public val limit: Int? = null,
    public val offset: Int? = null,
    public val context: OdxClientRequestContext? = null,
    
) {
    public fun resetPagination(): OdxClientKeywordRequest {
        return copy(order = null, limit = null, offset = null, fields = null)
    }
}

@Serializable
public data class OdxClientRequest(
    // Fix: Handle cases where ID is numeric or string
    @Serializable(with = OdxIdSerializer::class)
    public val id: String,
    
    public val action: String,
    @SerialName("model_id") public val modelId: String,
    public val keyword: OdxClientKeywordRequest? = null,
    @SerialName("fn_name")
    public val fnName: String? = null,
    public val params: JsonElement = JsonArray(emptyList()),
    @SerialName("odoo_instance")
    public val odooInstance: OdxInstanceInfo
)

@Serializable
public data class OdxServerResponse<T>(
    public val jsonrpc: String,
    
    // Fix: Handle cases where ID is numeric or string
    @Serializable(with = OdxIdSerializer::class)
    public val id: String? = null,
    
    public val result: T? = null,
    public val error: OdxServerErrorResponse? = null
)

@Serializable
public data class OdxServerErrorResponse(
    public val code: Int,
    public val message: String,
    public val data: JsonElement? = null
)

// --- UTILS ---

/**
 * Robust ID Serializer.
 * Matches Swift logic: accepts Int or String in JSON, always provides String in Code.
 */
public object OdxIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OdxId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonInput = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonInput.decodeJsonElement()
        return when {
            element is JsonPrimitive && !element.isString -> element.content // Converts Number to String
            element is JsonPrimitive && element.isString -> element.content
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

public fun toJsonElement(value: Any?): JsonElement {
    return when (value) {
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        is Array<*> -> JsonArray(value.map { toJsonElement(it) })
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
        is JsonElement -> value
        else -> JsonPrimitive(value.toString())
    }
}

// --- v2 (Odoo JSON-2, ODXProxy 0.9.0+) ---

/** `odoo_instance` for v2: no `user_id`, because JSON-2 derives the user from the API key. */
@Serializable
public data class OdxV2Instance(
    public val url: String,
    public val db: String,
    @SerialName("api_key") public val apiKey: String
)

/**
 * Body of `POST /v2/odoo/execute` (SYSTEM_ARCHITECTURE.md §4.6). [kwargs] is sent to
 * Odoo as named arguments, keyed by Odoo's Python parameter names.
 */
@Serializable
public data class OdxV2Request(
    @Serializable(with = OdxIdSerializer::class)
    public val id: String,
    @SerialName("model_id") public val modelId: String,
    public val method: String,
    public val kwargs: JsonObject,
    @SerialName("odoo_instance") public val odooInstance: OdxV2Instance
)

/** Body of `POST /v2/odoo/version`. */
@Serializable
public data class OdxVersionRequest(
    @Serializable(with = OdxIdSerializer::class)
    public val id: String,
    public val url: String
)

/** Odoo's `GET /json/version`, as returned by `OdxProxyV2.version`. */
@Serializable
public data class OdxV2VersionInfo(
    /** e.g. `[20, 0, 0, "final", 0, "e"]` */
    @SerialName("version_info") public val versionInfo: List<JsonElement>,
    /** e.g. `"20.0+e"` */
    public val version: String
) {
    /** Odoo's major version (`versionInfo[0]`), e.g. `20`. */
    public val major: Int?
        get() = (versionInfo.firstOrNull() as? JsonPrimitive)?.intOrNull
}

