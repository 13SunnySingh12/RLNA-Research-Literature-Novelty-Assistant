package com.rlna.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.rlna.config.AppProperties;
import com.rlna.config.SecurityConfig;
import com.rlna.config.WebConfig;
import com.rlna.entity.User;
import com.rlna.exception.ApiException;
import com.rlna.security.CurrentUserArgumentResolver;
import com.rlna.service.PaperService;
import com.rlna.service.ProjectService;
import com.rlna.service.RateLimiterService;
import com.rlna.service.UserService;

/**
 * HTTP contract: status codes, the shared error envelope, and the guarantee that
 * internal detail never reaches a client (Sections 21.3 and 27).
 */
@WebMvcTest(controllers = {ProjectController.class, PaperController.class})
@Import({SecurityConfig.class, WebConfig.class, CurrentUserArgumentResolver.class,
         com.rlna.security.ApiAuthenticationEntryPoint.class,
         com.rlna.security.ApiAccessDeniedHandler.class,
         com.rlna.exception.GlobalExceptionHandler.class,
         ApiContractTest.TestBeans.class})
class ApiContractTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        AppProperties appProperties() {
            return new AppProperties(
                    new AppProperties.Auth("https://example.test/jwks", "https://example.test"),
                    new AppProperties.Storage("bucket", "https://s3.us-west-004.backblazeb2.com",
                        "us-west-004", "key-id", "application-key", 300, false),
                    new AppProperties.AiService("http://ai.test", "token", 60, true),
                    new AppProperties.Upload(25),
                    new AppProperties.Academic("https://api.test", "a@b.c", 60),
                    java.util.List.of("http://localhost:5173"),
                    new AppProperties.RateLimit(20, 60, 20));
        }

        @Bean
        RateLimiterService rateLimiterService(AppProperties properties) {
            return new RateLimiterService(properties);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;
    @MockitoBean
    UserService userService;
    @MockitoBean
    ProjectService projectService;
    @MockitoBean
    PaperService paperService;
    @MockitoBean
    com.rlna.service.AnalyticsService analyticsService;

    private final UUID resourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("owner@example.test");
        when(userService.resolve(any())).thenReturn(user);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a request without a token is refused")
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("another user's resource is answered 404 with the standard envelope")
    void foreignResourceIsNotFound() throws Exception {
        when(projectService.get(any(), eq(resourceId))).thenThrow(ApiException.notFound("project"));

        mvc.perform(get("/api/projects/{id}", resourceId).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value(
                        "This project does not exist or you do not have access to it."));
    }

    @Test
    @DisplayName("a deletion of another user's resource is 404, not 403")
    void foreignDeleteIsNotFound() throws Exception {
        when(projectService.delete(any(), eq(resourceId))).thenThrow(ApiException.notFound("project"));

        mvc.perform(delete("/api/projects/{id}", resourceId).with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("validation failures list the offending fields")
    void validationEnvelope() throws Exception {
        mvc.perform(post("/api/projects").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.name").isNotEmpty());
    }

    @Test
    @DisplayName("malformed JSON is a clean 400, not a parser error")
    void malformedJson() throws Exception {
        mvc.perform(post("/api/projects").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("an unexpected server fault never leaks internals to the client")
    void internalErrorsAreOpaque() throws Exception {
        when(projectService.list(any())).thenThrow(
                new IllegalStateException("jdbc://user:password@db.internal/rlna exploded"));

        mvc.perform(get("/api/projects").with(jwt()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value(
                        "Something went wrong on our side. Please try again."))
                // A trace id lets a user quote the incident without the response
                // carrying the detail behind it.
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.stackTrace").doesNotExist());
    }

    @Test
    @DisplayName("a rejected upload reports which file failed and why")
    void uploadRejectionIsPerFile() throws Exception {
        when(paperService.upload(any(), any(), any()))
                .thenThrow(ApiException.badRequest("UNSUPPORTED_FILE_TYPE",
                        "Only PDF files are supported."));

        mvc.perform(multipart("/api/papers")
                        .file(new MockMultipartFile("files", "notes.txt",
                                "text/plain", "not a pdf".getBytes()))
                        .with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].accepted").value(false))
                .andExpect(jsonPath("$[0].filename").value("notes.txt"))
                .andExpect(jsonPath("$[0].errorCode").value("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    @DisplayName("an accepted upload returns 202, because indexing continues server-side")
    void uploadIsAccepted() throws Exception {
        when(paperService.upload(any(), any(), any())).thenReturn(
                new com.rlna.dto.PaperDto.UploadResult(
                        com.rlna.dto.PaperDto.from(new com.rlna.entity.Paper()), null));

        mvc.perform(multipart("/api/papers")
                        .file(new MockMultipartFile("files", "paper.pdf",
                                "application/pdf", "%PDF-1.4".getBytes()))
                        .with(jwt()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$[0].accepted").value(true));
    }
}
