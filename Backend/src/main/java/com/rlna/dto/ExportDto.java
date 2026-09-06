package com.rlna.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ExportDto() {

    public record Request(
            @NotNull(message = "Choose which analysis to export.") UUID analysisId,

            @NotNull
            @Pattern(regexp = "markdown|pdf|bibtex",
                     message = "Supported formats are markdown, pdf and bibtex.")
            String format) {}
}
