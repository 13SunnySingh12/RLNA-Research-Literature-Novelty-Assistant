package com.rlna.controller;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rlna.entity.User;
import com.rlna.security.CurrentUser;
import com.rlna.service.AuthSessionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    public record SessionResponse(UUID id, String email, String name, String authProvider,
                                  OffsetDateTime createdAt) {}

    private final AuthSessionService authSessionService;

    /**
     * Confirms the caller's token and returns their application profile,
     * provisioning it on first sign-in.
     */
    @GetMapping("/session")
    @Operation(summary = "Current signed-in user")
    public SessionResponse session(@CurrentUser User user) {
        return new SessionResponse(user.getId(), user.getEmail(), user.getName(),
                user.getAuthProvider(), user.getCreatedAt());
    }

    /**
     * Ends the session at the identity provider.
     *
     * <p>Neon Auth owns the session, not this service, so the request is
     * forwarded there with the caller's own credentials. The response is 204
     * either way: the client clears its local session regardless, and a failed
     * revocation must not leave a user apparently stuck signed in.
     */
    @PostMapping("/logout")
    @Operation(summary = "Sign out")
    public ResponseEntity<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                       String authorization) {
        authSessionService.revoke(authorization);
        return ResponseEntity.noContent().build();
    }
}
