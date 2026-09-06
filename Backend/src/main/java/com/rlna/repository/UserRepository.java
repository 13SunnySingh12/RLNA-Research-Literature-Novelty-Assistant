package com.rlna.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rlna.entity.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByAuthProviderUserId(String authProviderUserId);

    Optional<User> findByEmail(String email);
}
