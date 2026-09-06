package com.rlna.service;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Session termination against Neon Auth.
 *
 * <p>Neon Auth is the session authority; this service only holds verified
 * tokens. Revocation is therefore a forwarded request rather than local state
 * being cleared, and it is best-effort by design: the definitive sign-out is
 * the client ending its own Neon Auth session.
 */
@Slf4j
@Service
public class AuthSessionService {

    private final RestClient restClient;
    private final boolean enabled;

    public AuthSessionService(org.springframework.core.env.Environment environment) {
        String baseUrl = environment.getProperty("NEON_AUTH_BASE_URL", "");
        this.enabled = StringUtils.hasText(baseUrl);
        if (!enabled) {
            log.info("NEON_AUTH_BASE_URL is not set; sign-out will be handled entirely by the client.");
            this.restClient = null;
            return;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public void revoke(String authorizationHeader) {
        if (!enabled || !StringUtils.hasText(authorizationHeader)) {
            return;
        }
        try {
            restClient.post()
                    .uri("/sign-out")
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // Expected when the caller presents an access token rather than a
            // session token. The client still ends its own session.
            log.debug("Upstream sign-out was not accepted: {}", e.getMessage());
        }
    }
}
