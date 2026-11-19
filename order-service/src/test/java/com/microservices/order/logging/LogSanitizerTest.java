package com.microservices.order.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Values that come from a caller (email, SKU, path, a reason sent by another service) are cleaned before they go
 * into a log message: no control character (so no second line), at most 200 characters.
 */
class LogSanitizerTest {

    @Test
    void null_becomesAnEmptyString() {
        assertThat(LogSanitizer.clean(null)).isEmpty();
    }

    @Test
    void anOrdinaryValue_isUnchanged() {
        assertThat(LogSanitizer.clean("ann@example.com")).isEqualTo("ann@example.com");
        assertThat(LogSanitizer.clean("/api/orders/12")).isEqualTo("/api/orders/12");
        assertThat(LogSanitizer.clean("héllo wörld ✓ 日本語")).isEqualTo("héllo wörld ✓ 日本語");
        assertThat(LogSanitizer.clean("")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r", "\n", "\t", "\u0000", "\u0007", "\u001b", "\u007f", "\u0085", "\u009f"})
    void everyControlCharacter_becomesAnUnderscore(String control) {
        assertThat(LogSanitizer.clean("a" + control + "b")).isEqualTo("a_b");
    }

    @Test
    void crAndLf_inTheMiddleOfAValue_doNotSurvive() {
        String cleaned = LogSanitizer.clean("ann@example.com\r\nINFO fake line\nsecond");

        assertThat(cleaned).isEqualTo("ann@example.com__INFO fake line_second");
        assertThat(cleaned).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void theUnicodeLineAndParagraphSeparators_alsoBecomeAnUnderscore() {
        assertThat(LogSanitizer.clean("a b c")).isEqualTo("a_b_c");
    }

    @Test
    void aValueOfExactly200Characters_isKept_and201IsCutTo200() {
        assertThat(LogSanitizer.clean("x".repeat(200))).hasSize(200);
        assertThat(LogSanitizer.clean("x".repeat(201))).hasSize(200);
        assertThat(LogSanitizer.clean("y".repeat(100_000))).hasSize(200);
    }

    @Test
    void cuttingNeverLeavesHalfOfASurrogatePair() {
        // 199 letters, then a character that needs two chars: the cut at 200 would split it.
        String cleaned = LogSanitizer.clean("a".repeat(199) + "😀" + "tail");

        assertThat(cleaned.length()).isLessThanOrEqualTo(200);
        assertThat(Character.isHighSurrogate(cleaned.charAt(cleaned.length() - 1))).isFalse();
    }

    @Test
    void theControlCharactersAreReplaced_beforeTheLengthIsCut() {
        assertThat(LogSanitizer.clean("\n".repeat(300))).isEqualTo("_".repeat(200));
    }
}
