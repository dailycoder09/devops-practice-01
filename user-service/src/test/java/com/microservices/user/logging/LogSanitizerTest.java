package com.microservices.user.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @Test
    void null_becomesEmpty() {
        assertThat(LogSanitizer.clean(null)).isEmpty();
    }

    @Test
    void normalText_isNotChanged() {
        assertThat(LogSanitizer.clean("ann@example.com /api/x-1")).isEqualTo("ann@example.com /api/x-1");
    }

    @Test
    void everyControlCharacter_becomesAnUnderscore() {
        assertThat(LogSanitizer.clean("a\rb\nc\td\u0000e\u001bf\u007fg\u0085h")).isEqualTo("a_b_c_d_e_f_g_h");
    }

    @Test
    void unicodeLineAndParagraphSeparators_becomeUnderscores() {
        assertThat(LogSanitizer.clean("a b c")).isEqualTo("a_b_c");
    }

    @Test
    void atMost200Characters_areKept() {
        assertThat(LogSanitizer.clean("x".repeat(200))).hasSize(200);
        assertThat(LogSanitizer.clean("x".repeat(201))).hasSize(200);
        assertThat(LogSanitizer.clean("x".repeat(100_000))).hasSize(200);
    }

    @Test
    void cuttingNeverLeavesHalfOfAnEmoji() {
        String text = "x".repeat(199) + "😀";
        String cleaned = LogSanitizer.clean(text);

        assertThat(cleaned).isEqualTo("x".repeat(199));
    }
}
