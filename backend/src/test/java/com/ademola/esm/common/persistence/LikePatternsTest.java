package com.ademola.esm.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LikePatternsTest {

    @Test
    void wrapsTrimmedLowercasedTextInWildcards() {
        assertThat(LikePatterns.containsIgnoringCase("  Ada ")).isEqualTo("%ada%");
    }

    @Test
    void escapesWildcardCharactersTypedByTheUser() {
        assertThat(LikePatterns.containsIgnoringCase("50%_off\\")).isEqualTo("%50\\%\\_off\\\\%");
    }
}
