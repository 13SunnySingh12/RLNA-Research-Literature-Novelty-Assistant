package com.rlna.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.rlna.dto.AnalyticsDto;
import com.rlna.dto.ProjectDto;
import com.rlna.entity.User;
import com.rlna.security.CurrentUser;
import com.rlna.service.AnalyticsService;
import com.rlna.service.ProjectService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Projects")
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final AnalyticsService analyticsService;

    @GetMapping
    @Operation(summary = "List the signed-in user's projects")
    public List<ProjectDto> list(@CurrentUser User user) {
        return projectService.list(user.getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a project")
    public ProjectDto create(@CurrentUser User user, @Valid @RequestBody ProjectDto.CreateRequest request) {
        return projectService.create(user.getId(), request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one project")
    public ProjectDto get(@CurrentUser User user, @PathVariable UUID id) {
        return projectService.get(user.getId(), id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Rename or edit a project")
    public ProjectDto update(@CurrentUser User user, @PathVariable UUID id,
                             @Valid @RequestBody ProjectDto.UpdateRequest request) {
        return projectService.update(user.getId(), id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a project (its papers stay in the library, unassigned)")
    public Map<String, Object> delete(@CurrentUser User user, @PathVariable UUID id) {
        return Map.of("papersUnassigned", projectService.delete(user.getId(), id));
    }

    @GetMapping("/{id}/analytics")
    @Operation(summary = "Dashboard figures for a project")
    public AnalyticsDto analytics(@CurrentUser User user, @PathVariable UUID id) {
        return analyticsService.forUser(user.getId(), id);
    }

}
