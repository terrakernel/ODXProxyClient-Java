package io.odxproxy.exception

import io.odxproxy.model.OdxServerErrorResponse
import kotlinx.serialization.json.JsonElement

public class OdxServerErrorException : RuntimeException {
    public val code: Int
    public val data: JsonElement?

    public constructor(error: OdxServerErrorResponse) : super(error.message) {
        this.code = error.code
        this.data = error.data
    }

    public constructor(code: Int, message: String, debugData: String?) : super(message) {
        this.code = code
        this.data = null
    }

    override fun toString(): String {
        return "OdxServerErrorException(code=$code, message='$message', data=$data)"
    }
}