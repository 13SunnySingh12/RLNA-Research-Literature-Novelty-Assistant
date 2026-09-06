package com.rlna.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Upload validation rules (Sections 26.3 and 29.5). */
class PaperValidationTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("a real PDF header is accepted")
    void acceptsPdfHeader() {
        assertThat(PaperService.looksLikePdf(bytes("%PDF-1.7\nrest of the file"))).isTrue();
    }

    @Test
    @DisplayName("a PDF header after a little leading junk is still accepted")
    void acceptsPdfHeaderAfterJunk() {
        // The PDF specification tolerates bytes before the header, and real
        // files produced by scanners and mailers do carry them.
        assertThat(PaperService.looksLikePdf(bytes("\n\n   %PDF-1.4 content"))).isTrue();
    }

    @ParameterizedTest
    @DisplayName("content that is not a PDF is rejected whatever it claims to be")
    @ValueSource(strings = {
            "This is plain text.",
            "<html><body>Not a PDF</body></html>",
            "PK\u0003\u0004 zip archive pretending",
            "GIF89a image data",
    })
    void rejectsNonPdfContent(String content) {
        assertThat(PaperService.looksLikePdf(bytes(content))).isFalse();
    }

    @Test
    @DisplayName("a PDF header buried past the header window is rejected")
    void rejectsLateHeader() {
        // A marker thousands of bytes in is not a header; it is a string that
        // happens to appear inside some other kind of file.
        assertThat(PaperService.looksLikePdf(bytes(" ".repeat(2000) + "%PDF-1.4"))).isFalse();
    }

    @Test
    @DisplayName("an empty or truncated file is rejected")
    void rejectsEmpty() {
        assertThat(PaperService.looksLikePdf(new byte[0])).isFalse();
        assertThat(PaperService.looksLikePdf(bytes("%PD"))).isFalse();
    }

    @Test
    @DisplayName("identical bytes hash identically, which is what makes duplicates detectable")
    void hashIsStable() {
        byte[] content = bytes("%PDF-1.4 identical content");
        assertThat(PaperService.sha256(content)).isEqualTo(PaperService.sha256(content.clone()));
        assertThat(PaperService.sha256(content)).hasSize(64);
        assertThat(PaperService.sha256(content))
                .isNotEqualTo(PaperService.sha256(bytes("%PDF-1.4 different content")));
    }

    @Test
    @DisplayName("a provisional title is derived from the filename, readably")
    void titleFromFilename() {
        assertThat(PaperService.titleFromFilename("attention_is_all_you_need.pdf"))
                .isEqualTo("attention is all you need");
        assertThat(PaperService.titleFromFilename("1706.03762.pdf")).isEqualTo("1706.03762");
    }

    @Test
    @DisplayName("a path in the filename never survives into the title")
    void titleStripsPathComponents() {
        // Object keys are built from server-side ids, but a traversal-shaped
        // name must not reach the UI as a title either.
        assertThat(PaperService.titleFromFilename("../../etc/passwd.pdf")).isEqualTo("passwd");
        assertThat(PaperService.titleFromFilename("C:\\Users\\x\\paper.pdf")).isEqualTo("paper");
    }

    @Test
    @DisplayName("a missing or empty filename still yields something displayable")
    void titleFallsBack() {
        assertThat(PaperService.titleFromFilename(null)).isEqualTo("Untitled paper");
        assertThat(PaperService.titleFromFilename("   ")).isEqualTo("Untitled paper");
        assertThat(PaperService.titleFromFilename(".pdf")).isEqualTo("Untitled paper");
    }

    @Test
    @DisplayName("object keys are built from server-generated ids only")
    void objectKeysAreServerGenerated() {
        var userId = java.util.UUID.randomUUID();
        var paperId = java.util.UUID.randomUUID();
        String key = StorageService.paperKey(userId, paperId);

        assertThat(key).isEqualTo("papers/" + userId + "/" + paperId + "/original.pdf");
        // Two users cannot collide, and no user-supplied text is in the path.
        assertThat(StorageService.paperKey(java.util.UUID.randomUUID(), paperId)).isNotEqualTo(key);
    }
}
