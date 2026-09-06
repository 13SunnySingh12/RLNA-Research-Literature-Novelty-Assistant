package com.rlna.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;

import com.rlna.security.NeonAuthJwtDecoder;

import lombok.extern.slf4j.Slf4j;

/**
 * Token verification is offline: the signing keys are fetched once and cached,
 * so an authenticated request costs no round trip to the identity provider.
 */
@Slf4j
@Configuration
public class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(AppProperties properties) {
        AppProperties.Auth auth = properties.auth();
        JwtDecoder decoder = new NeonAuthJwtDecoder(auth.jwksUrl(), validator(auth.issuer()));
        log.info("JWT verification configured against JWKS {}", auth.jwksUrl());
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> validator(String issuer) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        if (issuer == null || issuer.isBlank()) {
            // Left open only for local development. A deployment without an
            // expected issuer would accept any token this JWKS happens to verify.
            log.warn("NEON_AUTH_ISSUER is not set; tokens will not be checked against an issuer.");
            return timestamps;
        }
        return new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(issuer));
    }
}
