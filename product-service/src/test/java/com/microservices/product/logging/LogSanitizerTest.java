package com.microservices.product.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @Test
    void null_becomesEmpty() {
        assertThat(LogSanitizer.clean(null)).isEmpty();
    }

    @Test
    void plainText_isUnchanged() {
        assertThat(LogSanitizer.clean("APPLE-IP15P-128 and some text, with: punctuation")).isEqualTo("APPLE-IP15P-128 and some text, with: punctuation");
    }

    @Test
    void carriageReturnAndLineFeed_becomeUnderscores() {
        assertThat(LogSanitizer.clean("a\r\nb")).isEqualTo("a__b");
    }

    @ParameterizedTest
    @ValueSource(chars = {'\0', '\u0001', '\t', '\n', '\u000b', '\f', '\r', '\u001b', '\u007f', '\u0085', '\u009f'})
    void everyControlCharacter_becomesAnUnderscore(char control) {
        assertThat(LogSanitizer.clean("x" + control + "y")).isEqualTo("x_y");
    }

    @Test
    void unicodeLineAndParagraphSeparators_becomeUnderscores() {
        assertThat(LogSanitizer.clean("x y z")).isEqualTo("x_y_z");
    }

    @Test
    void longValue_isCutAt200Characters() {
        assertThat(LogSanitizer.clean("a".repeat(500))).hasSize(200);
        assertThat(LogSanitizer.clean("a".repeat(200))).hasSize(200);
        assertThat(LogSanitizer.clean("a".repeat(199))).hasSize(199);
    }

    @Test
    void cutting_neverLeavesHalfOfASurrogatePair() {
        String value = "a".repeat(199) + "😀"; // 199 letters and one emoji (two chars)
        String cleaned = LogSanitizer.clean(value);
        assertThat(Character.isHighSurrogate(cleaned.charAt(cleaned.length() - 1))).isFalse();
    }

    @Test
    void textBeyondTheLimit_isNotScannedIntoTheResult() {
        assertThat(LogSanitizer.clean("a".repeat(200) + "\r\nforged")).doesNotContain("forged").doesNotContain("\n");
    }
}
