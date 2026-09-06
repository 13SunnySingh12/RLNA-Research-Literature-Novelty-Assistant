package com.rlna.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rlna.entity.Project;

/**
 * Every lookup is scoped by owner. There is intentionally no {@code findById}
 * overload that omits the user, so an ownership check cannot be forgotten at a
 * call site (Section 22.2, rule 2).
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByIdAndUserId(UUID id, UUID userId);

    List<Project> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    boolean existsByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);
}
