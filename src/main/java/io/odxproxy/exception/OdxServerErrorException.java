package io.odxproxy.exception;

import io.odxproxy.model.OdxServerErrorResponse;

public class OdxServerErrorException extends Exception {
    private final int code;
    private final Object data;

    public OdxServerErrorException(OdxServerErrorResponse errorResponse) {
        super(errorResponse.message);
        this.code = errorResponse.code;
        this.data = errorResponse.data;
    }
    
    public OdxServerErrorException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public Object getData() {
        return data;
    }

    @Override
    public String toString() {
        return "OdxServerErrorException{" +
                "code=" + code +
                ", message='" + getMessage() + '\'' +
                ", data=" + data +
                '}';
    }
}
