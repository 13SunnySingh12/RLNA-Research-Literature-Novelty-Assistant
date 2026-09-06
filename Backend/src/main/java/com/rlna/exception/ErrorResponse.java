package com.rlna.exception;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The single error envelope used by every endpoint (Section 21.3). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(Body error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String code, String message, String traceId, Map<String, String> fields) {}

    public static ErrorResponse of(String code, String message, String traceId) {
        return new ErrorResponse(new Body(code, message, traceId, null));
    }

    public static ErrorResponse of(String code, String message, String traceId, Map<String, String> fields) {
        return new ErrorResponse(new Body(code, message, traceId, fields));
    }
}
