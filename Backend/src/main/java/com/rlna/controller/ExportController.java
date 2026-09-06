package com.rlna.controller;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rlna.dto.ExportDto;
import com.rlna.entity.User;
import com.rlna.security.CurrentUser;
import com.rlna.service.ExportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Export")
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    /**
     * Streams the file back directly rather than storing it. An export is
     * derived entirely from a stored analysis, so keeping a copy in object
     * storage would add a lifecycle to manage for no benefit.
     */
    @PostMapping
    @Operation(summary = "Export a stored analysis as Markdown, PDF or BibTeX")
    public ResponseEntity<Resource> export(@CurrentUser User user,
                                           @Valid @RequestBody ExportDto.Request request) {
        ExportService.Export export = exportService.export(user.getId(), request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.filename()).build().toString())
                .contentType(MediaType.parseMediaType(export.contentType()))
                .contentLength(export.content().length)
                .body(new ByteArrayResource(export.content()));
    }
}
