package com.rlna.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/**
 * Base class for errors that are safe to show a user.
 *
 * <p>Anything thrown as an {@code ApiException} carries a stable machine code
 * and a message written for a human. Everything else is caught by the global
 * handler and reported generically, so internals never reach the client
 * (Section 27, principle 3).
 */
@Getter
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public ApiException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /**
     * A resource that does not exist, or exists but belongs to someone else.
     * Both cases return the same 404 so the API never confirms that another
     * user's resource exists (Section 22.2, rule 3).
     */
    public static ApiException notFound(String resource) {
        return new ApiException(resource.toUpperCase() + "_NOT_FOUND", HttpStatus.NOT_FOUND,
                "This " + resource.toLowerCase().replace('_', ' ')
                        + " does not exist or you do not have access to it.");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(code, HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(code, HttpStatus.CONFLICT, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    public static ApiException unavailable(String code, String message) {
        return new ApiException(code, HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
