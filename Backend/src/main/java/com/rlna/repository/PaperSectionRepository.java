package com.rlna.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rlna.entity.PaperSection;

public interface PaperSectionRepository extends JpaRepository<PaperSection, UUID> {

    List<PaperSection> findByPaperIdOrderByOrderIndex(UUID paperId);
}
