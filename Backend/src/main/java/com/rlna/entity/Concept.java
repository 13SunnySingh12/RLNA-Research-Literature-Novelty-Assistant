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
@Table(name = "concepts")
@Getter
@Setter
@NoArgsConstructor
public class Concept {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "paper_id", nullable = false, columnDefinition = "uuid")
    private UUID paperId;

    @Column(nullable = false)
    private String concept;

    @Column(name = "concept_type")
    private String conceptType;

    private Float confidence;
}
