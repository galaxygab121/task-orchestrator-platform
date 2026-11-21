package com.taskorchestrator.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorSanitizerTest {

    @Test
    void collapsesNewlinesAndTruncates() {
        assertThat(ErrorSanitizer.sanitize("line1\nline2\r\n\tline3")).isEqualTo("line1 line2 line3");
        assertThat(ErrorSanitizer.sanitize("x".repeat(2_000))).hasSize(ErrorSanitizer.MAX_LENGTH).endsWith("...");
        assertThat(ErrorSanitizer.sanitize(null)).isEqualTo("unknown error");
    }

    @Test
    void describesExceptionWithoutStackTrace() {
        assertThat(ErrorSanitizer.describe(new IllegalStateException("boom\nat Foo.bar")))
                .isEqualTo("IllegalStateException: boom at Foo.bar");
    }
}
