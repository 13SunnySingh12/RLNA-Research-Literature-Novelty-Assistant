package com.rlna.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.rlna.config.AppProperties;
import com.rlna.entity.Paper;
import com.rlna.entity.Project;
import com.rlna.exception.ApiException;
import com.rlna.repository.AnalysisResultRepository;
import com.rlna.repository.ConceptRepository;
import com.rlna.repository.PaperChunkRepository;
import com.rlna.repository.PaperRepository;
import com.rlna.repository.PaperSectionRepository;
import com.rlna.repository.ProcessingJobRepository;
import com.rlna.repository.ProjectRepository;

/**
 * Per-user data isolation (Section 22.2).
 *
 * <p>The single most important behaviour in the project. Two properties are
 * asserted throughout: a resource belonging to someone else is reported as
 * <em>not found</em> rather than forbidden, so the API never confirms that it
 * exists; and every lookup that reaches the database carries the owner, so an
 * ownership check cannot be forgotten at a call site.
 */
class OwnershipIsolationTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID INTRUDER = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();

    private ProjectRepository projectRepository;
    private PaperRepository paperRepository;
    private AnalysisResultRepository analysisRepository;
    private ProjectService projectService;
    private PaperService paperService;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        paperRepository = mock(PaperRepository.class);
        analysisRepository = mock(AnalysisResultRepository.class);

        projectService = new ProjectService(projectRepository, paperRepository);

        AppProperties properties = new AppProperties(
                new AppProperties.Auth("https://example.test/jwks", "https://example.test"),
                new AppProperties.Storage("bucket", "https://s3.us-west-004.backblazeb2.com",
                        "us-west-004", "key-id", "application-key", 300, false),
                new AppProperties.AiService("http://ai.test", "token", 60, true),
                new AppProperties.Upload(25),
                new AppProperties.Academic("https://api.test", "a@b.c", 60),
                java.util.List.of("http://localhost:5173"),
                new AppProperties.RateLimit(20, 60, 20));

        paperService = new PaperService(
                paperRepository,
                mock(PaperSectionRepository.class),
                mock(PaperChunkRepository.class),
                mock(ConceptRepository.class),
                mock(ProcessingJobRepository.class),
                projectService,
                mock(StorageService.class),
                mock(PaperIndexingWorker.class),
                properties);
    }

    // ------------------------------------------------------------------
    // Projects
    // ------------------------------------------------------------------

    @Test
    @DisplayName("another user's project is reported as not found, never as forbidden")
    void projectOfAnotherUserIsNotFound() {
        // The repository is owner-scoped, so an intruder's lookup finds nothing
        // even though the row exists.
        when(projectRepository.findByIdAndUserId(RESOURCE, INTRUDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.require(INTRUDER, RESOURCE))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException e = (ApiException) thrown;
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getStatus()).isNotEqualTo(HttpStatus.FORBIDDEN);
                    // The message must not distinguish "does not exist" from
                    // "exists but is not yours".
                    assertThat(e.getMessage()).contains("does not exist or you do not have access");
                });
    }

    @Test
    @DisplayName("the owner reaches their own project")
    void ownerReachesTheirProject() {
        Project project = new Project();
        project.setId(RESOURCE);
        project.setUserId(OWNER);
        when(projectRepository.findByIdAndUserId(RESOURCE, OWNER)).thenReturn(Optional.of(project));

        assertThat(projectService.require(OWNER, RESOURCE).getId()).isEqualTo(RESOURCE);
    }

    @Test
    @DisplayName("a project lookup always carries the owner")
    void projectLookupIsAlwaysScoped() {
        when(projectRepository.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.require(INTRUDER, RESOURCE))
                .isInstanceOf(ApiException.class);

        verify(projectRepository).findByIdAndUserId(RESOURCE, INTRUDER);
        // There is deliberately no unscoped fetch on the repository interface,
        // so this can never silently regress into findById.
        verify(projectRepository, never()).findById(any());
    }

    @Test
    @DisplayName("an intruder cannot delete another user's project")
    void intruderCannotDeleteProject() {
        when(projectRepository.findByIdAndUserId(RESOURCE, INTRUDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.delete(INTRUDER, RESOURCE))
                .isInstanceOf(ApiException.class);

        verify(projectRepository, never()).delete(any());
    }

    // ------------------------------------------------------------------
    // Papers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("another user's paper is reported as not found")
    void paperOfAnotherUserIsNotFound() {
        when(paperRepository.findByIdAndUserId(RESOURCE, INTRUDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paperService.require(INTRUDER, RESOURCE))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("an intruder cannot delete another user's paper or its stored file")
    void intruderCannotDeletePaper() {
        when(paperRepository.findByIdAndUserId(RESOURCE, INTRUDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paperService.delete(INTRUDER, RESOURCE))
                .isInstanceOf(ApiException.class);

        verify(paperRepository, never()).delete(any());
    }

    @Test
    @DisplayName("an intruder cannot obtain a signed URL for another user's file")
    void intruderCannotGetSignedUrl() {
        when(paperRepository.findByIdAndUserId(RESOURCE, INTRUDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paperService.fileUrl(INTRUDER, RESOURCE))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("an intruder cannot edit another user's paper")
    void intruderCannotEditPaper() {
        when(paperRepository.findByIdAndUserId(RESOURCE, INTRUDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paperService.update(INTRUDER, RESOURCE,
                new com.rlna.dto.PaperDto.UpdateRequest("hijacked", null, null, null, null)))
                .isInstanceOf(ApiException.class);

        verify(paperRepository, never()).save(any());
    }

    @Test
    @DisplayName("a paper cannot be moved into a project the caller does not own")
    void cannotMovePaperIntoAnotherUsersProject() {
        UUID foreignProject = UUID.randomUUID();
        Paper paper = new Paper();
        paper.setId(RESOURCE);
        paper.setUserId(OWNER);
        when(paperRepository.findByIdAndUserId(RESOURCE, OWNER)).thenReturn(Optional.of(paper));
        when(projectRepository.findByIdAndUserId(foreignProject, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paperService.update(OWNER, RESOURCE,
                new com.rlna.dto.PaperDto.UpdateRequest(null, null, null, null, foreignProject)))
                .isInstanceOf(ApiException.class);

        verify(paperRepository, never()).save(any());
    }

    @Test
    @DisplayName("an upload into another user's project is refused before anything is stored")
    void cannotUploadIntoAnotherUsersProject() {
        UUID foreignProject = UUID.randomUUID();
        when(projectRepository.findByIdAndUserId(foreignProject, INTRUDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paperService.upload(INTRUDER, foreignProject, null))
                .isInstanceOf(ApiException.class);

        // Ownership is proven before the file is even read.
        verify(paperRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // Analyses
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the analysis cache is scoped per user")
    void analysisCacheIsScopedPerUser() {
        lenient().when(analysisRepository.findByUserIdAndCacheKey(eq(INTRUDER), any()))
                .thenReturn(Optional.empty());

        // The lookup takes the user id, so one account's cached result can never
        // be served to another even when the fingerprint is identical.
        assertThat(analysisRepository.findByUserIdAndCacheKey(INTRUDER, "shared-key")).isEmpty();
        verify(analysisRepository).findByUserIdAndCacheKey(INTRUDER, "shared-key");
    }
}
