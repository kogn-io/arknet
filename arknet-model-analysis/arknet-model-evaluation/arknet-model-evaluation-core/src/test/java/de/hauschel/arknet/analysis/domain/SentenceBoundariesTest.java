// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SentenceBoundariesTest {

    @Test
    void anAbbreviationDotDoesNotEndTheSentence() {
        assertThat(SentenceBoundaries.sameSentence("Kunde, lt. FR-2", 0, 5, 11, 15)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde vgl. FR-2", 0, 5, 11, 15)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde s. FR-2", 0, 5, 9, 13)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde, bzw. FR-2", 0, 5, 12, 16)).isTrue();
    }

    @Test
    void aRealSentenceEndStillEndsTheSentence() {
        assertThat(SentenceBoundaries.sameSentence("Kunde. FR-2", 0, 5, 7, 11)).isFalse();
        assertThat(SentenceBoundaries.sameSentence("Kunde! FR-2", 0, 5, 7, 11)).isFalse();
        assertThat(SentenceBoundaries.sameSentence("Kunde; FR-2", 0, 5, 7, 11)).isFalse();
    }

    @Test
    void anAbbreviationIsRecognisedCaseInsensitively() {
        assertThat(SentenceBoundaries.sameSentence("Kunde. Lt. FR-2", 0, 5, 11, 15)).isFalse();
        assertThat(SentenceBoundaries.sameSentence("Kunde Lt. FR-2", 0, 5, 10, 14)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde VGL. FR-2", 0, 5, 11, 15)).isTrue();
    }

    @Test
    void multiPartAbbreviationsCoverTheirInnerDotsWithAndWithoutSpaces() {
        assertThat(SentenceBoundaries.sameSentence("Kunde z.B. FR-2", 0, 5, 11, 15)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde z. B. FR-2", 0, 5, 12, 16)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde i. d. R. FR-2", 0, 5, 15, 19)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde e.g. FR-2", 0, 5, 11, 15)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde e. g. FR-2", 0, 5, 12, 16)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde i.e. FR-2", 0, 5, 11, 15)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde i. e. FR-2", 0, 5, 12, 16)).isTrue();
    }

    @Test
    void uADotAIsAmbiguousAndEndsTheSentence() {
        assertThat(SentenceBoundaries.sameSentence("Kunde u. a. FR-2", 0, 5, 12, 16)).isFalse();
    }

    @Test
    void sAfterANumberIsAUnitAndEndsTheSentence() {
        assertThat(SentenceBoundaries.sameSentence("Kunde 2 s. FR-2", 0, 5, 11, 15)).isFalse();
        assertThat(SentenceBoundaries.sameSentence("Kunde 2s. FR-2", 0, 5, 10, 14)).isFalse();
        assertThat(SentenceBoundaries.sameSentence("Kunde 2\u00A0s. FR-2", 0, 5, 11, 15)).isFalse();
    }

    @Test
    void theGermanLegalFormEGDoesNotCountAsTheAbbreviationEG() {
        assertThat(SentenceBoundaries.sameSentence("Kunde e. G. FR-2", 0, 5, 12, 16)).isFalse();
        assertThat(SentenceBoundaries.sameSentence("Kunde e.G. FR-2", 0, 5, 11, 15)).isFalse();
    }

    @Test
    void aCapitalSDoesNotCountAsTheAbbreviationS() {
        assertThat(SentenceBoundaries.sameSentence("Kunde S. FR-2", 0, 5, 9, 13)).isFalse();
    }

    @Test
    void aWordEndingInAnAbbreviationIsNotAnAbbreviation() {
        assertThat(SentenceBoundaries.sameSentence("Kunde ist es. FR-2", 0, 5, 14, 18)).isFalse();
        assertThat(SentenceBoundaries.sameSentence("Kunde sagt Vgl. FR-2", 0, 5, 16, 20)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde Bus. FR-2", 0, 5, 11, 15)).isFalse();
    }

    @Test
    void aLineBreakAfterAnAbbreviationStillEndsTheSentence() {
        assertThat(SentenceBoundaries.sameSentence("Kunde lt.\nFR-2", 0, 5, 10, 14)).isFalse();
    }

    @Test
    void aPossessiveSDoesNotCountAsTheAbbreviationS() {
        assertThat(SentenceBoundaries.sameSentence("Kunde is the customer's. FR-2", 0, 5, 25, 29)).isFalse();
        assertThat(SentenceBoundaries.sameSentence("Kunde is the customer\u2019s. FR-2", 0, 5, 25, 29)).isFalse();
    }

    @Test
    void aNonBreakingSpaceInsideAMultiPartAbbreviationIsAccepted() {
        assertThat(SentenceBoundaries.sameSentence("Kunde z.\u00A0B. FR-2", 0, 5, 12, 16)).isTrue();
        assertThat(SentenceBoundaries.sameSentence("Kunde d.\u202Fh. FR-2", 0, 5, 12, 16)).isTrue();
    }
}
