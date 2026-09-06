package com.rlna.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.rlna.dto.ProjectDto;
import com.rlna.entity.Project;
import com.rlna.exception.ApiException;
import com.rlna.repository.PaperRepository;
import com.rlna.repository.ProjectRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private static final int MAX_PROJECTS_PER_USER = 100;

    private final ProjectRepository projectRepository;
    private final PaperRepository paperRepository;

    @Transactional(readOnly = true)
    public List<ProjectDto> list(UUID userId) {
        return projectRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(p -> ProjectDto.from(p, paperRepository.countByUserIdAndProjectId(userId, p.getId()),
                        statusCounts(userId, p.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectDto get(UUID userId, UUID projectId) {
        Project project = require(userId, projectId);
        return ProjectDto.from(project,
                paperRepository.countByUserIdAndProjectId(userId, projectId),
                statusCounts(userId, projectId));
    }

    /** Loads a project or throws 404. The single place ownership is proven. */
    @Transactional(readOnly = true)
    public Project require(UUID userId, UUID projectId) {
        return projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> ApiException.notFound("project"));
    }

    @Transactional
    public ProjectDto create(UUID userId, ProjectDto.CreateRequest request) {
        if (projectRepository.countByUserId(userId) >= MAX_PROJECTS_PER_USER) {
            throw ApiException.conflict("PROJECT_LIMIT_REACHED",
                    "You have reached the limit of " + MAX_PROJECTS_PER_USER + " projects.");
        }
        Project project = new Project();
        project.setUserId(userId);
        project.setName(request.name().trim());
        project.setDescription(request.description());
        project.setResearchTopic(request.researchTopic());
        return ProjectDto.from(projectRepository.save(project), 0, Map.of());
    }

    @Transactional
    public ProjectDto update(UUID userId, UUID projectId, ProjectDto.UpdateRequest request) {
        Project project = require(userId, projectId);
        if (StringUtils.hasText(request.name())) {
            project.setName(request.name().trim());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
        if (request.researchTopic() != null) {
            project.setResearchTopic(request.researchTopic());
        }
        return ProjectDto.from(projectRepository.save(project),
                paperRepository.countByUserIdAndProjectId(userId, projectId),
                statusCounts(userId, projectId));
    }

    /**
     * Deleting a project does not delete the papers inside it. The foreign key
     * is {@code ON DELETE SET NULL} by design (Section 18.1), so the papers stay
     * in the library unassigned rather than a project deletion silently
     * destroying indexed work the user may still want.
     */
    @Transactional
    public int delete(UUID userId, UUID projectId) {
        Project project = require(userId, projectId);
        int released = (int) paperRepository.countByUserIdAndProjectId(userId, projectId);
        projectRepository.delete(project);
        return released;
    }

    private Map<String, Long> statusCounts(UUID userId, UUID projectId) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : paperRepository.countByStatus(userId, projectId)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }
}
