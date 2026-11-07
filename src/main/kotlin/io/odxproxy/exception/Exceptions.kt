package io.odxproxy.exception

import io.odxproxy.model.OdxServerErrorResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every error this SDK raises from the proxy. ODXBrain calls ([io.odxproxy.OdxBrain]) raise
 * the [OdxBrainException] subclasses, so one `catch (OdxServerErrorException)` still covers
 * everything; catch a subclass to tell "not installed" from an outage.
 */
public open class OdxServerErrorException : RuntimeException {
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
        this.data = debugData?.let { JsonPrimitive(it) }
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
    public open val odooStatus: Int?
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
    public open val isRetryable: Boolean
        get() = code == UPSTREAM_CONNECT || odooStatus == 409

    override fun toString(): String {
        return "${javaClass.simpleName}(code=$code, httpStatus=$httpStatus, message='$message', data=$data)"
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
        /** `/brain/...`: ODXBrain is not installed on this proxy (HTTP 501). See [OdxBrainNotInstalledException]. */
        public const val ODXBRAIN_NOT_INSTALLED: Int = -32008
        /** `/brain/...`: ODXBrain is installed but unreachable (HTTP 502). Retry once. */
        public const val ODXBRAIN_UNREACHABLE: Int = -32009
        /** `/brain/...`: ODXBrain timed out (HTTP 504). Retry once. */
        public const val ODXBRAIN_TIMEOUT: Int = -32010
        /** `/brain/...`: ODXBrain rejected the **proxy's** key (HTTP 502). An operator problem; never retry. */
        public const val ODXBRAIN_CONFIG: Int = -32011
    }
}

/**
 * An error on a `/brain/v1/...` route (SYSTEM_ARCHITECTURE.md §7.2), and itself thrown for
 * ODXBrain's own errors that have no narrower class (e.g. `500` on an HTTP 200).
 *
 * On an HTTP 200, [code] is **ODXBrain's** HTTP status, never Odoo's, so [odooStatus] is
 * always `null` here. Branch on [type] (ODXBrain's frozen discriminant), never on the
 * message. [data] keeps ODXBrain's whole error object. The proxy's own refusals on these
 * routes (401 `-32000`, 403 license) stay plain [OdxServerErrorException].
 */
public open class OdxBrainException(error: OdxServerErrorResponse, httpStatus: Int?) :
    OdxServerErrorException(error, httpStatus) {

    /**
     * `data.type`: `invalid_request_error`, `model_mismatch`, `unsupported_version`,
     * `embedding_error`, `resolve_error`, `corpus_unavailable`, `sops_unavailable`,
     * `internal_error`, or the proxy's `odxbrain_not_installed`. `null` when absent.
     */
    public val type: String?
        get() {
            val t = (data as? JsonObject)?.get("type") as? JsonPrimitive ?: return null
            return if (t.isString) t.content else null
        }

    /** Codes 100–599 on `/brain/...` are ODXBrain's statuses, not Odoo's. */
    override val odooStatus: Int?
        get() = null

    /** `true` only for `-32009` (unreachable) and `-32010` (timed out): one retry is safe. */
    override val isRetryable: Boolean
        get() = code == ODXBRAIN_UNREACHABLE || code == ODXBRAIN_TIMEOUT
}

/**
 * This proxy has no ODXBrain: `-32008` on HTTP 501, or a bare 404 on `/brain/...` from a
 * proxy older than 0.10.0. A fact about the deployment: hide the feature, never retry.
 */
public class OdxBrainNotInstalledException(error: OdxServerErrorResponse, httpStatus: Int?) :
    OdxBrainException(error, httpStatus)

/**
 * ODXBrain can't answer. `-32009` on HTTP 502: installed but unreachable, retry once
 * ([isRetryable]). `503` on an HTTP 200: it is up but that index isn't loaded
 * ([type] `corpus_unavailable` / `sops_unavailable`); don't retry.
 */
public class OdxBrainUnavailableException(error: OdxServerErrorResponse, httpStatus: Int?) :
    OdxBrainException(error, httpStatus)

/** `-32010` on HTTP 504: the proxy's hop to ODXBrain timed out. Retry once. */
public class OdxBrainTimeoutException(error: OdxServerErrorResponse, httpStatus: Int?) :
    OdxBrainException(error, httpStatus)

/**
 * `-32011` on HTTP 502: ODXBrain rejected the **proxy's** own key. An operator config
 * error: the caller's key was accepted, so this is deliberately not an auth error
 * (`-32000`). Rotating the caller's key will not help. Never retry.
 */
public class OdxBrainConfigException(error: OdxServerErrorResponse, httpStatus: Int?) :
    OdxBrainException(error, httpStatus)

/**
 * `400` on an HTTP 200: ODXBrain rejected the input (empty query, bad edition, `k < 1`,
 * version below 14.0, an explicit `null`). Don't retry; see [type].
 */
public class OdxBrainRequestException(error: OdxServerErrorResponse, httpStatus: Int?) :
    OdxBrainException(error, httpStatus)
