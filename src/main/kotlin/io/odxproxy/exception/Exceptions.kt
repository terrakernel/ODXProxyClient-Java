package io.odxproxy.exception

import io.odxproxy.model.OdxServerErrorResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public class OdxServerErrorException : RuntimeException {
    public val code: Int
    public val data: JsonElement?

    /**
     * HTTP status of the proxy response that carried this error, when known.
     * Odoo-side errors arrive on HTTP 200; proxy-layer failures use other
     * statuses (401, 400, 403, 502, 504, ...). See SYSTEM_ARCHITECTURE.md §6.
     */
    public val httpStatus: Int?

    public constructor(error: OdxServerErrorResponse) : this(error, null)

    public constructor(error: OdxServerErrorResponse, httpStatus: Int?) : super(error.message) {
        this.code = error.code
        this.data = error.data
        this.httpStatus = httpStatus
    }

    public constructor(code: Int, message: String, debugData: String?) : this(code, message, debugData, null)

    public constructor(code: Int, message: String, debugData: String?, httpStatus: Int?) : super(message) {
        this.code = code
        this.data = null
        this.httpStatus = httpStatus
    }

    /**
     * For an Odoo-side error, the HTTP status Odoo answered with, which the proxy
     * forwards as [code]: 401 Odoo API key invalid/expired, 403 access rights or a
     * private method, 404 unknown model/method or missing record, 409 lock
     * conflict, 422 validation error or bad arguments, 5xx Odoo server error.
     * Always set on v2 ([io.odxproxy.OdxProxyV2]); on v1 only when Odoo itself
     * answered non-2xx. `null` otherwise.
     */
    public val odooStatus: Int?
        get() = if (httpStatus == 200 && code in 400..599) code else null

    /** Odoo's exception class from `data.name` (e.g. `odoo.exceptions.ValidationError`), when present. */
    public val odooErrorName: String?
        get() {
            val name = (data as? JsonObject)?.get("name") as? JsonPrimitive ?: return null
            return if (name.isString) name.content else null
        }

    /**
     * The proxy's license is expired or invalid: code `0` on HTTP 403. Odoo 19+
     * also sends code `0` for every `/jsonrpc` error, on HTTP 200, so `code == 0`
     * alone does not mean a license problem.
     */
    public val isLicenseError: Boolean
        get() = code == LICENSE_INVALID && httpStatus == 403

    /** v2: no JSON-2 on that Odoo (18 or older, use v1), or the database is not selectable on that host (`dbfilter`). */
    public val isJson2Unavailable: Boolean
        get() = code == JSON2_UNAVAILABLE

    /** v2: invalid model/method name, or db/api key not valid as an HTTP header. Odoo was not contacted. */
    public val isInvalidRequest: Boolean
        get() = code == INVALID_REQUEST

    /**
     * Whether retrying the same call (with backoff) can succeed: a failed
     * connection to Odoo, or an Odoo lock conflict (409). Timeouts are excluded
     * because the upstream call may already have run.
     */
    public val isRetryable: Boolean
        get() = code == UPSTREAM_CONNECT || odooStatus == 409

    override fun toString(): String {
        return "OdxServerErrorException(code=$code, httpStatus=$httpStatus, message='$message', data=$data)"
    }

    /** Proxy error codes (SYSTEM_ARCHITECTURE.md §6). */
    public companion object {
        public const val LICENSE_INVALID: Int = 0
        public const val AUTH_FAILED: Int = -32000
        public const val INVALID_ACTION: Int = -32001
        public const val MISSING_FN_NAME: Int = -32002
        public const val UPSTREAM_TIMEOUT: Int = -32003
        public const val UPSTREAM_CONNECT: Int = -32004
        public const val PROXY_INTERNAL: Int = -32005
        public const val JSON2_UNAVAILABLE: Int = -32006
        public const val INVALID_REQUEST: Int = -32007
    }
}
