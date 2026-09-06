package com.rlna.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "paper_sections")
@Getter
@Setter
@NoArgsConstructor
public class PaperSection {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "paper_id", nullable = false, columnDefinition = "uuid")
    private UUID paperId;

    @Column(name = "section_type", nullable = false)
    private String sectionType;

    private String heading;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex = 0;
}
