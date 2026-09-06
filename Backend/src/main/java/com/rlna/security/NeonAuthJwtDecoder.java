package com.rlna.security;

import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.SignedJWT;

import lombok.extern.slf4j.Slf4j;

/**
 * Verifies Neon Auth access tokens.
 *
 * <p>Neon Auth signs with EdDSA over Ed25519. Spring Security's
 * {@code NimbusJwtDecoder} cannot consume those keys, and not for a reason that
 * configuration can fix: its key selector converts every matched JWK to a
 * {@code java.security.Key}, and nimbus refuses to export an OKP key that way
 * ("Export to java.security.PublicKey not supported"). The conversion failure is
 * swallowed, the selector returns nothing, and the token is rejected as
 * "no matching key(s) found" even though the key matched perfectly.
 *
 * <p>So the key is used in its JWK form and handed straight to
 * {@link Ed25519Verifier}. RSA and EC keys still go through the standard verifier
 * factory, so a future key rotation onto a different algorithm keeps working.
 */
@Slf4j
public class NeonAuthJwtDecoder implements JwtDecoder {

    /** Floor between JWKS fetches, so unknown key ids cannot be used to hammer the endpoint. */
    private static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(2);
    private static final Duration MAX_CACHE_AGE = Duration.ofHours(1);

    private record CachedKeys(JWKSet keys, Instant fetchedAt) {}

    private final URI jwksUri;
    private final OAuth2TokenValidator<Jwt> validator;
    private final DefaultJWSVerifierFactory verifierFactory = new DefaultJWSVerifierFactory();
    private final AtomicReference<CachedKeys> cache = new AtomicReference<>();

    public NeonAuthJwtDecoder(String jwksUrl, OAuth2TokenValidator<Jwt> validator) {
        this.jwksUri = URI.create(jwksUrl);
        this.validator = validator;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        SignedJWT signed = parse(token);
        JWK key = resolveKey(signed);
        verifySignature(signed, key);

        Jwt jwt = toSpringJwt(token, signed);
        OAuth2TokenValidatorResult result = validator.validate(jwt);
        if (result.hasErrors()) {
            String description = result.getErrors().stream()
                    .map(OAuth2Error::getDescription)
                    .findFirst()
                    .orElse("The token is not valid.");
            throw new JwtValidationException(description, result.getErrors());
        }
        return jwt;
    }

    private SignedJWT parse(String token) {
        try {
            return SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new BadJwtException("The token is not a well-formed JWT.", e);
        }
    }

    private JWK resolveKey(SignedJWT signed) {
        String keyId = signed.getHeader().getKeyID();
        JWK key = lookup(keyId, false);
        if (key == null) {
            // An unknown key id usually means the provider rotated its keys, so
            // one bounded refresh is attempted before giving up.
            key = lookup(keyId, true);
        }
        if (key == null) {
            throw new BadJwtException("The token was signed with an unrecognised key.");
        }
        return key;
    }

    private JWK lookup(String keyId, boolean forceRefresh) {
        CachedKeys cached = cache.get();
        boolean stale = cached == null
                || Duration.between(cached.fetchedAt(), Instant.now()).compareTo(MAX_CACHE_AGE) > 0;
        boolean refreshAllowed = cached == null
                || Duration.between(cached.fetchedAt(), Instant.now()).compareTo(MIN_REFRESH_INTERVAL) > 0;

        if (stale || (forceRefresh && refreshAllowed)) {
            cached = fetch(cached);
        }
        if (cached == null) {
            return null;
        }
        if (keyId != null) {
            return cached.keys().getKeyByKeyId(keyId);
        }
        return cached.keys().getKeys().isEmpty() ? null : cached.keys().getKeys().get(0);
    }

    private CachedKeys fetch(CachedKeys previous) {
        try {
            JWKSet fetched = JWKSet.load(jwksUri.toURL());
            CachedKeys refreshed = new CachedKeys(fetched, Instant.now());
            cache.set(refreshed);
            log.debug("Loaded {} verification key(s) from Neon Auth", fetched.getKeys().size());
            return refreshed;
        } catch (Exception e) {
            // Serving from a stale cache beats rejecting every request because
            // the key endpoint had a bad minute.
            log.warn("Could not refresh Neon Auth verification keys: {}", e.getMessage());
            return previous;
        }
    }

    private void verifySignature(SignedJWT signed, JWK key) {
        try {
            JWSVerifier verifier = (key instanceof OctetKeyPair okp)
                    ? new Ed25519Verifier(okp.toPublicJWK())
                    : verifierFactory.createJWSVerifier(signed.getHeader(), toJavaKey(key));
            if (!signed.verify(verifier)) {
                throw new BadJwtException("The token signature is not valid.");
            }
        } catch (BadJwtException e) {
            throw e;
        } catch (Exception e) {
            throw new BadJwtException("The token signature could not be verified.", e);
        }
    }

    private java.security.Key toJavaKey(JWK key) throws Exception {
        if (key instanceof com.nimbusds.jose.jwk.AsymmetricJWK asymmetric) {
            return asymmetric.toPublicKey();
        }
        throw new BadJwtException("Unsupported verification key type.");
    }

    private Jwt toSpringJwt(String token, SignedJWT signed) {
        try {
            Map<String, Object> claims = signed.getJWTClaimsSet().getClaims();
            Instant issuedAt = signed.getJWTClaimsSet().getIssueTime() == null
                    ? null : signed.getJWTClaimsSet().getIssueTime().toInstant();
            Instant expiresAt = signed.getJWTClaimsSet().getExpirationTime() == null
                    ? null : signed.getJWTClaimsSet().getExpirationTime().toInstant();
            return new Jwt(token, issuedAt, expiresAt,
                    signed.getHeader().toJSONObject(), normalize(claims));
        } catch (ParseException e) {
            throw new BadJwtException("The token claims could not be read.", e);
        }
    }

    /**
     * Spring's {@link Jwt} expects {@code iat}/{@code exp}/{@code nbf} as
     * {@link Instant}, while Nimbus hands them back as {@link java.util.Date}.
     */
    private Map<String, Object> normalize(Map<String, Object> claims) {
        Map<String, Object> normalized = new java.util.LinkedHashMap<>(claims);
        for (String temporal : new String[] {"iat", "exp", "nbf", "auth_time"}) {
            Object value = normalized.get(temporal);
            if (value instanceof java.util.Date date) {
                normalized.put(temporal, date.toInstant());
            }
        }
        return normalized;
    }
}
