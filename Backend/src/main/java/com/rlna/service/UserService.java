package com.rlna.service;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rlna.entity.User;
import com.rlna.exception.ApiException;
import com.rlna.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps a verified access token onto an application user row, creating it on
 * first sight.
 *
 * <p>The subject claim is the only identity input. Nothing here reads a user id
 * from a request body, path, or query parameter (Section 22.2, rule 1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final AuthProviderLookup authProviderLookup;

    /**
     * Runs in its own transaction so that provisioning a first-time user is
     * committed even if the request it arrived on later fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User resolve(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ApiException("INVALID_TOKEN", org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Your session is not valid. Please sign in again.");
        }

        Optional<User> existing = userRepository.findByAuthProviderUserId(subject);
        if (existing.isPresent()) {
            return refreshProfile(existing.get(), jwt);
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            // Every downstream table keys off a user row, and email is the only
            // human-readable handle we get. Refuse rather than invent one.
            throw new ApiException("EMAIL_REQUIRED", org.springframework.http.HttpStatus.FORBIDDEN,
                    "Your account does not expose an email address, which RLNA needs to create your library.");
        }

        User user = new User();
        user.setAuthProviderUserId(subject);
        user.setEmail(email);
        user.setName(jwt.getClaimAsString("name"));
        user.setAuthProvider(authProviderLookup.findProvider(subject));
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two first requests raced. The unique constraint decided the winner;
            // read the committed row rather than failing the user's first action.
            return userRepository.findByAuthProviderUserId(subject)
                    .orElseThrow(() -> e);
        }
    }

    private User refreshProfile(User user, Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        String email = jwt.getClaimAsString("email");
        boolean changed = false;
        if (name != null && !name.equals(user.getName())) {
            user.setName(name);
            changed = true;
        }
        if (email != null && !email.equals(user.getEmail())) {
            user.setEmail(email);
            changed = true;
        }
        return changed ? userRepository.save(user) : user;
    }

}
