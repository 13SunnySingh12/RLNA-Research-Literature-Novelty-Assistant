package com.rlna.security;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rlna.exception.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Emits the standard error envelope for unauthenticated requests. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // The reason a token was refused is logged server-side and never returned:
        // an operator needs it to tell a misconfigured issuer from an expired
        // token, and the caller must learn nothing beyond "sign in".
        if (authException != null) {
            log.warn("Rejected token on {} {}: {}", request.getMethod(), request.getRequestURI(),
                    authException.getMessage());
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(
                "UNAUTHENTICATED",
                "Please sign in to continue.",
                UUID.randomUUID().toString().substring(0, 8)));
    }
}
