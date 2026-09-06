package com.rlna.service;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads which social provider an account signed in with.
 *
 * <p>Neon Auth keeps this in its own schema, which this application does not
 * own and does not control the shape of. The read therefore runs in its own
 * transaction: a failed statement aborts a PostgreSQL transaction entirely, so
 * running it inline would let a schema change at the identity provider break
 * user provisioning -- turning a cosmetic display field into a sign-in outage.
 *
 * <p>Isolated like this, the worst case is a null column.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthProviderLookup {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public String findProvider(String subject) {
        try {
            // Neon Auth uses quoted camelCase identifiers, hence the quoting.
            return jdbcTemplate.query(
                    """
                    SELECT "providerId" FROM neon_auth.account
                    WHERE "userId" = CAST(? AS uuid)
                    ORDER BY "createdAt"
                    LIMIT 1
                    """,
                    rs -> rs.next() ? rs.getString(1) : null,
                    subject);
        } catch (DataAccessException | IllegalArgumentException e) {
            log.debug("Auth provider lookup unavailable; continuing without it: {}", e.getMessage());
            return null;
        }
    }
}
